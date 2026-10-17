package com.base.editor.text

import android.graphics.Typeface
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.base.editor.BaseApplication
import com.base.editor.R

/**
 * Библиотека шрифтов BASE: встроенные гарнитуры и коллекция Google Fonts из assets.
 * Гарантирует корректную работу в превью, холсте и видеоэкспорте.
 */
object TextFonts {
    data class Entry(
        val id: String,
        val label: String,
        val category: String = "Без засечек",
        val assetPath: String? = null,
        val resId: Int? = null,
        val weight: Int = 400,
    ) {
        val family: FontFamily by lazy {
            try {
                val assets = BaseApplication.instance?.assets
                if (resId != null) {
                    FontFamily(Font(resId, FontWeight(weight)))
                } else if (assetPath != null && assets != null) {
                    val tf = Typeface.createFromAsset(assets, assetPath)
                    FontFamily(androidx.compose.ui.text.font.Typeface(tf))
                } else {
                    FontFamily.Default
                }
            } catch (e: Throwable) {
                FontFamily.Default
            }
        }
    }

    val categories = listOf("В тренде", "Без засечек", "Дисплей", "Рукописные", "С засечками")
    val CATEGORIES = categories

    fun forCategory(cat: String): List<Entry> =
        all.filter { it.category == cat }

