package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.base.editor.core.Clip
import com.base.editor.core.ClipTransform
import com.base.editor.core.CropRect
import com.base.editor.core.MediaType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Быстрый предпросмотр кадров при ведении пальцем по таймлайну. Тяжёлый seek CompositionPlayer на паузе занимает
 * секунды, поэтому пока палец двигается, кадр под курсором берётся напрямую из файла (MediaMetadataRetriever,
 * ближайший опорный кадр — быстро) и показывается поверх плеера; когда палец остановился, подгружается точный кадр.
 * Работает по принципу «последний запрос побеждает»: очередь не копится.
 */
class ScrubFrames(private val context: Context, private val scope: CoroutineScope) {
    /** Кадр + положение, с которым он должен быть показан (трансформация клипа на этой позиции). */
    class Frame(val image: ImageBitmap, val transform: ClipTransform)

    private class Req(
        val clip: Clip, val localMs: Long, val exact: Boolean, val transform: ClipTransform, val crop: CropRect,
        val chroma: com.base.editor.core.ChromaKey?, val bg: com.base.editor.core.BgRemoval?,
    )

    private val _frame = MutableStateFlow<Frame?>(null)
    val frame: StateFlow<Frame?> = _frame.asStateFlow()

    @Volatile private var target: Req? = null
    private var job: Job? = null
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUri: String? = null
    private var stillUri: String? = null
    private var still: Bitmap? = null

