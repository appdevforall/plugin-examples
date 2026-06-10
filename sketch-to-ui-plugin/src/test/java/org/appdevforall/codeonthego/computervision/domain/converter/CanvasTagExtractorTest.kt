package org.appdevforall.codeonthego.computervision.domain.converter

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.WidgetAnnotationMatcher
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasTagExtractorTest {

    private val extractor = CanvasTagExtractor(WidgetAnnotationMatcher())

    @Test
    fun `Given_yolo_widget_tag_on_canvas_When_extracting_Then_tag_is_returned`() {
        val tag = detection(
            label = DetectionLabels.WIDGET_TAG,
            text = "B-1",
            isYolo = true,
            region = SketchRegion.CANVAS,
            left = 10f,
            top = 20f,
            right = 30f,
            bottom = 40f
        )

        val result = extractor.extract(
            detections = listOf(tag),
            sourceWidth = 100,
            sourceHeight = 100,
            targetWidth = 200,
            targetHeight = 200
        )

        assertEquals(1, result.size)
        assertEquals("B-1", result.single().text)
        assertEquals(DetectionLabels.WIDGET_TAG, result.single().label)
    }

    @Test
    fun `Given_ocr_tag_on_canvas_When_extracting_Then_tag_is_returned`() {
        val tag = detection(
            label = DetectionLabels.TEXT,
            text = "C-1",
            isYolo = false,
            region = SketchRegion.CANVAS
        )

        val result = extractor.extract(
            detections = listOf(tag),
            sourceWidth = 100,
            sourceHeight = 100,
            targetWidth = 100,
            targetHeight = 100
        )

        assertEquals(1, result.size)
        assertEquals("C-1", result.single().text)
    }

    @Test
    fun `Given_tag_outside_canvas_When_extracting_Then_tag_is_ignored`() {
        val marginTag = detection(
            label = DetectionLabels.TEXT,
            text = "B-1",
            isYolo = false,
            region = SketchRegion.LEFT_METADATA
        )

        val result = extractor.extract(
            detections = listOf(marginTag),
            sourceWidth = 100,
            sourceHeight = 100,
            targetWidth = 100,
            targetHeight = 100
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `Given_invalid_yolo_widget_tag_When_extracting_Then_tag_is_ignored`() {
        val invalidTag = detection(
            label = DetectionLabels.WIDGET_TAG,
            text = "invalid",
            isYolo = true,
            region = SketchRegion.CANVAS
        )

        val result = extractor.extract(
            detections = listOf(invalidTag),
            sourceWidth = 100,
            sourceHeight = 100,
            targetWidth = 100,
            targetHeight = 100
        )

        assertTrue(result.isEmpty())
    }

    private fun detection(
        label: String,
        text: String,
        isYolo: Boolean,
        region: SketchRegion?,
        left: Float = 10f,
        top: Float = 10f,
        right: Float = 30f,
        bottom: Float = 30f
    ): DetectionResult {
        return DetectionResult(
            boundingBox = RectF(left, top, right, bottom),
            label = label,
            score = 0.99f,
            text = text,
            isYolo = isYolo,
            region = region
        )
    }
}
