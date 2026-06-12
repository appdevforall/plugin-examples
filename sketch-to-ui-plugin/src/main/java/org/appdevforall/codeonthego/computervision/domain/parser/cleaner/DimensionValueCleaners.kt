package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import me.xdrop.fuzzywuzzy.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.DimensionUnits
import org.appdevforall.codeonthego.computervision.domain.parser.DimensionValueSet
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner

internal object DimensionCleaner : ValueCleaner {
    private const val FUZZY_DIMENSION_THRESHOLD = 60
    private const val OCR_TRAILING_ZERO_MIN_VALUE = 1000L
    private const val OCR_DP_NOISE_D = 'd'
    private const val OCR_DP_NOISE_P = 'p'

    /** Resolves dimensions using fuzzy scores and numeric OCR corrections. */
    override fun clean(rawValue: String): String {
        val trimmedValue = rawValue.trim().lowercase()
        val normalized = trimmedValue.replace(" ", "_")

        if (DimensionValueSet.matchKeywords.any { it in normalized }) return DimensionValueSet.MATCH_PARENT
        if (DimensionValueSet.wrapKeywords.any { it in normalized }) return DimensionValueSet.WRAP_CONTENT

        val fuzzyResult = FuzzySearch.extractOne(normalized, DimensionValueSet.values)
        if (fuzzyResult.score >= FUZZY_DIMENSION_THRESHOLD) return fuzzyResult.string

        val compactValue = trimmedValue
            .replace(" ", "")
            .replace("_", "")

        val unit = resolveDimensionUnit(compactValue)
        val rawNumber = removeDimensionUnitNoise(compactValue)

        val numericPart = NumberCleaner.clean(rawNumber)
        val numMatch = AttributeRegexPatterns.LEADING_SIGNED_INTEGER.find(numericPart)?.value
            ?: return trimmedValue

        val correctedNum = removeOcrTrailingZero(numMatch).trimLeadingZeros()

        return "$correctedNum$unit"
    }

    private fun resolveDimensionUnit(value: String): String {
        return when {
            value.endsWith(DimensionUnits.SP) ||
                value.endsWith(DimensionUnits.OCR_SP) -> DimensionUnits.SP

            value.endsWith(DimensionUnits.PX) -> DimensionUnits.PX
            value.endsWith(DimensionUnits.IN) -> DimensionUnits.IN
            value.endsWith(DimensionUnits.MM) -> DimensionUnits.MM
            value.endsWith(DimensionUnits.PT) -> DimensionUnits.PT

            else -> DimensionUnits.DP
        }
    }

    private fun removeDimensionUnitNoise(value: String): String {
        val cleaned = DimensionUnits.noisySuffixes
            .firstOrNull { value.endsWith(it) }
            ?.let { suffix -> value.removeSuffix(suffix) }
            ?: value

        return cleaned.trimEnd { char ->
            char == OCR_DP_NOISE_D || char == OCR_DP_NOISE_P
        }
    }

    /** Removes a likely OCR-added trailing zero from unusually large dimensions. */
    private fun removeOcrTrailingZero(num: String): String {
        val isOcrArtifact = num.endsWith("0") && (num.toLongOrNull() ?: 0L) >= OCR_TRAILING_ZERO_MIN_VALUE
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
        return if (numericPart != normalized) "$numericPart${DimensionUnits.SP}" else rawValue
    }
}
