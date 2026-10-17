package com.base.editor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.base.editor.core.CropRect
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.animatedSelectColor
import com.base.editor.ui.theme.pressable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Пресеты пропорций рамки (в пикселях кадра). null — свободная рамка. */
private enum class CropPreset(val label: String, val ratio: Float?) {
    FREE("Свободный", null), V9_16("9:16", 9f / 16f), H16_9("16:9", 16f / 9f), SQ("1:1", 1f), P4_5("4:5", 4f / 5f), P3_4("3:4", 3f / 4f)
}

private const val MIN_SIDE_PX = 48f

/**
 * Окно кадрирования: кадр клипа, рамка с сеткой 3×3 и угловыми маркерами. Рамку можно тянуть за углы и переносить.
 * Результат — CropRect в долях исходного кадра; дальше он идёт в androidx.media3.effect.Crop (превью и экспорт).
 */
@Composable
fun CropDialog(frame: ImageBitmap?, initial: CropRect, onDone: (CropRect?) -> Unit, onCancel: () -> Unit) {
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        var rect by remember(initial) { mutableStateOf(initial) }
        var preset by remember { mutableStateOf(CropPreset.FREE) }
        Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Отмена", color = Color.White, fontSize = 15.sp, modifier = Modifier.pressable(onClick = onCancel).clip(RoundedCornerShape(10.dp)).padding(10.dp))
                Box(Modifier.weight(1f))
                Text("Сброс", color = Color.White.copy(alpha = .8f), fontSize = 15.sp,
                    modifier = Modifier.pressable { rect = CropRect(); preset = CropPreset.FREE }.clip(RoundedCornerShape(10.dp)).padding(10.dp))
                Text("Готово", color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.pressable { onDone(rect.takeIf { !it.isFull }) }.clip(RoundedCornerShape(10.dp)).background(BaseColors.Primary).padding(horizontal = 16.dp, vertical = 10.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                if (frame == null) Text("Загрузка кадра…", color = Color.White.copy(alpha = .6f))
                else {
                    val imgAspect = frame.width.toFloat() / frame.height
                    Box(Modifier.aspectRatio(imgAspect)) {
                        Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        CropFrame(imgAspect, rect, preset.ratio, onChange = { rect = it })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CropPreset.entries.forEach { p ->
                    val on = p == preset
                    Box(
                        Modifier.pressable {
                                // повторный тап по выбранной пропорции снимает её; «Свободный» всегда полностью снимает блокировку
                                preset = if (p == preset && p != CropPreset.FREE) CropPreset.FREE else p
                                if (frame != null && preset.ratio != null) rect = fitRatio(frame.width.toFloat() / frame.height, preset.ratio!!, rect)
                            }.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(animatedSelectColor(on, offColor = Color.White.copy(alpha = .1f)))
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(p.label, color = if (on) Color.Black else Color.White, fontSize = 11.sp, maxLines = 1) }
                }
            }
        }
    }
}

/** Самая большая рамка с пропорциями [ratio] (в пикселях), вписанная в изображение и центрированная на текущей рамке. */
private fun fitRatio(imgAspect: Float, ratio: Float, cur: CropRect): CropRect {
    // работаем в «пиксельных» единицах с высотой 1: ширина изображения = imgAspect
    val w = imgAspect; val h = 1f
    var bw = w; var bh = bw / ratio
    if (bh > h) { bh = h; bw = bh * ratio }
    val cx = ((cur.left + cur.right) / 2 * w).coerceIn(bw / 2, w - bw / 2)
    val cy = ((cur.top + cur.bottom) / 2 * h).coerceIn(bh / 2, h - bh / 2)
    return CropRect((cx - bw / 2) / w, (cy - bh / 2) / h, (cx + bw / 2) / w, (cy + bh / 2) / h).sane()
}

@Composable
private fun CropFrame(imgAspect: Float, rect: CropRect, ratio: Float?, onChange: (CropRect) -> Unit) {
    val curRect by rememberUpdatedState(rect)
    val curRatio by rememberUpdatedState(ratio)
    val onCh by rememberUpdatedState(onChange)
    var dragMode by remember { mutableStateOf(-1) }

    Canvas(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val cornerGrab = 48.dp.toPx()
                val edgeGrab = 36.dp.toPx()
                detectDragGestures(
                    onDragStart = { p ->
                        val W = size.width.toFloat()
                        val H = size.height.toFloat()
                        if (W <= 0f || H <= 0f) {
                            dragMode = -1
                            return@detectDragGestures
                        }
                        val r = curRect
                        val box = Rect(r.left * W, r.top * H, r.right * W, r.bottom * H)
                        val corners = listOf(box.topLeft, box.topRight, box.bottomLeft, box.bottomRight)
                        val nearestCorner = corners.mapIndexed { idx, c -> idx to (c - p).getDistance() }.minByOrNull { it.second }
                        if (nearestCorner != null && nearestCorner.second <= cornerGrab) {
                            dragMode = nearestCorner.first
                        } else {
                            val inVertical = p.y >= (box.top - cornerGrab) && p.y <= (box.bottom + cornerGrab)
                            val inHorizontal = p.x >= (box.left - cornerGrab) && p.x <= (box.right + cornerGrab)
                            val dL = if (inVertical) abs(p.x - box.left) else Float.MAX_VALUE
                            val dT = if (inHorizontal) abs(p.y - box.top) else Float.MAX_VALUE
                            val dR = if (inVertical) abs(p.x - box.right) else Float.MAX_VALUE
                            val dB = if (inHorizontal) abs(p.y - box.bottom) else Float.MAX_VALUE

                            val edges = listOf(4 to dL, 5 to dT, 6 to dR, 7 to dB)
                            val nearestEdge = edges.minByOrNull { it.second }
                            if (nearestEdge != null && nearestEdge.second <= edgeGrab) {
                                dragMode = nearestEdge.first
                            } else if (box.contains(p)) {
                                dragMode = 8
                            } else {
                                dragMode = -1
                            }
                        }
                    },
                    onDrag = { change, delta ->
                        val W = size.width.toFloat()
                        val H = size.height.toFloat()
                        if (W <= 0f || H <= 0f || dragMode < 0) return@detectDragGestures
                        change.consume()
                        val r = curRect
                        val box = Rect(r.left * W, r.top * H, r.right * W, r.bottom * H)
                        val out = when (dragMode) {
                            8 -> {
                                val dx = delta.x.coerceIn(-box.left, W - box.right)
                                val dy = delta.y.coerceIn(-box.top, H - box.bottom)
                                box.translate(dx, dy)
                            }
                            in 0..3 -> dragCorner(box, dragMode, change.position, curRatio, Size(W, H))
                            in 4..7 -> dragEdge(box, dragMode, change.position, delta, curRatio, Size(W, H))
                            else -> box
                        }
                        onCh(
                            CropRect(
                                (out.left / W).coerceIn(0f, 0.95f),
                                (out.top / H).coerceIn(0f, 0.95f),
                                (out.right / W).coerceIn(0.05f, 1f),
                                (out.bottom / H).coerceIn(0.05f, 1f)
                            ).sane()
                        )
                    },
                    onDragEnd = { dragMode = -1 },
                    onDragCancel = { dragMode = -1 }
                )
            }
    ) {
        val W = size.width
        val H = size.height
        val box = Rect(rect.left * W, rect.top * H, rect.right * W, rect.bottom * H)
        val dim = Color.Black.copy(alpha = 0.65f)
        drawRect(dim, Offset.Zero, Size(W, box.top))
        drawRect(dim, Offset(0f, box.bottom), Size(W, H - box.bottom))
        drawRect(dim, Offset(0f, box.top), Size(box.left, box.height))
        drawRect(dim, Offset(box.right, box.top), Size(W - box.right, box.height))

        val thin = Stroke(1.dp.toPx())
        for (k in 1..2) { // сетка 3×3
            val x = box.left + box.width * k / 3
            val y = box.top + box.height * k / 3
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, box.top), Offset(x, box.bottom), thin.width)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(box.left, y), Offset(box.right, y), thin.width)
        }

        drawRect(Color.White, box.topLeft, box.size, style = Stroke(2.dp.toPx()))

        val arm = 20.dp.toPx()
        val sw = 4.dp.toPx()
        fun corner(c: Offset, dx: Float, dy: Float) {
            drawLine(Color.White, Offset(c.x - dx * 2.dp.toPx(), c.y), Offset(c.x + dx * arm, c.y), sw)
            drawLine(Color.White, Offset(c.x, c.y - dy * 2.dp.toPx()), Offset(c.x, c.y + dy * arm), sw)
        }
        corner(box.topLeft, 1f, 1f)
        corner(box.topRight, -1f, 1f)
        corner(box.bottomLeft, 1f, -1f)
        corner(box.bottomRight, -1f, -1f)

        // Маркеры сторон посередине ребер
        val edgeArm = 16.dp.toPx()
        val midX = box.center.x
        val midY = box.center.y
        drawLine(Color.White, Offset(midX - edgeArm, box.top), Offset(midX + edgeArm, box.top), sw)
        drawLine(Color.White, Offset(midX - edgeArm, box.bottom), Offset(midX + edgeArm, box.bottom), sw)
        drawLine(Color.White, Offset(box.left, midY - edgeArm), Offset(box.left, midY + edgeArm), sw)
        drawLine(Color.White, Offset(box.right, midY - edgeArm), Offset(box.right, midY + edgeArm), sw)
    }
}

