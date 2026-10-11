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
    /** Тап по слою текста (id), по пустому месту видео (null). */
    fun onTapText(id: String)
    fun onTapVideo()
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
                            // Движение свободное: положение и поворот НИЧЕГО не «магнитят» на ходу. Линии лишь подсвечиваются и
                            // слегка вибрируют, когда элемент оказался в нескольких dp от центра; ровно он встаёт при отпускании.
                            val tx = NEAR_DP.dp.toPx() / w; val ty = NEAR_DP.dp.toPx() / h
                            fun nearRight(raw: Float): Boolean { val k = Math.round(raw / 90f) * 90f; return abs(raw - k) < 1.5f }
                            when (b) {
                                is CanvasTarget.Clip -> {
                                    val nx = b.transform.x + accPan.x / w; val ny = b.transform.y + accPan.y / h
                                    val rawRot = b.transform.rotationDeg + accRot
                                    val sx = abs(nx) < tx; val sy = abs(ny) < ty; val rs = nearRight(rawRot)
                                    if ((sx && !snapX) || (sy && !snapY) || (rs && !rotSnapped)) view.haptic(Haptic.SNAP)
                                    snapX = sx; snapY = sy; rotSnapped = rs
                                    lastClip = b.transform.copy(x = nx, y = ny, scale = b.transform.scale * accZoom, rotationDeg = rawRot).sane()
                                    act.onClipTransform(b.id, lastClip!!)
                                }
                                is CanvasTarget.Text -> {
                                    val px = (b.clip.positionX + accPan.x / w).coerceIn(-0.2f, 1.2f); val py = (b.clip.positionY + accPan.y / h).coerceIn(-0.2f, 1.2f)
                                    val rawRot = b.clip.rotationDeg + accRot
                                    val sx = abs(px - 0.5f) < tx; val sy = abs(py - 0.5f) < ty; val rs = nearRight(rawRot)
                                    if ((sx && !snapX) || (sy && !snapY) || (rs && !rotSnapped)) view.haptic(Haptic.SNAP)
                                    snapX = sx; snapY = sy; rotSnapped = rs
                                    lastText = b.clip.copy(positionX = px, positionY = py, fontSizeSp = (b.clip.fontSizeSp * accZoom).coerceIn(8f, 160f), rotationDeg = rawRot)
                                    act.onTextTransform(lastText!!, b.isDraft)
                                }
                            }
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })

                    if (transforming) {
                        // отпустили: если элемент почти по центру / поворот почти ровный — ставим в точности ровно
                        val b = base; val w = size.width.toFloat(); val h = size.height.toFloat()
                        val ex = SETTLE_DP.dp.toPx() / w; val ey = SETTLE_DP.dp.toPx() / h
                        fun settleRot(r: Float): Float { val k = Math.round(r / 90f) * 90f; return if (abs(r - k) < 2.5f) k else r }
                        if (b is CanvasTarget.Clip) lastClip?.let { c ->
                            val f = c.copy(x = if (abs(c.x) < ex) 0f else c.x, y = if (abs(c.y) < ey) 0f else c.y, rotationDeg = settleRot(c.rotationDeg))
                            if (f != c) { act.onClipTransform(b.id, f); view.haptic(Haptic.SNAP) }
                        }
                        if (b is CanvasTarget.Text) lastText?.let { c ->
                            val f = c.copy(positionX = if (abs(c.positionX - 0.5f) < ex) 0.5f else c.positionX, positionY = if (abs(c.positionY - 0.5f) < ey) 0.5f else c.positionY, rotationDeg = settleRot(c.rotationDeg))
                            if (f != c) { act.onTextTransform(f, (b as CanvasTarget.Text).isDraft); view.haptic(Haptic.SNAP) }
                        }
                        act.onGestureEnd(); dragging = false; snapX = false; snapY = false
                    }
                    else {
                        // тап: сверху вниз ищем текстовый слой под пальцем, иначе — видео
                        val hit = hitText(measurer, currentTexts, down.position, Size(size.width.toFloat(), size.height.toFloat()), this)
                        if (hit != null) act.onTapText(hit) else act.onTapVideo()
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
            }
            val t = target ?: return@Canvas
            when (t) {
                is CanvasTarget.Text -> measureBox(measurer, t.clip)?.let { b ->
                    drawSelection(Offset(b.centerX, b.centerY), b.width, b.height, b.rotationDeg)
                }
                is CanvasTarget.Clip -> {
                    // рамка строго по вписанному кадру клипа (ContentScale.Fit), а не по всему холсту;
                    // масштаб и поворот идут вокруг центра кадра, как в MultiTouchListener (PhotoEditor)
                    val c = t.transform
                    val (fw, fh) = fitSize(size.width, size.height, t.aspect)
                    drawSelection(Offset(size.width / 2 + c.x * size.width, size.height / 2 + c.y * size.height), fw * c.scale, fh * c.scale, c.rotationDeg)
                }
            }
        }
    }
}

/** Подсветка линий центра при приближении (dp) и точная постановка по центру при отпускании (dp). */
private const val NEAR_DP = 5
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
