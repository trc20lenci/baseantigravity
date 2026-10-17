package com.base.editor.text

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Готовые стили текста (карусель в нижней панели). Применяются поверх шрифта/позиции слоя. */
object TextStyles {
    class Preset(val id: String, val label: String, val fontId: String = "MONTSERRAT", val apply: (TextClip) -> TextClip)

    val all: List<Preset> = listOf(
        Preset("plain", "Обычный", "MONTSERRAT") { it.copy(styleId = "plain", fontId = "MONTSERRAT", textColor = 0xFFFFFFFF, backgroundColor = 0, strokeColor = 0, strokeWidth = 0f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0, glowRadius = 0f) },
        Preset("white_stroke", "Контур", "MONTSERRAT") { it.copy(styleId = "white_stroke", fontId = "MONTSERRAT", textColor = 0xFFFFFFFF, backgroundColor = 0, strokeColor = 0xFF000000, strokeWidth = 0.05f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0, glowRadius = 0f) },
        Preset("neon_yellow", "Неон Жёлтый", "MONTSERRAT") { it.copy(styleId = "neon_yellow", fontId = "MONTSERRAT", textColor = 0xFFFFE600, backgroundColor = 0, strokeColor = 0xFF000000, strokeWidth = 0.05f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0xFFFFE600, glowRadius = 0.20f) },
        Preset("neon_green", "Неон Зелёный", "MONTSERRAT") { it.copy(styleId = "neon_green", fontId = "MONTSERRAT", textColor = 0xFF00FF66, backgroundColor = 0, strokeColor = 0xFF000000, strokeWidth = 0.05f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0xFF00FF66, glowRadius = 0.20f) },
        Preset("fire_red", "Огненный", "RUSSO") { it.copy(styleId = "fire_red", fontId = "RUSSO", textColor = 0xFFFF2D55, backgroundColor = 0, strokeColor = 0xFF000000, strokeWidth = 0.05f, shadowColor = 0xFFFF2D55, shadowBlur = 0.15f, shadowDy = 0f, glowColor = 0xFFFF2D55, glowRadius = 0.20f) },
        Preset("karaoke_cyan", "Караоке Циан", "MONTSERRAT") { it.copy(styleId = "karaoke_cyan", fontId = "MONTSERRAT", textColor = 0xFF00E5FF, backgroundColor = 0, strokeColor = 0xFF000000, strokeWidth = 0.05f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0, glowRadius = 0f) },
        Preset("gold_glow", "Золотой", "OSWALD") { it.copy(styleId = "gold_glow", fontId = "OSWALD", textColor = 0xFFFFB300, backgroundColor = 0, strokeColor = 0xFF1F1F1F, strokeWidth = 0.05f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0xFFFFB300, glowRadius = 0.18f) },
        Preset("purple_vibe", "Фиолетовый", "RUBIK") { it.copy(styleId = "purple_vibe", fontId = "RUBIK", textColor = 0xFFB388FF, backgroundColor = 0, strokeColor = 0xFF16032B, strokeWidth = 0.05f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0, glowRadius = 0f) },
        Preset("badge_black", "Тёмная плашка", "MONTSERRAT") { it.copy(styleId = "badge_black", fontId = "MONTSERRAT", textColor = 0xFFFFFFFF, backgroundColor = 0xCC000000, strokeColor = 0, strokeWidth = 0f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0, glowRadius = 0f, bgCornerRadius = 12f) },
        Preset("badge_yellow", "Жёлтая плашка", "MONTSERRAT") { it.copy(styleId = "badge_yellow", fontId = "MONTSERRAT", textColor = 0xFF000000, backgroundColor = 0xFFFFE600, strokeColor = 0, strokeWidth = 0f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0, glowRadius = 0f, bgCornerRadius = 12f) },
        Preset("black_stroke", "Белый контур", "MONTSERRAT") { it.copy(styleId = "black_stroke", fontId = "MONTSERRAT", textColor = 0xFF000000, backgroundColor = 0, strokeColor = 0xFFFFFFFF, strokeWidth = 0.05f, shadowColor = 0, shadowBlur = 0f, shadowDy = 0f, glowColor = 0, glowRadius = 0f) },
        Preset("white_shadow", "Тень", "MONTSERRAT") { it.copy(styleId = "white_shadow", fontId = "MONTSERRAT", textColor = 0xFFFFFFFF, backgroundColor = 0, strokeColor = 0, strokeWidth = 0f, shadowColor = 0xB3000000, shadowBlur = 0.20f, shadowDy = 0.08f, glowColor = 0, glowRadius = 0f) },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }
}

/** Анимации появления текста. */
enum class TextAnimation(val id: String, val label: String) {
    NONE("none", "Без анимации"), FADE("fade", "Fade In"), POP("pop", "Scale Pop"), TYPEWRITER("typewriter", "Typewriter"), SLIDE("slide", "Slide In");

    companion object { fun of(id: String) = entries.firstOrNull { it.id == id } ?: NONE }
}

/** Состояние кадра анимации: прозрачность, масштаб, сдвиг по X (в долях ширины кадра), доля видимых символов. */
class AnimFrame(val alpha: Float, val scale: Float, val dx: Float, val visibleFraction: Float) {
    companion object { val FULL = AnimFrame(1f, 1f, 0f, 1f) }
}

object TextAnimator {
    const val BASE_MS = 600L

    fun durationMs(clip: TextClip): Long = when (TextAnimation.of(clip.animId)) {
        TextAnimation.NONE -> 0L
        TextAnimation.TYPEWRITER -> (clip.text.length * 55L).coerceIn(BASE_MS, 2500L)
        else -> BASE_MS
    }

    /** [localMs] — время от начала слоя. Для статичного кадра передайте [Long.MAX_VALUE]. */
    fun frame(clip: TextClip, localMs: Long): AnimFrame {
        val d = durationMs(clip)
        if (d == 0L || localMs >= d) return AnimFrame.FULL
        val p = (localMs.coerceAtLeast(0L).toFloat() / d).coerceIn(0f, 1f)
        return when (TextAnimation.of(clip.animId)) {
            TextAnimation.FADE -> AnimFrame(p, 1f, 0f, 1f)
            TextAnimation.POP -> AnimFrame(min(1f, p * 2.5f), 0.3f + 0.7f * easeOutBack(p), 0f, 1f)
            TextAnimation.TYPEWRITER -> AnimFrame(1f, 1f, 0f, p)
            TextAnimation.SLIDE -> AnimFrame(min(1f, p * 2f), 1f, -0.3f * (1f - easeOutCubic(p)), 1f)
            TextAnimation.NONE -> AnimFrame.FULL
        }
    }

    /** Фаза для кэша экспорта: пока идёт анимация, кадр меняется (шаг ≈ 33 мс), потом — константа. */
    fun phase(clip: TextClip, localMs: Long): Int {
        val d = durationMs(clip)
        return if (d == 0L || localMs >= d) -1 else (max(0L, localMs) / 33L).toInt()
    }

    private fun easeOutCubic(x: Float) = 1f - (1f - x).pow(3)
    private fun easeOutBack(x: Float): Float { val c1 = 1.70158f; val c3 = c1 + 1f; return 1f + c3 * (x - 1f).pow(3) + c1 * (x - 1f).pow(2) }
}
