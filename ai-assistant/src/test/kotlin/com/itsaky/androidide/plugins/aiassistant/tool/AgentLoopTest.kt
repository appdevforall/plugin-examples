package com.itsaky.androidide.plugins.aiassistant.tool

import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentLoop] — the agentic tool-loop, tested in isolation from
 * Android/streaming via plain fakes for generation and tool execution.
 */
class AgentLoopTest {

    /** Records prompts and returns scripted model responses in order. */
    private class ScriptedModel(private val responses: List<String>) {
        val prompts = mutableListOf<String>()
        var calls = 0
        suspend fun generate(prompt: String): String {
            prompts += prompt
            val r = responses.getOrElse(calls) { responses.last() }
            calls++
            return r
        }
    }

    private fun toolCall(name: String) = """<tool_call>{"tool":"$name","args":{}}</tool_call>"""

    @Test
    fun givenAModelThatCallsNoTools_whenTheLoopRuns_thenItStopsAfterOneTurn() = runTest {
        val model = ScriptedModel(listOf("All done — here is your answer."))
        val history = mutableListOf(ChatMessage(Role.USER, "hello"))
        var toolsInvoked = 0

        val result = AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { toolsInvoked++; emptyList() }
        )

        assertTrue(result.completed)
        assertEquals(1, result.turns)
        assertEquals(1, model.calls)
        assertEquals(0, toolsInvoked)
        // history now: user + one assistant turn
        assertEquals(2, history.size)
        assertEquals(Role.ASSISTANT, history[1].role)
    }

    @Test
    fun givenAModelThatCallsTheTerminalTool_whenTheLoopRuns_thenItFinishesViaOnFinalAnswerWithoutDispatchingIt() = runTest {
        // The terminal tool ("respond") signals completion; it has no handler, so
        // the loop must emit its message via onFinalAnswer and stop — never send
        // it to executeTools.
        val model = ScriptedModel(
            listOf("""<tool_call>{"tool":"respond","args":{"message":"All set!"}}</tool_call>""")
        )
        val history = mutableListOf(ChatMessage(Role.USER, "hi"))
        var toolsInvoked = 0
        var finalTurn = -1
        var finalMessage: String? = null

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { toolsInvoked++; emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onFinalAnswer(turn: Int, message: String) {
                    finalTurn = turn
                    finalMessage = message
                }
            }
        )

        assertTrue(result.completed)
        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertEquals(1, result.turns)
        assertEquals(0, toolsInvoked)          // terminal tool is NOT dispatched
        assertEquals(1, finalTurn)
        assertEquals("All set!", finalMessage)
    }

    @Test
    fun givenAModelThatCallsAToolThenAnswers_whenTheLoopRuns_thenItChainsTheToolAndFinishes() = runTest {
        // Turn 1: model calls a tool. Turn 2: sees results, gives final answer.
        val model = ScriptedModel(
            listOf(
                "Let me look. ${toolCall("open_file")}",
                "Opened it. Done."
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "open MainActivity.java"))
        val executed = mutableListOf<List<ToolCall>>()

        val result = AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { calls ->
                executed += calls
                listOf(ToolResult.success("Opened file in editor", "path/to/MainActivity.java"))
            }
        )

        assertTrue(result.completed)
        assertEquals(2, result.turns)
        assertEquals(2, model.calls)
        assertEquals(1, executed.size)
        assertEquals("open_file", executed[0][0].name)

        // The 2nd prompt must contain the fed-back tool results (the whole point).
        assertTrue(
            "2nd turn prompt should include tool results",
            model.prompts[1].contains("Tool results:") &&
                model.prompts[1].contains("MainActivity.java")
        )

        // history: user, assistant(turn1), user(tool results), assistant(turn2)
        assertEquals(4, history.size)
        assertEquals(Role.USER, history[2].role)
        assertTrue(history[2].content.startsWith("Tool results:"))
    }

    @Test
    fun givenAModelThatKeepsCallingDistinctTools_whenTheLoopRuns_thenItStopsAtTheIterationCap() = runTest {
        // Distinct calls each turn so stuck-detection doesn't fire before the cap.
        val model = ScriptedModel(
            listOf(
                "step1 ${toolCall("list_files")}",
                "step2 ${toolCall("read_file")}",
                "step3 ${toolCall("search_project")}"
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "keep going"))
        var maxReachedTurns = -1
        var toolBatches = 0

        val result = AgentLoop(maxIterations = 3).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.success("ok")) },
            events = object : AgentLoop.Events {
                override suspend fun onMaxIterationsReached(turns: Int) { maxReachedTurns = turns }
            }
        )

        assertFalse(result.completed)
        assertEquals(AgentLoop.StopReason.MAX_ITERATIONS, result.reason)
        assertEquals(3, result.turns)
        assertEquals(3, model.calls)
        assertEquals(3, toolBatches)
        assertEquals(3, maxReachedTurns)
    }

    @Test
    fun givenAModelThatRepeatsTheIdenticalToolCall_whenTheLoopRuns_thenItStops() = runTest {
        val model = ScriptedModel(listOf(toolCall("list_files")))  // same call every turn
        val history = mutableListOf(ChatMessage(Role.USER, "go"))
        var repeatedTurns = -1
        var toolBatches = 0

        val result = AgentLoop(maxIterations = 8).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.success("ok")) },
            events = object : AgentLoop.Events {
                override suspend fun onRepeatedToolCalls(turns: Int) { repeatedTurns = turns }
            }
        )

        assertFalse(result.completed)
        assertEquals(AgentLoop.StopReason.REPEATED, result.reason)
        assertEquals(2, result.turns)   // caught on the 2nd identical turn
        assertEquals(2, repeatedTurns)
        assertEquals(1, toolBatches)    // executed once, not repeatedly
    }

    @Test
    fun givenAToolThenAnswerRun_whenTheLoopRuns_thenEventsFireForEachModelTurnAndToolBatch() = runTest {
        val model = ScriptedModel(listOf(toolCall("read_file"), "done"))
        val history = mutableListOf(ChatMessage(Role.USER, "read it"))
        val modelTurns = mutableListOf<Int>()
        val toolTurns = mutableListOf<Int>()

        AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.success("contents")) },
            events = object : AgentLoop.Events {
                override suspend fun onModelTurn(turn: Int, text: String) { modelTurns += turn }
                override suspend fun onToolResults(turn: Int, calls: List<ToolCall>, results: List<ToolResult>) { toolTurns += turn }
            }
        )

        assertEquals(listOf(1, 2), modelTurns)
        assertEquals(listOf(1), toolTurns)
    }

    @Test
    fun givenAFailingTool_whenTheLoopRuns_thenTheResultsAreFedBackAsFAILED() = runTest {
        val model = ScriptedModel(listOf(toolCall("open_file"), "acknowledged"))
        val history = mutableListOf(ChatMessage(Role.USER, "open nope"))

        AgentLoop().run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.failure("File not found", "does not exist")) }
        )

        val fedBack = history.first { it.role == Role.USER && it.content.startsWith("Tool results:") }
        assertTrue(fedBack.content.contains("FAILED"))
        assertTrue(fedBack.content.contains("File not found"))
    }

    @Test
    fun givenLongToolOutput_whenTheLoopRuns_thenItIsTruncatedBeforeFeedingBack() = runTest {
        val big = "x".repeat(10_000)
        val model = ScriptedModel(listOf(toolCall("read_file"), "ok"))
        val history = mutableListOf(ChatMessage(Role.USER, "read big"))

        AgentLoop(toolOutputCharLimit = 500).run(
            history = history,
            generate = model::generate,
            executeTools = { listOf(ToolResult.success("read", big)) }
        )

        val fedBack = history.first { it.content.startsWith("Tool results:") }
        assertTrue(fedBack.content.contains("truncated"))
        assertFalse("full 10k output must not be fed back", fedBack.content.contains(big))
    }

    @Test
    fun givenASuccessfulToolResult_whenFormatToolResultsIsCalled_thenItBiasesTheModelToStop() {
        val loop = AgentLoop()
        val fedBack = loop.formatToolResults(
            listOf(ToolCall("open_file", emptyMap())),
            listOf(ToolResult.success("Opened file in editor", ".gitignore"))
        )
        // After success, finishing is the default and another tool call is discouraged.
        assertTrue(fedBack.contains("you are DONE"))
        assertTrue(fedBack.contains("respond"))
        assertTrue(fedBack.contains("Do NOT call another tool"))
    }

    @Test
    fun givenAFailedToolResult_whenFormatToolResultsIsCalled_thenItKeepsTheOpenEndedNextToolCue() {
        val loop = AgentLoop()
        val fedBack = loop.formatToolResults(
            listOf(ToolCall("open_file", emptyMap())),
            listOf(ToolResult.failure("File not found", "does not exist"))
        )
        assertTrue(fedBack.contains("FAILED"))
        assertTrue(fedBack.contains("call the next tool"))
    }

    @Test
    fun givenATranscript_whenRenderTranscriptIsCalled_thenItLabelsAssistantTurnsAndAddsNoTrailingCue() {
        val loop = AgentLoop()
        val transcript = loop.renderTranscript(
            listOf(
                ChatMessage(Role.USER, "hi"),
                ChatMessage(Role.ASSISTANT, "hello"),
                ChatMessage(Role.USER, "Tool results:\n[list_files] ok")
            )
        )
        assertTrue(transcript.contains("hi"))
        assertTrue(transcript.contains("Assistant: hello"))
        assertTrue(transcript.contains("Tool results:"))
        // The backend appends its own "Assistant:" cue; we must NOT add one, or a
        // doubled cue makes local models loop. Lock that in.
        assertFalse("must not append a trailing Assistant cue", transcript.trimEnd().endsWith("Assistant:"))
    }
}
