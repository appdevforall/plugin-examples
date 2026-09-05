package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextInputDetectionCollapserTest {

    @Test
    fun `Given_contained_text_inputs_When_collapsed_Then_largest_detection_is_preserved`() {
        val outer = detection(0f, 0f, 100f, 100f, score = 0.70f)
        val inner = detection(5f, 5f, 95f, 95f, score = 0.99f)

        val result = TextInputDetectionCollapser.collapse(listOf(inner, outer))

        assertEquals(1, result.size)
        assertSame(outer, result.single())
    }

    @Test
    fun `Given_equal_area_duplicate_text_inputs_When_collapsed_Then_highest_score_is_preserved`() {
        val lowerScore = detection(0f, 0f, 100f, 100f, score = 0.70f)
        val higherScore = detection(0f, 0f, 100f, 100f, score = 0.99f)

        val result = TextInputDetectionCollapser.collapse(listOf(lowerScore, higherScore))

        assertEquals(1, result.size)
        assertSame(higherScore, result.single())
    }

    @Test
    fun `Given_text_inputs_above_IoU_threshold_When_collapsed_Then_one_detection_is_preserved`() {
        val first = detection(0f, 0f, 100f, 100f)
        val second = detection(33f, 0f, 133f, 100f)

        val result = TextInputDetectionCollapser.collapse(listOf(first, second))

        assertEquals(1, result.size)
    }

    @Test
    fun `Given_text_inputs_below_overlap_thresholds_When_collapsed_Then_both_detections_are_preserved`() {
        val first = detection(0f, 0f, 100f, 100f)
        val second = detection(34f, 0f, 134f, 100f)

        val result = TextInputDetectionCollapser.collapse(listOf(first, second))

        assertEquals(2, result.size)
    }

    private fun detection(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        score: Float = 0.90f
    ): DetectionResult {
        return DetectionResult(
            boundingBox = rect(left, top, right, bottom),
            label = "text_entry_box",
            score = score
        )
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF {
        return RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }
}
