package com.base.editor.ui.editor

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import android.util.Log
import com.base.editor.captions.CaptionItem
import com.base.editor.text.TextClip
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.core.Clip
import com.base.editor.core.MediaType
import com.base.editor.core.Transition
import com.base.editor.data.Format
import com.base.editor.data.Thumbs
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.haptic
import com.base.editor.ui.theme.Haptic
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Мобильный многодорожечный холст: курсор (playhead) неподвижен по центру, шкала едет под ним.
 *  • сдвиг пальцем по пустому месту/невыбранному клипу — скраб (+ инерция)
 *  • щипок двумя пальцами — зум
 *  • перетаскивание ВЫБРАННОГО клипа — перемещение (с магнитом и «перепрыгиванием» соседей)
 *  • тянуть за белые ручки по краям выбранного клипа — Trim
 */
@Composable
fun TimelineView(
    clips: List<Clip>, transitions: List<Transition>, captions: List<CaptionItem>, texts: List<TextClip>,
    keyframes: Map<Long, List<com.base.editor.core.Keyframe>>, selectedId: Long?, selectedTextId: String?, selectedCaptionId: String?, playheadMs: Long, totalMs: Long, pxPerSecDp: Float,
    actions: TimelineActions, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val scope = rememberCoroutineScope()
    var widthPx by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }          // перерисовка после подгрузки миниатюр

    val hasOverlay = clips.any { it.row == 2 && it.type != MediaType.AUDIO }
    val hasText = texts.isNotEmpty()
    val hasCaptions = captions.isNotEmpty()
    val geo = Geo(density, widthPx.toFloat(), playheadMs, pxPerSecDp, hasOverlay, hasText, hasCaptions)
    val cur by rememberUpdatedState(TlState(geo, clips, transitions, captions, texts, keyframes, selectedId, selectedTextId, selectedCaptionId, totalMs))
    val act by rememberUpdatedState(actions)

    // Очередь миниатюр: draw только регистрирует недостающие ключи, загрузка — здесь.
    val pending = remember { LinkedHashMap<String, ThumbReq>() }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60)
            val batch = synchronized(pending) { pending.values.take(6).also { b -> b.forEach { pending.remove(it.key) } } }
            if (batch.isEmpty()) continue
            batch.forEach { Thumbs.load(ctx, it.uri, it.type, it.bucketMs, it.h) }
            tick++
        }
    }

    val flingRef = remember { arrayOfNulls<Job>(1) }
    val view = androidx.compose.ui.platform.LocalView.current

    Canvas(
        modifier.fillMaxWidth().height(TL_HEIGHT_DP.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                awaitEachGesture {
                    flingRef[0]?.cancel()
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val s0 = cur
                    val hit = s0.hit(down.position)

                    fun tap(h: Hit) = when (h) {
                        is Hit.Body -> act.select(h.clip.id)
                        is Hit.Handle -> act.select(h.clip.id)
                        is Hit.Junction -> act.openTransitions(h.leftId)
                        is Hit.Text -> act.openText(h.id)
                        is Hit.Caption -> act.openCaption(h.id)
                        is Hit.TextHandle -> act.openText(h.id)
                        is Hit.CaptionHandle -> act.openCaption(h.id)
                        Hit.AudioSlot -> act.addAudio()
                        Hit.OverlaySlot -> act.addOverlay()
                        Hit.TextSlot -> act.addText()
                        Hit.None -> act.select(null)
                    }

                    // Долгое нажатие на блок текста/субтитров: блок «отрывается» и едет за пальцем вдоль шкалы времени
                    val selectedBlock = (hit is Hit.Text && hit.id == s0.selectedTextId) || (hit is Hit.Caption && hit.id == s0.selectedCaptionId)
                    if (selectedBlock) {
                        val pressed = awaitLongPressOrCancellation(down.id)
                        if (pressed != null) view.haptic(Haptic.LONG)
                        if (pressed != null) {
                            val baseStart = when (hit) {
                                is Hit.Text -> s0.texts.firstOrNull { it.id == hit.id }?.startMs
                                is Hit.Caption -> s0.captions.firstOrNull { it.id == hit.id }?.startMs
                                else -> null
                            } ?: return@awaitEachGesture
                            val x0 = pressed.position.x
                            val edgeThreshold = with(density) { 48.dp.toPx() }
                            var lpAutoScrollMs = 0L
                            var lpPointerX = pressed.position.x
                            var lpRatio = 0f
                            var lpJob: Job? = null
                            try {
                                while (true) {
                                    val ch = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) break
                                    lpPointerX = ch.position.x
                                    val r = when {
                                        widthPx > 0 && lpPointerX < edgeThreshold -> -((edgeThreshold - lpPointerX) / edgeThreshold).coerceIn(0f, 1f)
                                        widthPx > 0 && lpPointerX > widthPx - edgeThreshold -> ((lpPointerX - (widthPx - edgeThreshold)) / edgeThreshold).coerceIn(0f, 1f)
                                        else -> 0f
                                    }
                                    lpRatio = r
                                    if (abs(r) > 0.05f && (lpJob == null || lpJob?.isActive != true)) {
                                        lpJob = scope.launch {
                                            while (isActive) {
                                                delay(16)
                                                if (abs(lpRatio) > 0.05f) {
                                                    val stepMs = (lpRatio * 1800f * 0.016f).toLong()
                                                    if (stepMs != 0L) {
                                                        lpAutoScrollMs += stepMs
                                                        val newStart = (baseStart + ((lpPointerX - x0) / cur.geo.pxPerMs).toLong() + lpAutoScrollMs).coerceAtLeast(0L)
                                                        if (hit is Hit.Text) act.moveText(hit.id, newStart) else if (hit is Hit.Caption) act.moveCaption(hit.id, newStart)
                                                        act.scrubTo(newStart)
                                                    }
                                                }
                                            }
                                        }
                                    } else if (abs(r) <= 0.05f) {
                                        lpJob?.cancel(); lpJob = null
                                    }
                                    val newStart = (baseStart + ((ch.position.x - x0) / cur.geo.pxPerMs).toLong() + lpAutoScrollMs).coerceAtLeast(0L)
                                    if (hit is Hit.Text) act.moveText(hit.id, newStart) else if (hit is Hit.Caption) act.moveCaption(hit.id, newStart)
                                    ch.consume()
                                }
                            } finally {
                                lpJob?.cancel()
                            }
                            return@awaitEachGesture
                        }
                        // отпустили до долгого нажатия — обычный тап; сдвинули палец — продолжаем как скраб
                        if (currentEvent.changes.firstOrNull { it.id == down.id }?.pressed != true) { tap(hit); return@awaitEachGesture }
                    }
                    var mode = Mode.PENDING
                    var startClip: Clip? = null
                    var baseS = 0L; var baseE = 0L
                    var ph = s0.geo.playheadMs.toDouble()
                    var pinchLast = 0f
                    val tracker = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                    var activeId: PointerId = down.id

                    val edgeThreshold = with(density) { 48.dp.toPx() }
                    var autoScrollMs = 0L
                    var lastPointerX = down.position.x
                    var edgeRatio = 0f
                    var edgeJob: Job? = null

                    fun updateEdgeScroll() {
                        val r = when {
                            widthPx > 0 && lastPointerX < edgeThreshold -> -((edgeThreshold - lastPointerX) / edgeThreshold).coerceIn(0f, 1f)
                            widthPx > 0 && lastPointerX > widthPx - edgeThreshold -> ((lastPointerX - (widthPx - edgeThreshold)) / edgeThreshold).coerceIn(0f, 1f)
                            else -> 0f
                        }
                        edgeRatio = r
                        if (abs(r) > 0.05f && (edgeJob == null || edgeJob?.isActive != true)) {
                            edgeJob = scope.launch {
                                while (isActive) {
                                    delay(16)
                                    if (abs(edgeRatio) > 0.05f) {
                                        val stepMs = (edgeRatio * 1800f * 0.016f).toLong()
                                        if (stepMs != 0L) {
                                            autoScrollMs += stepMs
                                            ph = (ph + stepMs).coerceIn(0.0, (cur.totalMs + 3000L).toDouble())
                                            act.scrubTo(ph.toLong())
                                            val currentDMs = (((lastPointerX - down.position.x) / cur.geo.pxPerMs).toLong() + autoScrollMs)
                                            when (mode) {
                                                Mode.MOVE -> startClip?.let { act.moveClip(it.id, it.startMs + currentDMs) }
                                                Mode.TRIM_START -> startClip?.let {
                                                    val target = (it.startMs + currentDMs).coerceAtLeast(0L)
                                                    act.trimStart(it.id, target)
                                                    act.scrubTo(target)
                                                }
                                                Mode.TRIM_END -> startClip?.let {
                                                    val target = (it.endMs + currentDMs).coerceAtLeast(0L)
                                                    act.trimEnd(it.id, target)
                                                    act.scrubTo(target)
                                                }
                                                Mode.TRIM_T_START -> (hit as? Hit.TextHandle)?.let { act.trimText(it.id, baseS + currentDMs, baseE) }
                                                Mode.TRIM_T_END -> (hit as? Hit.TextHandle)?.let { act.trimText(it.id, baseS, baseE + currentDMs) }
                                                Mode.TRIM_C_START -> (hit as? Hit.CaptionHandle)?.let { act.trimCaption(it.id, baseS + currentDMs, baseE) }
                                                Mode.TRIM_C_END -> (hit as? Hit.CaptionHandle)?.let { act.trimCaption(it.id, baseS, baseE + currentDMs) }
                                                else -> Unit
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (abs(r) <= 0.05f) {
                            edgeJob?.cancel(); edgeJob = null
                        }
                    }

                    try {
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                ev.changes.firstOrNull { it.id == activeId }?.let {
                                    tracker.addPosition(it.uptimeMillis, it.position)
                                }
                                break
                            }
                            if (pressed.size >= 2) {                                   // зум
                                val dist = (pressed[0].position - pressed[1].position).getDistance()
                                if (mode != Mode.ZOOM) { mode = Mode.ZOOM; pinchLast = dist; act.scrubStart() }
                                else if (pinchLast > 0f && dist > 0f) { act.setZoom(cur.geo.pxPerSecDp * dist / pinchLast); pinchLast = dist }
                                ev.changes.forEach { it.consume() }
                                continue
                            }
                            val ch = ev.changes.firstOrNull { it.id == activeId } ?: pressed[0].also { activeId = it.id }
                            if (mode == Mode.ZOOM) { ch.consume(); continue }
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            lastPointerX = ch.position.x
                            val total = ch.position - down.position
                            val g = cur.geo

                            if (mode == Mode.PENDING && abs(total.x) > slop) {
                                mode = when (hit) {
                                    is Hit.Handle -> if (hit.start) Mode.TRIM_START else Mode.TRIM_END
                                    is Hit.TextHandle -> if (hit.start) Mode.TRIM_T_START else Mode.TRIM_T_END
                                    is Hit.CaptionHandle -> if (hit.start) Mode.TRIM_C_START else Mode.TRIM_C_END
                                    is Hit.Body -> if (hit.clip.id == s0.selectedId && hit.clip.row != 0) Mode.MOVE else Mode.SCROLL
                                    else -> Mode.SCROLL
                                }
                                if (mode != Mode.SCROLL) view.haptic(Haptic.LIGHT)               // схватили блок/ручку
                                when (mode) {
                                    Mode.SCROLL -> act.scrubStart()
                                    Mode.TRIM_T_START, Mode.TRIM_T_END -> s0.texts.firstOrNull { it.id == (hit as Hit.TextHandle).id }?.let { baseS = it.startMs; baseE = it.endMs }
                                    Mode.TRIM_C_START, Mode.TRIM_C_END -> s0.captions.firstOrNull { it.id == (hit as Hit.CaptionHandle).id }?.let { baseS = it.startMs; baseE = it.endMs }
                                    else -> { startClip = (hit as? Hit.Body)?.clip ?: (hit as Hit.Handle).clip; act.editBegin() }
                                }
                            }
                            if (mode == Mode.PENDING) continue

                            val dMs = (total.x / g.pxPerMs).toLong() + autoScrollMs
                            when (mode) {
                                Mode.SCROLL -> {
                                    ph = (ph - (ch.position.x - ch.previousPosition.x) * 1.2f / g.pxPerMs).coerceIn(0.0, cur.totalMs.toDouble())
                                    act.scrubTo(ph.toLong())
                                }
                                Mode.MOVE -> {
                                    act.moveClip(startClip!!.id, startClip.startMs + dMs)
                                    updateEdgeScroll()
                                }
                                Mode.TRIM_START -> {
                                    val target = (startClip!!.startMs + dMs).coerceAtLeast(0L)
                                    act.trimStart(startClip.id, target)
                                    act.scrubTo(target)
                                    updateEdgeScroll()
                                }
                                Mode.TRIM_END -> {
                                    val target = (startClip!!.endMs + dMs).coerceAtLeast(0L)
                                    act.trimEnd(startClip.id, target)
                                    act.scrubTo(target)
                                    updateEdgeScroll()
                                }
                                Mode.TRIM_T_START -> {
                                    act.trimText((hit as Hit.TextHandle).id, baseS + dMs, baseE)
                                    updateEdgeScroll()
                                }
                                Mode.TRIM_T_END -> {
                                    act.trimText((hit as Hit.TextHandle).id, baseS, baseE + dMs)
                                    updateEdgeScroll()
                                }
                                Mode.TRIM_C_START -> {
                                    act.trimCaption((hit as Hit.CaptionHandle).id, baseS + dMs, baseE)
                                    updateEdgeScroll()
                                }
                                Mode.TRIM_C_END -> {
                                    act.trimCaption((hit as Hit.CaptionHandle).id, baseS, baseE + dMs)
                                    updateEdgeScroll()
                                }
                                else -> Unit
                            }
                            ch.consume()
                        }
                    } finally {
                        edgeJob?.cancel()
                    }

                    when (mode) {
                        Mode.PENDING -> tap(hit)                                    // тап
                        Mode.SCROLL -> {                                            // инерция ("разброс")
                            val rawVx = -tracker.calculateVelocity().x
                            val vMs = (rawVx * 1.6f) / cur.geo.pxPerMs
                            flingRef[0] = scope.launch {
                                try {
                                    val anim = Animatable(ph.toFloat())
                                    val decay = splineBasedDecay<Float>(density)
                                    anim.animateDecay(vMs, decay) {
                                        ph = value.toDouble().coerceIn(0.0, cur.totalMs.toDouble())
                                        act.scrubTo(ph.toLong())
                                    }
                                } finally { act.scrubEnd() }       // последняя позиция — в плеер без задержки (в т.ч. при остановке касанием)
                            }
                        }
                        Mode.MOVE, Mode.TRIM_START, Mode.TRIM_END -> {
                            act.editEnd()
                            act.scrubEnd()
                        }
                        Mode.ZOOM, Mode.TRIM_T_START, Mode.TRIM_T_END, Mode.TRIM_C_START, Mode.TRIM_C_END -> Unit
                    }
                }
            },
    ) {
        tick.let { }                     // подписка на перерисовку
        // zero-crash: ошибка отрисовки не должна ронять приложение при любых зумах/скроллах
        try { drawTimeline(cur, measurer, pending) } catch (e: Exception) { Log.e("BaseTimeline", "drawTimeline", e) }
    }
}

// ───────────────────────── геометрия и хит-тест ─────────────────────────

/** Высота таймлайна фиксирована: компактный вид без пустых дыр. */
const val TL_HEIGHT_DP = 280
private enum class Mode { PENDING, SCROLL, MOVE, TRIM_START, TRIM_END, ZOOM, TRIM_T_START, TRIM_T_END, TRIM_C_START, TRIM_C_END }
private class ThumbReq(val key: String, val uri: String, val type: MediaType, val bucketMs: Long, val h: Int)

private sealed interface Hit {
    class Body(val clip: Clip) : Hit
    class Handle(val clip: Clip, val start: Boolean) : Hit
    class Junction(val leftId: Long) : Hit
    class Text(val id: String) : Hit
    class Caption(val id: String) : Hit
    class TextHandle(val id: String, val start: Boolean) : Hit
    class CaptionHandle(val id: String, val start: Boolean) : Hit
    data object AudioSlot : Hit
    data object OverlaySlot : Hit
    data object TextSlot : Hit
    data object None : Hit
}

/** Всё в пикселях. x(t) = центр + (t − playhead)·pxPerMs. */
class Geo(
    private val d: Density,
    val width: Float,
    val playheadMs: Long,
    val pxPerSecDp: Float,
    val hasOverlay: Boolean = false,
    val hasText: Boolean = false,
    val hasCaptions: Boolean = false,
) {
    val pxPerMs = with(d) { pxPerSecDp.dp.toPx() } / 1000f
    val centerX = width / 2f
    fun x(t: Long) = centerX + (t - playheadMs) * pxPerMs
    fun dp(v: Int) = with(d) { v.dp.toPx() }
    val rulerH = dp(24); val mainTop = dp(26); val mainH = dp(70)
    val slotH = dp(44); val gap = dp(4)
    val overlayTop = mainTop + mainH + gap
    val textTop = if (hasOverlay) overlayTop + slotH + gap else mainTop + mainH + gap
    val capTop = textTop + slotH + gap
    val audioTop = if (hasCaptions) capTop + slotH + gap else textTop + slotH + gap
    val junctionR = dp(14); val handleW = dp(16); val handleSlop = dp(14); val corner = dp(5)
    val total: Float get() = audioTop + slotH + dp(8)
}

private class TlState(
    val geo: Geo, val clips: List<Clip>, val transitions: List<Transition>, val captions: List<CaptionItem>, val texts: List<TextClip>,
    val keyframes: Map<Long, List<com.base.editor.core.Keyframe>>, val selectedId: Long?, val selectedTextId: String?, val selectedCaptionId: String?, val totalMs: Long,
) {
    val audioClips: List<Clip> = clips.filter { it.row != 0 && it.type == MediaType.AUDIO }.sortedBy { it.startMs }
    val overlayClips: List<Clip> = clips.filter { it.row == 2 && it.type != MediaType.AUDIO }.sortedBy { it.startMs }

    /** Стыки соседних клипов основной дорожки: (левый клип, правый клип). Кнопки скрыты у выбранного клипа. */
    fun junctions(): List<Pair<Clip, Clip>> {
        val main = clips.filter { it.row == 0 }
        return main.mapNotNull { l ->
            val r = main.firstOrNull { it.startMs == l.endMs && it.id != l.id } ?: return@mapNotNull null
            if (l.id == selectedId || r.id == selectedId) null else l to r
        }
    }

    fun hit(p: Offset): Hit {
        val g = geo
        if (p.y in g.mainTop..(g.mainTop + g.mainH)) {
            junctions().forEach { (l, _) ->
                if (abs(p.x - g.x(l.endMs)) <= g.junctionR + g.handleSlop / 2) return Hit.Junction(l.id)
            }
            clips.filter { it.row == 0 }.forEach { c ->
                val l = g.x(c.startMs); val r = g.x(c.endMs)
                if (c.id == selectedId) {
                    val hw = min(g.handleW, (r - l) / 4)
                    if (p.x in (l - g.handleSlop)..(l + hw + g.handleSlop / 2)) return Hit.Handle(c, true)
                    if (p.x in (r - hw - g.handleSlop / 2)..(r + g.handleSlop)) return Hit.Handle(c, false)
                }
                if (p.x in l..r) return Hit.Body(c)
            }
        }
        if (overlayClips.isNotEmpty() && p.y in g.overlayTop..(g.overlayTop + g.slotH)) {
            overlayClips.forEach { c ->
                val l = g.x(c.startMs); val r = g.x(c.endMs)
                if (c.id == selectedId) {
                    val hw = min(g.handleW, (r - l) / 4)
                    if (p.x in (l - g.handleSlop)..(l + hw + g.handleSlop / 2)) return Hit.Handle(c, true)
                    if (p.x in (r - hw - g.handleSlop / 2)..(r + g.handleSlop)) return Hit.Handle(c, false)
                }
                if (p.x in l..r) return Hit.Body(c)
            }
            val btnLeft = max(g.x(0), 0f)
            if (p.x in btnLeft..(btnLeft + g.dp(180))) return Hit.OverlaySlot
        }
        if (p.y in g.textTop..(g.textTop + g.slotH)) {
            if (texts.isNotEmpty()) {
                texts.firstOrNull { it.id == selectedTextId }?.let { t ->
                    val l = g.x(t.startMs); val r = g.x(t.endMs); val hw = min(g.handleW, (r - l) / 4)
                    if (p.x in (l - g.handleSlop)..(l + hw + g.handleSlop / 2)) return Hit.TextHandle(t.id, true)
                    if (p.x in (r - hw - g.handleSlop / 2)..(r + g.handleSlop)) return Hit.TextHandle(t.id, false)
                }
                texts.lastOrNull { p.x in g.x(it.startMs)..g.x(it.endMs) }?.let { return Hit.Text(it.id) }
            } else {
                val btnLeft = max(g.x(0), 0f)
                if (p.x in btnLeft..(btnLeft + g.dp(180))) return Hit.TextSlot
            }
        }
        if (captions.isNotEmpty() && p.y in g.capTop..(g.capTop + g.slotH)) {
            captions.firstOrNull { it.id == selectedCaptionId }?.let { c ->
                val l = g.x(c.startMs); val r = g.x(c.endMs); val hw = min(g.handleW, (r - l) / 4)
                if (p.x in (l - g.handleSlop)..(l + hw + g.handleSlop / 2)) return Hit.CaptionHandle(c.id, true)
                if (p.x in (r - hw - g.handleSlop / 2)..(r + g.handleSlop)) return Hit.CaptionHandle(c.id, false)
            }
            captions.firstOrNull { p.x in g.x(it.startMs)..g.x(it.endMs) }?.let { return Hit.Caption(it.id) }
        }
        if (p.y in g.audioTop..(g.audioTop + g.slotH)) {
            if (audioClips.isNotEmpty()) {
                audioClips.forEach { c ->
                    val l = g.x(c.startMs); val r = g.x(c.endMs)
                    if (c.id == selectedId) {
                        val hw = min(g.handleW, (r - l) / 4)
                        if (p.x in (l - g.handleSlop)..(l + hw + g.handleSlop / 2)) return Hit.Handle(c, true)
                        if (p.x in (r - hw - g.handleSlop / 2)..(r + g.handleSlop)) return Hit.Handle(c, false)
                    }
                    if (p.x in l..r) return Hit.Body(c)
                }
            } else {
                val btnLeft = max(g.x(0), 0f)
                if (p.x in btnLeft..(btnLeft + g.dp(180))) return Hit.AudioSlot
            }
        }
        return Hit.None
    }
}

// ───────────────────────── отрисовка ─────────────────────────

private fun DrawScope.drawTimeline(s: TlState, measurer: TextMeasurer, pending: LinkedHashMap<String, ThumbReq>) {
    val g = s.geo
    if (g.width <= 0f) return
    val small = TextStyle(color = Color.White.copy(alpha = .6f), fontSize = 11.sp)

    // линейка
    val steps = listOf(0.5f, 1f, 2f, 5f, 10f, 30f, 60f, 300f)
    val stepSec = steps.firstOrNull { it * g.pxPerMs * 1000f >= 70.dp.toPx() } ?: steps.last()
    val stepMs = (stepSec * 1000).toLong()
    val firstT = max(0L, ((g.playheadMs - (g.centerX / g.pxPerMs).toLong()) / stepMs) * stepMs)
    var t = firstT
    var guard = 0
    while (g.x(t) < g.width + 60f && guard++ < 600) {
        val x = g.x(t)
        if (x > -60f) {
            val label = Format.duration(t)
            safeText(measurer, label, Offset(x - 16.dp.toPx(), 2.dp.toPx()), small)
            drawCircle(Color.White.copy(alpha = .35f), 1.5.dp.toPx(), Offset(g.x(t + stepMs / 2), 22.dp.toPx()))
        }
        t += stepMs
    }

    // слоты дорожек: наложения, текст, субтитры, аудио (плотно подогнаны без лишних дыр)
    val slotFrom = g.x(0)
    val slotLeft = max(slotFrom, -g.corner)
    fun slot(top: Float, label: String?) {
        drawRoundRect(BaseColors.DarkSlot, Offset(slotLeft, top), Size(max(0f, g.width - slotLeft + g.corner), g.slotH), CornerRadius(g.corner))
        if (label != null) {
            val plusStyle = TextStyle(color = Color.White.copy(alpha = .95f), fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            val labelStyle = TextStyle(color = Color.White.copy(alpha = .85f), fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
            val plusLayout = measurer.measure("+", plusStyle)
            val textOnly = label.removePrefix("+").trimStart()
            val textLayout = measurer.measure(textOnly, labelStyle)
            val startX = max(slotFrom, 0f) + 16.dp.toPx()
            val plusY = top + (g.slotH - plusLayout.size.height) / 2f
            val textY = top + (g.slotH - textLayout.size.height) / 2f
            drawText(plusLayout, topLeft = Offset(startX, plusY))
            drawText(textLayout, topLeft = Offset(startX + plusLayout.size.width + 6.dp.toPx(), textY))
        }
    }
    if (s.overlayClips.isNotEmpty()) slot(g.overlayTop, null)
    slot(g.textTop, if (s.texts.isEmpty()) "+  Добавить текст" else null)
    if (s.captions.isNotEmpty()) slot(g.capTop, null)
    slot(g.audioTop, if (s.audioClips.isEmpty()) "+  Добавить аудио" else null)

    fun block(top: Float, l: Float, r: Float, color: Color, label: String, isAudio: Boolean = false) {
        if (r < -20f || l > g.width + 20f || r - l < 2f) return
        val corner = 5.dp.toPx()
        val y = top + 2.dp.toPx()
        val hgt = g.slotH - 4.dp.toPx()
        val w = max(2f, r - l)
        drawRoundRect(color, Offset(l, y), Size(w, hgt), CornerRadius(corner))
        // декоративные волны на блоке аудио (Photo 1)
        if (isAudio && w > 24.dp.toPx()) {
            val waveColor = Color.White.copy(alpha = 0.28f)
            val step = 6.dp.toPx()
            var wx = l + 8.dp.toPx()
            val cy = y + hgt / 2f
            while (wx < r - 8.dp.toPx()) {
                val waveH = (kotlin.math.sin(wx * 0.15f).toFloat().let { kotlin.math.abs(it) } * 10.dp.toPx() + 4.dp.toPx()).coerceAtMost(hgt - 6.dp.toPx())
                drawLine(waveColor, Offset(wx, cy - waveH / 2f), Offset(wx, cy + waveH / 2f), 2.dp.toPx(), cap = StrokeCap.Round)
                wx += step
            }
        }
        // подпись — белая, четкая, ровно посередине по вертикали
        if (w > 32.dp.toPx() && r > 0f && l < g.width) {
            clipRect(left = max(l, 0f), top = top, right = min(r, g.width), bottom = top + g.slotH) {
                val style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                val m = measurer.measure(label, style)
                val textY = top + (g.slotH - m.size.height) / 2f
                drawText(m, topLeft = Offset(max(l, 0f) + 8.dp.toPx(), textY))
            }
        }
    }

    // рамка и детальные белые ручки обрезки с тёмной полоской внутри (Photo 2)
    fun handles(top: Float, hgt: Float, l: Float, r: Float) {
        if (r - l <= 8f) return
        val hw = min(g.handleW, (r - l) / 4)
        val stroke = 2.dp.toPx()
        val corner = 4.dp.toPx()
        val w = max(2f, r - l)
        drawRoundRect(Color.White, Offset(l, top), Size(w, hgt), CornerRadius(corner), Stroke(stroke))
        drawRoundRect(Color.White, Offset(l, top), Size(hw, hgt), CornerRadius(corner))
        drawRoundRect(Color.White, Offset(r - hw, top), Size(hw, hgt), CornerRadius(corner))
        val grip = Color(0xFF333333)
        val cy = top + hgt / 2f
        val barH = min(hgt * 0.45f, 15.dp.toPx())
        val strokeW = 2.2.dp.toPx()
        drawLine(grip, Offset(l + hw / 2f, cy - barH / 2f), Offset(l + hw / 2f, cy + barH / 2f), strokeW, cap = StrokeCap.Round)
        drawLine(grip, Offset(r - hw / 2f, cy - barH / 2f), Offset(r - hw / 2f, cy + barH / 2f), strokeW, cap = StrokeCap.Round)
    }

    s.texts.forEach { t ->
        block(g.textTop, g.x(t.startMs), g.x(t.endMs), Color(0xFFE65100), t.text)
        if (t.id == s.selectedTextId) handles(g.textTop + 2.dp.toPx(), g.slotH - 4.dp.toPx(), g.x(t.startMs), g.x(t.endMs))
    }
    s.captions.forEach { c ->
        block(g.capTop, g.x(c.startMs), g.x(c.endMs), Color(0xFFF57C00), c.text)
        if (c.id == s.selectedCaptionId) handles(g.capTop + 2.dp.toPx(), g.slotH - 4.dp.toPx(), g.x(c.startMs), g.x(c.endMs))
    }

    // блоки музыки на аудиодорожке: имя файла + длительность; выбранный — с рамкой и ручками обрезки
    s.audioClips.forEach { c ->
        val l = g.x(c.startMs); val r = g.x(c.endMs)
        val name = c.uri.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Музыка" }
        block(g.audioTop, l, r, Color(0xFF00897B), "♪ $name · ${Format.duration(c.lengthMs)}", isAudio = true)
        if (c.id == s.selectedId && r - l > 8f) {
            handles(g.audioTop + 2.dp.toPx(), g.slotH - 4.dp.toPx(), l, r)
        }
    }

    // наложения: блок с именем и длительностью; выбранный — с рамкой и белыми ручками обрезки
    s.overlayClips.forEach { c ->
        val l = g.x(c.startMs); val r = g.x(c.endMs)
        val name = c.uri.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Наложение" }
        block(g.overlayTop, l, r, Color(0xFF5E35B1), (if (c.type == MediaType.IMAGE) "▣ " else "▶ ") + "$name · ${Format.duration(c.lengthMs)}")
        if (c.id == s.selectedId) handles(g.overlayTop + 2.dp.toPx(), g.slotH - 4.dp.toPx(), l, r)
    }

    // клипы основной дорожки
    val tileW = g.mainH
    val h = g.mainH.roundToInt()
    s.clips.filter { it.row == 0 }.forEach { c ->
        val l = g.x(c.startMs); val r = g.x(c.endMs)
        if (r < -20f || l > g.width + 20f) return@forEach
        val rect = Rect(l, g.mainTop, r, g.mainTop + g.mainH)
        val path = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(rect, CornerRadius(g.corner))) }
        clipPath(path) {
            drawRect(BaseColors.TileEmpty, rect.topLeft, rect.size)
            var tx = l + floorTo(max(0f, -l), tileW)
            while (tx < min(r, g.width) ) {
                val srcMs = c.srcInMs + ((tx - l) / g.pxPerMs).toLong()
                val bucket = if (c.type == MediaType.VIDEO) (srcMs / 1000) * 1000 else 0L
                val key = Thumbs.key(c.uri, bucket, h)
                val th = Thumbs.peek(key)
                val w = min(tileW, r - tx)
                if (th != null) {
                    val bw = th.image.width; val bh = th.image.height
                    val side = min(bw, bh)
                    val srcW = (side * (w / tileW)).toInt().coerceAtLeast(1)
                    drawImage(th.image, IntOffset((bw - side) / 2, (bh - side) / 2), IntSize(srcW, side),
                        IntOffset(tx.roundToInt(), g.mainTop.roundToInt()), IntSize(w.roundToInt().coerceAtLeast(1), h))
                } else synchronized(pending) { if (!pending.containsKey(key)) pending[key] = ThumbReq(key, c.uri, c.type, bucket, h) }
                tx += tileW
            }
        }
        // тонкий разделитель между стыкующимися клипами
        drawLine(Color.Black.copy(alpha = .6f), Offset(l, g.mainTop), Offset(l, g.mainTop + g.mainH), 1.dp.toPx())

        if (c.id == s.selectedId) {
            val hw = min(g.handleW, (r - l) / 4)
            handles(g.mainTop, g.mainH, l, r)
            // длительность
            val label = "%.1fs".format(c.lengthMs / 1000f)
            val m = measurer.measure(label, TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium))
            val bx = max(l + hw + 6.dp.toPx(), 6.dp.toPx()).coerceAtMost(r - hw - m.size.width - 14.dp.toPx())
            if (bx > l) {
                drawRoundRect(Color.Black.copy(alpha = .65f), Offset(bx, g.mainTop + 5.dp.toPx()), Size(m.size.width + 10.dp.toPx(), m.size.height + 4.dp.toPx()), CornerRadius(5.dp.toPx()))
                drawText(m, topLeft = Offset(bx + 5.dp.toPx(), g.mainTop + 7.dp.toPx()))
            }
        }
    }

    // маркеры ключевых кадров на полоске выбранного клипа (ромбики в точках timeMs)
    s.clips.filter { it.row == 0 && it.id == s.selectedId }.forEach { c ->
        val keys = s.keyframes[c.id].orEmpty()
        val cy = g.mainTop + g.mainH / 2
        val r = 5.dp.toPx()
        keys.forEach { k ->
            val x = g.x(c.startMs + k.timeMs)
            if (x < -20f || x > g.width + 20f) return@forEach
            val d = Path().apply { moveTo(x, cy - r); lineTo(x + r, cy); lineTo(x, cy + r); lineTo(x - r, cy); close() }
            drawPath(d, Color.White)
            val ri = r * 0.55f
            val di = Path().apply { moveTo(x, cy - ri); lineTo(x + ri, cy); lineTo(x, cy + ri); lineTo(x - ri, cy); close() }
            drawPath(di, BaseColors.Primary)
        }
    }

    // точки добавления перехода на стыках
    s.junctions().forEach { (l, _) ->
        val x = g.x(l.endMs)
        if (x < -20f || x > g.width + 20f) return@forEach
        val has = s.transitions.any { it.leftId == l.id }
        val cy = g.mainTop + g.mainH / 2
        val r = g.junctionR
        drawCircle(if (has) BaseColors.Primary else Color.White, r, Offset(x, cy))
        val k = 5.dp.toPx(); val ink = Color(0xFF111318)
        val left = Path().apply { moveTo(x - k * 1.6f, cy - k); lineTo(x - k * .15f, cy); lineTo(x - k * 1.6f, cy + k); close() }
        val right = Path().apply { moveTo(x + k * 1.6f, cy - k); lineTo(x + k * .15f, cy); lineTo(x + k * 1.6f, cy + k); close() }
        drawPath(left, ink); drawPath(right, ink)
    }

    // курсор
    drawLine(Color.White, Offset(g.centerX, 0f), Offset(g.centerX, g.total + 6.dp.toPx()), 2.dp.toPx())
}

private fun floorTo(v: Float, step: Float) = (v / step).toInt() * step

/**
 * Безопасный вывод текста. Стандартный drawText(measurer, …) считает maxWidth = ширина холста − topLeft.x
 * и падает с IllegalArgumentException, когда подпись начинается правее видимой области.
 * Здесь размер текста меряется без ограничений, а невидимые подписи пропускаются.
 */
private fun DrawScope.safeText(measurer: TextMeasurer, text: String, topLeft: Offset, style: TextStyle) {
    if (text.isEmpty() || !topLeft.x.isFinite() || !topLeft.y.isFinite()) return
    if (size.width <= 0f || size.height <= 0f || topLeft.x > size.width || topLeft.y > size.height) return
    val layout: TextLayoutResult = try {
        measurer.measure(text, style, softWrap = false, maxLines = 1, constraints = Constraints())
    } catch (e: Exception) { return }
    if (topLeft.x + layout.size.width < 0f) return
    drawText(layout, topLeft = topLeft)
}
