package com.base.editor.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.base.editor.captions.CaptionFont
import com.base.editor.captions.CaptionFonts
import com.base.editor.captions.CaptionItem
import com.base.editor.captions.CaptionPresets
import com.base.editor.captions.CaptionStyle
import com.base.editor.captions.GenerationState
import com.base.editor.captions.WordAnimation
import com.base.editor.captions.WordTimestamp
import com.base.editor.captions.asr.SpeechLanguage
import com.base.editor.data.Format
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.Lucide
import com.base.editor.ui.theme.animatedSelectColor
import com.base.editor.ui.theme.pressable
import com.base.editor.text.TextFonts
import com.base.editor.text.TextStyles
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import kotlin.math.roundToInt

private enum class CaptionViewMode { MAIN_GEN, LANG_PICKER, TEMPLATES_GRID, ITEMS_LIST, STYLES_TAB }
private enum class GenSource { VIDEO, VOICE }
private enum class CaptionStyleMainTab(val label: String) { FONTS("Шрифты"), STYLES("Стили") }

private val COLOR_PALETTE = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFFFFE600L, 0xFFFF9800L, 0xFFFF2D55L,
    0xFF00E5FFL, 0xFF00FF66L, 0xFFB388FFL, 0xFFE91E63L, 0xFF2196F3L,
)

@Composable
fun CaptionPanel(
    items: List<CaptionItem>,
    style: CaptionStyle,
    generation: GenerationState,
    playheadMs: Long,
    editingId: String?,
    onPrefetch: (SpeechLanguage) -> Unit,
    onGenerate: (SpeechLanguage) -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onOpenItem: (CaptionItem) -> Unit,
    onCloseEdit: () -> Unit,
    onUpdateText: (String, String) -> Unit,
    onUpdateTiming: (String, Long, Long) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onClearAll: () -> Unit,
    onPreset: (String) -> Unit,
    onStyle: ((CaptionStyle) -> CaptionStyle) -> Unit,
    onClose: () -> Unit,
) {
    var viewMode by rememberSaveable { mutableStateOf(if (items.isEmpty()) CaptionViewMode.MAIN_GEN else CaptionViewMode.ITEMS_LIST) }
    var selectedLang by rememberSaveable { mutableStateOf(SpeechLanguage.RU) }
    var genSource by rememberSaveable { mutableStateOf(GenSource.VIDEO) }
    var sheetHeightRatio by rememberSaveable { mutableStateOf(0.38f) }

    LaunchedEffect(selectedLang) { onPrefetch(selectedLang) }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.apply {
                setGravity(Gravity.BOTTOM)
                setDimAmount(0f)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .pressable(onClick = onClose),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .pressable { /* absorb clicks */ }
                    .then(
                        when (viewMode) {
                            CaptionViewMode.MAIN_GEN -> Modifier.wrapContentHeight()
                            CaptionViewMode.STYLES_TAB -> Modifier.fillMaxHeight(sheetHeightRatio)
                            else -> Modifier.fillMaxHeight(0.82f)
                        }
                    )
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                    .background(Color(0xFF1E1F24))
                    .navigationBarsPadding()
                    .padding(top = 10.dp, bottom = 12.dp)
            ) {
            // Drag handle with gesture expansion for STYLES_TAB
            val screenH = LocalView.current.resources.displayMetrics.heightPixels.toFloat()
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(viewMode) {
                        if (viewMode == CaptionViewMode.STYLES_TAB) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                val delta = -dragAmount / screenH
                                sheetHeightRatio = (sheetHeightRatio + delta).coerceIn(0.35f, 0.85f)
                            }
                        }
                    }
                    .pressable {
                        if (viewMode == CaptionViewMode.STYLES_TAB) {
                            sheetHeightRatio = if (sheetHeightRatio < 0.6f) 0.82f else 0.38f
                        }
                    }
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                )
            }

            Spacer(Modifier.height(8.dp))

            when (viewMode) {
                CaptionViewMode.MAIN_GEN -> {
                    MainGenerationScreen(
                        selectedLang = selectedLang,
                        genSource = genSource,
                        selectedPresetId = style.id,
                        generation = generation,
                        onSourceChange = { genSource = it },
                        onOpenLang = { viewMode = CaptionViewMode.LANG_PICKER },
                        onOpenTemplates = { viewMode = CaptionViewMode.TEMPLATES_GRID },
                        onPresetPick = { onPreset(it) },
                        onGenerate = { onGenerate(selectedLang) },
                        onCancel = onCancel,
                        onDismissError = onDismissError,
                        onClose = onClose,
                        onViewExisting = { if (items.isNotEmpty()) viewMode = CaptionViewMode.ITEMS_LIST }
                    )
                }

                CaptionViewMode.LANG_PICKER -> {
                    LanguageSelectionScreen(
                        current = selectedLang,
                        onPick = {
                            selectedLang = it
                            viewMode = CaptionViewMode.MAIN_GEN
                        },
                        onBack = { viewMode = CaptionViewMode.MAIN_GEN }
                    )
                }

                CaptionViewMode.TEMPLATES_GRID -> {
                    TemplatesGridScreen(
                        currentPresetId = style.id,
                        onPick = {
                            onPreset(it)
                            viewMode = CaptionViewMode.MAIN_GEN
                        },
                        onBack = { viewMode = CaptionViewMode.MAIN_GEN }
                    )
                }

                CaptionViewMode.ITEMS_LIST -> {
                    ItemsListScreen(
                        items = items,
                        playheadMs = playheadMs,
                        onOpenItem = onOpenItem,
                        onAdd = onAdd,
                        onClearAll = onClearAll,
                        onSwitchToGen = { viewMode = CaptionViewMode.MAIN_GEN },
                        onSwitchToStyle = { sheetHeightRatio = 0.38f; viewMode = CaptionViewMode.STYLES_TAB },
                        onClose = onClose
                    )
                }

                CaptionViewMode.STYLES_TAB -> {
                    CaptionStyleEditScreen(
                        style = style,
                        onPreset = onPreset,
                        onStyle = onStyle,
                        onBack = { viewMode = CaptionViewMode.ITEMS_LIST },
                        onClose = onClose
                    )
                }
            }
        }
    }
}

    items.firstOrNull { it.id == editingId }?.let { EditDialog(it, onCloseEdit, onUpdateText, onUpdateTiming, onDelete) }
}

