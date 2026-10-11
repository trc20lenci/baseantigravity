package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.base.editor.core.Clip
import com.base.editor.core.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil

/**
 * Удаление фона на устройстве: сегментация силуэта лёгкой TFLite-моделью (selfie segmenter, ≈250 КБ) через LiteRT по кадрам
 * исходника; маски (12 к/с) пишутся в файл и дальше применяются шейдером [com.base.editor.media.gl.ClipFxEffect].
 * Идея «маска → альфа» та же, что у backgroundremover; сама модель — лёгкая, только для людей.
 * Работает в фоне с прогрессом, отменяется вместе с корутиной.
 */
class BgRemover(private val context: Context, private val store: MaskStore = MaskStore(context)) {

    class Failure(message: String) : Exception(message)

    suspend fun process(clip: Clip, onProgress: (Float) -> Unit) = withContext(Dispatchers.Default) {
        if (clip.type == MediaType.AUDIO) throw Failure("Для звука фон не удаляется")
        val spanMs = clip.srcSpanMs
        if (spanMs > MAX_SPAN_MS) throw Failure("Для удаления фона клип должен быть не длиннее ${MAX_SPAN_MS / 1000} с")
        val uri = Uri.parse(clip.uri)
        val sampler = FastFrameSampler(context)
        // пропорции кадра: по формату дорожки; запасной вариант — по первому кадру
        val dims = if (clip.type == MediaType.VIDEO) sampler.probe(uri)?.let { it.uprightW to it.uprightH } else null
        val (uw, uh) = dims ?: frameSize(clip, uri)
        // горизонтальному видео — «пейзажная» модель 256×144 (быстрее и без чёрных полос), остальному — универсальная 256×256
        val landscape = uw.toFloat() / uh >= 1.2f
        val modelFile = ensureModel(landscape)
        val count = if (clip.type == MediaType.IMAGE) 1 else ceil(spanMs * FPS / 1000.0).toInt() + 1
        val frames = arrayOfNulls<ByteArray>(count)
        val done = AtomicInteger(0)
        var maskW = 0; var maskH = 0

        // сеть одного потока: [1,H,W,3] → [1,H,W,C]; форма читается из модели
        class Net(file: File) {
            val interp = Interpreter(directBuffer(file), Interpreter.Options().setNumThreads(2))
            val inH: Int; val inW: Int; val outH: Int; val outW: Int; val outC: Int; val floatInput: Boolean
            val input: ByteBuffer; val output: ByteBuffer
            init {
                val ins = interp.getInputTensor(0).shape(); val outs = interp.getOutputTensor(0).shape()
                inH = ins[1]; inW = ins[2]; outH = outs[1]; outW = outs[2]; outC = if (outs.size > 3) outs[3] else 1
                floatInput = interp.getInputTensor(0).dataType() == DataType.FLOAT32
                input = ByteBuffer.allocateDirect(inW * inH * 3 * (if (floatInput) 4 else 1)).order(ByteOrder.nativeOrder())
                output = ByteBuffer.allocateDirect(outW * outH * outC * 4).order(ByteOrder.nativeOrder())
            }
            fun close() = runCatching { interp.close() }
        }
        val probeNet = Net(modelFile)
        val inW = probeNet.inW; val inH = probeNet.inH
        // содержимое вписывается в вход модели с сохранением пропорций (иначе человек сплющивается и маска хуже)
        val au = uw.toFloat() / uh; val am = inW.toFloat() / inH
        val cw: Int; val ch: Int
        if (au >= am) { cw = inW; ch = (inW / au).toInt().coerceIn(8, inH) } else { ch = inH; cw = (inH * au).toInt().coerceIn(8, inW) }
        val rect = FastFrameSampler.Rect(inW, inH, (inW - cw) / 2, (inH - ch) / 2, cw, ch)
        maskW = cw; maskH = ch

        fun infer(net: Net, px: IntArray): ByteArray {
            net.input.rewind()
            for (p in px) {
                val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
                if (net.floatInput) { net.input.putFloat(r / 255f); net.input.putFloat(g / 255f); net.input.putFloat(b / 255f) }
                else { net.input.put(r.toByte()); net.input.put(g.toByte()); net.input.put(b.toByte()) }
            }
            net.input.rewind(); net.output.rewind()
            net.interp.run(net.input, net.output)
            net.output.rewind()
            val fb = net.output.asFloatBuffer()
            var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
            for (k in 0 until net.outW * net.outH * net.outC) { val v = fb.get(k); if (v < mn) mn = v; if (v > mx) mx = v }
            val logits = mn < -0.01f || mx > 1.01f
            val bytes = ByteArray(cw * ch)
            // маска вырезается из области содержимого (без чёрных полей) и приводится к размеру cw×ch
            val sxk = net.outW.toFloat() / inW; val syk = net.outH.toFloat() / inH
            for (y in 0 until ch) for (x in 0 until cw) {
                val ox = (((rect.padX + x) * sxk).toInt()).coerceIn(0, net.outW - 1); val oy = (((rect.padY + y) * syk).toInt()).coerceIn(0, net.outH - 1)
                val k = oy * net.outW + ox
                val v = if (net.outC == 1) fb.get(k) else {
                    val bgV = fb.get(k * net.outC); val fgV = fb.get(k * net.outC + 1)       // канал 1 — «человек»
                    if (logits) 1f / (1f + kotlin.math.exp(bgV - fgV)) else fgV
                }
                val pr = if (net.outC == 1 && logits) 1f / (1f + kotlin.math.exp(-v)) else v
                bytes[y * cw + x] = (pr.coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
            return bytes
        }

        var fast = false
        if (clip.type == MediaType.VIDEO) {
            // основной путь: последовательное аппаратное декодирование + сеть в одном потоке
            fast = runCatching {
                sampler.sample(uri, clip.srcInMs, count, FPS, rect, { isActive }) { i, px ->
                    frames[i] = infer(probeNet, px); onProgress(done.incrementAndGet().toFloat() / count)
                }
            }.getOrDefault(false)
        }
        probeNet.close()
        if (!fast) {
            done.set(0); java.util.Arrays.fill(frames, null)
            // запасной путь: кадры через MediaMetadataRetriever, параллельно по потокам
            val workers = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
            coroutineScope {
                (0 until workers).map { w ->
                    async(Dispatchers.Default) {
                        val net = Net(modelFile)
                        val retriever = if (clip.type == MediaType.VIDEO) MediaMetadataRetriever().also { it.setDataSource(context, uri) } else null
                        try {
                            val px = IntArray(inW * inH)
                            var i = w
                            while (i < count) {
                                ensureActive()
                                val bmp = frame(clip, retriever, clip.srcInMs + (i * 1000L / FPS)) ?: throw Failure("Не удалось прочитать кадр")
                                // кадр кладётся в вход модели с чёрными полями
                                val canvasBmp = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888)
                                android.graphics.Canvas(canvasBmp).drawBitmap(bmp, null, android.graphics.Rect(rect.padX, rect.padY, rect.padX + cw, rect.padY + ch), null)
                                canvasBmp.getPixels(px, 0, inW, 0, 0, inW, inH)
                                frames[i] = infer(net, px)
                                onProgress(done.incrementAndGet().toFloat() / count)
                                i += workers
                            }
                        } finally { runCatching { retriever?.release() }; net.close() }
                    }
                }.awaitAll()
            }
        }
        val first = frames[0] ?: throw Failure("Модель не вернула маску")
        // модель могла вернуть «фон» вместо «человека»: по краям кадра значения выше, чем в центре
        val invert = borderMean(first, maskW, maskH) > centerMean(first, maskW, maskH) + 20
        // сглаживание: убираем шум у нуля и смешиваем с предыдущим кадром (иначе контур дрожит от кадра к кадру)
        var prev: ByteArray? = null
        for (i in frames.indices) {
            val f = frames[i] ?: ByteArray(maskW * maskH) { 255.toByte() }
            for (k in f.indices) {
                var v = f[k].toInt() and 0xFF
                if (invert) v = 255 - v
                if (v < 20) v = 0 else if (v > 235) v = 255
                val pv = prev?.let { it[k].toInt() and 0xFF }
                f[k] = (if (pv != null) (v * 0.7f + pv * 0.3f).toInt() else v).toByte()
            }
            prev = f; frames[i] = f
        }
        val part = File(store.file(clip.uri).path + ".part").apply { delete() }
        try {
            RandomAccessFile(part, "rw").use { out ->
                val header = ByteBuffer.allocate(MaskStore.HEADER.toInt()).order(ByteOrder.BIG_ENDIAN)
                header.putInt(MaskStore.MAGIC).putInt(maskW).putInt(maskH).putInt(FPS).putLong(clip.srcInMs).putInt(count)
                out.write(header.array())
                for (f in frames) out.write(f!!)
            }
            val target = store.file(clip.uri); target.delete()
            if (!part.renameTo(target)) throw Failure("Не удалось сохранить маски")
        } finally { part.delete() }
    }

    /** Размер кадра, если дорожку не удалось прочитать быстро: по первому кадру / фото. */
    private fun frameSize(clip: Clip, uri: Uri): Pair<Int, Int> {
        val bmp = if (clip.type == MediaType.VIDEO) MediaMetadataRetriever().let { r ->
            try { r.setDataSource(context, uri); MediaFrames.scaledFrame(r, clip.srcInMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 256) } finally { r.release() }
        } else frame(clip, null, 0)
        return (bmp?.width ?: 16) to (bmp?.height ?: 9)
    }

    private fun frame(clip: Clip, r: MediaMetadataRetriever?, srcMs: Long): Bitmap? =
        if (r != null) MediaFrames.scaledFrame(r, srcMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, MAX_SIDE)
        else ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(clip.uri))) { dec, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > MAX_SIDE) dec.setTargetSampleSize(big / MAX_SIDE)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }

    private fun borderMean(b: ByteArray, w: Int, h: Int): Float {
        var s = 0L; var n = 0
        for (x in 0 until w) { s += b[x].toInt() and 0xFF; s += b[(h - 1) * w + x].toInt() and 0xFF; n += 2 }
        for (y in 0 until h) { s += b[y * w].toInt() and 0xFF; s += b[y * w + w - 1].toInt() and 0xFF; n += 2 }
        return s.toFloat() / n
    }

    private fun centerMean(b: ByteArray, w: Int, h: Int): Float {
        var s = 0L; var n = 0
        for (y in h / 3 until 2 * h / 3) for (x in w / 3 until 2 * w / 3) { s += b[y * w + x].toInt() and 0xFF; n++ }
        return s.toFloat() / n.coerceAtLeast(1)
    }

    private fun directBuffer(f: File): ByteBuffer {
        val bytes = f.readBytes()
        return ByteBuffer.allocateDirect(bytes.size).put(bytes).also { it.rewind() }
    }

    private fun ensureModel(landscape: Boolean): File {
        val name = if (landscape) MODEL_LANDSCAPE else MODEL_GENERAL
        val url = if (landscape) URL_LANDSCAPE else URL_GENERAL
        val f = File(File(context.applicationContext.filesDir, "models").apply { mkdirs() }, name)
        if (f.exists() && isTflite(f)) return f
        val part = File(f.path + ".part").apply { delete() }
        var last: Exception? = null
        repeat(3) {
            try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
                try {
                    if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
                    c.inputStream.use { i -> part.outputStream().use { i.copyTo(it) } }
                } finally { c.disconnect() }
                if (!isTflite(part)) throw IOException("файл модели повреждён")
                f.delete(); if (!part.renameTo(f)) throw IOException("не удалось сохранить модель")
                return f
            } catch (e: IOException) { last = e; Log.w(TAG, "загрузка модели", e); part.delete() }
        }
        throw Failure("Не удалось загрузить модель сегментации: ${last?.message}")
    }

    /** TFLite-файл: размер в разумных пределах и сигнатура «TFL3» со смещения 4. */
    private fun isTflite(f: File): Boolean {
        if (f.length() !in 50_000..20_000_000) return false
        return f.inputStream().use { i -> val h = ByteArray(8); i.read(h) == 8 && String(h, 4, 4, Charsets.ISO_8859_1) == "TFL3" }
    }

    private companion object {
        const val TAG = "BaseBg"
        const val FPS = 6
        const val MAX_SIDE = 256
        const val MAX_SPAN_MS = 120_000L
        // модели MediaPipe Selfie Segmentation из репозитория Scrip00/Selfie-segmentation-library (универсальная 256×256 и «пейзажная» 256×144)
        const val MODEL_GENERAL = "selfie_general.tflite"
        const val MODEL_LANDSCAPE = "selfie_landscape.tflite"
        const val URL_GENERAL = "https://raw.githubusercontent.com/Scrip00/Selfie-segmentation-library/main/backgroundremover/backremlib/src/main/assets/selfie_segmentation.tflite"
        const val URL_LANDSCAPE = "https://raw.githubusercontent.com/Scrip00/Selfie-segmentation-library/main/mediapipe/modules/selfie_segmentation/selfie_segmentation_landscape.tflite"
    }
}
