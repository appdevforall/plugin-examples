package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import me.xdrop.fuzzywuzzy.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.DimensionValueSet
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner

internal object DimensionCleaner : ValueCleaner {
    private const val FUZZY_DIMENSION_THRESHOLD = 60
    private const val OCR_TRAILING_ZERO_MIN_VALUE = 1000L

    private const val DP_UNIT = "dp"
    private const val SP_UNIT = "sp"
    private const val PX_UNIT = "px"
    private const val IN_UNIT = "in"
    private const val MM_UNIT = "mm"
    private const val PT_UNIT = "pt"

    private const val OCR_SP_UNIT = "5p"
    private const val OCR_DUPLICATED_DP_UNIT = "ddp"
    private const val OCR_DP_ZERO_UNIT = "d0"
    private const val OCR_DP_LETTER_O_UNIT = "do"
    private const val OCR_DP_LETTER_E_UNIT = "de"

    private const val OCR_DP_NOISE_D = 'd'
    private const val OCR_DP_NOISE_P = 'p'

    private val noisyDimensionSuffixes = listOf(
        OCR_DUPLICATED_DP_UNIT,
        DP_UNIT,
        OCR_DP_ZERO_UNIT,
        OCR_DP_LETTER_O_UNIT,
        OCR_DP_LETTER_E_UNIT,
        SP_UNIT,
        OCR_SP_UNIT,
        PX_UNIT,
        IN_UNIT,
        MM_UNIT,
        PT_UNIT
    )

    /** Resolves dimensions using fuzzy scores and numeric OCR corrections. */
    override fun clean(rawValue: String): String {
        val trimmedValue = rawValue.trim().lowercase()
        val normalized = trimmedValue.replace(" ", "_")

        if (DimensionValueSet.matchKeywords.any { it in normalized }) {
            return DimensionValueSet.MATCH_PARENT
        }

        if (DimensionValueSet.wrapKeywords.any { it in normalized }) {
            return DimensionValueSet.WRAP_CONTENT
        }

        val fuzzyResult = FuzzySearch.extractOne(normalized, DimensionValueSet.values)
        if (fuzzyResult.score >= FUZZY_DIMENSION_THRESHOLD) {
            return fuzzyResult.string
        }

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
            value.endsWith(SP_UNIT) || value.endsWith(OCR_SP_UNIT) -> SP_UNIT
            value.endsWith(PX_UNIT) -> PX_UNIT
            value.endsWith(IN_UNIT) -> IN_UNIT
            value.endsWith(MM_UNIT) -> MM_UNIT
            value.endsWith(PT_UNIT) -> PT_UNIT
            else -> DP_UNIT
        }
    }

    private fun removeDimensionUnitNoise(value: String): String {
        val cleaned = noisyDimensionSuffixes
            .firstOrNull { suffix -> value.endsWith(suffix) }
            ?.let { suffix -> value.removeSuffix(suffix) }
            ?: value

        return cleaned.trimEnd { char ->
            char == OCR_DP_NOISE_D || char == OCR_DP_NOISE_P
        }
    }

    /** Removes a likely OCR-added trailing zero from unusually large dimensions. */
    private fun removeOcrTrailingZero(num: String): String {
        val numericValue = num.toLongOrNull() ?: 0L
        val isOcrArtifact = num.endsWith("0") &&
            numericValue >= OCR_TRAILING_ZERO_MIN_VALUE

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
    private const val SP_UNIT = "sp"

    override fun clean(rawValue: String): String {
        val normalized = rawValue
            .lowercase()
            .replace(" ", "")
            .replace(AttributeRegexPatterns.SP_SUFFIX, "")

        val numericPart = NumberCleaner.clean(normalized.replace("_", ""))

        return if (numericPart != normalized) {
            "$numericPart$SP_UNIT"
        } else {
            rawValue
        }
    }
}