@Composable
private fun SubtitleKaraokePreviewText(
    preset: CaptionStyle,
    isFirst: Boolean = false,
    fontSize: androidx.compose.ui.unit.TextUnit = 22.sp
) {
    val family = CaptionFonts.family(preset.font, preset.fontWeight, preset.italic)
    val annotated = buildAnnotatedString {
        withStyle(SpanStyle(color = Color(preset.activeColor))) {
            append("А")
        }
        withStyle(SpanStyle(color = Color(preset.textColor))) {
            append("а")
        }
    }

    Box(contentAlignment = Alignment.Center) {
        if (preset.backgroundColor != 0) {
            Box(
                Modifier
                    .size(54.dp, 34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(preset.backgroundColor))
            )
        }
        Text(
            text = annotated,
            fontFamily = family,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            letterSpacing = 1.sp,
            style = androidx.compose.ui.text.TextStyle(
                shadow = if (preset.shadowColor != 0) Shadow(
                    color = Color(preset.shadowColor),
                    offset = androidx.compose.ui.geometry.Offset(2f, 2f),
                    blurRadius = 4f
                ) else Shadow(
                    color = Color.Black,
                    offset = androidx.compose.ui.geometry.Offset(1f, 1f),
                    blurRadius = 2f
                )
            )
        )
    }
}

/**
 * Экран 1: Главное окно создания субтитров.
 */
