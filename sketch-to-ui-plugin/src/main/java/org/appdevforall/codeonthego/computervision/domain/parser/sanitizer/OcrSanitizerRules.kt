package org.appdevforall.codeonthego.computervision.domain.parser.sanitizer

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey


class AttributeKeyPhraseSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "\\btext\\s*[-_ ]\\s*(?:colou?r|calar|colar)\\b" to "textcolor",
        "\\btexteolou?r\\b|\\btexteolor\\b" to "textcolor",
        "\\binput\\s*[-_ ]?\\s*type\\b" to "input_type",
        "\\btext\\s+pass\\s*word\\b" to "textPassword",
        "\\btext\\s+password\\b" to "textPassword",
        "\\blay(?:out|aut)[_\\- ]?gr(?:av|a)ity\\b" to "layout_gravity",
        "\\blayoutgravity\\b" to "layout_gravity",
        "\\blayout[_\\- ]*heiqht\\b" to "layout_height"
    )
}

class CompactDimensionSanitizer : OcrSanitizer {
    private val compactWidthRegex = Regex(
        "\\blay(?:out|aut)?[_\\-\\s]*w(?:idth)?[iIl1|]?\\s*([0-9oOIlLSBZz]+)\\s*(d[pbe]|de|clp|dp)?\\b",
        RegexOption.IGNORE_CASE
    )
    private val compactHeightRegex = Regex(
        "\\blay(?:out|aut)?[_\\-\\s]*h(?:ei(?:ght?)?)?[iIl1|]?\\s*([0-9oOIlLSBZz]+)\\s*(d[pbe]|de|clp|dp)?\\b",
        RegexOption.IGNORE_CASE
    )
    private val compactBareWidthRegex = Regex(
        "\\bwidth[iIl1|]?\\s*([0-9oOIlLSBZz]+)\\s*(d[pbe]|de|clp|dp)?\\b",
        RegexOption.IGNORE_CASE
    )
    private val compactBareHeightRegex = Regex(
        "\\bheight[iIl1|]?\\s*([0-9oOIlLSBZz]+)\\s*(d[pbe]|de|clp|dp)?\\b",
        RegexOption.IGNORE_CASE
    )

    override fun sanitize(input: String): String {
        return input
            .replace(compactWidthRegex) { match ->
                "layout_width: ${match.groupValues[1]}${match.groupValues[2].normalizeDimensionUnit()}"
            }
            .replace(compactHeightRegex) { match ->
                match.toDimensionReplacement("layout_height")
            }
            .replace(compactBareWidthRegex) { match ->
                "width: ${match.groupValues[1]}${match.groupValues[2].normalizeDimensionUnit()}"
            }
            .replace(compactBareHeightRegex) { match ->
                match.toDimensionReplacement("height")
            }
    }

    private fun MatchResult.toDimensionReplacement(key: String): String {
        val rawNumber = groupValues[1]
        val rawUnit = groupValues[2]
        if (rawUnit.isBlank() && value.count(Char::isDigit) <= MAX_LOW_CONFIDENCE_UNITLESS_HEIGHT_DIGITS) return ""
        return "$key: ${rawNumber}${rawUnit.normalizeDimensionUnit()}"
    }

    private fun String.normalizeDimensionUnit(): String {
        return when (lowercase()) {
            "", "dp", "d", "de", "clp" -> "dp"
            "sp" -> "sp"
            else -> "dp"
        }
    }

    private companion object {
        private const val MAX_LOW_CONFIDENCE_UNITLESS_HEIGHT_DIGITS = 2
    }
}

class ColorSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "backgroundired" to "background: red",
        "backgroundred" to "background: red",
        "\\bback[a-z]*[-_.]?\\s*[:;]\\s*" to "background: "
    )
}

class TextAttributeSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "text\\s*st[yj]l?e?" to "text_style"
    )
}

class DimensionSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "[il]ay[a-z]*[-_.\\s]*w[a-z0-9]*\\.?\\s*[:;]\\s*" to "layout_width: ",
        "[il]ay[a-z]*[-_.\\s]*hei[a-z0-9]*\\.?\\s*[:;]\\s*" to "layout_height: ",
        "m?w?at[ce]h[-_\\s]?p[ar]+ent" to "match_parent"
    )
}

class MarginPaddingSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        "layout_margin\\s+(top|bottom|start|end|left|right)" to "layout_margin_$1",
        "padding\\s+(top|bottom|start|end|left|right)" to "padding_$1"
    )
}

class StructureSanitizer : DictionaryRegexSanitizer() {
    private val idKeyPattern = AttributeKey.ID.aliases
        .filterNot { it == AttributeKey.ID.aliases.first() }
        .sortedByDescending { it.length }
        .joinToString("|")

    override val rawRules = mapOf(
        "horizontal\\s+gravity\\s*:\\s*center\\s+layout" to "layout_gravity: center_horizontal",
        "\\b(?:$idKeyPattern)\\b\\s*[:;]" to "id: ",
        "\\bS[ec][rt]\\b\\s*[:;]?" to "src: "
    )
}
