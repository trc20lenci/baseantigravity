package com.base.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.Alignment
import com.base.editor.ui.theme.haptic
import com.base.editor.ui.theme.Haptic
import com.base.editor.ui.theme.BaseColors
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.base.editor.core.ClipTransform
import com.base.editor.text.TextClip
import com.base.editor.text.TextClipRenderer.measureBox
import kotlin.math.abs

/** Что сейчас выделено на холсте. */
sealed interface CanvasTarget {
    /** Видео/фото клип: его кадр можно двигать, масштабировать и вращать. */
    data class Clip(val id: Long, val transform: ClipTransform, val aspect: Float) : CanvasTarget
    data class Text(val clip: TextClip, val isDraft: Boolean) : CanvasTarget
}

/** Действия жестов; вызываются на главном потоке. */
interface CanvasActions {
    fun onGestureStart()
    fun onClipTransform(clipId: Long, transform: ClipTransform)
    fun onTextTransform(clip: TextClip, isDraft: Boolean)
    fun onGestureEnd()
    /** Тап по слою текста (id), по видео с координатами касания (0..1). */
    fun onTapText(id: String)
    fun onTapVideo(xRatio: Float = -1f, yRatio: Float = -1f)
}

/**
 * Интерактивный слой поверх плеера. Логика жестов перенесена из PhotoEditor
 * (MultiTouchListener.java, MIT): в начале касания запоминается исходная трансформация,
 * дальше pan / pinch / rotate считаются как ПОЛНОЕ смещение жеста от этой точки
 * (translate = base + pan, scale = base * zoom, rotation = base + angle) и применяются
 * относительно ЦЕНТРА вписанного в кадр клипа — контейнер никогда не растягивается,
 * пропорции исходника жёстко соблюдаются (ContentScale.Fit / Presentation.LAYOUT_SCALE_TO_FIT).
 * Работает для выделенного клипа и для текстовых слоёв.
 * Выделенный элемент обводится рамкой с маркерами по углам.
 */
