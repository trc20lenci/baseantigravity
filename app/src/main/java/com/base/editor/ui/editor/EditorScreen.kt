package com.base.editor.ui.editor

import com.base.editor.ui.theme.Lucide
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import com.base.editor.ui.theme.BaseMotion
import com.base.editor.ui.theme.haptic
import com.base.editor.ui.theme.pressable
import com.base.editor.captions.GenerationState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlin.math.sin
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.rounded.Add
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import com.base.editor.media.ExportState
import com.base.editor.R
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.base.editor.captions.CaptionOps
import androidx.compose.material.icons.rounded.ClosedCaption
import com.base.editor.data.Format
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.animatedSelectColor
import com.base.editor.ui.theme.pressable
import com.base.editor.ui.theme.soon
import kotlin.math.abs
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Palette
import kotlin.math.roundToInt

@UnstableApi
@Composable
fun EditorScreen(onClose: () -> Unit, onAddMedia: () -> Unit, vm: EditorViewModel = viewModel()) {
    val ctx = LocalContext.current
    // Здесь — только редко меняющееся состояние. Позиция курсора читается внутри PreviewStage / TransportRow / TimelineHost,
    // поэтому перемотка не перекомпонует весь экран (верхняя панель, панели инструментов и т.д.).
    val clips by vm.clips.collectAsStateWithLifecycle()
    val formatPanel by vm.formatPanelOpen.collectAsStateWithLifecycle()
    val selected by vm.selectedId.collectAsStateWithLifecycle()
    val event by vm.events.collectAsStateWithLifecycle()
    val transitions by vm.transitions.collectAsStateWithLifecycle()
    val transitionFor by vm.transitionFor.collectAsStateWithLifecycle()
    val transitionMax by vm.transitionMaxMs.collectAsStateWithLifecycle()
    val captionItems by vm.captions.items.collectAsStateWithLifecycle()
    val captionStyle by vm.captions.style.collectAsStateWithLifecycle()
    val captionGen by vm.captions.generation.collectAsStateWithLifecycle()
    val captionPanel by vm.captionPanelOpen.collectAsStateWithLifecycle()
    val editingCaption by vm.editingCaptionId.collectAsStateWithLifecycle()
    val texts by vm.texts.collectAsStateWithLifecycle()
    val keyframes by vm.keyframes.collectAsStateWithLifecycle()
    val selText by vm.canvasTextId.collectAsStateWithLifecycle()
    val selCaption by vm.selectedCaptionId.collectAsStateWithLifecycle()
    val textSub by vm.textSub.collectAsStateWithLifecycle()
    val textInput by vm.textInputOpen.collectAsStateWithLifecycle()
    val textEditorTab by vm.textEditorTab.collectAsStateWithLifecycle()
    val captionInput by vm.captionInputOpen.collectAsStateWithLifecycle()
    val exportSheet by vm.exportSheetOpen.collectAsStateWithLifecycle()
    val mediaTool by vm.mediaTool.collectAsStateWithLifecycle()
    val bgJob by vm.bgJob.collectAsStateWithLifecycle()
    val bgBar by vm.bgBarOpen.collectAsStateWithLifecycle()
    val ttsOpen by vm.ttsSheetOpen.collectAsStateWithLifecycle()
    val canvasBgSel by vm.canvasBg.collectAsStateWithLifecycle()
    val blurRadius by vm.blurRadius.collectAsStateWithLifecycle()
    val customBgUri by vm.customBgUri.collectAsStateWithLifecycle()
    val projectBgImages by vm.projectBgImages.collectAsStateWithLifecycle()
    val pagTemplates by vm.pagTemplates.collectAsStateWithLifecycle()

    SideEffect { vm.onRequestAddMedia = onAddMedia }
    val hapticView = androidx.compose.ui.platform.LocalView.current
    LaunchedEffect(Unit) { com.base.editor.ui.theme.HapticBus.events.collect { hapticView.haptic(it) } }
    // выбор музыки с устройства (MIME audio/*) — результат уходит во ViewModel
    val audioPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(vm::onAudioPicked) }
    SideEffect { vm.onRequestAddAudio = { audioPicker.launch(arrayOf("audio/*")) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.scrubStart(); vm.saveNow() }
    val export by vm.exportState.collectAsStateWithLifecycle()
    export?.let { ExportDialog(it, onCancel = vm::cancelExport, onDismiss = vm::dismissExport) }
    LaunchedEffect(event) { event?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); vm.events.value = null } }
    fun close() { vm.saveNow(); onClose() }
    var fullscreen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    BackHandler {
        when {
            fullscreen -> fullscreen = false
            exportSheet -> vm.closeExportSheet()
            ttsOpen -> vm.closeTtsSheet()
            textSub != null -> vm.setTextSub(null)
            bgBar -> vm.closeBgBar()
            mediaTool != null -> vm.closeMediaTool()
            formatPanel -> vm.closeFormat()
            captionPanel -> vm.closeCaptions()
            transitionFor != null -> vm.closeTransitions()
            selText != null || selCaption != null || selected != null -> vm.select(null)
            else -> close()
        }
    }

    // Вкладка редактирования текста (шрифты, стили, цвета, размер, затемнение)
    if (textInput) texts.firstOrNull { it.id == selText }?.let { clip ->
        ModernTextEditorSheet(
            clip = clip,
            initialTab = textEditorTab,
            onChange = { transform -> vm.editText(transform) },
            onDone = vm::closeTextInput,
        )
    }
    if (captionInput) captionItems.firstOrNull { it.id == selCaption }?.let { TextInputSheet(it.text, onChange = vm::setCaptionValue, onDone = vm::closeCaptionInput) }
    if (exportSheet) ExportSheet(
        durationMs = vm.totalMs.value, initialResolution = vm.resolution.value, initialFps = vm.exportFps.value,
        onDismiss = vm::closeExportSheet,
        onStart = { res, fps -> vm.setResolution(res); vm.setExportFps(fps); vm.closeExportSheet(); vm.startExport() },
    )
    if (ttsOpen) {
        val voice by vm.ttsVoice.collectAsStateWithLifecycle()
        val busy by vm.ttsBusy.collectAsStateWithLifecycle()
        val text by vm.ttsText.collectAsStateWithLifecycle()
        TtsSheet(text, voice, busy, onText = vm::setTtsText, onVoice = vm::setTtsVoice, onGenerate = vm::generateSpeech, onDismiss = vm::closeTtsSheet)
    }
    val cropId by vm.cropClipId.collectAsStateWithLifecycle()
    if (cropId != null) {
        val frame by vm.cropFrame.collectAsStateWithLifecycle()
        val init by vm.cropInitial.collectAsStateWithLifecycle()
        CropDialog(frame, init, onDone = vm::applyCrop, onCancel = vm::cancelCrop)
    }

    if (captionPanel) {
        val playhead by vm.playheadMs.collectAsStateWithLifecycle()
        CaptionPanel(
            items = captionItems, style = captionStyle, generation = captionGen, playheadMs = playhead, editingId = editingCaption,
            onPrefetch = vm::prefetchCaptionModel,
            onGenerate = { lang ->
                vm.closeCaptions()
                vm.generateCaptions(lang)
            },
            onCancel = vm.captions::cancelGeneration, onDismissError = vm.captions::dismissError,
            onOpenItem = vm::openCaptionItem, onCloseEdit = { vm.editingCaptionId.value = null },
            onUpdateText = vm.captions::updateText, onUpdateTiming = vm.captions::updateTiming, onDelete = vm.captions::delete,
            onAdd = vm::addCaptionHere, onClearAll = vm.captions::clearAll,
            onPreset = vm.captions::applyPreset, onStyle = vm.captions::updateStyle, onClose = vm::closeCaptions,
        )
    }

    LaunchedEffect(captionGen) {
        val st = captionGen
        if (st is GenerationState.Failed) {
            vm.events.value = st.message
            vm.captions.dismissError()
        }
    }

    Column(Modifier.fillMaxSize().background(BaseColors.DarkBg).systemBarsPadding()) {
        // верхняя панель: логотип BASE, круглый крестик, пилюля формата и пилюля экспорта
        AnimatedVisibility(!fullscreen, enter = barEnter, exit = barExit) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIcon(Lucide.X, "Выйти", ::close)
                Spacer(Modifier.width(8.dp))
                Image(painterResource(R.drawable.logo_base_white), "BASE", Modifier.height(20.dp))
                Spacer(Modifier.weight(1f))
                val curFormat by vm.format.collectAsStateWithLifecycle()
                FormatTopButton(curFormat, onClick = { if (formatPanel) vm.closeFormat() else vm.openFormat() })
                Spacer(Modifier.width(8.dp))
                Text(
                    "Экспорт",
                    Modifier
                        .pressable(onClick = vm::openExportSheet)
                        .clip(RoundedCornerShape(20.dp))
                        .background(BaseColors.Primary)
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        // плеер занимает всё, что осталось над фиксированной нижней областью, поэтому его размер не меняется
        // ни при открытии панелей, ни при смене инструментов
        PreviewStage(vm, texts, captionItems, captionStyle, selected, fullscreen, { fullscreen = !fullscreen }, Modifier.weight(1f))

        AnimatedVisibility(!fullscreen, enter = barEnter, exit = barExit) {
          Column {
            TransportRow(vm, hasMainSelected = clips.any { it.id == selected && (it.row == 0 || it.row == 2) && it.type != com.base.editor.core.MediaType.AUDIO })

            // Нижняя область ФИКСИРОВАННОЙ высоты: таймлайн + панель инструментов. Таймлайн живой в любом режиме
            // редактирования (клип, аудио, текст, субтитры): мотать проект и двигать курсор можно всегда.
            val mode = when {
                bgBar && selText == null && selCaption == null && selected == null -> ToolMode.BACKGROUND
                selText != null -> ToolMode.TEXT
                selCaption != null -> ToolMode.CAPTION
                selected != null && clips.firstOrNull { it.id == selected }?.type == com.base.editor.core.MediaType.AUDIO -> ToolMode.AUDIO
                selected != null -> ToolMode.MEDIA
                else -> ToolMode.MAIN
            }
            val toolbarH = TOOLBAR_HEIGHT_DP
            val panelOpen = transitionFor != null || formatPanel || mediaTool != null
            Box(Modifier.fillMaxWidth().height((TL_HEIGHT_DP + toolbarH).dp).background(BaseColors.DarkBg)) {
                Column(Modifier.fillMaxSize()) {
                    TimelineHost(vm, clips, transitions, captionItems, texts, keyframes, selected, selText, selCaption)
                    Crossfade(mode, animationSpec = androidx.compose.animation.core.tween(160, easing = EaseOutStrong), label = "toolbar", modifier = Modifier.fillMaxWidth().height(toolbarH.dp).background(BaseColors.DarkPanel)) { m ->
                        when (m) {
                            ToolMode.MAIN -> ScrollToolRow {
                                ToolButton(Lucide.Scissors, "Изменить") { vm.selectAtPlayhead() }
                                ToolButton(Lucide.Music, "Звук", onClick = vm::addAudio)
                                ToolButton(Lucide.Type, "Текст", onClick = vm::openNewText)
                                ToolButton(Lucide.Captions, "Субтитры", onClick = vm::openCaptions)
                                ToolButton(Lucide.AudioLines, "Озвучка", onClick = vm::openTtsSheet)
                                ToolButton(Lucide.Layers, "Наложение", onClick = vm::addOverlay)
                                ToolButton(Lucide.Image, "Фон", onClick = vm::openBgBar)
                            }
                            ToolMode.MEDIA -> ScrollToolRow {
                                ToolButton(Lucide.ChevronLeft, "Назад") { vm.select(null) }
                                ToolButton(Lucide.SquareSplitHorizontal, "Разделить", onClick = vm::split)
                                ToolButton(Lucide.Copy, "Копия", onClick = vm::duplicateSelected)
                                ToolButton(Lucide.RotateCcw, "Реверс", onClick = vm::reverseSelected)
                                ToolButton(Lucide.Gauge, "Скорость") { vm.openMediaTool(MediaTool.SPEED) }
                                ToolButton(Lucide.Volume2, "Громкость") { vm.openMediaTool(MediaTool.VOLUME) }
                                ToolButton(Lucide.WandSparkles, "Фон") { vm.openMediaTool(MediaTool.BG) }
                                ToolButton(Lucide.Pipette, "Хромакей") { vm.openMediaTool(MediaTool.CHROMA) }
                                ToolButton(Lucide.Crop, "Кадрирование", onClick = vm::openCrop)
                                ToolButton(Lucide.Trash2, "Удалить", onClick = vm::deleteSelected)
                            }
                            ToolMode.BACKGROUND -> {
                                val curPlayhead = vm.playheadMs.value
                                val curClip = clips.firstOrNull { it.row == 0 && curPlayhead in it.startMs..it.endMs } ?: clips.firstOrNull { it.row == 0 }
                                val localMs = (curPlayhead - (curClip?.startMs ?: 0L)).coerceAtLeast(0L)
                                val srcMs = ((curClip?.srcInMs ?: 0L) + localMs * (curClip?.speed ?: 1f)).toLong()
                                BackgroundsBar(
                                    current = canvasBgSel,
                                    blurRadius = blurRadius,
                                    customImageUri = customBgUri,
                                    projectBgImages = projectBgImages,
                                    previewClipUri = curClip?.uri,
                                    previewClipType = curClip?.type,
                                    previewClipTimeMs = srcMs,
                                    onPick = vm::setCanvasBg,
                                    onBlurRadiusChange = vm::setBlurRadius,
                                    onPickCustomImage = vm::setCustomBgImage,
                                    onSelectImage = vm::selectBgImage,
                                    onBack = vm::closeBgBar,
                                )
                            }
                            ToolMode.AUDIO -> ScrollToolRow {
                                ToolButton(Lucide.ChevronLeft, "Назад") { vm.select(null) }
                                ToolButton(Lucide.SquareSplitHorizontal, "Разделить", onClick = vm::split)
                                ToolButton(Lucide.Copy, "Копия", onClick = vm::duplicateSelected)
                                ToolButton(Lucide.RotateCcw, "Реверс", onClick = vm::reverseSelected)
                                ToolButton(Lucide.Gauge, "Скорость") { vm.openMediaTool(MediaTool.SPEED) }
                                ToolButton(Lucide.Volume2, "Громкость") { vm.openMediaTool(MediaTool.VOLUME) }
                                ToolButton(Lucide.Trash2, "Удалить", onClick = vm::deleteSelected)
                            }
                            ToolMode.TEXT -> texts.firstOrNull { it.id == selText }?.let { t ->
                                TextToolbar(
                                    clip = t, sub = textSub, templates = pagTemplates,
                                    onBack = { vm.select(null) }, onSub = vm::setTextSub, onEditText = vm::openTextInput,
                                    onSplit = vm::splitText, onDuplicate = vm::duplicateSelected, onDelete = vm::deleteText, onChange = vm::editText,
                                    onStyle = vm::applyTextStyle, onAnimation = vm::applyTextAnimation, onImportPag = vm::importPag,
                                )
                            }
                            ToolMode.CAPTION -> ToolRow {
                                ToolButton(Lucide.ChevronLeft, "Назад") { vm.select(null) }
                                ToolButton(Lucide.Pencil, "Текст", onClick = vm::openCaptionInput)
                                ToolButton(Lucide.SquareSplitHorizontal, "Разделить", onClick = vm::splitCaption)
                                ToolButton(Lucide.Palette, "Стиль", onClick = vm::openCaptions)
                                ToolButton(Lucide.Trash2, "Удалить", onClick = vm::deleteCaption)
                            }
                        }
                    }
                }

                // пока открыта большая панель (переходы, формат) — всё под ней не реагирует на касания
                if (panelOpen) Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                })
                val toolClip = clips.firstOrNull { it.id == selected }
                BottomPanel(visible = mediaTool != null && toolClip != null) {
                    if (toolClip != null) when (mediaTool) {
                        MediaTool.SPEED -> SpeedPanel(toolClip.speed, onCommit = vm::setClipSpeed, onClose = vm::closeMediaTool)
                        MediaTool.VOLUME -> VolumePanel(toolClip.volume, onChange = vm::setClipVolume, onClose = vm::closeMediaTool)
                        MediaTool.CHROMA -> {
                            val ck by vm.controller.state.collectAsStateWithLifecycle()
                            val pipOn by vm.pipetteOn.collectAsStateWithLifecycle()
                            ChromaPanel(
                                key = ck.chromas[toolClip.id], pipetteOn = pipOn, onPipette = vm::startPipette, onReset = vm::resetChroma,
                                onChange = vm::updateChroma, onDone = vm::commitFx, onClose = vm::closeMediaTool,
                            )
                        }
                        MediaTool.BG -> {
                            val st by vm.controller.state.collectAsStateWithLifecycle()
                            BgPanel(
                                hasMask = vm.bgMaskReady(), bg = st.bgs[toolClip.id], job = bgJob?.takeIf { it.clipId == toolClip.id },
                                onStart = vm::startBg, onCancel = vm::cancelBg, onChange = vm::updateBg, onDone = vm::commitFx,
                                onDisable = vm::disableBg, onClose = vm::closeMediaTool,
                            )
                        }
                        null -> Unit
                    }
                }
                val curFormat by vm.format.collectAsStateWithLifecycle()
                BottomPanel(visible = formatPanel) { FormatPanel(curFormat, vm::setFormat, vm::closeFormat) }
                val tf = transitionFor
                BottomPanel(visible = tf != null && !captionPanel) {
                    if (tf != null) TransitionPanel(
                        current = vm.currentTransition(tf), maxMs = transitionMax, items = vm.catalog.items,
                        onPick = { id, dur -> vm.applyTransition(tf, id, dur) },
                        onDuration = { dur -> vm.currentTransition(tf)?.let { vm.applyTransition(tf, it.shaderId, dur) } },
                        onClose = vm::closeTransitions,
                    )
                }
            }
          }
        }
    }

    val ttsBusy by vm.ttsBusy.collectAsStateWithLifecycle()
    val isGenCaptions = captionGen is GenerationState.Downloading || captionGen is GenerationState.Recognizing
    if (isGenCaptions || ttsBusy) {
        GenerationProgressModal(
            isTts = ttsBusy,
            captionGen = captionGen,
            onCancel = {
                if (ttsBusy) vm.cancelTts()
                else vm.captions.cancelGeneration()
            }
        )
    }
}

