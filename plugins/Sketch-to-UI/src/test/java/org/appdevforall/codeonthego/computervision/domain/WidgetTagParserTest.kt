package org.appdevforall.codeonthego.computervision.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetTagParserTest {

    @Test
    fun `Given_clean_widget_tag_When_parsed_Then_tag_is_recognized_and_normalized`() {
        assertTrue(WidgetTagParser.isTag("B-12"))
        assertEquals("B-12", WidgetTagParser.normalizeTagText("B-12"))
        assertEquals(12, WidgetTagParser.extractOrdinal("B-12"))
    }

    @Test
    fun `Given_OCR_prefix_and_ordinal_When_normalized_Then_OCR_characters_are_corrected`() {
        assertEquals("B-10", WidgetTagParser.normalizeTagText("8-I0"))
        assertEquals("B-2", WidgetTagParser.normalizeTagText("8-Z"))
    }

    @Test
    fun `Given_tag_with_trailing_annotation_When_extracted_Then_annotation_is_preserved`() {
        assertEquals(
            "T-1" to "layout_width: 200dp",
            WidgetTagParser.extractTag("T-1 layout_width: 200dp")
        )
        assertFalse(WidgetTagParser.isTag("T-1 layout_width: 200dp"))
    }

    @Test
    fun `Given_widget_tag_sequence_When_checked_Then_only_complete_tag_sequences_are_accepted`() {
        assertTrue(WidgetTagParser.isTagSequence("B-1 T-2 P-3"))
        assertFalse(WidgetTagParser.isTagSequence("B-1 submit"))
        assertFalse(WidgetTagParser.isTagSequence(""))
    }

    @Test
    fun `Given_widget_tag_When_mapped_Then_expected_Android_tag_is_returned`() {
        assertEquals("EditText", WidgetTagParser.androidTagFor("T-1"))
        assertEquals("ImageView", WidgetTagParser.androidTagFor("P-1"))
        assertEquals("Switch", WidgetTagParser.androidTagFor("SW-1"))
        assertEquals("View", WidgetTagParser.androidTagFor("X-1"))
        assertNull(WidgetTagParser.extractOrdinal("T-name"))
    }
}
