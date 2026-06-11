package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import me.xdrop.fuzzywuzzy.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.ColorPatterns

internal object ColorCleaner : ValueCleaner {
    val colorMap = mapOf(
        "red" to "#FF0000", "rel" to "#FF0000", "rad" to "#FF0000", "reo" to "#FF0000",
        "green" to "#00FF00",
        "blue" to "#0000FF", "ine" to "#0000FF", "hne" to "#0000FF", "hlue" to "#0000FF",
        "ane" to "#0000FF", "lne" to "#0000FF",
        "black" to "#000000", "white" to "#FFFFFF", "gray" to "#808080",
        "grey" to "#808080", "dark_gray" to "#A9A9A9", "yellow" to "#FFFF00",
        "cyan" to "#00FFFF", "magenta" to "#FF00FF", "purple" to "#800080",
        "orange" to "#FFA500", "brown" to "#A52A2A", "pink" to "#FFC0CB",
        "light_gray" to "#D3D3D3", "dark_blue" to "#00008B", "dark_green" to "#006400",
        "dark_red" to "#8B0000", "teal" to "#008080", "navy" to "#000080",
        "transparent" to "@android:color/transparent"
    )

    /** Selects a mapped color when its fuzzy-match score meets the acceptance threshold. */
    override fun clean(rawValue: String): String {
        if (rawValue.startsWith("#") || rawValue.startsWith("@")) return rawValue

        val normalizedValue = rawValue.lowercase()
            .replace(ColorPatterns.NON_LETTER_OR_UNDERSCORE, "")
            .replace(" ", "_")
        val exactColor = colorMap[normalizedValue]
        if (exactColor != null) return exactColor

        val result = FuzzySearch.extractOne(normalizedValue, colorMap.keys.toList())
        return if (result.score >= 70) colorMap[result.string] ?: rawValue else rawValue
    }
}