/** Единая кривая появления/исчезновения панелей: сильный ease-out, 120–180 мс. */
private val EaseOutStrong = BaseMotion.EaseOut

/** Панели скрываются/появляются при входе в полноэкранный режим: прозрачность + высота, 140–220 мс. */
private val barEnter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(BaseMotion.ENTER_MS, easing = BaseMotion.EaseOut)) +
    androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(BaseMotion.ENTER_MS, easing = BaseMotion.EaseOut))
private val barExit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(BaseMotion.EXIT_MS, easing = BaseMotion.EaseOut)) +
    androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(BaseMotion.EXIT_MS, easing = BaseMotion.EaseOut))

/** Кольцо пипетки на кадре: тянется пальцем, на каждое движение сообщает позицию (доли холста) и размер холста. */
@Composable
private fun PipetteOverlay(pos: androidx.compose.ui.geometry.Offset, onMove: (androidx.compose.ui.geometry.Offset, Float, Float) -> Unit) {
    androidx.compose.foundation.Canvas(
        Modifier.fillMaxSize()
            .pointerInput(Unit) { detectTapGestures { p -> onMove(androidx.compose.ui.geometry.Offset(p.x / size.width, p.y / size.height), size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    onMove(androidx.compose.ui.geometry.Offset((change.position.x / size.width).coerceIn(0f, 1f), (change.position.y / size.height).coerceIn(0f, 1f)), size.width.toFloat(), size.height.toFloat())
                }
            },
    ) {
        val c = androidx.compose.ui.geometry.Offset(pos.x * size.width, pos.y * size.height)
        val ring = Color(0xFFEAE6E2)
        drawCircle(ring, 34.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(11.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = .35f), 28.5.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        val sq = 4.5.dp.toPx()
        drawRect(Color.Black, androidx.compose.ui.geometry.Offset(c.x - sq - 1.dp.toPx(), c.y - sq - 1.dp.toPx()), androidx.compose.ui.geometry.Size((sq + 1.dp.toPx()) * 2, (sq + 1.dp.toPx()) * 2))
        drawRect(Color.White, androidx.compose.ui.geometry.Offset(c.x - sq, c.y - sq), androidx.compose.ui.geometry.Size(sq * 2, sq * 2))
    }
}

private enum class ToolMode { MAIN, MEDIA, AUDIO, TEXT, CAPTION, BACKGROUND }

/**
 * Окно предпросмотра. Позиция курсора, «живая» трансформация и пропорции читаются ТОЛЬКО здесь — рекомпозиция
 * при перемотке ограничена этим блоком. Пропорции рамки берутся из выбранного формата мгновенно (одним кадром);
 * пока плеер ещё не показал композицию нового формата, старая картинка заполняет рамку (ZOOM), а не сжимается.
 */
@UnstableApi
@Composable
private fun PreviewStage(
    vm: EditorViewModel, texts: List<com.base.editor.text.TextClip>, captionItems: List<com.base.editor.captions.CaptionItem>,
    captionStyle: com.base.editor.captions.CaptionStyle, selected: Long?, fullscreen: Boolean, onToggleFullscreen: () -> Unit, modifier: Modifier,
) {
    val playhead by vm.playheadMs.collectAsStateWithLifecycle()
    val total by vm.totalMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val liveClipState = vm.liveClipTransform.collectAsStateWithLifecycle()        // State не читается в композиции: жест не вызывает рекомпозицию
    val aspectNow by vm.canvasAspect.collectAsStateWithLifecycle()
    val rev by vm.targetRev.collectAsStateWithLifecycle()
    val selText by vm.canvasTextId.collectAsStateWithLifecycle()
    val clipAspects by vm.clipAspects.collectAsStateWithLifecycle()
    val cropClip by vm.cropClipId.collectAsStateWithLifecycle()
    val clips by vm.clips.collectAsStateWithLifecycle()
    val scrubOn by vm.scrubOverlayOn.collectAsStateWithLifecycle()
    val scrubFrame by vm.scrubFrame.collectAsStateWithLifecycle()
    val bgSel by vm.canvasBg.collectAsStateWithLifecycle()
    val cover by vm.overlayCover.collectAsStateWithLifecycle()
    val pipOn by vm.pipetteOn.collectAsStateWithLifecycle()
    val pipPos by vm.pipettePos.collectAsStateWithLifecycle()
    var gl by remember { mutableStateOf<PreviewGlView?>(null) }
    gl?.let { view ->
        // всё, что связано с жестом и кадром, идёт мимо композиции — напрямую в GL-вид
        LaunchedEffect(view) { vm.mainAspect.collect { view.setFrameAspect(it) } }
        LaunchedEffect(view) { vm.mainClipTransform.collect { view.setCommittedTransform(it) } }
        LaunchedEffect(view) { vm.canvasAspect.collect { view.setCanvasAspect(it) } }
        LaunchedEffect(view) {
            vm.canvasBg.collect {
                view.setCanvasBackground(it.argb, it == com.base.editor.core.CanvasBg.BLUR, it == com.base.editor.core.CanvasBg.IMAGE)
            }
        }
        LaunchedEffect(view) {
            kotlinx.coroutines.flow.combine(vm.customBgUri, vm.canvasBg) { uri, bg ->
                if (bg == com.base.editor.core.CanvasBg.IMAGE && uri != null) uri else null
            }.collect { uriStr ->
                if (uriStr == null) {
                    view.setCustomBackgroundImage(null)
                } else {
                    withContext(Dispatchers.IO) {
                        val bmp = runCatching {
                            val u = android.net.Uri.parse(uriStr)
                            val c = view.context
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                                android.graphics.ImageDecoder.decodeBitmap(
                                    android.graphics.ImageDecoder.createSource(c.contentResolver, u)
                                ) { dec, info, _ ->
                                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                                    val maxDim = 1280
                                    if (info.size.width > maxDim || info.size.height > maxDim) {
                                        val scale = maxDim.toFloat() / maxOf(info.size.width, info.size.height)
                                        dec.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                                    }
                                }
                            } else {
                                c.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                            }
                        }.getOrNull()
                        withContext(Dispatchers.Main) {
                            view.setCustomBackgroundImage(bmp)
                        }
                    }
                }
            }
        }
        LaunchedEffect(view) { vm.blurRadius.collect { view.setBlurRadius(it) } }
        LaunchedEffect(view) { vm.canvasPx.collect { (w, h) -> view.setBufferSize(w, h) } }
        LaunchedEffect(view) {
            vm.liveClipTransform.collect { l ->
                if (l == null) {
                    view.setLive(null, null)
                } else {
                    val clipRow = vm.controller.state.value.clips.firstOrNull { it.id == l.id }?.row ?: 0
                    if (clipRow == 0) {
                        view.setLive(l.baked, l.current)
                    } else {
                        view.setLive(null, null)
                    }
                }
            }
        }
        LaunchedEffect(view) { vm.glClearNext.collect { view.clearLiveOnNextFrame() } }
    }

    Box(modifier.fillMaxWidth().background(Color.Black).clipToBounds(), contentAlignment = Alignment.Center) {
        // GL-вид создаётся ОДИН раз и абсолютно статичен в дереве Compose: fillMaxSize() без dynamic aspectRatio!
        // Декодирование видео и трансформация холста полностью отвязаны друг от друга.
        AndroidView(
            factory = { c ->
                PreviewGlView(c).also { v ->
                    v.onSurface = { surf -> vm.controller.setOutputSurface(surf) }
                    v.onFrameRendered = { vm.controller.onFrameRendered() }
                    gl = v
                }
            },
            update = { },
            modifier = Modifier.fillMaxSize(),
        )

        // Рамка выбранного формата: ТОЛЬКО для Compose-слоёв (субтитры, текст, оверлеи жестов).
        // GLSurfaceView не входит в этот контейнер, исключая пересоздание буферов SurfaceFlinger'ом при смене формата.
        Box(Modifier.aspectRatio(aspectNow.coerceIn(0.2f, 5f)).clipToBounds()) {
            // кадр под курсором напрямую из файла, пока плеер на паузе догоняет позицию при перемотке
            val sf = scrubFrame
            if (scrubOn && sf != null) Box(Modifier.fillMaxSize().background(bgSel.argb?.let { Color(it) } ?: Color.Transparent)) {
                if (bgSel == com.base.editor.core.CanvasBg.BLUR) {
                    // подложка «размытое видео»: тот же кадр на весь холст, сильно размытый (на Android 12+)
                    Image(sf.image, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(28.dp))
                }
                Image(
                    sf.image, null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        val t = sf.transform
                        translationX = t.x * size.width; translationY = t.y * size.height
                        scaleX = t.scale; scaleY = t.scale; rotationZ = t.rotationDeg
                    },
                )
            }
            val overlayClips = clips.filter { it.row == com.base.editor.media.CompositionFactory.OVERLAY_ROW }
            OverlayStageLayer(overlayClips, playhead, playing, liveClipState.value, vm::effectiveTransform)
            val visibleTexts = texts.filter { it.isVisibleAt(playhead) }
            TextOverlay(visibleTexts.filter { it.pagTemplate == null }, aspectNow, playhead)
            visibleTexts.filter { it.pagTemplate != null }.forEach { PagTitleOverlay(it, playhead, aspectNow) }
            CaptionOverlay(CaptionOps.captionAt(captionItems, playhead), captionStyle, playhead, aspectNow)
            // свободные жесты: перемещение / масштаб / поворот выделенного клипа или текста
            val target = remember(selected, selText, texts, playhead, clips, clipAspects, cropClip, rev) { vm.canvasTarget() }
            CanvasTransformOverlay(aspectNow, target, visibleTexts, vm, liveClipState)
            if (pipOn) PipetteOverlay(pipPos) { pos, w, h -> vm.movePipette(pos, w, h) }

        }
        // полноэкранный режим: иконка внизу справа; в полноэкранном — Play/Pause, время, полоса перемотки и сворачивание
        if (fullscreen) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(if (playing) Lucide.Pause else Lucide.Play, if (playing) "Пауза" else "Воспроизвести", vm::togglePlay, size = 44.dp)
                Spacer(Modifier.width(8.dp))
                Text("${Format.duration(playhead)} / ${Format.duration(total)}", color = Color.White, fontSize = 13.sp)
                androidx.compose.material3.Slider(
                    value = playhead.toFloat(),
                    onValueChange = { vm.scrubTo(it.toLong()) },
                    onValueChangeFinished = { vm.scrubEnd() },
                    valueRange = 0f..total.coerceAtLeast(1L).toFloat(),
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = BaseColors.Primary,
                        activeTrackColor = BaseColors.Primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                    ),
                )
                RoundIcon(Lucide.Minimize, "Выйти из полноэкранного режима", onToggleFullscreen, size = 44.dp)
            }
        } else {
            Box(Modifier.align(Alignment.BottomEnd).padding(10.dp)) {
                RoundIcon(Lucide.Maximize, "На весь экран", onToggleFullscreen)
            }
        }
    }
}