@Composable
private fun MainGenerationScreen(
    selectedLang: SpeechLanguage,
    genSource: GenSource,
    selectedPresetId: String,
    generation: GenerationState,
    onSourceChange: (GenSource) -> Unit,
    onOpenLang: () -> Unit,
    onOpenTemplates: () -> Unit,
    onPresetPick: (String) -> Unit,
    onGenerate: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    onClose: () -> Unit,
    onViewExisting: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Шапка: заголовок и крестик (без знака вопроса)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Автоматические субтитры",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )

            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pressable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Lucide.X, "Закрыть", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Генерация на основе: [ Видео | Озвучка ]
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Генерация на основе:", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF282932))
                    .padding(3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (genSource == GenSource.VIDEO) Color(0xFF3B3C47) else Color.Transparent)
                        .pressable { onSourceChange(GenSource.VIDEO) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Видео", color = if (genSource == GenSource.VIDEO) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (genSource == GenSource.VOICE) Color(0xFF3B3C47) else Color.Transparent)
                        .pressable { onSourceChange(GenSource.VOICE) }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Озвучка", color = if (genSource == GenSource.VOICE) Color.White else Color.White.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // Исходный язык: Русский >
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF282932))
                .pressable(onClick = onOpenLang)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Исходный язык", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(selectedLang.label, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Lucide.ChevronRight, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(16.dp))

        // Шаблоны >
        Row(
            Modifier
                .fillMaxWidth()
                .pressable(onClick = onOpenTemplates),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Шаблоны", color = Color.White.copy(alpha = 0.9f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Icon(Lucide.ChevronRight, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
        }

        Spacer(Modifier.height(10.dp))

        // Карусель шаблонов стиля с подсветкой одной буквы
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(CaptionPresets.all, key = { it.id }) { preset ->
                val isSel = preset.id == selectedPresetId
                val isDefault = preset.id == CaptionPresets.all.first().id
                Box(
                    Modifier
                        .size(76.dp, 64.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF282932))
                        .border(
                            if (isSel) 2.dp else 1.dp,
                            if (isSel) BaseColors.Primary else Color.White.copy(alpha = 0.1f),
                            RoundedCornerShape(12.dp)
                        )
                        .pressable { onPresetPick(preset.id) }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    SubtitleKaraokePreviewText(preset = preset, isFirst = false, fontSize = 20.sp)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Индикатор процесса или ошибки
        when (generation) {
            is GenerationState.Downloading, is GenerationState.Recognizing -> {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(color = BaseColors.Primary, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Распознавание речи...", color = Color.White, fontSize = 14.sp)
                    Spacer(Modifier.width(12.dp))
                    Text("Отмена", color = Color(0xFFFF2D55), fontSize = 13.sp, modifier = Modifier.pressable(onClick = onCancel))
                }
            }
            is GenerationState.Failed -> {
                Text(
                    generation.message,
                    color = Color(0xFFFF2D55),
                    fontSize = 12.sp,
                    modifier = Modifier.pressable(onClick = onDismissError).padding(bottom = 8.dp)
                )
            }
            else -> Unit
        }

        // Большая кнопка "Создать" (Циан) чистая, без звездочек и без бейджа
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(BaseColors.Primary)
                .pressable(onClick = onGenerate),
            contentAlignment = Alignment.Center
        ) {
            Text("Создать", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))
    }
}

/**
 * Экран 2: Выбор языка с RadioButton (по скриншоту 2).
 */
