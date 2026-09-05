package org.appdevforall.codeonthego.computervision.domain

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.xml.LayoutRenderer
import org.appdevforall.codeonthego.computervision.domain.xml.XmlContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckboxLabelLayoutTest {

    @Test
    fun `consumed numeric suffix does not render as extra TextView`() {
        val checkbox = box("checkbox_checked", "", x = 0, y = 0, w = 20, h = 20)
        val option = box("text", "Option", x = 28, y = 1, w = 48, h = 18)
        val one = box("text", "1", x = 84, y = 1, w = 8, h = 18)

        val associated = TextAssociator.assignNearbyTextToWidgets(
            boxes = listOf(checkbox, option, one),
            availableTexts = listOf(option, one)
        )
        val layoutItems = LayoutTreeBuilder.buildLayoutTree(associated)
        val context = XmlContext()
        val renderer = LayoutRenderer(context, annotations = emptyMap())

        layoutItems.forEach { renderer.render(it) }

        val xml = context.toString()
        assertTrue(xml, xml.contains("""android:text="Option 1""""))
        assertFalse(xml, xml.contains("<TextView"))
        assertTrue(layoutItems.single() is LayoutItem.CheckboxGroup)
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
