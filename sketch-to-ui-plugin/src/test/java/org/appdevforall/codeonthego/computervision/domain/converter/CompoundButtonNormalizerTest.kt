package org.appdevforall.codeonthego.computervision.domain.converter

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.junit.Assert.assertEquals
import org.junit.Test

class CompoundButtonNormalizerTest {

    private val normalizer = CompoundButtonNormalizer()

    @Test
    fun `Given_radio_detection_near_checkbox_tag_When_normalized_Then_it_becomes_checkbox`() {
        val radio = box(
            label = DetectionLabels.RADIO_BUTTON_CHECKED,
            text = "",
            x = 100,
            y = 100,
            w = 20,
            h = 20
        )
        val checkboxTag = box(
            label = DetectionLabels.TEXT,
            text = "C-1",
            x = 80,
            y = 95,
            w = 20,
            h = 20
        )

        val result = normalizer.normalizeByNearestTag(
            boxes = listOf(radio),
            canvasTags = listOf(checkboxTag)
        )

        assertEquals(1, result.size)
        assertEquals(DetectionLabels.CHECKBOX_CHECKED, result.single().label)
    }

    @Test
    fun `Given_checkbox_detection_near_radio_tag_When_normalized_Then_it_becomes_radio`() {
        val checkbox = box(
            label = DetectionLabels.CHECKBOX_UNCHECKED,
            text = "",
            x = 100,
            y = 100,
            w = 20,
            h = 20
        )
        val radioTag = box(
            label = DetectionLabels.TEXT,
            text = "R-1",
            x = 80,
            y = 95,
            w = 20,
            h = 20
        )

        val result = normalizer.normalizeByNearestTag(
            boxes = listOf(checkbox),
            canvasTags = listOf(radioTag)
        )

        assertEquals(1, result.size)
        assertEquals(DetectionLabels.RADIO_BUTTON_UNCHECKED, result.single().label)
    }

    @Test
    fun `Given_compound_detection_far_from_tag_When_normalized_Then_label_is_not_changed`() {
        val radio = box(
            label = DetectionLabels.RADIO_BUTTON_UNCHECKED,
            text = "",
            x = 100,
            y = 300,
            w = 20,
            h = 20
        )
        val checkboxTag = box(
            label = DetectionLabels.TEXT,
            text = "C-1",
            x = 80,
            y = 50,
            w = 20,
            h = 20
        )

        val result = normalizer.normalizeByNearestTag(
            boxes = listOf(radio),
            canvasTags = listOf(checkboxTag)
        )

        assertEquals(DetectionLabels.RADIO_BUTTON_UNCHECKED, result.single().label)
    }

    @Test
    fun `Given_overlapping_same_compound_detections_When_normalized_Then_larger_detection_is_kept`() {
        val small = box(
            label = DetectionLabels.CHECKBOX_UNCHECKED,
            text = "",
            x = 100,
            y = 100,
            w = 16,
            h = 16
        )
        val large = box(
            label = DetectionLabels.CHECKBOX_UNCHECKED,
            text = "",
            x = 98,
            y = 98,
            w = 24,
            h = 24
        )

        val result = normalizer.normalizeByNearestTag(
            boxes = listOf(small, large),
            canvasTags = emptyList()
        )

        assertEquals(1, result.size)
        assertEquals(large, result.single())
    }

    @Test
    fun `Given_switch_detection_When_normalized_Then_it_is_not_treated_as_compound_button`() {
        val switch = box(
            label = DetectionLabels.SWITCH_OFF,
            text = "Remember me",
            x = 100,
            y = 100,
            w = 80,
            h = 40
        )
        val checkboxTag = box(
            label = DetectionLabels.TEXT,
            text = "C-1",
            x = 80,
            y = 95,
            w = 20,
            h = 20
        )

        val result = normalizer.normalizeByNearestTag(
            boxes = listOf(switch),
            canvasTags = listOf(checkboxTag)
        )

        assertEquals(DetectionLabels.SWITCH_OFF, result.single().label)
    }

    private fun box(
        label: String,
        text: String,
        x: Int,
        y: Int,
        w: Int,
        h: Int
    ): ScaledBox {
        return ScaledBox(
            label = label,
            text = text,
            x = x,
            y = y,
            w = w,
            h = h,
            centerX = x + w / 2,
            centerY = y + h / 2,
            rect = Rect(x, y, x + w, y + h)
        )
    }
}