@Composable
private fun LanguageSelectionScreen(
    current: SpeechLanguage,
    onPick: (SpeechLanguage) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pressable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Lucide.ArrowLeft, "Назад", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Исходный язык", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "Выберите язык аудио, чтобы распознавание было максимально точным",
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp)
        )

        Spacer(Modifier.height(14.dp))

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(SpeechLanguage.entries, key = { it.name }) { lang ->
                val isSelected = lang == current
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .pressable { onPick(lang) }
                        .padding(horizontal = 10.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(lang.label, color = Color.White, fontSize = 15.sp)
                    RadioButton(
                        selected = isSelected,
                        onClick = { onPick(lang) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = BaseColors.Primary,
                            unselectedColor = Color.White.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        }
    }
}

/**
 * Экран 3: 2-колоночная сетка шаблонов (по скриншоту 3).
 */
@Composable
private fun TemplatesGridScreen(
    currentPresetId: String,
    onPick: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pressable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(Lucide.ArrowLeft, "Назад", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text("Шаблоны", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(14.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(CaptionPresets.all, key = { it.id }) { preset ->
                val isSel = preset.id == currentPresetId
                val isDefault = preset.id == CaptionPresets.all.first().id
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(88.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF282932))
                        .border(
                            if (isSel) 2.dp else 1.dp,
                            if (isSel) BaseColors.Primary else Color.White.copy(alpha = 0.1f),
                            RoundedCornerShape(12.dp)
                        )
                        .pressable { onPick(preset.id) }
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        SubtitleKaraokePreviewText(preset = preset, isFirst = false, fontSize = 20.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = preset.name,
                            fontSize = 11.sp,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * Экран со списком реплик (когда субтитры уже сгенерированы).
 */
@Composable
private fun ItemsListScreen(
    items: List<CaptionItem>,
    playheadMs: Long,
    onOpenItem: (CaptionItem) -> Unit,
    onAdd: () -> Unit,
    onClearAll: () -> Unit,
    onSwitchToGen: () -> Unit,
    onSwitchToStyle: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(BaseColors.Primary)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Реплики (${items.size})", color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF282932))
                        .pressable(onClick = onSwitchToStyle)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("Стиль", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF282932))
                        .pressable(onClick = onSwitchToGen)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("+ Создать", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }

            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pressable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Lucide.Check, "Готово", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF282932))
                    .pressable(onClick = onClearAll)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("Удалить все", color = Color(0xFFFF2D55), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(items, key = { it.id }) { c ->
                val isNow = playheadMs >= c.startMs && playheadMs < c.endMs
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isNow) BaseColors.Primary.copy(alpha = 0.15f) else Color.Transparent)
                        .pressable { onOpenItem(c) }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(Format.duration(c.startMs), color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    Text(c.text.ifBlank { "(пусто)" }, color = Color.White, fontSize = 14.sp, maxLines = 2, modifier = Modifier.weight(1f))
                    Icon(Lucide.Pencil, null, tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

/**
 * Экран глубокой настройки стилей субтитров (Шрифты + Стили).
 * По запросу пользователя: доступ к шрифтам и стилям текстового редактора,
 * строго без анимаций («без анимаций»), и карточки пресетов с буквами «Aa».
 */
@Composable
private fun CaptionStyleEditScreen(
    style: CaptionStyle,
    onPreset: (String) -> Unit,
    onStyle: ((CaptionStyle) -> CaptionStyle) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
) {
    var mainTab by rememberSaveable { mutableStateOf(CaptionStyleMainTab.FONTS) }
    var selectedCategory by rememberSaveable { mutableStateOf("В тренде") }
    var subTab by rememberSaveable { mutableStateOf(StyleSubTab.COLOR) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
    ) {
        // Шапка: Назад, Заголовок, Готово
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .pressable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Lucide.ArrowLeft, "Назад", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("Стиль субтитров", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }

            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .pressable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(Lucide.Check, "Готово", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(10.dp))

        // Основные вкладки: [ Шрифты | Стили ] (строго без анимаций!)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CaptionStyleMainTab.entries.forEach { tab ->
                val isSelected = mainTab == tab
                Box(
                    Modifier
                        .weight(1f)
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) Color(0xFF35363F) else Color(0xFF24252B))
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, BaseColors.Primary, RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .pressable { mainTab = tab },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab.label,
                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        when (mainTab) {
            CaptionStyleMainTab.FONTS -> {
                // Категории шрифтов
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(TextFonts.CATEGORIES, key = { it }) { cat ->
                        val isSelected = cat == selectedCategory
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) Color(0xFF35363F) else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isSelected) Color.Transparent else Color.White.copy(alpha = 0.15f),
                                    RoundedCornerShape(16.dp)
                                )
                                .pressable { selectedCategory = cat }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = cat,
                                fontSize = 12.sp,
                                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.6f),
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                val fontsForCategory = remember(selectedCategory) {
                    TextFonts.forCategory(selectedCategory)
                }

                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(fontsForCategory, key = { it.id }) { f ->
                        val isSelected = style.fontId == f.id
                        Box(
                            Modifier
                                .height(46.dp)
                                .pressable { onStyle { it.copy(fontId = f.id) } }
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF282932))
                                .then(
                                    if (isSelected) Modifier.border(2.dp, BaseColors.Primary, RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                                .padding(horizontal = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = f.label,
                                fontFamily = f.family,
                                fontSize = 13.sp,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            CaptionStyleMainTab.STYLES -> {
                // Пресеты с буквами Aa (как просил пользователь: "вместо этих слов буквы аа просто")
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 2.dp)
                ) {
                    // Текстовые стили TextStyles
                    items(TextStyles.all, key = { "ts_${it.id}" }) { p ->
                        val isSelected = style.id == p.id
                        val sample = remember(p.id) { p.apply(com.base.editor.text.TextClip(text = "Aa", startMs = 0L)) }
                        Box(
                            Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF282932))
                                .border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) BaseColors.Primary else Color.White.copy(alpha = 0.1f),
                                    RoundedCornerShape(10.dp)
                                )
                                .pressable {
                                    onStyle { cur ->
                                        cur.copy(
                                            id = p.id,
                                            name = p.label,
                                            fontId = p.fontId,
                                            textColor = sample.textColor.toInt(),
                                            activeColor = sample.textColor.toInt(),
                                            strokeColor = sample.strokeColor.toInt(),
                                            strokeEm = sample.strokeWidth,
                                            backgroundColor = sample.backgroundColor.toInt(),
                                            backgroundCornerEm = if (sample.bgCornerRadius > 0f) 0.30f else 0f,
                                            shadowColor = if (sample.glowColor != 0L) sample.glowColor.toInt() else sample.shadowColor.toInt(),
                                            shadowBlurEm = if (sample.glowRadius > 0f) sample.glowRadius else sample.shadowBlur,
                                            shadowDyEm = sample.shadowDy,
                                        )
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            if (p.id == "plain") {
                                Text("Aa", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
                            } else {
                                if (sample.hasBackground) {
                                    Box(
                                        Modifier
                                            .size(34.dp, 24.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(sample.backgroundColor))
                                    )
                                }
                                Text(
                                    text = "Aa",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = TextFonts.family(sample.fontId),
                                    color = Color(sample.textColor),
                                    style = androidx.compose.ui.text.TextStyle(
                                        shadow = when {
                                            sample.glowColor != 0L -> Shadow(Color(sample.glowColor), androidx.compose.ui.geometry.Offset.Zero, 8f)
                                            sample.shadowColor != 0L -> Shadow(Color(sample.shadowColor), androidx.compose.ui.geometry.Offset(0f, 2f), 3f)
                                            else -> null
                                        }
                                    )
                                )
                            }
                        }
                    }

                    // Пресеты CaptionPresets
                    items(CaptionPresets.all, key = { "cp_${it.id}" }) { p ->
                        val isSelected = style.id == p.id
                        Box(
                            Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF282932))
                                .border(
                                    if (isSelected) 2.dp else 1.dp,
                                    if (isSelected) BaseColors.Primary else Color.White.copy(alpha = 0.1f),
                                    RoundedCornerShape(10.dp)
                                )
                                .pressable { onPreset(p.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            SubtitleKaraokePreviewText(preset = p, fontSize = 18.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Подвкладки [Цвет | Обводка | Сияние | Фон | Тени]
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StyleSubTab.entries.forEach { s ->
                        val active = subTab == s
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) BaseColors.Primary else Color(0xFF282932))
                                .pressable { subTab = s }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(s.label, color = if (active) Color.Black else Color.White, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Контент подвкладок
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
                    when (subTab) {
                        StyleSubTab.COLOR -> {
                            Text("Цвет активного слова:", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(COLOR_PALETTE, key = { it }) { colorHex ->
                                    val isSel = (style.activeColor.toLong() and 0xFFFFFFL) == (colorHex and 0xFFFFFFL)
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .pressable { onStyle { it.copy(activeColor = colorHex.toInt()) } }
                                            .clip(CircleShape)
                                            .background(Color(colorHex))
                                            .then(if (isSel) Modifier.border(3.dp, BaseColors.Primary, CircleShape) else Modifier)
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text("Цвет остального текста:", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(COLOR_PALETTE, key = { it }) { colorHex ->
                                    val isSel = (style.textColor.toLong() and 0xFFFFFFL) == (colorHex and 0xFFFFFFL)
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .pressable { onStyle { it.copy(textColor = colorHex.toInt()) } }
                                            .clip(CircleShape)
                                            .background(Color(colorHex))
                                            .then(if (isSel) Modifier.border(3.dp, BaseColors.Primary, CircleShape) else Modifier)
                                    )
                                }
                            }
                        }
                        StyleSubTab.STROKE -> {
                            Text("Цвет обводки:", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            val noStroke = style.strokeEm <= 0f
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .pressable { onStyle { it.copy(strokeEm = 0f) } }
                                            .clip(CircleShape)
                                            .background(Color(0xFF282932))
                                            .then(
                                                if (noStroke) Modifier.border(2.5.dp, BaseColors.Primary, CircleShape)
                                                else Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🚫", fontSize = 14.sp)
                                    }
                                }
                                items(COLOR_PALETTE, key = { it }) { colorHex ->
                                    val isSel = !noStroke && (style.strokeColor.toLong() and 0xFFFFFFL) == (colorHex and 0xFFFFFFL)
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .pressable { onStyle { it.copy(strokeColor = colorHex.toInt(), strokeEm = if (it.strokeEm <= 0f) 0.10f else it.strokeEm) } }
                                            .clip(CircleShape)
                                            .background(Color(colorHex))
                                            .then(if (isSel) Modifier.border(3.dp, BaseColors.Primary, CircleShape) else Modifier)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Толщина", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = style.strokeEm,
                                    onValueChange = { em -> onStyle { it.copy(strokeEm = em) } },
                                    valueRange = 0f..0.25f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = BaseColors.Primary, activeTrackColor = BaseColors.Primary)
                                )
                                Text("${(style.strokeEm * 100).toInt()}", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                            }
                        }
                        StyleSubTab.GLOW -> {
                            Text("Цвет свечения:", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(COLOR_PALETTE, key = { it }) { colorHex ->
                                    val isSel = (style.shadowColor.toLong() and 0xFFFFFFL) == (colorHex and 0xFFFFFFL)
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .pressable { onStyle { it.copy(shadowColor = colorHex.toInt(), shadowDyEm = 0f, shadowBlurEm = 0.25f) } }
                                            .clip(CircleShape)
                                            .background(Color(colorHex))
                                            .then(if (isSel) Modifier.border(3.dp, BaseColors.Primary, CircleShape) else Modifier)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Радиус", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = style.shadowBlurEm,
                                    onValueChange = { r -> onStyle { it.copy(shadowBlurEm = r) } },
                                    valueRange = 0f..0.50f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = BaseColors.Primary, activeTrackColor = BaseColors.Primary)
                                )
                                Text("${(style.shadowBlurEm * 100).toInt()}", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                            }
                        }
                        StyleSubTab.BACKGROUND -> {
                            Text("Фоновая плашка:", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            val noBg = style.backgroundColor == 0
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                item {
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .pressable { onStyle { it.copy(backgroundColor = 0) } }
                                            .clip(CircleShape)
                                            .background(Color(0xFF282932))
                                            .then(
                                                if (noBg) Modifier.border(2.5.dp, BaseColors.Primary, CircleShape)
                                                else Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("🚫", fontSize = 14.sp)
                                    }
                                }
                                items(COLOR_PALETTE, key = { it }) { colorHex ->
                                    val isSel = !noBg && (style.backgroundColor.toLong() and 0xFFFFFFL) == (colorHex and 0xFFFFFFL)
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .pressable { onStyle { it.copy(backgroundColor = (0xCC000000L or (colorHex and 0xFFFFFFL)).toInt()) } }
                                            .clip(CircleShape)
                                            .background(Color(colorHex))
                                            .then(if (isSel) Modifier.border(3.dp, BaseColors.Primary, CircleShape) else Modifier)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Закругление", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = style.backgroundCornerEm,
                                    onValueChange = { cr -> onStyle { it.copy(backgroundCornerEm = cr) } },
                                    valueRange = 0f..0.50f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = BaseColors.Primary, activeTrackColor = BaseColors.Primary)
                                )
                                Text("${(style.backgroundCornerEm * 100).toInt()}", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                            }
                        }
                        StyleSubTab.SHADOW -> {
                            Text("Цвет тени:", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(COLOR_PALETTE, key = { it }) { colorHex ->
                                    val isSel = (style.shadowColor.toLong() and 0xFFFFFFL) == (colorHex and 0xFFFFFFL)
                                    Box(
                                        Modifier
                                            .size(32.dp)
                                            .pressable { onStyle { it.copy(shadowColor = (0xB3000000L or (colorHex and 0xFFFFFFL)).toInt(), shadowDyEm = 0.08f) } }
                                            .clip(CircleShape)
                                            .background(Color(colorHex))
                                            .then(if (isSel) Modifier.border(3.dp, BaseColors.Primary, CircleShape) else Modifier)
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("Смещение", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.width(80.dp))
                                Slider(
                                    value = style.shadowDyEm,
                                    onValueChange = { dy -> onStyle { it.copy(shadowDyEm = dy) } },
                                    valueRange = 0f..0.20f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = BaseColors.Primary, activeTrackColor = BaseColors.Primary)
                                )
                                Text("${(style.shadowDyEm * 100).toInt()}", color = Color.White, fontSize = 12.sp, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Диалог быстрой правки текста конкретной реплики субтитров. */
@Composable
private fun EditDialog(
    item: CaptionItem,
    onClose: () -> Unit,
    onUpdateText: (String, String) -> Unit,
    onUpdateTiming: (String, Long, Long) -> Unit,
    onDelete: (String) -> Unit,
) {
    var text by remember(item.id) { mutableStateOf(item.text) }
    var start by remember(item.id) { mutableStateOf((item.startMs / 1000f).toString()) }
    var end by remember(item.id) { mutableStateOf((item.endMs / 1000f).toString()) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = Color(0xFF1E1F24),
        title = { Text("Редактировать реплику", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Текст субтитров") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = start, onValueChange = { start = it },
                        label = { Text("Старт (с)") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = end, onValueChange = { end = it },
                        label = { Text("Конец (с)") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onUpdateText(item.id, text)
                val sMs = (start.toFloatOrNull() ?: 0f) * 1000f
                val eMs = (end.toFloatOrNull() ?: (sMs / 1000f + 1f)) * 1000f
                if (eMs > sMs) onUpdateTiming(item.id, sMs.toLong(), eMs.toLong())
                onClose()
            }) { Text("Сохранить", color = BaseColors.Primary, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onDelete(item.id); onClose() }) { Text("Удалить", color = Color(0xFFFF2D55)) }
                TextButton(onClick = onClose) { Text("Отмена", color = Color.White.copy(alpha = .7f)) }
            }
        },
    )
}
