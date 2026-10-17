package com.base.editor.ui.editor

import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.base.editor.text.TextClip
import com.base.editor.text.TextFonts
import com.base.editor.text.TextStyles
import com.base.editor.ui.theme.BaseColors
import com.base.editor.ui.theme.Lucide
import com.base.editor.ui.theme.animatedSelectColor
import com.base.editor.ui.theme.pressable

enum class TextEditorTab { FONTS, STYLES, ANIMATION }
enum class StyleSubTab(val label: String) {
    COLOR("Цвет"),
    STROKE("Обводка"),
    GLOW("Сияние"),
    BACKGROUND("Фон"),
    SHADOW("Тени"),
}

/** Быстрая строка ввода текста (используется для редактирования реплик субтитров). */
@Composable
fun TextInputSheet(text: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.apply {
                setGravity(Gravity.BOTTOM)
                setDimAmount(0f)
                setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Color(0xFF1E1F24))
                .imePadding().navigationBarsPadding().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = onChange,
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("Введите текст") }, minLines = 1, maxLines = 3,
            )
            Text(
                "Готово",
                Modifier.pressable(onClick = onDone).padding(start = 10.dp).clip(RoundedCornerShape(10.dp)).background(BaseColors.Primary).padding(horizontal = 18.dp, vertical = 12.dp),
                color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private val COLOR_PALETTE = listOf(
    0xFF000000L, 0xFFFFFFFFL, 0xFFE0E0E0L, 0xFF888888L, 0xFF333333L,
    0xFFFFE600L, 0xFFFFB300L, 0xFFFF9800L, 0xFFFF2D55L, 0xFFEC4899L,
    0xFF7C3AEDL, 0xFF3F51B5L, 0xFF2196F3L, 0xFF00E5FFL, 0xFF00FF66L, 0xFF4CAF50L,
)

/**
 * Полноценная вкладка редактирования текста (Шрифты + Стили) по дизайн-макетам.
 * Вкладка «Стили» содержит готовые карточки стилей с реальным рендером и 5 подвкладок:
 * [Цвет | Обводка | Сияние | Фон | Тени].
 */
@Composable
fun ModernTextEditorSheet(
    clip: TextClip,
    initialTab: TextEditorTab = TextEditorTab.FONTS,
    onChange: ((TextClip) -> TextClip) -> Unit,
    onDone: () -> Unit,
) {
    var activeTab by remember(initialTab) { mutableStateOf(initialTab) }
    var selectedCategory by remember { mutableStateOf("В тренде") }
    var styleSubTab by remember { mutableStateOf(StyleSubTab.COLOR) }

    Dialog(
        onDismissRequest = onDone,
        properties = DialogProperties(
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.apply {
                setGravity(Gravity.BOTTOM)
                setDimAmount(0.25f)
                setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
                setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }

        val focus = remember { FocusRequester() }

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(Color(0xFF1E1F24))
                .imePadding()
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        ) {
            // Drag handle
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(36.dp, 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.25f))
                )
            }

            // Поле ввода текста с галочкой справа
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF282932))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = clip.text,
                        onValueChange = { newText -> onChange { it.copy(text = newText) } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focus),
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            fontFamily = TextFonts.family(clip.fontId)
                        ),
                        cursorBrush = SolidColor(BaseColors.Primary),
                        maxLines = 3,
                        decorationBox = { innerTextField ->
                            if (clip.text.isEmpty()) {
                                Text(
                                    "Введите текст...",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 15.sp,
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(Modifier.width(10.dp))

                Box(
                    Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BaseColors.Primary)
                        .pressable(onClick = onDone),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Lucide.Check,
                        contentDescription = "Готово",
                        tint = Color.Black,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // Переключатель вкладок (Шрифты / Стили / Анимация)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TabButton(
                    title = "Шрифты",
                    active = activeTab == TextEditorTab.FONTS,
                    onClick = { activeTab = TextEditorTab.FONTS },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    title = "Стили",
                    active = activeTab == TextEditorTab.STYLES,
                    onClick = { activeTab = TextEditorTab.STYLES },
                    modifier = Modifier.weight(1f)
                )
                TabButton(
                    title = "Анимация",
                    active = activeTab == TextEditorTab.ANIMATION,
                    onClick = { activeTab = TextEditorTab.ANIMATION },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(Modifier.height(8.dp))

            when (activeTab) {
                TextEditorTab.FONTS -> {
                    // Категории шрифтов
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(TextFonts.CATEGORIES, key = { it }) { cat ->
                            val isSelected = cat == selectedCategory
                            Box(
                                modifier = Modifier
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
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                    ) {
                        items(fontsForCategory, key = { it.id }) { f ->
                            val isSelected = clip.fontId == f.id
                            Box(
                                modifier = Modifier
                                    .height(48.dp)
                                    .pressable { onChange { it.copy(fontId = f.id) } }
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xFF2A2B32))
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
                                    fontSize = 14.sp,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }

                TextEditorTab.STYLES -> {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .height(310.dp)
                            .padding(horizontal = 14.dp)
                    ) {
                        // 1. Готовые стили (Aa карточки с РЕАЛЬНЫМ стилем)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(vertical = 4.dp),
                        ) {
                            items(TextStyles.all, key = { it.id }) { p ->
                                val isSelected = clip.styleId == p.id
                                val sample = remember(p.id) { p.apply(clip) }
                                Box(
                                    Modifier
                                        .size(54.dp)
                                        .pressable { onChange { p.apply(it) } }
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFF24252B))
                                        .then(
                                            if (isSelected) Modifier.border(2.dp, BaseColors.Primary, RoundedCornerShape(12.dp))
                                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (p.id == "plain") {
                                        Text("🚫", fontSize = 18.sp)
                                    } else {
                                        if (sample.hasBackground) {
                                            Box(
                                                Modifier
                                                    .size(38.dp, 28.dp)
                                                    .clip(RoundedCornerShape(sample.bgCornerRadius.dp))
                                                    .background(Color(sample.backgroundColor))
                                            )
                                        }
                                        Text(
                                            text = "Aa",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = TextFonts.family(sample.fontId),
                                            color = Color(sample.textColor),
                                            style = TextStyle(
                                                shadow = when {
                                                    sample.glowColor != 0L -> Shadow(Color(sample.glowColor), androidx.compose.ui.geometry.Offset.Zero, 10f)
                                                    sample.shadowColor != 0L -> Shadow(Color(sample.shadowColor), androidx.compose.ui.geometry.Offset(0f, 3f), 4f)
                                                    else -> null
                                                }
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 2. Подвкладки стилей: [Цвет | Обводка | Сияние | Фон | Тени]
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            StyleSubTab.entries.forEach { sub ->
                                val active = styleSubTab == sub
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(if (active) BaseColors.Primary else Color(0xFF2A2B32))
                                        .pressable { styleSubTab = sub }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = sub.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = if (active) Color.Black else Color.White
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // 3. Содержимое активной подвкладки
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (styleSubTab) {
                                StyleSubTab.COLOR -> {
                                    Column {
                                        ColorPaletteRow(
                                            selectedRgb = clip.textColor and 0x00FFFFFFL,
                                            onPick = { rgb ->
                                                onChange { c ->
                                                    val alpha = (c.textColor ushr 24) and 0xFFL
                                                    c.copy(textColor = (alpha shl 24) or (rgb and 0x00FFFFFFL))
                                                }
                                            }
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        val alphaFloat = ((clip.textColor ushr 24) and 0xFF).toFloat() / 255f
                                        LabelSlider(
                                            label = "Затемнение",
                                            value = alphaFloat.coerceIn(0.05f, 1f),
                                            valueRange = 0.05f..1f,
                                            display = "${(alphaFloat * 100).toInt()}%",
                                            onValueChange = { newAlpha ->
                                                onChange { c ->
                                                    val aInt = (newAlpha * 255).toInt().coerceIn(0, 255)
                                                    c.copy(textColor = (aInt.toLong() shl 24) or (c.textColor and 0x00FFFFFFL))
                                                }
                                            }
                                        )
                                    }
                                }

                                StyleSubTab.STROKE -> {
                                    Column {
                                        val hasNoStroke = clip.strokeColor == 0L || clip.strokeWidth <= 0f
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MiniOptionButton("Без обводки", hasNoStroke) {
                                                onChange { it.copy(strokeColor = 0L, strokeWidth = 0f) }
                                            }
                                            MiniOptionButton("С обводкой", !hasNoStroke) {
                                                onChange { it.copy(strokeColor = if (it.strokeColor == 0L) 0xFF000000L else it.strokeColor, strokeWidth = if (it.strokeWidth <= 0f) 0.05f else it.strokeWidth) }
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        ColorPaletteRow(
                                            selectedRgb = clip.strokeColor and 0x00FFFFFFL,
                                            allowNone = true,
                                            isNoneSelected = hasNoStroke,
                                            onPickNone = { onChange { it.copy(strokeColor = 0L, strokeWidth = 0f) } },
                                            onPick = { rgb ->
                                                onChange { it.copy(strokeColor = 0xFF000000L or (rgb and 0x00FFFFFFL), strokeWidth = if (it.strokeWidth <= 0f) 0.05f else it.strokeWidth) }
                                            }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        LabelSlider(
                                            label = "Толщина",
                                            value = clip.strokeWidth.coerceIn(0.01f, 0.45f),
                                            valueRange = 0.01f..0.45f,
                                            display = "${(clip.strokeWidth * 100).toInt()}",
                                            onValueChange = { w -> onChange { it.copy(strokeWidth = w) } }
                                        )
                                    }
                                }

                                StyleSubTab.GLOW -> {
                                    Column {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MiniOptionButton("Без сияния", clip.glowColor == 0L) {
                                                onChange { it.copy(glowColor = 0L, glowRadius = 0f) }
                                            }
                                            MiniOptionButton("Сияние", clip.glowColor != 0L) {
                                                onChange { it.copy(glowColor = if (it.glowColor == 0L) it.textColor else it.glowColor, glowRadius = if (it.glowRadius == 0f) 0.25f else it.glowRadius) }
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        ColorPaletteRow(
                                            selectedRgb = clip.glowColor and 0x00FFFFFFL,
                                            onPick = { rgb ->
                                                onChange { it.copy(glowColor = 0xFF000000L or (rgb and 0x00FFFFFFL), glowRadius = if (it.glowRadius == 0f) 0.25f else it.glowRadius) }
                                            }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        LabelSlider(
                                            label = "Радиус",
                                            value = clip.glowRadius.coerceIn(0f, 0.50f),
                                            valueRange = 0f..0.50f,
                                            display = "${(clip.glowRadius * 100).toInt()}",
                                            onValueChange = { r -> onChange { it.copy(glowRadius = r) } }
                                        )
                                    }
                                }

                                StyleSubTab.BACKGROUND -> {
                                    Column {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MiniOptionButton("Без фона", !clip.hasBackground) {
                                                onChange { it.copy(backgroundColor = 0L) }
                                            }
                                            MiniOptionButton("С фоном", clip.hasBackground) {
                                                onChange { it.copy(backgroundColor = if (it.hasBackground) it.backgroundColor else 0xCC000000L) }
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        ColorPaletteRow(
                                            selectedRgb = clip.backgroundColor and 0x00FFFFFFL,
                                            onPick = { rgb ->
                                                onChange { c ->
                                                    val alpha = if (c.hasBackground) (c.backgroundColor ushr 24) and 0xFFL else 0xCCL
                                                    c.copy(backgroundColor = (alpha shl 24) or (rgb and 0x00FFFFFFL))
                                                }
                                            }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        LabelSlider(
                                            label = "Закругление",
                                            value = clip.bgCornerRadius.coerceIn(0f, 24f),
                                            valueRange = 0f..24f,
                                            display = "${clip.bgCornerRadius.toInt()} dp",
                                            onValueChange = { r -> onChange { it.copy(bgCornerRadius = r) } }
                                        )
                                    }
                                }

                                StyleSubTab.SHADOW -> {
                                    Column {
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            MiniOptionButton("Без тени", clip.shadowColor == 0L) {
                                                onChange { it.copy(shadowColor = 0L, shadowBlur = 0f, shadowDy = 0f) }
                                            }
                                            MiniOptionButton("С тенью", clip.shadowColor != 0L) {
                                                onChange { it.copy(shadowColor = if (it.shadowColor == 0L) 0xB3000000L else it.shadowColor, shadowBlur = if (it.shadowBlur == 0f) 0.20f else it.shadowBlur, shadowDy = if (it.shadowDy == 0f) 0.08f else it.shadowDy) }
                                            }
                                        }
                                        Spacer(Modifier.height(6.dp))
                                        ColorPaletteRow(
                                            selectedRgb = clip.shadowColor and 0x00FFFFFFL,
                                            onPick = { rgb ->
                                                onChange { it.copy(shadowColor = 0xB3000000L or (rgb and 0x00FFFFFFL), shadowBlur = if (it.shadowBlur == 0f) 0.20f else it.shadowBlur, shadowDy = if (it.shadowDy == 0f) 0.08f else it.shadowDy) }
                                            }
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        LabelSlider(
                                            label = "Смещение",
                                            value = clip.shadowDy.coerceIn(0f, 0.25f),
                                            valueRange = 0f..0.25f,
                                            display = "${(clip.shadowDy * 100).toInt()}",
                                            onValueChange = { dy -> onChange { it.copy(shadowDy = dy) } }
                                        )
                                    }
                                }
                            }
                        }

                        // 4. Слайдер "Размер" (всегда внизу вкладки стилей)
                        LabelSlider(
                            label = "Размер",
                            value = clip.fontSizeSp.coerceIn(12f, 72f),
                            valueRange = 12f..72f,
                            display = "${clip.fontSizeSp.toInt()}",
                            onValueChange = { newSize -> onChange { it.copy(fontSizeSp = newSize) } }
                        )
                    }
                }
                TextEditorTab.ANIMATION -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                    ) {
                        items(com.base.editor.text.TextAnimation.entries, key = { it.id }) { a ->
                            val isSelected = clip.animId == a.id
                            LiveAnimationCard(
                                animation = a,
                                selected = isSelected,
                                onClick = { onChange { it.copy(animId = a.id) } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniOptionButton(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) BaseColors.Primary else Color(0xFF282932))
            .pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) Color.Black else Color.White
        )
    }
}

@Composable
private fun ColorPaletteRow(
    selectedRgb: Long,
    allowNone: Boolean = false,
    isNoneSelected: Boolean = false,
    onPickNone: () -> Unit = {},
    onPick: (Long) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (allowNone) {
            item {
                Box(
                    Modifier
                        .size(32.dp)
                        .pressable { onPickNone() }
                        .clip(CircleShape)
                        .background(Color(0xFF282932))
                        .then(
                            if (isNoneSelected) Modifier.border(2.5.dp, BaseColors.Primary, CircleShape)
                            else Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🚫", fontSize = 14.sp)
                }
            }
        }
        items(COLOR_PALETTE, key = { it }) { colorHex ->
            val candidateRgb = (colorHex and 0x00FFFFFFL)
            val isSel = !isNoneSelected && selectedRgb == candidateRgb
            Box(
                Modifier
                    .size(32.dp)
                    .pressable { onPick(colorHex) }
                    .clip(CircleShape)
                    .background(Color(colorHex))
                    .then(
                        if (isSel) Modifier.border(3.dp, BaseColors.Primary, CircleShape)
                        else Modifier.border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    )
            )
        }
    }
}

@Composable
private fun LabelSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    display: String,
    onValueChange: (Float) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.width(90.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = BaseColors.Primary,
                activeTrackColor = BaseColors.Primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f),
            ),
        )
        Text(display, color = Color.White, fontSize = 12.sp, modifier = Modifier.width(42.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun TabButton(
    title: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(38.dp)
            .pressable(onClick = onClick)
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Color(0xFF35363F) else Color(0xFF24252B)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            color = if (active) Color.White else Color.White.copy(alpha = 0.6f),
        )
    }
}

/**
 * Панель инструментов текстового слоя в слоте под таймлайном.
 * Добавлена кнопка "Копия".
 * В разделе «Анимация» отображаются интерактивные карточки с живым анимированным предпросмотром.
 */
@Composable
fun TextToolbar(
    clip: TextClip,
    sub: TextSub?,
    templates: List<com.base.editor.pag.PagTemplateStore.Template>,
    onBack: () -> Unit,
    onSub: (TextSub?) -> Unit,
    onEditText: () -> Unit,
    onSplit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onChange: ((TextClip) -> TextClip) -> Unit,
    onStyle: (com.base.editor.text.TextStyles.Preset) -> Unit,
    onAnimation: (com.base.editor.text.TextAnimation) -> Unit,
    onImportPag: (android.net.Uri) -> Unit,
) {
    androidx.compose.animation.Crossfade(sub, label = "textSub") { cur ->
        if (cur == null) {
            Row(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelAction("Назад", Lucide.ChevronLeft, onClick = onBack)
                PanelAction("Текст", Lucide.Pencil, onClick = onEditText)
                PanelAction("Разделить", Lucide.SquareSplitHorizontal, onClick = onSplit)
                PanelAction("Копия", Lucide.Copy, onClick = onDuplicate)
                PanelAction("Шрифты", Lucide.CaseSensitive) { onSub(TextSub.FONTS) }
                PanelAction("Стили", Lucide.Paintbrush) { onSub(TextSub.STYLES) }
                PanelAction("Анимация", Lucide.Sparkles) { onSub(TextSub.ANIMATION) }
                PanelAction("Удалить", Lucide.Trash2, onClick = onDelete)
            }
        } else {
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PanelAction("Назад", Lucide.ChevronLeft) { onSub(null) }
                LazyRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (cur) {
                        TextSub.FONTS -> items(TextFonts.all, key = { it.id }) { f ->
                            Chip(clip.fontId == f.id, { onChange { it.copy(fontId = f.id) } }) {
                                Text(f.label, fontSize = 14.sp, fontFamily = f.family, color = chipText(clip.fontId == f.id))
                            }
                        }
                        TextSub.STYLES -> items(TextStyles.all, key = { it.id }) { p ->
                            val on = clip.styleId == p.id
                            Chip(on, { onStyle(p) }) {
                                Text(p.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = chipText(on))
                            }
                        }
                        TextSub.ANIMATION -> {
                            // Карточки анимаций с ЖИВЫМ анимированным предпросмотром (как гифка)
                            items(com.base.editor.text.TextAnimation.entries.toList(), key = { it.id }) { a ->
                                val on = clip.animId == a.id
                                LiveAnimationCard(
                                    animation = a,
                                    selected = on,
                                    onClick = { onAnimation(a) }
                                )
                            }
                            items(templates, key = { it.ref }) { t ->
                                Chip(clip.pagTemplate == t.ref, {
                                    onChange { it.copy(pagTemplate = if (clip.pagTemplate == t.ref) null else t.ref) }
                                }) { Text(t.title, fontSize = 13.sp, color = chipText(clip.pagTemplate == t.ref)) }
                            }
                            item {
                                val picker = androidx.activity.compose.rememberLauncherForActivityResult(
                                    androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
                                ) { uri -> if (uri != null) onImportPag(uri) }
                                Chip(false, { picker.launch(arrayOf("*/*")) }) { Text("+ .pag", fontSize = 13.sp, color = Color.White) }
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * Карточка анимации с зацикленной визуальной анимацией букв (эффект гифки).
 */
@Composable
private fun LiveAnimationCard(
    animation: com.base.editor.text.TextAnimation,
    selected: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "animCard")
    val animProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress"
    )

    Column(
        Modifier
            .pressable(onClick = onClick)
            .width(68.dp)
            .height(58.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF22232A))
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) BaseColors.Primary else Color.White.copy(alpha = 0.1f),
                RoundedCornerShape(10.dp)
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier
                .size(36.dp, 26.dp),
            contentAlignment = Alignment.Center
        ) {
            when (animation) {
                com.base.editor.text.TextAnimation.NONE -> {
                    Text("Aa", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                com.base.editor.text.TextAnimation.FADE -> {
                    val alpha = if (animProgress < 0.7f) animProgress / 0.7f else 1f
                    Text("Aa", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.alpha(alpha))
                }
                com.base.editor.text.TextAnimation.POP -> {
                    val scale = if (animProgress < 0.6f) 0.3f + (animProgress / 0.6f) * 0.9f else 1f
                    Text("Aa", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.scale(scale.coerceIn(0.3f, 1.3f)))
                }
                com.base.editor.text.TextAnimation.SLIDE -> {
                    val dx = if (animProgress < 0.6f) (-(1f - (animProgress / 0.6f)) * 20f).dp else 0.dp
                    Text("Aa", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.offset(x = dx))
                }
                com.base.editor.text.TextAnimation.TYPEWRITER -> {
                    val chars = if (animProgress < 0.25f) "A" else if (animProgress < 0.5f) "Aa" else "Aa!"
                    Text(chars, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Text(
            text = animation.label,
            fontSize = 9.sp,
            color = if (selected) BaseColors.Primary else Color.White.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

private fun chipText(on: Boolean) = if (on) Color.Black else Color.White

@Composable
private fun Chip(on: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .pressable(onClick = onClick)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(animatedSelectColor(on))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun PanelAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Column(
        Modifier
            .pressable(onClick = onClick)
            .width(62.dp)
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
    }
}