/** Время / Play / Undo-Redo / ромбик ключа: курсор читается здесь, остальной экран не перекомпонуется. */
@Composable
private fun TransportRow(vm: EditorViewModel, hasMainSelected: Boolean) {
    val playhead by vm.playheadMs.collectAsStateWithLifecycle()
    val total by vm.totalMs.collectAsStateWithLifecycle()
    val playing by vm.isPlaying.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val keyframesRev by vm.keyframesRevision.collectAsStateWithLifecycle()
    val keyframes by vm.keyframes.collectAsStateWithLifecycle()
    val hasKey = remember(playhead, keyframesRev, keyframes, hasMainSelected) { vm.hasKeyframeAtCursor() }

    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(Modifier.align(Alignment.CenterStart)) {
            Text(Format.duration(playhead), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text("  /  ${Format.duration(total)}", color = Color.White.copy(alpha = .5f), fontSize = 14.sp)
        }
        RoundIcon(if (playing) Lucide.Pause else Lucide.Play, if (playing) "Пауза" else "Воспроизвести", vm::togglePlay, size = 52.dp)
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            // ромбик ключевого кадра: только когда выбран клип основной дорожки (режим «Изменить»)
            if (hasMainSelected) {
                KeyframeButton(hasKey = hasKey, onClick = vm::toggleKeyframe)
                Spacer(Modifier.width(4.dp))
            }
            RoundIcon(Lucide.Undo2, "Отменить", vm::undo, enabled = canUndo)
            RoundIcon(Lucide.Redo2, "Повторить", vm::redo, enabled = canRedo)
        }
    }
}

