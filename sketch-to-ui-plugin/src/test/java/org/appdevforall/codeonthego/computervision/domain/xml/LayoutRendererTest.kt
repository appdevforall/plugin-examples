package org.appdevforall.codeonthego.computervision.domain.xml

import android.graphics.Rect
import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutRendererTest {

    @Test
    fun `checkbox group uses canonical ids when OCR annotation id is noisy`() {
        val first = checkboxBox(y = 0, text = "Option A")
        val second = checkboxBox(y = 20, text = "Option B", checked = true)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = mapOf(
                first to "id: cbgraup2 | textColor: gray"
            )
        )

        renderer.render(LayoutItem.CheckboxGroup(listOf(first, second), "vertical"))

        val xml = context.toString()

        assertTrue(xml.contains("""android:id="@+id/cb_group_2_a""""))
        assertTrue(xml.contains("""android:id="@+id/cb_group_2_b""""))
        assertTrue(!xml.contains("cbgraup2"))
    }

    @Test
    fun `checkbox group noisy textColor annotation emits valid textColor without losing option labels`() {
        val first = checkboxBox(y = 0, text = "Option 1", checked = true)
        val second = checkboxBox(y = 20, text = "Option 2")
        val third = checkboxBox(y = 40, text = "Option 3", checked = true)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = mapOf(
                first to "layout-width: 200dP layout-height. content wrap id: co group1 text: Sclect.optian textcalar: black"
            )
        )

        renderer.render(LayoutItem.CheckboxGroup(listOf(first, second, third), "vertical"))

        val xml = context.toString()

        assertTrue(xml.contains("""android:id="@+id/cb_group_1_a""""))
        assertTrue(xml.contains("""android:id="@+id/cb_group_1_b""""))
        assertTrue(xml.contains("""android:id="@+id/cb_group_1_c""""))
        assertTrue(xml.contains("""android:text="Option 1""""))
        assertTrue(xml.contains("""android:text="Option 2""""))
        assertTrue(xml.contains("""android:text="Option 3""""))
        assertEquals(3, Regex("""android:textColor="#000000"""").findAll(xml).count())
        assertFalse(xml.contains("""android:textColor="group1""""))
        assertFalse(xml.contains("<TextView"))
    }

    @Test
    fun `radio group ignores group style ids on child radios`() {
        val first = radioBox(y = 0, text = "choice A")
        val second = radioBox(y = 20, text = "choice B", checked = true)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = mapOf(
                first to "id: rb_group_1 | textSize: 16sp"
            )
        )

        renderer.render(LayoutItem.RadioGroup(listOf(first, second), "vertical"))

        val xml = context.toString()

        assertTrue(xml.contains("""android:id="@+id/radio_button_unchecked_0""""))
        assertTrue(xml.contains("""android:id="@+id/radio_button_checked_0""""))
        assertTrue(!xml.contains("""android:id="@+id/rb_group_1""""))
    }

    @Test
    fun `radio group ignores group style ids even when parser leaks trailing tokens`() {
        val first = radioBox(y = 0, text = "choice A")
        val second = radioBox(y = 20, text = "choice B", checked = true)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = mapOf(
                first to "id: rb_group_1_text_site_16sp | textColor: black"
            )
        )

        renderer.render(LayoutItem.RadioGroup(listOf(first, second), "vertical"))

        val xml = context.toString()

        assertTrue(xml.contains("""android:id="@+id/radio_button_unchecked_0""""))
        assertTrue(!xml.contains("""android:id="@+id/rb_group_1_text_site_16sp""""))
    }

    @Test
    fun `switch and image metadata render recovered xml without tag text`() {
        val switch = box("switch_off", "P-1 P-1", x = 0, y = 0, w = 80, h = 40)
        val image = box("image_placeholder", "", x = 0, y = 60, w = 200, h = 100)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = identityAnnotationMap(
                switch to "layout_width: layaut_width: 00dp id: Switeh 1 id: Switeh 1 checked:felse checked:false",
                image to "layout-idth: 200oP | layout.height: wrap content | src: images | layaut-graity: start w/ap iol: m_View 1"
            )
        )

        renderer.render(LayoutItem.SimpleView(switch))
        renderer.render(LayoutItem.SimpleView(image))

        val xml = context.toString()

        assertTrue(xml.contains("""<androidx.appcompat.widget.SwitchCompat"""))
        assertTrue(xml.contains("""android:id="@+id/switch_1""""))
        assertTrue(xml.contains("""android:layout_width="100dp""""))
        assertTrue(xml.contains("""android:checked="false""""))
        assertTrue(xml.contains("""android:text=""""))
        assertFalse(xml.contains("""android:text="P-1 P-1""""))
        assertTrue(xml.contains("""<ImageView"""))
        assertTrue(xml.contains("""android:id="@+id/im_view_1""""))
        assertTrue(xml.contains("""android:layout_gravity="start""""))
        assertTrue(xml.contains("""android:src="@drawable/images""""))
    }

    @Test
    fun `real switch labels remain visible`() {
        val switch = box("switch_off", "WiFi", x = 0, y = 0, w = 80, h = 40)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = emptyMap()
        )

        renderer.render(LayoutItem.SimpleView(switch))

        val xml = context.toString()

        assertTrue(xml.contains("""<androidx.appcompat.widget.SwitchCompat"""))
        assertTrue(xml.contains("""android:text="WiFi""""))
    }

    @Test
    fun `Given_switch_with_center_gravity_and_noisy_label_When_rendered_Then_gravity_and_label_are_normalized`() {
        val switch = box("switch_off", "Rememberme", x = 0, y = 0, w = 80, h = 40)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = identityAnnotationMap(
                switch to "id: remember | layoutgravity:center | layoutgravity:centerhorizontal"
            )
        )

        renderer.render(LayoutItem.SimpleView(switch))

        val xml = context.toString()

        assertTrue(xml.contains("""<androidx.appcompat.widget.SwitchCompat"""))
        assertTrue(xml.contains("""android:id="@+id/remember""""))
        assertTrue(xml.contains("""android:layout_gravity="center_horizontal""""))
        assertTrue(xml.contains("""android:text="Remember me""""))
        assertFalse(xml.contains("""android:layout_gravity="center""""))
    }

    @Test
    fun `Given_non_password_EditText_with_explicit_text_When_rendered_Then_text_is_included`() {
        val input = box("text_entry_box", "", x = 0, y = 0, w = 200, h = 52)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = identityAnnotationMap(
                input to "id: display_name | text: Alice | hint: Name"
            )
        )

        renderer.render(LayoutItem.SimpleView(input))

        val xml = context.toString()

        assertTrue(xml.contains("""<EditText"""))
        assertTrue(xml.contains("""android:id="@+id/display_name""""))
        assertTrue(xml.contains("""android:text="Alice""""))
        assertTrue(xml.contains("""android:hint="Name""""))
    }

    @Test
    fun `Given_password_EditText_with_metadata_text_When_rendered_Then_metadata_text_is_excluded`() {
        val input = box("text_entry_box", "Password", x = 0, y = 0, w = 200, h = 52)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = identityAnnotationMap(
                input to "layoutwidth | layoutwidthi20O0dp | layoutheight: | layoutheight30 | t | extassword | textPassword | credential | ieredeetal"
            )
        )

        renderer.render(LayoutItem.SimpleView(input))

        val xml = context.toString()

        assertTrue(xml.contains("""<EditText"""))
        assertTrue(xml.contains("""android:id="@+id/credential""""))
        assertTrue(xml.contains("""android:inputType="textPassword""""))
        assertTrue(xml.contains("""android:hint="Password""""))
        assertFalse(xml.contains("""android:text=""""))
        assertFalse(xml.contains("credential ieredeetal"))
    }

    @Test
    fun `Given_radio_group_with_text_color_annotation_When_rendered_Then_option_labels_are_preserved`() {
        val first = radioBox(y = 0, text = "Vanilla")
        val second = radioBox(y = 20, text = "Chocolate")
        val third = radioBox(y = 40, text = "Strawberry")
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = identityAnnotationMap(
                first to "texteolor:blue | textSize:16sp"
            )
        )

        renderer.render(LayoutItem.RadioGroup(listOf(first, second, third), "vertical"))

        val xml = context.toString()

        assertTrue(xml.contains("""android:text="Vanilla""""))
        assertTrue(xml.contains("""android:text="Chocolate""""))
        assertTrue(xml.contains("""android:text="Strawberry""""))
        assertFalse(xml.contains("""android:text="blue""""))
        assertEquals(3, Regex("""android:textColor="#0000FF"""").findAll(xml).count())
        assertEquals(3, Regex("""android:textSize="16sp"""").findAll(xml).count())
    }

    @Test
    fun `Given_image_with_compact_height_annotation_When_rendered_Then_recovered_height_is_included`() {
        val image = box("image_placeholder", "", x = 0, y = 0, w = 200, h = 100)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = identityAnnotationMap(
                image to "width:64dp | heighti64de | id: img_logo | src:logo.png"
            )
        )

        renderer.render(LayoutItem.SimpleView(image))

        val xml = context.toString()

        assertTrue(xml.contains("""<ImageView"""))
        assertTrue(xml.contains("""android:id="@+id/img_logo""""))
        assertTrue(xml.contains("""android:layout_width="64dp""""))
        assertTrue(xml.contains("""android:layout_height="64dp""""))
        assertTrue(xml.contains("""android:src="@drawable/logo""""))
        assertFalse(xml.contains("""android:layout_height="1dp""""))
    }

    @Test
    fun `image without recoverable metadata keeps fallback id`() {
        val image = box("image_placeholder", "", x = 0, y = 0, w = 200, h = 100)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = emptyMap()
        )

        renderer.render(LayoutItem.SimpleView(image))

        val xml = context.toString()

        assertTrue(xml.contains("""<ImageView"""))
        assertTrue(xml.contains("""android:id="@+id/image_placeholder_0""""))
        assertFalse(xml.contains("""android:id="@+id/im_view_1""""))
    }

    @Test
    fun `spinner annotation id is used for widget id and generated entries array`() {
        val dropdown = box("dropdown", "StateofResidence", x = 0, y = 0, w = 260, h = 52)
        val context = XmlContext()

        val renderer = LayoutRenderer(
            context = context,
            annotations = mapOf(
                dropdown to "width:150dp height:75dp id:residencestate entries:[California,Oregon,washington]"
            )
        )

        renderer.render(LayoutItem.SimpleView(dropdown))

        val xml = context.toString()

        assertTrue(xml.contains("""<TextView"""))
        assertTrue(xml.contains("""android:text="StateofResidence""""))
        assertTrue(xml.contains("""<Spinner"""))
        assertTrue(xml.contains("""android:id="@+id/residencestate""""))
        assertTrue(xml.contains("""android:layout_width="150dp""""))
        assertTrue(xml.contains("""android:layout_height="75dp""""))
        assertTrue(xml.contains("""android:entries="@array/residencestate_array""""))
        assertEquals(listOf("California", "Oregon", "washington"), context.stringArrays["residencestate_array"])
        assertFalse(xml.contains("""android:id="@+id/spinner_0""""))
        assertFalse(xml.contains("""android:entries="@array/spinner_0_array""""))
    }

    private fun checkboxBox(y: Int, text: String, checked: Boolean = false): ScaledBox {
        val label = if (checked) "checkbox_checked" else "checkbox_unchecked"
        return ScaledBox(
            label = label,
            text = text,
            x = 0,
            y = y,
            w = 20,
            h = 20,
            centerX = 10,
            centerY = y + 10,
            rect = Rect(0, y, 20, y + 20)
        )
    }

    private fun radioBox(y: Int, text: String, checked: Boolean = false): ScaledBox {
        val label = if (checked) "radio_button_checked" else "radio_button_unchecked"
        return ScaledBox(
            label = label,
            text = text,
            x = 0,
            y = y,
            w = 20,
            h = 20,
            centerX = 10,
            centerY = y + 10,
            rect = Rect(0, y, 20, y + 20)
        )
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

    private fun identityAnnotationMap(vararg pairs: Pair<ScaledBox, String>): Map<ScaledBox, String> {
        return object : AbstractMap<ScaledBox, String>() {
            override val entries: Set<Map.Entry<ScaledBox, String>> = object : AbstractSet<Map.Entry<ScaledBox, String>>() {
                override val size: Int = pairs.size

                override fun iterator(): Iterator<Map.Entry<ScaledBox, String>> {
                    return pairs.map { (key, value) ->
                        object : Map.Entry<ScaledBox, String> {
                            override val key: ScaledBox = key
                            override val value: String = value
                        }
                    }.iterator()
                }
            }

            override fun get(key: ScaledBox): String? {
                return pairs.firstOrNull { (candidate, _) ->
                    candidate === key || candidate.sameTestBoxAs(key)
                }?.second
            }
        }
    }

    private fun ScaledBox.sameTestBoxAs(other: ScaledBox): Boolean {
        return label == other.label &&
            text == other.text &&
            x == other.x &&
            y == other.y &&
            w == other.w &&
            h == other.h
    }
}