@Composable
fun CanvasTransformOverlay(
    videoAspect: Float,
    target: CanvasTarget?,
    texts: List<TextClip>,
    actions: CanvasActions,
    live: androidx.compose.runtime.State<LiveClip?>,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val slop = LocalViewConfiguration.current.touchSlop
    val currentTarget by rememberUpdatedState(target)
    val currentTexts by rememberUpdatedState(texts)
    val act by rememberUpdatedState(actions)
    val view = androidx.compose.ui.platform.LocalView.current
    // привязка к центру: линии показываются, пока элемент тянут, и мигают, когда он «встал» ровно по центру
    var dragging by remember { mutableStateOf(false) }
    var snapX by remember { mutableStateOf(false) }
    var snapY by remember { mutableStateOf(false) }
    var snapScale by remember { mutableStateOf(false) }
    val blink by androidx.compose.animation.core.rememberInfiniteTransition(label = "snapBlink").animateFloat(
        0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(380), androidx.compose.animation.core.RepeatMode.Reverse), label = "blink",
    )

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier.aspectRatio(videoAspect.coerceIn(0.2f, 5f)).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var transforming = false
                    var accPan = Offset.Zero; var accZoom = 1f; var accRot = 0f
                    var base: CanvasTarget? = null                 // состояние элемента на момент начала жеста
                    var rotSnapped = false
                    var lastClip: ClipTransform? = null; var lastText: TextClip? = null
                    do {
                        val event = awaitPointerEvent()
                        val pan = event.calculatePan(); val zoom = event.calculateZoom(); val rot = event.calculateRotation()
                        accPan += pan; accZoom *= zoom; accRot += rot
                        val t = currentTarget
                        if (!transforming && t != null &&
                            (accPan.getDistance() > slop || abs(accZoom - 1f) > 0.04f || abs(accRot) > 3f)) {
                            transforming = true; base = t; act.onGestureStart()
                        }
                        val b = base
                        if (transforming && b != null && event.changes.any { it.pressed }) {
                            val w = size.width.toFloat(); val h = size.height.toFloat()
                            dragging = true
                            // Магнит к центру с «гистерезисом»: рядом с центром элемент прилипает и держится ровно, пока палец не отведён
                            // заметно дальше; вдали от центра движение полностью свободное. Линии мигают, пока он «на месте».
                            val catchX = CATCH_DP.dp.toPx() / w; val catchY = CATCH_DP.dp.toPx() / h
                            val freeX = RELEASE_DP.dp.toPx() / w; val freeY = RELEASE_DP.dp.toPx() / h
                            fun angleOff(raw: Float): Float { val k = Math.round(raw / 90f) * 90f; return abs(raw - k) }
                            fun angleSnap(raw: Float) = Math.round(raw / 90f) * 90f
                            when (b) {
                                is CanvasTarget.Clip -> {
                                    var nx = b.transform.x + accPan.x / w; var ny = b.transform.y + accPan.y / h
                                    val rawRot = b.transform.rotationDeg + accRot
                                    val rawScale = b.transform.scale * accZoom
                                    val hx = if (snapX) abs(nx) < freeX else abs(nx) < catchX
                                    val hy = if (snapY) abs(ny) < freeY else abs(ny) < catchY
                                    val hr = if (rotSnapped) angleOff(rawRot) < ROT_RELEASE else angleOff(rawRot) < ROT_CATCH
                                    val hs = if (snapScale) abs(rawScale - 1.0f) < 0.08f else abs(rawScale - 1.0f) < 0.04f
                                    if ((hx && !snapX) || (hy && !snapY) || (hr && !rotSnapped) || (hs && !snapScale)) view.haptic(Haptic.SNAP)
                                    snapX = hx; snapY = hy; rotSnapped = hr; snapScale = hs
                                    if (hx) nx = 0f; if (hy) ny = 0f
                                    val rot = if (hr) angleSnap(rawRot) else rawRot
                                    val finalScale = if (hs) 1.0f else rawScale
                                    lastClip = b.transform.copy(x = nx, y = ny, scale = finalScale, rotationDeg = rot).sane()
                                    act.onClipTransform(b.id, lastClip!!)
                                }
                                is CanvasTarget.Text -> {
                                    var px = (b.clip.positionX + accPan.x / w).coerceIn(-0.2f, 1.2f); var py = (b.clip.positionY + accPan.y / h).coerceIn(-0.2f, 1.2f)
                                    val rawRot = b.clip.rotationDeg + accRot
                                    val hx = if (snapX) abs(px - 0.5f) < freeX else abs(px - 0.5f) < catchX
                                    val hy = if (snapY) abs(py - 0.5f) < freeY else abs(py - 0.5f) < catchY
                                    val hr = if (rotSnapped) angleOff(rawRot) < ROT_RELEASE else angleOff(rawRot) < ROT_CATCH
                                    if ((hx && !snapX) || (hy && !snapY) || (hr && !rotSnapped)) view.haptic(Haptic.SNAP)
                                    snapX = hx; snapY = hy; rotSnapped = hr
                                    if (hx) px = 0.5f; if (hy) py = 0.5f
                                    val rot = if (hr) angleSnap(rawRot) else rawRot
                                    lastText = b.clip.copy(positionX = px, positionY = py, fontSizeSp = (b.clip.fontSizeSp * accZoom).coerceIn(8f, 160f), rotationDeg = rot)
                                    act.onTextTransform(lastText!!, b.isDraft)
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (transforming) {
                        // отпустили: если элемент почти по центру / поворот почти ровный / масштаб почти 1.0 — ставим в точности ровно
                        val b = base; val w = size.width.toFloat(); val h = size.height.toFloat()
                        val ex = SETTLE_DP.dp.toPx() / w; val ey = SETTLE_DP.dp.toPx() / h
                        fun settleRot(r: Float): Float { val k = Math.round(r / 90f) * 90f; return if (abs(r - k) < 2.5f) k else r }
                        if (b is CanvasTarget.Clip) lastClip?.let { c ->
                            val s = if (abs(c.scale - 1.0f) < 0.05f) 1.0f else c.scale
                            val f = c.copy(
                                x = if (abs(c.x) < ex) 0f else c.x,
                                y = if (abs(c.y) < ey) 0f else c.y,
                                scale = s,
                                rotationDeg = settleRot(c.rotationDeg)
                            )
                            if (f != c) { act.onClipTransform(b.id, f); view.haptic(Haptic.SNAP) }
                        }
                        if (b is CanvasTarget.Text) lastText?.let { c ->
                            val f = c.copy(positionX = if (abs(c.positionX - 0.5f) < ex) 0.5f else c.positionX, positionY = if (abs(c.positionY - 0.5f) < ey) 0.5f else c.positionY, rotationDeg = settleRot(c.rotationDeg))
                            if (f != c) { act.onTextTransform(f, (b as CanvasTarget.Text).isDraft); view.haptic(Haptic.SNAP) }
                        }
                        act.onGestureEnd(); dragging = false; snapX = false; snapY = false; snapScale = false
                    }
                    else {
                        // тап: сверху вниз ищем текстовый слой под пальцем, иначе — видео
                        val hit = hitText(measurer, currentTexts, down.position, Size(size.width.toFloat(), size.height.toFloat()), this)
                        if (hit != null) {
                            act.onTapText(hit)
                        } else {
                            val xr = if (size.width > 0) down.position.x / size.width.toFloat() else -1f
                            val yr = if (size.height > 0) down.position.y / size.height.toFloat() else -1f
                            act.onTapVideo(xr, yr)
                        }
                    }
                }
            },
        ) {
            if (dragging) {
                val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
                val on = BaseColors.Primary
                // вертикальная и горизонтальная линии по центру; когда элемент «встал» ровно — линия яркая и мигает
                drawLine(on.copy(alpha = if (snapX) blink else 0.28f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height),
                    strokeWidth = if (snapX) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (snapX) null else dash)
                drawLine(on.copy(alpha = if (snapY) blink else 0.28f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2),
                    strokeWidth = if (snapY) 2.dp.toPx() else 1.dp.toPx(), pathEffect = if (snapY) null else dash)

                // Обозначение идеального кадра по увеличению (1.0x): подсвечиваем идеальные границы вписанного кадра
                if (snapScale) {
                    val clipAspect = (target as? CanvasTarget.Clip)?.aspect ?: videoAspect
                    val (idealW, idealH) = fitSize(size.width, size.height, clipAspect)
                    val idealTl = Offset((size.width - idealW) / 2f, (size.height - idealH) / 2f)
                    drawRect(
                        color = on.copy(alpha = blink),
                        topLeft = idealTl,
                        size = Size(idealW, idealH),
                        style = Stroke(width = 2.dp.toPx(), pathEffect = dash)
                    )
                }
            }
            val t = target ?: return@Canvas
            when (t) {
                is CanvasTarget.Text -> measureBox(measurer, t.clip)?.let { b ->
                    drawSelection(Offset(b.centerX, b.centerY), b.width, b.height, b.rotationDeg)
                }
                is CanvasTarget.Clip -> {
                    // рамка строго по вписанному кадру клипа (ContentScale.Fit), а не по всему холсту;
                    // масштаб и поворот идут вокруг центра кадра, как в MultiTouchListener (PhotoEditor)
                    // положение под пальцами читается ЗДЕСЬ, в отрисовке: жест перерисовывает только рамку, без рекомпозиции экрана
                    val c = live.value?.takeIf { it.id == t.id }?.current ?: t.transform
                    val (fw, fh) = fitSize(size.width, size.height, t.aspect)
                    drawSelection(Offset(size.width / 2 + c.x * size.width, size.height / 2 + c.y * size.height), fw * c.scale, fh * c.scale, c.rotationDeg)
                }
            }
        }
    }
}