/** Таймлайн с закреплёнными кнопками; курсор и зум читаются здесь. */
@Composable
private fun TimelineHost(
    vm: EditorViewModel, clips: List<com.base.editor.core.Clip>, transitions: List<com.base.editor.core.Transition>,
    captionItems: List<com.base.editor.captions.CaptionItem>, texts: List<com.base.editor.text.TextClip>,
    keyframes: Map<Long, List<com.base.editor.core.Keyframe>>, selected: Long?, selText: String?, selCaption: String?,
) {
    val density = LocalDensity.current
    val playhead by vm.playheadMs.collectAsStateWithLifecycle()
    val total by vm.totalMs.collectAsStateWithLifecycle()
    val zoom by vm.pxPerSec.collectAsStateWithLifecycle()
    val muted by vm.muted.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxWidth().height(TL_HEIGHT_DP.dp)) {
        TimelineView(clips, transitions, captionItems, texts, keyframes, selected, selText, selCaption, playhead, total, zoom, vm, Modifier.fillMaxWidth())
        // кнопка «звук клипа» слева от нулевой отметки — уезжает вместе со шкалой
        val scrollPx = playhead * zoom * density.density / 1000f
        Column(
            Modifier.pressable(onClick = vm::toggleMute).offset { IntOffset(-scrollPx.roundToInt(), with(density) { 36.dp.roundToPx() }) }.padding(start = 12.dp).width(64.dp)
                .clip(RoundedCornerShape(10.dp)).padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(if (muted) Lucide.VolumeX else Lucide.Volume2, null, tint = Color.White, modifier = Modifier.size(26.dp))
            Text(if (muted) "Вкл. звук клипа" else "Выкл. звук клипа", color = Color.White.copy(alpha = .8f), fontSize = 10.sp, textAlign = TextAlign.Center, lineHeight = 12.sp)
        }
        // «+» закреплён у правого края на уровне основной дорожки — доступен при любой прокрутке и зуме
        Box(
            Modifier.pressable(onClick = vm::addMedia).align(Alignment.TopEnd).padding(top = 46.dp, end = 8.dp).size(44.dp)
                .shadow(6.dp, CircleShape).clip(CircleShape).background(Color.White),
            contentAlignment = Alignment.Center,
        ) { Icon(Lucide.Plus, "Добавить видео или фото", tint = Color.Black, modifier = Modifier.size(28.dp)) }
    }
}

