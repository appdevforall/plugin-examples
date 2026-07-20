package com.itsaky.androidide.plugins.aiassistant.tool

import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role

/**
 * The agentic tool-loop.
 *
 * Text-based tool calling has no "tool" message role and no streaming+history
 * API, so the loop keeps its own transcript and re-renders it into the prompt
 * each turn — feeding tool results back (the edge that had made the agent
 * single-shot) and giving it cross-turn memory.
 *
 * Each turn: render transcript → prompt, [generate] a reply, extract tool calls;
 * if none, stop; otherwise run the tools, append their results, and loop.
 *
 * Free of Android/coroutine/UI deps so it unit-tests with plain fakes;
 * [ChatViewModel] injects the real streaming generation and tool executor.
 */
class AgentLoop(
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val toolOutputCharLimit: Int = DEFAULT_TOOL_OUTPUT_CHAR_LIMIT,
    private val extractToolCalls: (String) -> List<ToolCall> = ToolCallExtractor::extractToolCalls,
    private val terminalTool: String? = null,
) {

    companion object {
        /** Max model turns per user message; a backstop against a model that never stops calling tools. */
        const val DEFAULT_MAX_ITERATIONS = 8

        /** Per-tool-result cap fed back into the prompt, so big outputs don't blow a local model's context. */
        const val DEFAULT_TOOL_OUTPUT_CHAR_LIMIT = 4000
    }

    /** Callbacks so the caller can drive UI/state; all no-ops by default. */
    interface Events {
        /** A model turn finished with [text] (already streamed into the UI by the generator). */
        suspend fun onModelTurn(turn: Int, text: String) {}

        /** [calls] were executed with [results]; the caller renders them in the UI. */
        suspend fun onToolResults(turn: Int, calls: List<ToolCall>, results: List<ToolResult>) {}

        /** The loop stopped because it hit [maxIterations] while still calling tools. */
        suspend fun onMaxIterationsReached(turns: Int) {}

        /** The loop stopped because the model asked for the exact same tool calls twice in a row. */
        suspend fun onRepeatedToolCalls(turns: Int) {}

        /** The model called the terminal tool to finish; [message] is its final answer. */
        suspend fun onFinalAnswer(turn: Int, message: String) {}
    }

    /** Why the loop stopped. */
    enum class StopReason { COMPLETED, MAX_ITERATIONS, REPEATED }

    /** Outcome of a run. [completed] is true when the model ended on its own (no more tool calls). */
    data class Result(val turns: Int, val reason: StopReason) {
        val completed: Boolean get() = reason == StopReason.COMPLETED
    }

    /**
     * Run the loop against [history] (mutated in place; seed it with the user
     * message). [generate] renders one model turn; [executeTools] runs a tool batch.
     */
    suspend fun run(
        history: MutableList<ChatMessage>,
        generate: suspend (prompt: String) -> String,
        executeTools: suspend (List<ToolCall>) -> List<ToolResult>,
        events: Events = object : Events {},
    ): Result {
        var turn = 0
        var previousSignature: String? = null
        while (turn < maxIterations) {
            turn++

            val text = generate(renderTranscript(history))
            history.add(ChatMessage(Role.ASSISTANT, text))
            events.onModelTurn(turn, text)

            val calls = extractToolCalls(text)
            if (calls.isEmpty()) {
                return Result(turn, StopReason.COMPLETED)
            }

            // Terminal tool: the model signals it's finished by calling it (with a
            // `message`). Emit that as the final answer and stop — don't dispatch
            // it as a real tool (there's no handler for it).
            terminalTool?.let { tt ->
                calls.firstOrNull { it.name == tt }?.let { done ->
                    events.onFinalAnswer(turn, done.args["message"]?.toString().orEmpty())
                    return Result(turn, StopReason.COMPLETED)
                }
            }

            // Stuck-detection: a weak model can loop by re-requesting the exact
            // same tool calls forever. If it does, stop instead of spinning.
            val signature = signatureOf(calls)
            if (signature == previousSignature) {
                events.onRepeatedToolCalls(turn)
                return Result(turn, StopReason.REPEATED)
            }
            previousSignature = signature

            val results = executeTools(calls)
            events.onToolResults(turn, calls, results)
            history.add(ChatMessage(Role.USER, formatToolResults(calls, results)))
        }

        events.onMaxIterationsReached(turn)
        return Result(turn, StopReason.MAX_ITERATIONS)
    }

    /** A stable fingerprint of a batch of tool calls (name + args), order-sensitive. */
    private fun signatureOf(calls: List<ToolCall>): String =
        calls.joinToString("|") { call ->
            call.name + "(" + call.args.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" } + ")"
        }

    /**
     * Flatten the transcript into a single prompt string.
     *
     * The backend appends its own trailing "Assistant:" cue, so we must not add
     * one (a doubled "Assistant:" made local models degenerate into repetition).
     * We render only the body: assistant turns are labelled, tool-result turns
     * already carry their own prefix, and there is no trailing cue.
     */
    fun renderTranscript(history: List<ChatMessage>): String {
        val sb = StringBuilder()
        for ((index, message) in history.withIndex()) {
            if (index > 0) sb.append("\n\n")
            when (message.role) {
                Role.ASSISTANT -> sb.append("Assistant: ").append(message.content)
                else -> sb.append(message.content)
            }
        }
        return sb.toString()
    }

    /** Render tool results for feeding back into the next prompt, capping each body. */
    fun formatToolResults(calls: List<ToolCall>, results: List<ToolResult>): String {
        val sb = StringBuilder("Tool results:\n")
        results.forEachIndexed { index, result ->
            val name = calls.getOrNull(index)?.name ?: "tool"
            val body = if (result.success) {
                buildString {
                    append(result.message)
                    result.data?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                }
            } else {
                buildString {
                    append("FAILED: ").append(result.message)
                    result.error_details?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                }
            }
            sb.append("[").append(name).append("] ").append(truncate(body)).append("\n\n")
        }
        sb.append(
            "Base your reply strictly on the tool result(s) above — report only what they actually say; " +
                "do not invent, assume, or contradict them. "
        )
        if (results.isNotEmpty() && results.all { it.success }) {
            sb.append(
                "The action succeeded. If this satisfies the user's request, you are DONE — reply with the " +
                    "\"respond\" tool briefly confirming what happened. Do NOT call another tool unless the " +
                    "request clearly needs a further step."
            )
        } else {
            sb.append("If the task is complete, give the user your final answer. Otherwise, call the next tool.")
        }
        return sb.toString()
    }

    private fun truncate(text: String): String =
        if (text.length <= toolOutputCharLimit) text
        else text.take(toolOutputCharLimit) + "\n…[truncated ${text.length - toolOutputCharLimit} chars]"
}