    val all: List<Entry> by lazy {
        listOf(
            // В тренде / Популярные
            Entry("MONTSERRAT", "Montserrat", "В тренде", resId = R.font.montserrat, weight = 700),
            Entry("RUBIK", "Rubik", "В тренде", resId = R.font.rubik, weight = 700),
            Entry("OSWALD", "Oswald", "В тренде", resId = R.font.oswald, weight = 600),
            Entry("GOOGLE_SANS", "Google Sans", "В тренде", assetPath = "fonts/google_sans.ttf"),
            Entry("COMFORTAA", "Comfortaa", "В тренде", assetPath = "fonts/comfortaa.ttf"),
            Entry("LUCKIEST_GUY", "Luckiest Guy", "В тренде", assetPath = "fonts/luckiest_guy.ttf"),
            Entry("INTER", "Inter", "В тренде", resId = R.font.inter, weight = 800),
            Entry("FREDOKA", "Fredoka", "В тренде", assetPath = "fonts/fredoka.ttf"),
            Entry("RUSSO", "Russo One", "В тренде", resId = R.font.russo_one, weight = 400),
            Entry("PACIFICO", "Pacifico", "В тренде", resId = R.font.pacifico, weight = 400),
            Entry("CAVEAT", "Caveat", "В тренде", resId = R.font.caveat, weight = 700),

            // Без засечек (Sans Serif)
            Entry("ADVENT_PRO", "Advent Pro", "Без засечек", assetPath = "fonts/advent_pro.ttf"),
            Entry("GEOLOGICA", "Geologica", "Без засечек", assetPath = "fonts/geologica.ttf"),
            Entry("GRUPPO", "Gruppo", "Без засечек", assetPath = "fonts/gruppo.ttf"),
            Entry("HUBOT_SANS", "Hubot Sans", "Без засечек", assetPath = "fonts/hubot_sans.ttf"),
            Entry("JOSEFIN_SANS", "Josefin Sans", "Без засечек", assetPath = "fonts/josefin_sans.ttf"),
            Entry("JOST", "Jost", "Без засечек", assetPath = "fonts/jost.ttf"),
            Entry("LITER", "Liter", "Без засечек", assetPath = "fonts/liter.ttf"),
            Entry("QUESTRIAL", "Questrial", "Без засечек", assetPath = "fonts/questrial.ttf"),
            Entry("RALEWAY", "Raleway", "Без засечек", assetPath = "fonts/raleway.ttf"),
            Entry("SOFIA_SANS", "Sofia Sans", "Без засечек", assetPath = "fonts/sofia_sans_extra_condensed.ttf"),
            Entry("FIRA_COND", "Fira Condensed", "Без засечек", resId = R.font.fira_condensed, weight = 800),
            Entry("MONTSERRAT_BLACK", "Montserrat Black", "Без засечек", resId = R.font.montserrat, weight = 900),

            // Дисплей / Акцентные
            Entry("DELA_GOTHIC", "Dela Gothic", "Дисплей", assetPath = "fonts/dela_gothic_one.ttf"),
            Entry("PAYTONE_ONE", "Paytone One", "Дисплей", assetPath = "fonts/paytone_one.ttf"),
            Entry("PIXELIFY_SANS", "Pixelify Sans", "Дисплей", assetPath = "fonts/pixelify_sans.ttf"),
            Entry("PRESS_START", "Press Start 2P", "Дисплей", assetPath = "fonts/press_start_2p.ttf"),
            Entry("DOTO", "Doto", "Дисплей", assetPath = "fonts/doto.ttf"),
            Entry("TEKTUR", "Tektur", "Дисплей", assetPath = "fonts/tektur.ttf"),
            Entry("SPECIAL_ELITE", "Special Elite", "Дисплей", assetPath = "fonts/special_elite.ttf"),
            Entry("CHICLE", "Chicle", "Дисплей", assetPath = "fonts/chicle.ttf"),
            Entry("BBH_BARTLE", "BBH Bartle", "Дисплей", assetPath = "fonts/bbh_bartle.ttf"),
            Entry("COSSETTE_TITRE", "Cossette Titre", "Дисплей", assetPath = "fonts/cossette_titre.ttf"),
            Entry("DIPLOMATA_SC", "Diplomata SC", "Дисплей", assetPath = "fonts/diplomata_sc.ttf"),
            Entry("FONTDINER_SWANKY", "Fontdiner Swanky", "Дисплей", assetPath = "fonts/fontdiner_swanky.ttf"),
            Entry("GEOSTAR_FILL", "Geostar Fill", "Дисплей", assetPath = "fonts/geostar_fill.ttf"),
            Entry("GRAVITAS_ONE", "Gravitas One", "Дисплей", assetPath = "fonts/gravitas_one.ttf"),
            Entry("HENNY_PENNY", "Henny Penny", "Дисплей", assetPath = "fonts/henny_penny.ttf"),
            Entry("MONTSERRAT_ALT", "Montserrat Alt", "Дисплей", assetPath = "fonts/montserrat_alternates.ttf"),
            Entry("MOO_LAH_LAH", "Moo Lah Lah", "Дисплей", assetPath = "fonts/moo_lah_lah.ttf"),

            // Рукописные / Скрипты
            Entry("GREAT_VIBES", "Great Vibes", "Рукописные", assetPath = "fonts/great_vibes.ttf"),
            Entry("NEUCHA", "Neucha", "Рукописные", assetPath = "fonts/neucha.ttf"),
            Entry("BALLET", "Ballet", "Рукописные", assetPath = "fonts/ballet.ttf"),
            Entry("COMFORTER_BRUSH", "Comforter Brush", "Рукописные", assetPath = "fonts/comforter_brush.ttf"),
            Entry("DR_SUGIYAMA", "Dr Sugiyama", "Рукописные", assetPath = "fonts/dr_sugiyama.ttf"),
            Entry("EDU_HAND", "Edu Hand", "Рукописные", assetPath = "fonts/edu_qld_hand.ttf"),
            Entry("HERR_VON_MUELLERHOFF", "Herr Mueller", "Рукописные", assetPath = "fonts/herr_von_muellerhoff.ttf"),
            Entry("PATTAYA", "Pattaya", "Рукописные", assetPath = "fonts/pattaya.ttf"),
            Entry("PLAYPEN_SANS", "Playpen Sans", "Рукописные", assetPath = "fonts/playpen_sans.ttf"),
            Entry("PLAYWRITE", "Playwrite", "Рукописные", assetPath = "fonts/playwrite_be_vlg.ttf"),
            Entry("SASSY_FRASS", "Sassy Frass", "Рукописные", assetPath = "fonts/sassy_frass.ttf"),
            Entry("THE_NAUTIGAL", "The Nautigal", "Рукописные", assetPath = "fonts/the_nautigal.ttf"),
            Entry("WAITING_SUNRISE", "Waiting Sunrise", "Рукописные", assetPath = "fonts/waiting_for_the_sunrise.ttf"),
            Entry("ZEYADA", "Zeyada", "Рукописные", assetPath = "fonts/zeyada.ttf"),

            // С засечками (Serif)
            Entry("BODONI_MODA", "Bodoni Moda", "С засечками", assetPath = "fonts/bodoni_moda.ttf"),
            Entry("ALICE", "Alice", "С засечками", assetPath = "fonts/alice.ttf"),
            Entry("EB_GARAMOND", "EB Garamond", "С засечками", assetPath = "fonts/eb_garamond.ttf"),
            Entry("YESEVA_ONE", "Yeseva One", "С засечками", assetPath = "fonts/yeseva_one.ttf"),
            Entry("NEWSREADER", "Newsreader", "С засечками", assetPath = "fonts/newsreader.ttf"),
            Entry("CAACUPE_ONE", "Caacupe One", "С засечками", assetPath = "fonts/caacupe_one.ttf"),
        )
    }

    private val byIdMap: Map<String, Entry> by lazy {
        all.associateBy { it.id.uppercase() }
    }

    fun family(id: String): FontFamily =
        byIdMap[id.uppercase()]?.family ?: all.first().family
}