/** Тянем угол [idx] (0 TL, 1 TR, 2 BL, 3 BR) к точке [p]; противоположный угол неподвижен. Рамка не выходит за [bounds]. */
private fun dragCorner(box: Rect, idx: Int, p: Offset, ratio: Float?, bounds: Size): Rect {
    val fixed = when (idx) { 0 -> box.bottomRight; 1 -> box.bottomLeft; 2 -> box.topRight; else -> box.topLeft }
    val x = p.x.coerceIn(0f, bounds.width)
    val y = p.y.coerceIn(0f, bounds.height)
    val sx = if (x >= fixed.x) 1f else -1f
    val sy = if (y >= fixed.y) 1f else -1f
    var w = abs(x - fixed.x)
    var h = abs(y - fixed.y)
    if (ratio != null) {
        if (w / ratio > h) h = w / ratio else w = h * ratio
    }
    val maxW = if (sx > 0) bounds.width - fixed.x else fixed.x
    val maxH = if (sy > 0) bounds.height - fixed.y else fixed.y
    if (w > maxW || h > maxH) {
        val k = min(maxW / max(w, 1f), maxH / max(h, 1f))
        w *= k
        h *= k
    }
    if (w < MIN_SIDE_PX || h < MIN_SIDE_PX) {
        if (ratio != null) {
            w = max(w, MIN_SIDE_PX)
            h = w / ratio
            if (h < MIN_SIDE_PX) { h = MIN_SIDE_PX; w = h * ratio }
        } else {
            w = max(w, MIN_SIDE_PX)
            h = max(h, MIN_SIDE_PX)
        }
        w = min(w, maxW)
        h = min(h, maxH)
    }
    val x2 = fixed.x + sx * w
    val y2 = fixed.y + sy * h
    return Rect(min(fixed.x, x2), min(fixed.y, y2), max(fixed.x, x2), max(fixed.y, y2))
}

