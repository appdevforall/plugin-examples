package org.appdevforall.codeonthego.computervision.domain.converter

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.WidgetAnnotationMatcher
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextAssociationProcessorTest {

    private val processor = TextAssociationProcessor(WidgetAnnotationMatcher())

    @Test
    fun `Given_labelable_widget_and_nearby_text_When_associating_Then_text_is_assigned_and_text_box_is_consumed`() {
        val checkbox = box(
            label = DetectionLabels.CHECKBOX_CHECKED,
            text = "",
            x = 100,
            y = 100,
            w = 20,
            h = 20
        )
        val label = box(
            label = DetectionLabels.TEXT,
            text = "Milk",
            x = 130,
            y = 100,
            w = 40,
            h = 20
        )

        val result = processor.associateText(listOf(checkbox, label))

        assertEquals(1, result.size)
        assertEquals(DetectionLabels.CHECKBOX_CHECKED, result.single().label)
        assertEquals("Milk", result.single().text)
    }

    @Test
    fun `Given_tag_like_text_When_finalizing_ui_elements_Then_tag_text_is_removed`() {
        val button = box(
            label = DetectionLabels.BUTTON,
            text = "Login",
            x = 100,
            y = 100,
            w = 80,
            h = 40
        )
        val tagText = box(
            label = DetectionLabels.TEXT,
            text = "B-1",
            x = 80,
            y = 100,
            w = 20,
            h = 20
        )
        val normalText = box(
            label = DetectionLabels.TEXT,
            text = "Welcome",
            x = 100,
            y = 200,
            w = 80,
            h = 20
        )

        val result = processor.finalizeUiElements(listOf(button, tagText, normalText))

        assertTrue(result.contains(button))
        assertTrue(result.contains(normalText))
        assertFalse(result.contains(tagText))
    }

    @Test
    fun `Given_widget_When_finalizing_ui_elements_Then_widget_is_kept`() {
        val switch = box(
            label = DetectionLabels.SWITCH_OFF,
            text = "Remember me",
            x = 100,
            y = 100,
            w = 100,
            h = 40
        )

        val result = processor.finalizeUiElements(listOf(switch))

        assertEquals(listOf(switch), result)
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
