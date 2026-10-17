package com.base.editor.core

/** Фон холста — то, что видно на полях вокруг кадра (когда клип отодвинут, уменьшен или не совпадает по пропорциям). */
enum class CanvasBg(val id: String, val label: String, val argb: Int?) {
    BLACK("black", "Чёрный", 0xFF000000.toInt()),
    BLUR("blur", "Размытие", null),               // исходное видео, растянутое и размытое
    WHITE("white", "Белый", 0xFFFFFFFF.toInt()),
    GREY_LIGHT("grey_light", "Светло-серый", 0xFFE0E0E0.toInt()),
    GREY_MID("grey_mid", "Серый", 0xFF888888.toInt()),
    GREY_DARK("grey_dark", "Тёмно-серый", 0xFF333333.toInt()),
    YELLOW("yellow", "Жёлтый", 0xFFFFE600.toInt()),
    AMBER("amber", "Янтарный", 0xFFFFB300.toInt()),
    ORANGE("orange", "Оранжевый", 0xFFFF9800.toInt()),
    RED("red", "Красный", 0xFFFF2D55.toInt()),
    PINK("pink", "Розовый", 0xFFEC4899.toInt()),
    PURPLE("purple", "Фиолетовый", 0xFF7C3AED.toInt()),
    INDIGO("indigo", "Индиго", 0xFF3F51B5.toInt()),
    BLUE("blue", "Синий", 0xFF2196F3.toInt()),
    CYAN("cyan", "Голубой", 0xFF00E5FF.toInt()),
    TEAL("teal", "Бирюзовый", 0xFF00CCDD.toInt()),
    GREEN("green", "Зелёный", 0xFF00FF66.toInt()),
    GREEN_DARK("green_dark", "Тёмно-зелёный", 0xFF4CAF50.toInt()),
    BROWN("brown", "Коричневый", 0xFF795548.toInt()),
    IMAGE("image", "Изображение", null);

    companion object {
        fun of(id: String?) = entries.firstOrNull { it.id == id } ?: BLACK
        fun fromColor(colorInt: Int): CanvasBg = entries.firstOrNull { it.argb == colorInt } ?: BLACK
    }
}
