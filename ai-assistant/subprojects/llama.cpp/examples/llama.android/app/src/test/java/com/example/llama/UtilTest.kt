package com.example.llama

import kotlinx.serialization.InternalSerializationApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UtilTest {

    // Define the set of known tools that our parser will use for recovery.
    private val toolKeys = setOf("get_current_datetime", "get_device_battery")

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with well-formed JSON returns correct ToolCall`() {
        val response = """<tool_call>{"tool_name": "get_current_datetime"}</tool_call>"""
        val expected = LocalLLMToolCall("get_current_datetime", emptyMap())
        val result = Util.parseToolCall(response, toolKeys)
        assertNotNull(result)
        assertEquals(expected, result)
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with JSON but no XML tags returns correct ToolCall`() {
        val response = """{"tool_name": "get_device_battery", "args": {}}"""
        val expected = LocalLLMToolCall("get_device_battery", emptyMap())
        val result = Util.parseToolCall(response, toolKeys)
        assertNotNull(result)
        assertEquals(expected, result)
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with extra text around JSON still finds and parses it`() {
        val response = """
            <tool_call>
              Some introductory text from the model.
              {
                "tool_name": "get_current_datetime"
              }
              And some trailing text.
            </tool_call>
        """.trimIndent()
        val expected = LocalLLMToolCall("get_current_datetime", emptyMap())
        val result = Util.parseToolCall(response, toolKeys)
        assertEquals(expected, result)
    }

    // --- RECOVERY LOGIC TESTS ---

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with malformed JSON falls back to recovery and succeeds`() {
        // This JSON is missing a closing brace, so the primary parser will fail.
        val response = """<tool_call>{"tool_name": "get_device_battery", </tool_call>"""
        val expected = LocalLLMToolCall("get_device_battery", emptyMap())
        val result = Util.parseToolCall(response, toolKeys)

        assertNotNull("The parser should have recovered the tool call", result)
        assertEquals(expected, result)
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with no JSON but a known tool name succeeds via recovery`() {
        val response =
            """<tool_call>I think I need to use the "get_current_datetime" tool.</tool_call>"""
        val expected = LocalLLMToolCall("get_current_datetime", emptyMap())
        val result = Util.parseToolCall(response, toolKeys)
        assertEquals(expected, result)
    }

    // --- FAILURE AND EDGE CASE TESTS ---

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with unknown tool in recovery path returns null`() {
        // The JSON is malformed, and the tool name is not in our `toolKeys`.
        val response = """<tool_call>{"tool_name": "make_sandwich", </tool_call>"""
        val result = Util.parseToolCall(response, toolKeys)
        assertNull("Should return null for an unknown tool in a malformed response", result)
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with valid JSON for an unknown tool SUCCEEDS on primary path`() {
        // IMPORTANT: The primary JSON parsing logic does NOT validate the tool name against the keys.
        // It only checks if the name is blank. This test confirms that behavior.
        val response = """<tool_call>{"tool_name": "make_sandwich"}</tool_call>"""
        val expected = LocalLLMToolCall("make_sandwich", emptyMap())
        val result = Util.parseToolCall(response, toolKeys)
        assertEquals(
            "Should still parse valid JSON even if tool name is not in toolKeys",
            expected,
            result
        )
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with no tool information returns null`() {
        val response = "<tool_call>Just some text without any tool name.</tool_call>"
        val result = Util.parseToolCall(response, toolKeys)
        assertNull(result)
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with empty input returns null`() {
        val response = ""
        val result = Util.parseToolCall(response, toolKeys)
        assertNull(result)
    }

    @OptIn(InternalSerializationApi::class)
    @Test
    fun `parseToolCall with valid JSON but no tool_name key returns null`() {
        // The primary parser will fail because "tool_name" is missing.
        // The recovery parser will fail because the string '"get_current_datetime"' is not present.
        val response = """<tool_call>{"args": {"location": "here"}}</tool_call>"""
        val result = Util.parseToolCall(response, toolKeys)
        assertNull(result)
    }
}
