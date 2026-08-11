package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult
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

    /** Records the turns it was given and returns scripted model responses in order. */
    private class ScriptedModel(private val responses: List<String>) {
        val turns = mutableListOf<List<ChatMessage>>()
        var calls = 0
        suspend fun generate(history: List<ChatMessage>): String {
            turns += history
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
        // Terminal tool ("respond") finishes via onFinalAnswer and is never dispatched.
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
    fun givenATerminalCallUnderAnAlternateKey_whenTheLoopRuns_thenTheAnswerStillReachesOnFinalAnswer() = runTest {
        // Models substitute "text" for "message", which dropped the finished answer entirely.
        val model = ScriptedModel(
            listOf("""<tool_call>{"tool":"respond","args":{"text":"All set!"}}</tool_call>""")
        )
        val history = mutableListOf(ChatMessage(Role.USER, "hi"))
        var finalMessage: String? = null

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onFinalAnswer(turn: Int, message: String) { finalMessage = message }
            }
        )

        assertTrue(result.completed)
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

        // Turn 2 must get the fed-back results as their own USER turn, not folded into turn 1.
        val secondTurnInput = model.turns[1]
        assertEquals(3, secondTurnInput.size)
        assertEquals(Role.ASSISTANT, secondTurnInput[1].role)
        assertEquals(Role.USER, secondTurnInput[2].role)
        assertTrue(
            "2nd turn should include tool results",
            secondTurnInput[2].content.contains("<tool_response>") &&
                secondTurnInput[2].content.contains("MainActivity.java")
        )

        // history: user, assistant(turn1), user(tool results), assistant(turn2)
        assertEquals(4, history.size)
        assertEquals(Role.USER, history[2].role)
        assertTrue(history[2].content.startsWith("<tool_response>"))
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
    fun givenAModelThatRepeatsAToolCallThatSucceeded_whenTheLoopRuns_thenItEndsWithoutRunningItAgain() = runTest {
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

        // The work already succeeded, so a re-request means "done" — not an error.
        assertTrue(result.completed)
        assertEquals(AgentLoop.StopReason.COMPLETED, result.reason)
        assertEquals(2, result.turns)
        assertEquals(1, toolBatches)     // the side effect runs exactly once
        assertEquals(-1, repeatedTurns)  // no "kept requesting the same action" warning
    }

    @Test
    fun givenAModelThatRepeatsAToolCallThatFailed_whenTheLoopRuns_thenItStopsAfterToleratingBoundedRepeats() = runTest {
        val model = ScriptedModel(listOf(toolCall("list_files")))  // same call every turn
        val history = mutableListOf(ChatMessage(Role.USER, "go"))
        var repeatedTurns = -1
        var toolBatches = 0

        // A failed batch keeps the retry tolerance: one repeat allowed, the second aborts.
        val result = AgentLoop(maxIterations = 8).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.failure("nope")) },
            events = object : AgentLoop.Events {
                override suspend fun onRepeatedToolCalls(turns: Int) { repeatedTurns = turns }
            }
        )

        assertFalse(result.completed)
        assertEquals(AgentLoop.StopReason.REPEATED, result.reason)
        assertEquals(3, result.turns)   // turn1 + one tolerated repeat, abort on turn3
        assertEquals(3, repeatedTurns)
        assertEquals(2, toolBatches)    // executed twice, then stopped
    }

    @Test
    fun givenMaxConsecutiveRepeatsOfOne_whenAFailedCallIsRepeated_thenItStopsOnTheSecondTurn() = runTest {
        val model = ScriptedModel(listOf(toolCall("list_files")))
        val history = mutableListOf(ChatMessage(Role.USER, "go"))
        var toolBatches = 0

        val result = AgentLoop(maxIterations = 8, maxConsecutiveRepeats = 1).run(
            history = history,
            generate = model::generate,
            executeTools = { toolBatches++; listOf(ToolResult.failure("nope")) }
        )

        assertEquals(AgentLoop.StopReason.REPEATED, result.reason)
        assertEquals(2, result.turns)
        assertEquals(1, toolBatches)
    }

    @Test
    fun givenATurnCoEmittingRespondAndARealTool_whenTheLoopRuns_thenTheRealToolStillRuns() = runTest {
        // `respond` co-emitted with a real tool must not drop the real tool.
        val model = ScriptedModel(
            listOf(
                """<tool_call>{"tool":"open_file","args":{"file_path":"MainActivity.java"}}</tool_call>""" +
                    """<tool_call>{"tool":"respond","args":{"message":"Opening it."}}</tool_call>""",
                "Done."
            )
        )
        val history = mutableListOf(ChatMessage(Role.USER, "open MainActivity"))
        val executed = mutableListOf<List<ToolCall>>()

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { calls ->
                executed += calls
                listOf(ToolResult.success("Opened file in editor", "path/MainActivity.java"))
            }
        )

        assertTrue(result.completed)
        assertEquals(1, executed.size)
        assertEquals(listOf("open_file"), executed[0].map { it.name })  // respond dropped, open_file kept
    }

    @Test
    fun givenATurnWithOnlyTheTerminalTool_whenTheLoopRuns_thenItFinishesImmediately() = runTest {
        val model = ScriptedModel(
            listOf("""<tool_call>{"tool":"respond","args":{"message":"Hi!"}}</tool_call>""")
        )
        val history = mutableListOf(ChatMessage(Role.USER, "hello"))
        var toolsInvoked = 0
        var finalMessage: String? = null

        val result = AgentLoop(terminalTool = "respond").run(
            history = history,
            generate = model::generate,
            executeTools = { toolsInvoked++; emptyList() },
            events = object : AgentLoop.Events {
                override suspend fun onFinalAnswer(turn: Int, message: String) { finalMessage = message }
            }
        )

        assertTrue(result.completed)
        assertEquals(1, result.turns)
        assertEquals(0, toolsInvoked)
        assertEquals("Hi!", finalMessage)
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

        val fedBack = history.first { it.role == Role.USER && it.content.startsWith("<tool_response>") }
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

        val fedBack = history.first { it.content.startsWith("<tool_response>") }
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
        // Must not append a trailing "Assistant:" cue (the backend adds its own).
        assertFalse("must not append a trailing Assistant cue", transcript.trimEnd().endsWith("Assistant:"))
    }
}