    fun request(
        clip: Clip, localMs: Long, exact: Boolean, transform: ClipTransform, crop: CropRect,
        chroma: com.base.editor.core.ChromaKey? = null, bg: com.base.editor.core.BgRemoval? = null,
    ) {
        target = Req(clip, localMs, exact, transform, crop, chroma, bg)
        if (job?.isActive == true) return
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val r = target ?: break
                target = null
                val bmp = runCatching { decode(r) }.onFailure { Log.w(TAG, "кадр скраббинга", it) }.getOrNull() ?: continue
                _frame.value = Frame(bmp.asImageBitmap(), r.transform)
            }
        }
    }

    fun clear() { target = null; _frame.value = null }

    fun release() {
        job?.cancel(); clear()
        runCatching { retriever?.release() }; retriever = null; retrieverUri = null; still = null
    }

    private fun decode(r: Req): Bitmap? {
        val src = if (r.clip.type == MediaType.VIDEO) videoFrame(r) else imageFrame(r.clip.uri)
        // эффекты применяются до кадрирования (как в GPU-цепочке), чтобы при перемотке кадр выглядел так же, как после остановки
        return src?.let { crop(applyFx(it, r), r.crop) }
    }

    private fun videoFrame(r: Req): Bitmap? {
        val uri = r.clip.uri
        if (retriever == null || retrieverUri != uri) {
            runCatching { retriever?.release() }
            retriever = MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(uri)) }
            retrieverUri = uri
        }
        val option = if (r.exact) MediaMetadataRetriever.OPTION_CLOSEST else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        return retriever?.let { MediaFrames.scaledFrame(it, (r.clip.srcInMs + r.localMs) * 1000, option, MAX_SIDE) }
    }

    private fun imageFrame(uri: String): Bitmap? {
        if (stillUri == uri && still != null) return still
        val bmp = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, Uri.parse(uri))) { dec, info, _ ->
            val big = maxOf(info.size.width, info.size.height)
            if (big > MAX_SIDE) dec.setTargetSampleSize(big / MAX_SIDE)
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        stillUri = uri; still = bmp
        return bmp
    }

    /** Хромакей и удаление фона на CPU для кадра-подмены при перемотке: те же формулы, что в шейдере, но на уменьшенном кадре. */
    private fun applyFx(src: Bitmap, r: Req): Bitmap {
        val ck = r.chroma; val bg = r.bg
        if (ck == null && bg == null) return src
        val w = src.width; val h = src.height
        val px = IntArray(w * h); src.getPixels(px, 0, w, 0, 0, w, h)
        var mask: ByteArray? = null; var mw = 0; var mh = 0
        var blurred: IntArray? = null
        if (bg != null) {
            MaskStore(context).reader(r.clip.uri)?.use { m ->
                mask = ByteArray(m.width * m.height); mw = m.width; mh = m.height
                m.read(r.clip.srcInMs + (r.localMs * r.clip.speed).toLong(), mask!!)
            }
            if (bg.mode == com.base.editor.core.BgMode.BLUR && mask != null) {
                // дешёвое размытие: уменьшить и растянуть обратно с фильтрацией
                val small = Bitmap.createScaledBitmap(src, maxOf(2, w / 24), maxOf(2, h / 24), true)
                val back = Bitmap.createScaledBitmap(small, w, h, true)
                blurred = IntArray(w * h).also { back.getPixels(it, 0, w, 0, 0, w, h) }
            }
        }
        fun ch(c: Int, shift: Int) = ((c shr shift) and 0xFF) / 255f
        fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
        fun cbcr(r: Float, g: Float, b: Float) = floatArrayOf(-0.168736f * r - 0.331264f * g + 0.5f * b, 0.5f * r - 0.418688f * g - 0.081312f * b)
        val keyC = ck?.let { cbcr(ch(it.color, 16), ch(it.color, 8), ch(it.color, 0)) }
        val sim = (ck?.similarity ?: 0f) * 0.6f; val sm = (ck?.smoothness ?: 0f) * 0.4f
        val m0 = mask
        val outW = (bg?.outlineWidth ?: 0f); val ringR = ((2f + outW * 10f) * w / 1080f).coerceAtLeast(1.5f)
        fun maskAt(x: Int, y: Int): Float {
            val mm = m0 ?: return 1f
            val xi = (x.coerceIn(0, w - 1) * mw / w).coerceIn(0, mw - 1); val yi = (y.coerceIn(0, h - 1) * mh / h).coerceIn(0, mh - 1)
            return smooth(0.30f, 0.70f, (mm[yi * mw + xi].toInt() and 0xFF) / 255f)
        }
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x; val c = px[i]
            var rr = ch(c, 16); var gg = ch(c, 8); var bb = ch(c, 0)
            if (bg != null && m0 != null) {
                val m = maskAt(x, y)
                val br: Float; val bgg: Float; val bbb: Float
                if (bg.mode == com.base.editor.core.BgMode.BLUR && blurred != null) { val bc = blurred[i]; br = ch(bc, 16); bgg = ch(bc, 8); bbb = ch(bc, 0) }
                else { br = ch(bg.bgColor, 16); bgg = ch(bg.bgColor, 8); bbb = ch(bg.bgColor, 0) }
                var or = br + (rr - br) * m; var og = bgg + (gg - bgg) * m; var ob = bbb + (bb - bbb) * m
                if (bg.outline) {
                    var ring = 0f
                    for (k in 0 until 8) { val a = k * Math.PI.toFloat() / 4f; ring = maxOf(ring, maskAt(x + (Math.cos(a.toDouble()) * ringR).toInt(), y + (Math.sin(a.toDouble()) * ringR).toInt())) }
                    val o = (ring - m).coerceIn(0f, 1f)
                    or += (ch(bg.outlineColor, 16) - or) * o; og += (ch(bg.outlineColor, 8) - og) * o; ob += (ch(bg.outlineColor, 0) - ob) * o
                }
                rr = or; gg = og; bb = ob
            }
            if (ck != null && keyC != null) {
                val q = cbcr(rr, gg, bb)
                val d = Math.hypot((q[0] - keyC[0]).toDouble(), (q[1] - keyC[1]).toDouble()).toFloat()
                val a = smooth(sim, sim + maxOf(sm, 0.002f), d)
                val spill = 1f - smooth(sim, sim + sm + 0.12f, d)
                val luma = 0.299f * rr + 0.587f * gg + 0.114f * bb
                val cr = rr + (luma - rr) * spill * 0.6f; val cg = gg + (luma - gg) * spill * 0.6f; val cb = bb + (luma - bb) * spill * 0.6f
                rr = ch(ck.bgColor, 16) + (cr - ch(ck.bgColor, 16)) * a; gg = ch(ck.bgColor, 8) + (cg - ch(ck.bgColor, 8)) * a; bb = ch(ck.bgColor, 0) + (cb - ch(ck.bgColor, 0)) * a
            }
            px[i] = (0xFF shl 24) or ((rr * 255f).toInt().coerceIn(0, 255) shl 16) or ((gg * 255f).toInt().coerceIn(0, 255) shl 8) or (bb * 255f).toInt().coerceIn(0, 255)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun crop(b: Bitmap, c: CropRect): Bitmap {
        if (c.isFull) return b
        val x = (c.left * b.width).toInt().coerceIn(0, b.width - 1); val y = (c.top * b.height).toInt().coerceIn(0, b.height - 1)
        val w = (c.width * b.width).toInt().coerceIn(1, b.width - x); val h = (c.height * b.height).toInt().coerceIn(1, b.height - y)
        return Bitmap.createBitmap(b, x, y, w, h)
    }

    private companion object { const val TAG = "BaseScrub"; const val MAX_SIDE = 720 }
}
