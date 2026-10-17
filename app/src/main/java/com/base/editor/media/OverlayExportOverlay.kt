package com.base.editor.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Size
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import com.base.editor.core.Clip
import com.base.editor.core.ClipTransform
import com.base.editor.core.MediaType
import kotlin.math.max

/**
 * Отрисовка слоёв наложений (фото/картинки) при экспорте видео через единый конвейер BitmapOverlay.
 * Исключает необходимость в тяжелом MultipleInputVideoGraph для статических наложений.
 */
@UnstableApi
class OverlayExportOverlay(
    private val context: Context,
    private val clips: List<Clip>,
    private val canvas: Size,
    private val transformAt: (Long, Long) -> ClipTransform,
) : BitmapOverlay() {
    private val w = max(2, canvas.width)
    private val h = max(2, canvas.height)
    private val bitmap: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val blank: Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    private val canvasDraw = android.graphics.Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val imageCache = mutableMapOf<String, Bitmap>()
    private val settings: OverlaySettings = StaticOverlaySettings.Builder()
        .setOverlayFrameAnchor(0f, 0f)
        .setBackgroundFrameAnchor(0f, 0f)
        .build()

    init {
        // Предзагрузка изображений наложений
        for (c in clips) {
            if (c.type == MediaType.IMAGE && !imageCache.containsKey(c.uri)) {
                try {
                    val src = android.graphics.ImageDecoder.createSource(context.contentResolver, Uri.parse(c.uri))
                    val bmp = android.graphics.ImageDecoder.decodeBitmap(src) { dec, _, _ ->
                        dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                    imageCache[c.uri] = bmp
                } catch (e: Exception) {
                    android.util.Log.e("BaseOverlay", "Не удалось загрузить наложение для экспорта: ${c.uri}", e)
                }
            }
        }
    }

    private val retrievers = mutableMapOf<String, android.media.MediaMetadataRetriever>()

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val ms = presentationTimeUs / 1000
        val active = clips.filter { ms >= it.startMs && ms < it.endMs }
        if (active.isEmpty()) return blank

        bitmap.eraseColor(Color.TRANSPARENT)
        val ca = w.toFloat() / h

        for (c in active) {
            val srcBmp = (imageCache[c.uri] ?: if (c.type == MediaType.VIDEO) {
                val localMs = ((ms - c.startMs) * c.speed).toLong().coerceAtLeast(0L)
                val retriever = retrievers.getOrPut(c.uri) {
                    android.media.MediaMetadataRetriever().apply { setDataSource(context, Uri.parse(c.uri)) }
                }
                com.base.editor.media.MediaFrames.scaledFrame(retriever, (c.srcInMs + localMs) * 1000, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, max(w, h))
            } else null) ?: continue
            val t = transformAt(c.id, ms - c.startMs)
            val fa = srcBmp.width.toFloat() / srcBmp.height

            // Геометрия кадра (ContentScale.Fit)
            val (fw, fh) = if (fa >= ca) w.toFloat() to (w / fa) else (h * fa) to h.toFloat()
            val scale = if (t.scale.isFinite() && t.scale > 0.01f) t.scale else 1f
            val rot = if (t.rotationDeg.isFinite()) t.rotationDeg else 0f
            val cx = w / 2f + t.x * w
            val cy = h / 2f + t.y * h

            val m = Matrix()
            m.postScale(fw / srcBmp.width, fh / srcBmp.height)
            m.postTranslate(-fw / 2f, -fh / 2f)
            m.postScale(scale, scale)
            m.postRotate(rot)
            m.postTranslate(cx, cy)

            canvasDraw.drawBitmap(srcBmp, m, paint)
        }
        return bitmap
    }
}
