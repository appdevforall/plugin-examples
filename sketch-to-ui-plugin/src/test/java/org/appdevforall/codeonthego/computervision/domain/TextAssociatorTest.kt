package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextAssociatorTest {

    @Test
    fun `label fragments are merged and consumed for checkbox`() {
        val checkbox = box("checkbox_checked", "", x = 0, y = 0, w = 20, h = 20)
        val option = box("text", "Option", x = 28, y = 1, w = 48, h = 18)
        val one = box("text", "1", x = 84, y = 1, w = 8, h = 18)

        val result = TextAssociator.assignNearbyTextToWidgets(
            boxes = listOf(checkbox, option, one),
            availableTexts = listOf(option, one)
        )

        assertEquals("Option 1", result.single { it.label == "checkbox_checked" }.text)
        assertFalse(result.any { it.label == "text" && it.text == "1" })
    }

    @Test
    fun `checkbox label OCR artifact is normalized conservatively`() {
        val checkbox = box("checkbox_unchecked", "", x = 0, y = 0, w = 20, h = 20)
        val label = box("text", "Joption 2", x = 28, y = 1, w = 80, h = 18)

        val result = TextAssociator.assignNearbyTextToWidgets(
            boxes = listOf(checkbox, label),
            availableTexts = listOf(label)
        )

        assertEquals("Option 2", result.single { it.label == "checkbox_unchecked" }.text)
    }

    private fun box(label: String, text: String, x: Int, y: Int, w: Int, h: Int): ScaledBox {
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
