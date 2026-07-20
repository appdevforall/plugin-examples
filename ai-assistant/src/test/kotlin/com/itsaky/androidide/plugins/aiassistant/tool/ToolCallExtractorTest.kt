package com.itsaky.androidide.plugins.aiassistant.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ToolCallExtractor]. Focus: only explicit tool calls are honored,
 * and prose never fires a tool (the "build Android apps" -> run_app regression).
 */
class ToolCallExtractorTest {

    @Test
    fun givenAnExplicitToolCallTag_whenExtracting_thenTheCallIsExtractedWithArgs() {
        val calls = ToolCallExtractor.extractToolCalls(
            """Sure. <tool_call>{"tool":"open_file","args":{"file_path":"app/Main.java"}}</tool_call>"""
        )
        assertEquals(1, calls.size)
        assertEquals("open_file", calls[0].name)
        assertEquals("app/Main.java", calls[0].args["file_path"])
    }

    @Test
    fun givenABareJsonToolCall_whenExtracting_thenItIsExtracted() {
        val calls = ToolCallExtractor.extractToolCalls(
            """{"tool":"list_files","args":{"directory":"src"}}"""
        )
        assertEquals(1, calls.size)
        assertEquals("list_files", calls[0].name)
        assertEquals("src", calls[0].args["directory"])
    }

    @Test
    fun givenAChattyReplyMentioningBuildingApps_whenExtracting_thenNoToolIsFired() {
        val calls = ToolCallExtractor.extractToolCalls(
            "Hi! I can help you build Android apps. What would you like to run or create next?"
        )
        assertTrue("prose must not produce tool calls, got $calls", calls.isEmpty())
    }

    @Test
    fun givenAPlainGreeting_whenExtracting_thenNoToolCallsAreProduced() {
        assertTrue(ToolCallExtractor.extractToolCalls("Hello, how can I help?").isEmpty())
    }

    @Test
    fun givenNarratedIntentWithoutATag_whenExtracting_thenNoToolCallsAreProduced() {
        // The model describing what it would do must NOT be treated as a tool call.
        val calls = ToolCallExtractor.extractToolCalls(
            "Let me list the files in src and then read MainActivity.kt for you."
        )
        assertTrue("narration must not produce tool calls, got $calls", calls.isEmpty())
    }
}
