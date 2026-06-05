package com.appdevforall.sketchtoui.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedStringsWriterTest {

    @Test
    fun `adds new string array when missing`() {
        val existing = """
            <resources>
                <string name="app_name">My Application</string>
            </resources>
        """.trimIndent()
        val generated = residenceStateArray()

        val updated = GeneratedStringArrayMerger.merge(existing, generated)

        assertTrue(updated!!.contains("""<string name="app_name">My Application</string>"""))
        assertEquals(1, Regex("""<string-array name="residencestate_array">""").findAll(updated).count())
        assertTrue(updated.contains("<item>California</item>"))
        assertTrue(updated.contains("<item>Oregon</item>"))
        assertTrue(updated.contains("<item>washington</item>"))
    }

    @Test
    fun `adds generated residence state array to app-name-only resources`() {
        val existing = """
            <resources>
                <string name="app_name">My Application</string>
            </resources>
        """.trimIndent()
        val generated = """
                <string-array name="residencestate_array">
                    <item>California</item>
                    <item>Oregon</item>
                    <item>washington</item>
                </string-array>
        """.trimIndent()

        val updated = GeneratedStringArrayMerger.merge(existing, generated)

        assertTrue(updated!!.contains("""<string name="app_name">My Application</string>"""))
        assertEquals(1, Regex("""<string-array name="residencestate_array">""").findAll(updated).count())
        assertTrue(updated.contains("<item>California</item>"))
        assertTrue(updated.contains("<item>Oregon</item>"))
        assertTrue(updated.contains("<item>washington</item>"))
    }

    @Test
    fun `spinner layout array reference has matching saved string array`() {
        val layoutXml = """
            <Spinner
                android:id="@+id/residencestate"
                android:layout_width="150dp"
                android:layout_height="75dp"
                android:entries="@array/residencestate_array" />
        """.trimIndent()
        val existing = """
            <resources>
                <string name="app_name">My Application</string>
            </resources>
        """.trimIndent()

        val updated = GeneratedStringArrayMerger.merge(existing, residenceStateArray())

        assertTrue(layoutXml.contains("""android:entries="@array/residencestate_array""""))
        assertTrue(updated!!.contains("""<string-array name="residencestate_array">"""))
    }

    @Test
    fun `does not duplicate existing string array with same items`() {
        val existing = """
            <resources>
                <string name="app_name">My Application</string>

                <string-array name="residencestate_array">
                    <item>California</item>
                    <item>Oregon</item>
                    <item>washington</item>
                </string-array>
            </resources>
        """.trimIndent()

        val updated = GeneratedStringArrayMerger.merge(existing, residenceStateArray())

        assertEquals(existing, updated)
        assertEquals(1, Regex("""<string-array name="residencestate_array">""").findAll(updated!!).count())
    }

    @Test
    fun `updates existing string array with different items without duplicating`() {
        val existing = """
            <resources>
                <string name="app_name">My Application</string>

                <string-array name="residencestate_array">
                    <item>California</item>
                    <item>Oregon</item>
                </string-array>
            </resources>
        """.trimIndent()

        val updated = GeneratedStringArrayMerger.merge(existing, residenceStateArray())

        assertTrue(updated!!.contains("""<string name="app_name">My Application</string>"""))
        assertEquals(1, Regex("""<string-array name="residencestate_array">""").findAll(updated).count())
        assertTrue(updated.contains("<item>washington</item>"))
    }

    @Test
    fun `removes duplicate existing string arrays with same name`() {
        val existing = """
            <resources>
                <string-array name="residencestate_array">
                    <item>California</item>
                    <item>Oregon</item>
                    <item>washington</item>
                </string-array>

                <string-array name="residencestate_array">
                    <item>California</item>
                    <item>Oregon</item>
                    <item>washington</item>
                </string-array>
            </resources>
        """.trimIndent()

        val updated = GeneratedStringArrayMerger.merge(existing, residenceStateArray())

        assertEquals(1, Regex("""<string-array name="residencestate_array">""").findAll(updated!!).count())
    }

    @Test
    fun `preserves unrelated resources while merging string arrays`() {
        val existing = """
            <resources>
                <string name="app_name">My Application</string>
                <plurals name="message_count">
                    <item quantity="one">%d message</item>
                    <item quantity="other">%d messages</item>
                </plurals>
                <string-array name="other_array">
                    <item>Existing</item>
                </string-array>
            </resources>
        """.trimIndent()

        val updated = GeneratedStringArrayMerger.merge(existing, residenceStateArray())

        assertTrue(updated!!.contains("""<string name="app_name">My Application</string>"""))
        assertTrue(updated.contains("""<plurals name="message_count">"""))
        assertTrue(updated.contains("""<string-array name="other_array">"""))
        assertTrue(updated.contains("""<string-array name="residencestate_array">"""))
        assertFalse(updated.contains("""<resources><resources>"""))
    }

    private fun residenceStateArray(): String {
        return """
            <string-array name="residencestate_array">
                <item>California</item>
                <item>Oregon</item>
                <item>washington</item>
            </string-array>
        """.trimIndent()
    }
}