private const val TOOLBAR_HEIGHT_DP = 74

@Composable
private fun FormatIcon(format: com.base.editor.core.CanvasFormat, tint: Color, size: androidx.compose.ui.unit.Dp = 16.dp) {
    val aspect = format.aspect ?: (9f / 16f)
    Box(
        modifier = Modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        val (w, h) = if (aspect >= 1f) {
            size to (size / aspect)
        } else {
            (size * aspect) to size
        }
        Box(
            modifier = Modifier
                .size(w.coerceAtLeast(6.dp), h.coerceAtLeast(6.dp))
                .border(1.5.dp, tint, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun FormatTopButton(
    format: com.base.editor.core.CanvasFormat,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FormatIcon(format, tint = Color.White, size = 16.dp)
        Text(
            text = format.label,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Icon(
            imageVector = Lucide.ChevronDown,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp)
        )
    }
}

/** Панель «Соотношение сторон»: пресеты пропорций холста в виде карточек. */
@Composable
private fun FormatPanel(
    current: com.base.editor.core.CanvasFormat,
    onPick: (com.base.editor.core.CanvasFormat) -> Unit,
    onClose: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color(0xFF1E1E20))
            .padding(top = 10.dp, bottom = 16.dp)
    ) {
        // Drag handle
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f))
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Соотношение сторон",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            RoundIcon(Lucide.Check, "Готово", onClose)
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            com.base.editor.core.CanvasFormat.entries.forEach { f ->
                val on = f == current
                val cardBg = if (on) Color(0xFFE5E5EA) else Color(0xFF2C2C2E)
                val contentColor = if (on) Color.Black else Color.White
                Column(
                    modifier = Modifier
                        .width(76.dp)
                        .height(82.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardBg)
                        .pressable { onPick(f) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        FormatIcon(f, tint = contentColor, size = 26.dp)
                    }
                    Text(
                        text = f.label,
                        color = contentColor,
                        fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/** Панель поверх фиксированной области (не влияет на размеры соседей); появляется плавно (fade), без вылета снизу. */
@Composable
private fun BoxScope.BottomPanel(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible, modifier = Modifier.align(Alignment.BottomCenter),
        enter = fadeIn(androidx.compose.animation.core.tween(180, easing = EaseOutStrong)), exit = fadeOut(androidx.compose.animation.core.tween(120, easing = EaseOutStrong)),
    ) { content() }
}

/** Строка инструментов: каждая вкладка получает ровно равную долю ширины и центрируется. */
@Composable
private fun ToolRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, content = content)
}

/** Ряд кнопок с горизонтальной прокруткой — для панелей, где инструментов больше, чем помещается в экран. */
@Composable
private fun ScrollToolRow(content: @Composable RowScope.() -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalToolScroll provides true) {
        Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

/** В прокручиваемых рядах кнопки имеют нормальную фиксированную ширину, а не сжимаются по весу. */
private val LocalToolScroll = androidx.compose.runtime.compositionLocalOf { false }

@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 44.dp, enabled: Boolean = true) {
    val a by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else .35f, androidx.compose.animation.core.tween(BaseMotion.STATE_MS, easing = BaseMotion.EaseOut), label = "iconAlpha")
    Box(Modifier.pressable(enabled = enabled, onClick = onClick).size(size).clip(CircleShape).alpha(a), contentAlignment = Alignment.Center) {
        // смена иконки (Play ↔ Pause, ромбик ключа) — короткий кроссфейд
        Crossfade(icon, animationSpec = androidx.compose.animation.core.tween(BaseMotion.STATE_MS, easing = BaseMotion.EaseOut), label = "icon") {
            Icon(it, desc, tint = Color.White, modifier = Modifier.size(if (size > 48.dp) 34.dp else 26.dp))
        }
    }
}

