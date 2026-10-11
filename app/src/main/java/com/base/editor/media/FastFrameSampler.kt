package com.base.editor.media

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Быстрый выбор кадров для сегментации: видео декодируется ПОСЛЕДОВАТЕЛЬНО аппаратным декодером в ImageReader (в разы
 * быстрее, чем доставать каждый кадр через MediaMetadataRetriever с перемоткой от ближайшего опорного кадра).
 * Нужные кадры (по одному на 1/fps секунды) переводятся из YUV в RGB сразу в размер входа модели, с учётом поворота и
 * с чёрными полями (letterbox), чтобы человек не сплющивался.
 */
class FastFrameSampler(private val context: Context) {
    class Info(val w: Int, val h: Int, val rotation: Int) {
        val uprightW get() = if (rotation == 90 || rotation == 270) h else w
        val uprightH get() = if (rotation == 90 || rotation == 270) w else h
    }

    /** Размеры видеодорожки (по формату, без декодирования); null — не удалось. */
    fun probe(uri: Uri): Info? = runCatching {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val i = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return null
            val f = ex.getTrackFormat(i)
            Info(f.getInteger(MediaFormat.KEY_WIDTH), f.getInteger(MediaFormat.KEY_HEIGHT), if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else 0)
        } finally { ex.release() }
    }.getOrNull()

    /** Куда в кадре входа модели ложится содержимое (остальное — чёрное). */
    class Rect(val inW: Int, val inH: Int, val padX: Int, val padY: Int, val cw: Int, val ch: Int)

    /**
     * Вызывает [onFrame](индекс, пиксели ARGB размером inW×inH) для кадров fromMs + i·1000/fps, i < count.
     * true — успех; false/исключение — декодер не справился, вызывающий должен перейти на запасной путь.
     */
    fun sample(uri: Uri, fromMs: Long, count: Int, fps: Int, rect: Rect, isActive: () -> Boolean, onFrame: (Int, IntArray) -> Unit): Boolean {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var reader: ImageReader? = null
        val thread = HandlerThread("fast-sampler").also { it.start() }
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            extractor.selectTrack(track)
            val fmt = extractor.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val w = fmt.getInteger(MediaFormat.KEY_WIDTH); val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
            val rot = if (fmt.containsKey(MediaFormat.KEY_ROTATION)) fmt.getInteger(MediaFormat.KEY_ROTATION) else 0
            val ready = Semaphore(0)
            reader = ImageReader.newInstance(w, h, ImageFormat.YUV_420_888, 4)
            reader.setOnImageAvailableListener({ ready.release() }, Handler(thread.looper))
            codec = MediaCodec.createDecoderByType(mime).also { it.configure(fmt, reader.surface, null, 0); it.start() }
            extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val info = MediaCodec.BufferInfo()
            val px = IntArray(rect.inW * rect.inH)
            fun targetUs(i: Int) = (fromMs + i * 1000L / fps) * 1000
            var next = 0; var inputDone = false; var guard = 0
            while (next < count && isActive()) {
                if (!inputDone) {
                    val ib = codec.dequeueInputBuffer(5_000)
                    if (ib >= 0) {
                        val n = extractor.readSampleData(codec.getInputBuffer(ib)!!, 0)
                        if (n < 0) { codec.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(ib, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val ob = codec.dequeueOutputBuffer(info, 5_000)
                if (ob < 0) { if (++guard > 4000) return false; continue }
                guard = 0
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val wanted = info.size > 0 && info.presentationTimeUs >= targetUs(next)
                codec.releaseOutputBuffer(ob, wanted)                       // true — кадр уходит в ImageReader
                if (wanted) {
                    if (!ready.tryAcquire(1500, TimeUnit.MILLISECONDS)) return false
                    val img = reader.acquireLatestImage() ?: return false
                    try { convert(img, rot, w, h, rect, px) } finally { img.close() }
                    // один декодированный кадр закрывает все цели, которые он уже перекрыл (на случай fps цели больше fps видео)
                    do { onFrame(next, px); next++ } while (next < count && info.presentationTimeUs >= targetUs(next))
                }
                if (eos) {
                    // видео кончилось раньше диапазона: оставшиеся цели — последний кадр
                    while (next < count) { onFrame(next, px); next++ }
                }
            }
            return next >= count
        } catch (e: Exception) {
            Log.w(TAG, "быстрый декодер не справился, запасной путь", e)
            return false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { reader?.close() }
            runCatching { extractor.release() }; thread.quitSafely()
        }
    }

    /** YUV_420_888 → ARGB: выборка по сетке содержимого с поворотом, BT.601; поля остаются чёрными. */
    private fun convert(img: Image, rot: Int, w: Int, h: Int, r: Rect, out: IntArray) {
        val yP = img.planes[0]; val uP = img.planes[1]; val vP = img.planes[2]
        val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
        val wu = if (rot == 90 || rot == 270) h else w; val hu = if (rot == 90 || rot == 270) w else h
        java.util.Arrays.fill(out, 0xFF000000.toInt())
        for (oy in 0 until r.ch) {
            val yu = (((oy + 0.5f) * hu / r.ch).toInt()).coerceIn(0, hu - 1)
            for (ox in 0 until r.cw) {
                val xu = (((ox + 0.5f) * wu / r.cw).toInt()).coerceIn(0, wu - 1)
                val sx: Int; val sy: Int
                when (rot) { 90 -> { sx = yu; sy = h - 1 - xu }; 180 -> { sx = w - 1 - xu; sy = h - 1 - yu }; 270 -> { sx = w - 1 - yu; sy = xu }; else -> { sx = xu; sy = yu } }
                val yv = (yb.get(sy * yP.rowStride + sx * yP.pixelStride).toInt() and 0xFF) - 16
                val uv = (ub.get((sy shr 1) * uP.rowStride + (sx shr 1) * uP.pixelStride).toInt() and 0xFF) - 128
                val vv = (vb.get((sy shr 1) * vP.rowStride + (sx shr 1) * vP.pixelStride).toInt() and 0xFF) - 128
                val c = 298 * yv.coerceAtLeast(0)
                val rr = ((c + 409 * vv + 128) shr 8).coerceIn(0, 255)
                val gg = ((c - 100 * uv - 208 * vv + 128) shr 8).coerceIn(0, 255)
                val bb = ((c + 516 * uv + 128) shr 8).coerceIn(0, 255)
                out[(r.padY + oy) * r.inW + r.padX + ox] = (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
            }
        }
    }

    private companion object { const val TAG = "BaseFastSampler" }
}