/** Тянем сторону [idx] (4: Left, 5: Top, 6: Right, 7: Bottom). */
private fun dragEdge(box: Rect, idx: Int, p: Offset, delta: Offset, ratio: Float?, bounds: Size): Rect {
    if (ratio == null) {
        return when (idx) {
            4 -> box.copy(left = (box.left + delta.x).coerceIn(0f, box.right - MIN_SIDE_PX))
            5 -> box.copy(top = (box.top + delta.y).coerceIn(0f, box.bottom - MIN_SIDE_PX))
            6 -> box.copy(right = (box.right + delta.x).coerceIn(box.left + MIN_SIDE_PX, bounds.width))
            7 -> box.copy(bottom = (box.bottom + delta.y).coerceIn(box.top + MIN_SIDE_PX, bounds.height))
            else -> box
        }
    } else {
        val minW = MIN_SIDE_PX
        val minH = minW / ratio
        return when (idx) {
            4 -> { // Left: right is fixed
                var w = (box.right - p.x).coerceIn(minW, bounds.width)
                var h = w / ratio
                if (h > bounds.height) { h = bounds.height; w = h * ratio }
                val cy = box.center.y
                val top = (cy - h / 2f).coerceIn(0f, bounds.height - h)
                val newL = (box.right - w).coerceIn(0f, box.right - minW)
                Rect(newL, top, box.right, top + h)
            }
            6 -> { // Right: left is fixed
                var w = (p.x - box.left).coerceIn(minW, bounds.width - box.left)
                var h = w / ratio
                if (h > bounds.height) { h = bounds.height; w = h * ratio }
                val cy = box.center.y
                val top = (cy - h / 2f).coerceIn(0f, bounds.height - h)
                val newR = (box.left + w).coerceIn(box.left + minW, bounds.width)
                Rect(box.left, top, newR, top + h)
            }
            5 -> { // Top: bottom is fixed
                var h = (box.bottom - p.y).coerceIn(minH, bounds.height)
                var w = h * ratio
                if (w > bounds.width) { w = bounds.width; h = w / ratio }
                val cx = box.center.x
                val left = (cx - w / 2f).coerceIn(0f, bounds.width - w)
                val newT = (box.bottom - h).coerceIn(0f, box.bottom - minH)
                Rect(left, newT, left + w, box.bottom)
            }
            7 -> { // Bottom: top is fixed
                var h = (p.y - box.top).coerceIn(minH, bounds.height - box.top)
                var w = h * ratio
                if (w > bounds.width) { w = bounds.width; h = w / ratio }
                val cx = box.center.x
                val left = (cx - w / 2f).coerceIn(0f, bounds.width - w)
                val newB = (box.top + h).coerceIn(box.top + minH, bounds.height)
                Rect(left, box.top, left + w, newB)
            }
            else -> box
        }
    }
}