/**
 * Кнопка ключевого кадра (как в CapCut): ромбик с плюсом — ключа на курсоре нет (тап добавит),
 * ромбик с минусом — курсор стоит на существующем ключе (тап удалит).
 */
@Composable
private fun KeyframeButton(hasKey: Boolean, onClick: () -> Unit) {
    Box(Modifier.pressable(onClick = onClick).size(44.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
            val w = size.width; val h = size.height
            val cx = w / 2f; val cy = h / 2f
            val r = w * 0.30f
            val diamond = androidx.compose.ui.graphics.Path().apply {
                moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
            }
            drawPath(diamond, if (hasKey) BaseColors.Primary else Color.White)
            // плюс или минус внутри ромбика
            val ink = Color(0xFF111318)
            val lw = w * 0.075f; val arm = r * 0.48f
            drawLine(ink, androidx.compose.ui.geometry.Offset(cx - arm, cy), androidx.compose.ui.geometry.Offset(cx + arm, cy), lw)
            if (!hasKey) drawLine(ink, androidx.compose.ui.geometry.Offset(cx, cy - arm), androidx.compose.ui.geometry.Offset(cx, cy + arm), lw)
        }
    }
}

@Composable
private fun RowScope.ToolButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.pressable(onClick = onClick).then(if (LocalToolScroll.current) Modifier.width(72.dp) else Modifier.weight(1f).widthIn(min = 56.dp)).fillMaxHeight().clip(RoundedCornerShape(12.dp)),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TransitionPanel(
    current: com.base.editor.core.Transition?, maxMs: Long, items: List<com.base.editor.core.TransitionInfo>,
    onPick: (String?, Long) -> Unit, onDuration: (Long) -> Unit, onClose: () -> Unit,
) {
    val minMs = 200L
    val hi = maxMs.coerceAtLeast(minMs + 1).toFloat()
    var dur by remember(current?.leftId, maxMs) { mutableStateOf((current?.durationMs ?: 500L).coerceIn(minMs, maxMs.coerceAtLeast(minMs)).toFloat()) }
    Column(Modifier.fillMaxWidth().background(BaseColors.DarkPanel).padding(top = 8.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Переходы", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            RoundIcon(Lucide.Check, "Готово", onClose, size = 40.dp)
        }
        LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                TransitionTile("Нет", Icons.Rounded.Block, selected = current == null) { onPick(null, dur.toLong()) }
            }
            items(items, key = { it.id }) { t ->
                TransitionTile(t.label, Lucide.Paintbrush, selected = current?.shaderId == t.id, previewAsset = "shaders/transitions/previews/${t.id}.webp") { onPick(t.id, dur.toLong()) }
            }
        }
        if (current != null && maxMs > minMs) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Длительность", color = Color.White.copy(alpha = .7f), fontSize = 13.sp)
                Slider(
                    value = dur, onValueChange = { dur = it }, valueRange = minMs.toFloat()..hi,
                    onValueChangeFinished = { onDuration(dur.toLong()) },
                    colors = SliderDefaults.colors(thumbColor = BaseColors.Primary, activeTrackColor = BaseColors.Primary),
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                Text("%.1f с".format(dur / 1000f), color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun TransitionTile(label: String, icon: ImageVector, selected: Boolean, previewAsset: String? = null, onClick: () -> Unit) {
    Column(
        Modifier.pressable(onClick = onClick).width(96.dp).clip(RoundedCornerShape(12.dp))
            .border(BorderStroke(if (selected) 2.dp else 0.dp, if (selected) BaseColors.Primary else Color.Transparent), RoundedCornerShape(12.dp))
            .background(BaseColors.DarkSlot),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.fillMaxWidth().height(54.dp).background(Color(0xFF15161A)), contentAlignment = Alignment.Center) {
            if (previewAsset != null) LiveTransitionPreview(previewAsset, Modifier.fillMaxSize())
            else Icon(icon, null, tint = if (selected) BaseColors.Primary else Color.White, modifier = Modifier.size(26.dp))
        }
        Text(label, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, minLines = 2, modifier = Modifier.padding(horizontal = 4.dp, vertical = 5.dp))
    }
}

/** Зацикленная анимация перехода (WebP из assets). Рисуется системным декодером, без сторонних библиотек. */
@Composable
private fun LiveTransitionPreview(asset: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val drawable by produceState<android.graphics.drawable.Drawable?>(null, asset) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(ctx.assets, asset))
            }.getOrNull()
        }
    }
    drawable?.let { d ->
        AndroidView(
            factory = { c -> android.widget.ImageView(c).apply { scaleType = android.widget.ImageView.ScaleType.CENTER_CROP } },
            update = { v ->
                v.setImageDrawable(d)
                (d as? android.graphics.drawable.AnimatedImageDrawable)?.apply { repeatCount = android.graphics.drawable.AnimatedImageDrawable.REPEAT_INFINITE; start() }
            },
            onRelease = { v -> (v.drawable as? android.graphics.drawable.AnimatedImageDrawable)?.stop() },
            modifier = modifier,
        )
    }
}

