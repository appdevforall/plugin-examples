package org.appdevforall.codeonthego.computervision.domain.parser.sanitizer

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.AttributeKeyPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.ColorPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.DimensionPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.SpacingPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.StructurePatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.TextPatterns


class AttributeKeyPhraseSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        AttributeKeyPatterns.TEXT_COLOR_SPACED to "textcolor",
        AttributeKeyPatterns.TEXT_COLOR_COMPACT_OCR to "textcolor",
        AttributeKeyPatterns.INPUT_TYPE to "input_type",
        AttributeKeyPatterns.TEXT_PASS_WORD to "textPassword",
        AttributeKeyPatterns.TEXT_PASSWORD to "textPassword",
        AttributeKeyPatterns.LAYOUT_GRAVITY_OCR to "layout_gravity",
        AttributeKeyPatterns.LAYOUT_GRAVITY_COMPACT to "layout_gravity",
        AttributeKeyPatterns.LAYOUT_HEIGHT_OCR to "layout_height",
        AttributeKeyPatterns.ENTRIES_WITH_OCR_I_BEFORE_BRACKET to "entries: ",
        AttributeKeyPatterns.ENTRIES_BEFORE_BRACKET to "entries: "
    )
}

class CompactDimensionSanitizer : OcrSanitizer {
    override fun sanitize(input: String): String {
        return input
            .replace(DimensionPatterns.COMPACT_LAYOUT_WIDTH) { match ->
                "layout_width: ${match.groupValues[1]}${match.groupValues[2].normalizeDimensionUnit()}"
            }
            .replace(DimensionPatterns.COMPACT_LAYOUT_HEIGHT) { match ->
                match.toDimensionReplacement("layout_height")
            }
            .replace(DimensionPatterns.COMPACT_BARE_WIDTH) { match ->
                "width: ${match.groupValues[1]}${match.groupValues[2].normalizeDimensionUnit()}"
            }
            .replace(DimensionPatterns.COMPACT_BARE_HEIGHT) { match ->
                match.toDimensionReplacement("height")
            }
    }

    /** Rejects short unitless heights by counting their detected numeric characters. */
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
        ColorPatterns.BACKGROUND_I_RED to "background: red",
        ColorPatterns.BACKGROUND_RED to "background: red",
        ColorPatterns.BACKGROUND_KEY_OCR to "background: "
    )
}

class TextAttributeSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        TextPatterns.TEXT_STYLE_OCR to "text_style"
    )
}

class DimensionSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        DimensionPatterns.LAYOUT_WIDTH_OCR to "layout_width: ",
        DimensionPatterns.LAYOUT_HEIGHT_OCR to "layout_height: ",
        DimensionPatterns.MATCH_PARENT_OCR to "match_parent"
    )
}

class MarginPaddingSanitizer : DictionaryRegexSanitizer() {
    override val rawRules = mapOf(
        SpacingPatterns.LAYOUT_MARGIN_SIDE to "layout_margin_$1",
        SpacingPatterns.PADDING_SIDE to "padding_$1"
    )
}

class StructureSanitizer : DictionaryRegexSanitizer() {
    private val idKeyPattern = AttributeKey.ID.aliases
        .filterNot { it == AttributeKey.ID.aliases.first() }
        .sortedByDescending { it.length }
        .joinToString("|")

    override val rawRules = mapOf(
        StructurePatterns.HORIZONTAL_GRAVITY_CENTER_LAYOUT to "layout_gravity: center_horizontal",
        StructurePatterns.idKeyWithSeparator(idKeyPattern) to "id: ",
        StructurePatterns.SRC_KEY_OCR to "src: "
    )
}
