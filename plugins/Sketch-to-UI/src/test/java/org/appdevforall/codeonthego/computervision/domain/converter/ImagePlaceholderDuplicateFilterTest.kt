package org.appdevforall.codeonthego.computervision.domain.converter

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImagePlaceholderDuplicateFilterTest {

    private val filter = ImagePlaceholderDuplicateFilter()

    @Test
    fun `Given_annotated_image_and_small_nearby_duplicate_When_filtered_Then_duplicate_is_removed`() {
        val annotated = imageBox(x = 100, y = 100, w = 100, h = 100)
        val duplicate = imageBox(x = 120, y = 205, w = 20, h = 20)
        val button = box(label = DetectionLabels.BUTTON, text = "Login", x = 0, y = 0, w = 80, h = 40)

        val result = filter.filter(
            uiElements = listOf(button, annotated, duplicate),
            annotations = mapOf(annotated to "id: img_logo")
        )

        assertEquals(listOf(button, annotated), result)
    }

    @Test
    fun `Given_no_annotated_images_When_filtered_Then_all_elements_are_kept`() {
        val first = imageBox(x = 100, y = 100, w = 100, h = 100)
        val second = imageBox(x = 120, y = 205, w = 20, h = 20)

        val result = filter.filter(
            uiElements = listOf(first, second),
            annotations = emptyMap()
        )

        assertEquals(listOf(first, second), result)
    }

    @Test
    fun `Given_far_image_placeholder_When_filtered_Then_it_is_kept`() {
        val annotated = imageBox(x = 100, y = 100, w = 100, h = 100)
        val farImage = imageBox(x = 280, y = 400, w = 20, h = 20)

        val result = filter.filter(
            uiElements = listOf(annotated, farImage),
            annotations = mapOf(annotated to "id: img_logo")
        )

        assertTrue(result.contains(annotated))
        assertTrue(result.contains(farImage))
    }

    private fun imageBox(x: Int, y: Int, w: Int, h: Int): ScaledBox {
        return box(
            label = DetectionLabels.IMAGE_PLACEHOLDER,
            text = "",
            x = x,
            y = y,
            w = w,
            h = h
        )
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