@Composable
private fun ExportDialog(state: ExportState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val finished = state is ExportState.Done || state is ExportState.Failed
    AlertDialog(
        onDismissRequest = { if (finished) onDismiss() },
        containerColor = BaseColors.DarkPanel,
        title = { Text(when (state) { is ExportState.Done -> "Готово"; is ExportState.Failed -> "Ошибка экспорта"; else -> "Экспорт видео" }, color = Color.White) },
        text = {
            Column {
                when (state) {
                    ExportState.Preparing -> { Text("Подготовка…", color = Color.White.copy(alpha = .8f)); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp), color = BaseColors.Primary) }
                    is ExportState.Progress -> {
                        Text("${state.percent}%" + if (state.attempt > 0) " (упрощённый режим)" else "", color = Color.White.copy(alpha = .8f))
                        LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp), color = BaseColors.Primary)
                    }
                    is ExportState.Retrying -> Text("Устройство не справилось с первой попыткой — повторяем без эффектов и с меньшим разрешением.", color = Color.White.copy(alpha = .8f))
                    is ExportState.Done -> Text("Видео сохранено в галерею: Movies/BASE.", color = Color.White.copy(alpha = .8f))
                    is ExportState.Failed -> Text(state.message, color = Color.White.copy(alpha = .8f))
                }
            }
        },
        confirmButton = { if (finished) TextButton(onDismiss) { Text("OK", color = BaseColors.Primary) } },
        dismissButton = { if (!finished) TextButton(onCancel) { Text("Отмена", color = Color.White) } },
    )
}

