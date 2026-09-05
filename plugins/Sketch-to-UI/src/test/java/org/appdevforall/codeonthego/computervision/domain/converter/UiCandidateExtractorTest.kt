package org.appdevforall.codeonthego.computervision.domain.converter

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiCandidateExtractorTest {

    private val extractor = UiCandidateExtractor()

    @Test
    fun `Given_invalid_widget_tag_When_extracting_candidates_Then_it_is_removed`() {
        val invalidTag = detection(
            label = DetectionLabels.WIDGET_TAG,
            text = "not-a-tag",
            isYolo = true,
            region = SketchRegion.CANVAS
        )
        val button = detection(
            label = DetectionLabels.BUTTON,
            text = "Login",
            isYolo = true,
            region = SketchRegion.CANVAS
        )

        val result = extractor.extract(listOf(invalidTag, button))

        assertEquals(listOf(button), result)
    }

    @Test
    fun `Given_switches_in_same_vertical_band_When_extracting_candidates_Then_only_one_switch_is_kept`() {
        val firstSwitch = detection(
            label = DetectionLabels.SWITCH_OFF,
            text = "",
            top = 100f,
            bottom = 130f,
            region = SketchRegion.CANVAS
        )
        val duplicatedSwitch = detection(
            label = DetectionLabels.SWITCH_ON,
            text = "",
            top = 105f,
            bottom = 135f,
            region = SketchRegion.CANVAS
        )

        val result = extractor.extract(listOf(firstSwitch, duplicatedSwitch))

        assertEquals(1, result.size)
        assertEquals(firstSwitch, result.single())
    }

    @Test
    fun `Given_cross_boundary_canvas_text_When_extracting_candidates_Then_valid_text_is_kept`() {
        val text = detection(
            label = DetectionLabels.TEXT,
            text = "Remember me",
            isYolo = false,
            region = SketchRegion.CROSS_BOUNDARY
        )

        val result = extractor.extract(listOf(text))

        assertEquals(listOf(text), result)
    }

    @Test
    fun `Given_margin_detection_When_extracting_candidates_Then_it_is_removed`() {
        val marginText = detection(
            label = DetectionLabels.TEXT,
            text = "B-1 width 100dp",
            isYolo = false,
            region = SketchRegion.LEFT_METADATA
        )

        val result = extractor.extract(listOf(marginText))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `Given_canvas_metadata_text_When_extracting_candidates_Then_it_is_removed`() {
        val metadataText = detection(
            label = DetectionLabels.TEXT,
            text = "layout_width:100dp",
            isYolo = false,
            region = SketchRegion.CROSS_BOUNDARY
        )

        val result = extractor.extract(listOf(metadataText))

        assertFalse(result.contains(metadataText))
    }

    private fun detection(
        label: String,
        text: String,
        left: Float = 100f,
        top: Float = 100f,
        right: Float = 200f,
        bottom: Float = 130f,
        score: Float = 0.99f,
        isYolo: Boolean = true,
        region: SketchRegion? = null
    ): DetectionResult {
        return DetectionResult(
            boundingBox = RectF(left, top, right, bottom),
            label = label,
            score = score,
            text = text,
            isYolo = isYolo,
            region = region
        )
    }
}
