package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import me.xdrop.fuzzywuzzy.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.parser.GravityValueSet
import org.appdevforall.codeonthego.computervision.domain.parser.InputTypeValueSet
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.CoreParserPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.GravityPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.InputTypePatterns

internal object TextStyleCleaner : ValueCleaner {
    private val textStyleValues = listOf("normal", "bold", "italic", "bold|italic")

    /** Selects the closest supported text style when its fuzzy score is sufficient. */
    override fun clean(rawValue: String): String {
        val normalizedValue = rawValue.lowercase().replace(" ", "_")
        if (normalizedValue in textStyleValues) return normalizedValue

        val result = FuzzySearch.extractOne(normalizedValue, textStyleValues)
        return if (result.score >= 60) result.string else rawValue
    }
}

internal object GravityCleaner : ValueCleaner {
    private const val CENTER_HORIZONTAL = "center_horizontal"
    private const val CENTER_VERTICAL = "center_vertical"
    private const val GRAVITY_TOKEN_SEPARATOR = " "

    override fun clean(rawValue: String): String {
        val normalized = rawValue.lowercase()
            .replace(GravityPatterns.CENTER_HORIZONTAL_COMPACT, CENTER_HORIZONTAL)
            .replace(GravityPatterns.CENTER_VERTICAL_COMPACT, CENTER_VERTICAL)
            .replace(GravityPatterns.CENTER_HORIZONTAL_SPACED, CENTER_HORIZONTAL)
            .replace(GravityPatterns.CENTER_VERTICAL_SPACED, CENTER_VERTICAL)
            .replace(GravityPatterns.GRAVITY_UNSAFE_CHARS, GRAVITY_TOKEN_SEPARATOR)
        return GravityValueSet.values.sortedByDescending { it.length }.firstOrNull { value ->
            GravityPatterns.wholeGravityValue(value).containsMatchIn(normalized)
        } ?: rawValue.trim()
    }
}

internal object InputTypeCleaner : ValueCleaner {
    private val inputTypeValues = InputTypeValueSet.values.map { it.lowercase() }.toSet()

    /** Selects supported input types whose fuzzy scores meet the acceptance threshold. */
    override fun clean(rawValue: String): String {
        val normalized = rawValue.trim()
            .replace(CoreParserPatterns.WHITESPACE, "")
            .replace(InputTypePatterns.INPUT_TYPE_UNSAFE_CHARS, "")
        if (normalized.isBlank()) return rawValue.trim()

        return normalized.split('|')
            .mapNotNull { part ->
                val result = FuzzySearch.extractOne(part.lowercase(), inputTypeValues)
                if (result.score >= 70) {
                    InputTypeValueSet.values.firstOrNull { it.equals(result.string, ignoreCase = true) }
                } else {
                    null
                }
            }
            .distinct()
            .joinToString("|")
            .ifBlank { rawValue.trim() }
    }
}
