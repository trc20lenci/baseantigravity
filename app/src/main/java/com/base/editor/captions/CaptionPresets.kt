package com.base.editor.captions

/**
 * Новые стили субтитров BASE: динамическая подсветка звучащего слова (Караоке / Pop / Highlight),
 * контрастная обводка и поддержка расширенной библиотеки шрифтов.
 */
object CaptionPresets {
    private fun c(v: Long) = v.toInt()
    const val NEON_YELLOW = 0xFFFFE600L
    const val NEON_GREEN = 0xFF00FF66L

    private val base = CaptionStyle(
        id = "neon_yellow",
        name = "Неон Жёлтый",
        font = CaptionFont.MONTSERRAT,
        fontId = "MONTSERRAT",
        fontWeight = 900,
        uppercase = true,
        sizeFrac = 0.0625f,
        textColor = c(0xFFFFFFFF),
        activeColor = c(NEON_YELLOW),
        strokeColor = c(0xFF000000),
        strokeEm = 0.10f,
        shadowColor = c(0x66000000),
        shadowBlurEm = 0f,
        shadowDyEm = 0f,
        animation = WordAnimation.POP,
        activeScale = 1.15f,
        maxWidthFrac = 0.90f,
        positionY = 0.78f,
    )

    val all: List<CaptionStyle> = listOf(
        base.copy(id = "neon_yellow", name = "Неон Жёлтый", font = CaptionFont.MONTSERRAT, activeColor = c(NEON_YELLOW), animation = WordAnimation.POP),
        base.copy(id = "neon_green", name = "Неон Зелёный", font = CaptionFont.RUBIK, activeColor = c(NEON_GREEN), animation = WordAnimation.POP),
        base.copy(id = "fire_red", name = "Огненный", font = CaptionFont.RUSSO, activeColor = c(0xFFFF2D55), animation = WordAnimation.POP, activeScale = 1.18f),
        base.copy(id = "karaoke_cyan", name = "Караоке Циан", font = CaptionFont.OSWALD, activeColor = c(0xFF00E5FF), strokeEm = 0.09f, animation = WordAnimation.KARAOKE),
        base.copy(id = "gold_glow", name = "Золотой Поп", font = CaptionFont.MONTSERRAT, activeColor = c(0xFFFFB300), strokeColor = c(0xFF1F1F1F), animation = WordAnimation.POP),
        base.copy(id = "purple_vibe", name = "Фиолетовый", font = CaptionFont.RUBIK, activeColor = c(0xFFB388FF), strokeColor = c(0xFF16032B), strokeEm = 0.11f, animation = WordAnimation.POP),
        base.copy(id = "dark_badge", name = "Тёмная плашка", font = CaptionFont.OSWALD, activeColor = c(NEON_YELLOW), strokeEm = 0f, backgroundColor = c(0xCC000000), animation = WordAnimation.HIGHLIGHT, activeScale = 1.05f),
        base.copy(id = "white_minimal", name = "Минимал", font = CaptionFont.PACIFICO, activeColor = c(0xFF00E5FF), strokeEm = 0f, shadowColor = c(0x99000000), shadowDyEm = 0.08f, animation = WordAnimation.HIGHLIGHT),
    )

    val default: CaptionStyle = all.first()
    fun byId(id: String): CaptionStyle? = all.firstOrNull { it.id == id }
}
