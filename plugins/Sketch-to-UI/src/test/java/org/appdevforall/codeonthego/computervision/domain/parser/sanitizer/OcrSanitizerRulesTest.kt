package org.appdevforall.codeonthego.computervision.domain.parser.sanitizer

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrSanitizerRulesTest {

    @Test
    fun `Given_merged_background_color_tokens_When_sanitized_Then_background_color_key_value_is_recovered`() {
        val sanitizer = ColorSanitizer()

        assertEquals("background: red", sanitizer.sanitize("backgroundired"))
        assertEquals("background: red", sanitizer.sanitize("backgroundred"))
    }

    @Test
    fun `Given_text_style_OCR_variants_When_sanitized_Then_text_style_key_is_normalized`() {
        val sanitizer = TextAttributeSanitizer()

        assertEquals("text_style", sanitizer.sanitize("text style"))
        assertEquals("text_style", sanitizer.sanitize("text stjle"))
    }

    @Test
    fun `Given_dimension_key_OCR_mistakes_When_sanitized_Then_width_and_height_keys_are_recovered`() {
        val sanitizer = DimensionSanitizer()

        assertEquals("layout_width: 120dp", sanitizer.sanitize("iayout widh. 120dp"))
        assertEquals("layout_height: 48dp", sanitizer.sanitize("layout heist. 48dp"))
    }

    @Test
    fun `Given_match_parent_OCR_variants_When_sanitized_Then_match_parent_value_is_normalized`() {
        val sanitizer = DimensionSanitizer()

        assertEquals("layout_width: match_parent", sanitizer.sanitize("layout_width: match parent"))
        assertEquals("layout_width: match_parent", sanitizer.sanitize("layout_width: match-parrent"))
    }

    @Test
    fun `Given_spacing_edge_names_When_sanitized_Then_margin_and_padding_keys_are_normalized`() {
        val sanitizer = MarginPaddingSanitizer()

        assertEquals("layout_margin_top: 16dp", sanitizer.sanitize("layout_margin top: 16dp"))
        assertEquals("padding_end: 8dp", sanitizer.sanitize("padding end: 8dp"))
    }

    @Test
    fun `Given_horizontal_center_layout_phrase_When_sanitized_Then_layout_gravity_is_recovered`() {
        val sanitizer = StructureSanitizer()

        assertEquals(
            "layout_gravity: center_horizontal",
            sanitizer.sanitize("horizontal gravity: center layout")
        )
    }

    @Test
    fun `Given_noisy_metadata_When_default_sanitizer_runs_Then_multiple_OCR_cleanup_rules_are_applied`() {
        val sanitizer = OcrSanitizerFactory.createDefaultSanitizer()
        val input = "backgroundired | text stjle: bold | iayout widh. match parrent | padding end: 8dp"

        assertEquals(
            "background: red | text_style: bold | layout_width: match_parent | padding_end: 8dp",
            sanitizer.sanitize(input)
        )
    }
}
