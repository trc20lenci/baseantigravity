package com.base.editor.captions

import org.json.JSONArray
import org.json.JSONObject

/** Сохранение субтитров и стиля в проект. Устойчиво к отсутствующим полям (старые проекты). */
object CaptionJson {
    fun encode(items: List<CaptionItem>, style: CaptionStyle): String = JSONObject()
        .put("style", style(style))
        .put("items", JSONArray().also { arr ->
            items.forEach { c ->
                arr.put(JSONObject().put("id", c.id).put("s", c.startMs).put("e", c.endMs).put("t", c.text)
                    .put("w", JSONArray().also { wa -> c.words.forEach { w -> wa.put(JSONArray().put(w.word).put(w.startMs).put(w.endMs)) } }))
            }
        }).toString()

    fun decode(json: String?): Pair<List<CaptionItem>, CaptionStyle> {
        if (json.isNullOrBlank()) return emptyList<CaptionItem>() to CaptionPresets.default
        return runCatching {
            val o = JSONObject(json)
            val style = o.optJSONObject("style")?.let(::style) ?: CaptionPresets.default
            val arr = o.optJSONArray("items") ?: JSONArray()
            val items = (0 until arr.length()).map { i ->
                val c = arr.getJSONObject(i)
                val wa = c.optJSONArray("w") ?: JSONArray()
                CaptionItem(c.getString("id"), c.getLong("s"), c.getLong("e"), c.optString("t"),
                    (0 until wa.length()).map { j -> wa.getJSONArray(j).let { WordTimestamp(it.getString(0), it.getLong(1), it.getLong(2)) } })
            }
            items to style
        }.getOrDefault(emptyList<CaptionItem>() to CaptionPresets.default)
    }

    private fun style(s: CaptionStyle) = JSONObject()
        .put("id", s.id).put("name", s.name).put("font", s.font.name).put("fontId", s.fontId).put("weight", s.fontWeight).put("italic", s.italic)
        .put("upper", s.uppercase).put("size", s.sizeFrac.toDouble()).put("ls", s.letterSpacingEm.toDouble())
        .put("text", s.textColor).put("active", s.activeColor).put("stroke", s.strokeColor).put("strokeEm", s.strokeEm.toDouble())
        .put("bg", s.backgroundColor).put("bgPad", s.backgroundPaddingEm.toDouble()).put("bgRad", s.backgroundCornerEm.toDouble())
        .put("sh", s.shadowColor).put("shBlur", s.shadowBlurEm.toDouble()).put("shDy", s.shadowDyEm.toDouble())
        .put("anim", s.animation.name).put("scale", s.activeScale.toDouble())
        .put("maxW", s.maxWidthFrac.toDouble()).put("y", s.positionY.toDouble())

    private fun style(o: JSONObject): CaptionStyle {
        val d = CaptionPresets.default
        val legacyFont = runCatching { CaptionFont.valueOf(o.optString("font")) }.getOrDefault(d.font)
        val fId = o.optString("fontId", o.optString("font", legacyFont.name))
        return CaptionStyle(
            id = o.optString("id", d.id), name = o.optString("name", d.name),
            font = legacyFont,
            fontId = fId,
            fontWeight = o.optInt("weight", d.fontWeight), italic = o.optBoolean("italic", d.italic),
            uppercase = o.optBoolean("upper", d.uppercase), sizeFrac = o.optDouble("size", d.sizeFrac.toDouble()).toFloat(),
            letterSpacingEm = o.optDouble("ls", 0.0).toFloat(),
            textColor = o.optInt("text", d.textColor), activeColor = o.optInt("active", d.activeColor),
            strokeColor = o.optInt("stroke", d.strokeColor), strokeEm = o.optDouble("strokeEm", d.strokeEm.toDouble()).toFloat(),
            backgroundColor = o.optInt("bg", d.backgroundColor),
            backgroundPaddingEm = o.optDouble("bgPad", d.backgroundPaddingEm.toDouble()).toFloat(),
            backgroundCornerEm = o.optDouble("bgRad", d.backgroundCornerEm.toDouble()).toFloat(),
            shadowColor = o.optInt("sh", d.shadowColor), shadowBlurEm = o.optDouble("shBlur", d.shadowBlurEm.toDouble()).toFloat(),
            shadowDyEm = o.optDouble("shDy", d.shadowDyEm.toDouble()).toFloat(),
            animation = runCatching { WordAnimation.valueOf(o.optString("anim")) }.getOrDefault(d.animation),
            activeScale = o.optDouble("scale", d.activeScale.toDouble()).toFloat(),
            maxWidthFrac = o.optDouble("maxW", d.maxWidthFrac.toDouble()).toFloat(),
            positionY = o.optDouble("y", d.positionY.toDouble()).toFloat(),
        )
    }
}