@Composable
private fun GenerationProgressModal(
    isTts: Boolean,
    captionGen: GenerationState,
    onCancel: () -> Unit,
) {
    val inf = rememberInfiniteTransition(label = "wave_inf")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    val percent: Int = when {
        isTts -> {
            val p by produceState(initialValue = 15) {
                var v = 15
                while (v < 92) {
                    delay(130)
                    v += (2..5).random()
                    value = v.coerceAtMost(92)
                }
            }
            p
        }
        captionGen is GenerationState.Downloading -> {
            if (captionGen.fraction > 0f) (captionGen.fraction * 50).toInt().coerceIn(1, 50)
            else 15
        }
        captionGen is GenerationState.Recognizing -> {
            if (captionGen.fraction > 0f) (50 + captionGen.fraction * 50).toInt().coerceIn(51, 99)
            else {
                val p by produceState(initialValue = 20) {
                    var v = 20
                    while (v < 92) {
                        delay(160)
                        v += (1..4).random()
                        value = v.coerceAtMost(92)
                    }
                }
                p
            }
        }
        else -> 15
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .pressable { /* absorb clicks */ },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(280.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF23252E))
                .padding(horizontal = 22.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                Modifier
                    .size(32.dp, 3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            )

            Spacer(Modifier.height(18.dp))

            Box(
                Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "✦",
                    color = Color(0xFF00E5FF),
                    fontSize = 12.sp,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp, top = 24.dp)
                )

                Text(
                    text = "✦",
                    color = Color(0xFF00E5FF),
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 8.dp, top = 18.dp)
                )

                Box(
                    Modifier
                        .size(176.dp, 46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF14242B)),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val baseHeights = remember {
                            listOf(6f, 8f, 12f, 16f, 22f, 26f, 18f, 24f, 28f, 22f, 26f, 20f, 16f, 12f, 18f, 14f, 10f, 8f, 6f)
                        }
                        baseHeights.forEachIndexed { idx, bh ->
                            val dynamicH = (bh * (0.55f + 0.45f * sin(phase + idx * 0.65f))).coerceIn(4f, 32f)
                            Box(
                                Modifier
                                    .size(2.5.dp, dynamicH.dp)
                                    .clip(RoundedCornerShape(1.25.dp))
                                    .background(Color(0xFF00E5FF))
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text = if (isTts) "Генерация озвучки..." else "Генерация субтитров...",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "$percent%",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(20.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF383A44))
                    .pressable(onClick = onCancel),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Отмена",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
