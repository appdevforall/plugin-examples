package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import me.xdrop.fuzzywuzzy.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.DimensionValueSet
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner

internal object DimensionCleaner : ValueCleaner {
    /** Resolves dimensions using fuzzy scores and numeric OCR corrections. */
    override fun clean(rawValue: String): String {
        val trimmedValue = rawValue.trim().lowercase()
        val normalized = trimmedValue.replace(" ", "_")

        if (DimensionValueSet.matchKeywords.any { it in normalized }) return DimensionValueSet.MATCH_PARENT
        if (DimensionValueSet.wrapKeywords.any { it in normalized }) return DimensionValueSet.WRAP_CONTENT

        val fuzzyResult = FuzzySearch.extractOne(normalized, DimensionValueSet.values)
        if (fuzzyResult.score >= 60) return fuzzyResult.string

        val unitMatch = AttributeRegexPatterns.DIMENSION_UNIT_SUFFIX.find(trimmedValue)
        val originalUnit = unitMatch?.value ?: "dp"
        val firstToken = trimmedValue.substringBefore(" ")
        val rawNumber = firstToken.removeSuffix(originalUnit).trim()
            .let { number -> if (unitMatch != null && number.endsWith("d")) number.dropLast(1) + "0" else number }
        val numericPart = NumberCleaner.clean(rawNumber)
        val numMatch = AttributeRegexPatterns.LEADING_SIGNED_INTEGER.find(numericPart)?.value
            ?: return trimmedValue
        val correctedNum = removeOcrTrailingZero(numMatch).trimLeadingZeros()

        return "$correctedNum$originalUnit"
    }

    /** Removes a likely OCR-added trailing zero from unusually large dimensions. */
    private fun removeOcrTrailingZero(num: String): String {
        val isOcrArtifact = num.endsWith("0") && (num.toLongOrNull() ?: 0L) >= 1000L
        return if (isOcrArtifact) num.dropLast(1) else num
    }

    /** Removes redundant leading zeros while preserving the numeric sign. */
    private fun String.trimLeadingZeros(): String {
        val negative = startsWith("-")
        val digits = if (negative) drop(1) else this
        val trimmed = digits.trimStart('0').ifEmpty { "0" }
        return if (negative) "-$trimmed" else trimmed
    }
}

internal object SpDimensionCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        val normalized = rawValue.lowercase().replace(" ", "").replace(AttributeRegexPatterns.SP_SUFFIX, "")
        val numericPart = NumberCleaner.clean(normalized.replace("_", ""))
        return if (numericPart != normalized) "${numericPart}sp" else rawValue
    }
}