/** Подсветка линий центра при приближении (dp) и точная постановка по центру при отпускании (dp). */
private const val CATCH_DP = 9          // на каком расстоянии от центра элемент прилипает
private const val RELEASE_DP = 18       // на каком — отлипает (больше, чем «прилипает»: магнит «держит»)
private const val ROT_CATCH = 3f        // градусы
private const val ROT_RELEASE = 7f
private const val SETTLE_DP = 7

/** Размер кадра с соотношением [aspect], вписанного в холст w×h без растяжения (ContentScale.Fit). */
internal fun fitSize(w: Float, h: Float, aspect: Float): Pair<Float, Float> {
    val a = aspect.coerceIn(0.05f, 20f)
    return if (a >= w / h) w to w / a else h * a to h
}

private fun hitText(measurer: TextMeasurer, texts: List<TextClip>, p: Offset, size: Size, density: androidx.compose.ui.unit.Density): String? {
    val scope = androidx.compose.ui.graphics.drawscope.CanvasDrawScope()
    var found: String? = null
    val bmp = androidx.compose.ui.graphics.ImageBitmap(1, 1)
    scope.draw(density, androidx.compose.ui.unit.LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bmp), size) {
        for (t in texts.asReversed()) {                          // верхний слой — последний
            val b = measureBox(measurer, t) ?: continue
            if (b.contains(p.x, p.y, slop = 12.dp.toPx())) { found = t.id; break }
        }
    }
    return found
}

/** Тонкая рамка с угловыми маркерами; поворачивается вместе с элементом. */
private fun DrawScope.drawSelection(center: Offset, w: Float, h: Float, rotationDeg: Float) {
    withTransform({ rotate(rotationDeg, center) }) {
        val tl = Offset(center.x - w / 2, center.y - h / 2)
        drawRect(Color.White, tl, Size(w, h), style = Stroke(1.5.dp.toPx()))
        val r = 5.dp.toPx()
        listOf(tl, Offset(tl.x + w, tl.y), Offset(tl.x, tl.y + h), Offset(tl.x + w, tl.y + h)).forEach {
            drawCircle(Color.White, r, it); drawCircle(Color(0xFF00CCDD), r * 0.55f, it)
        }
    }
}
