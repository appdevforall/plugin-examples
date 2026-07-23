package com.itsaky.androidide.plugins.aiassistant.tool

import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage
import com.itsaky.androidide.plugins.services.LlmInferenceService.ChatMessage.Role

/**
 * The agentic tool-loop: each turn renders the transcript into a prompt, generates a
 * reply, and runs any tool calls, looping until the model stops or a limit is hit.
 * Free of Android/coroutine/UI deps so it unit-tests with plain fakes.
 */
class AgentLoop(
    private val maxIterations: Int = DEFAULT_MAX_ITERATIONS,
    private val toolOutputCharLimit: Int = DEFAULT_TOOL_OUTPUT_CHAR_LIMIT,
    private val maxConsecutiveRepeats: Int = DEFAULT_MAX_CONSECUTIVE_REPEATS,
    private val extractToolCalls: (String) -> List<ToolCall> = ToolCallExtractor::extractToolCalls,
    private val terminalTool: String? = null,
) {

    companion object {
        /** Max model turns per user message; a backstop against a model that never stops calling tools. */
        const val DEFAULT_MAX_ITERATIONS = 8

        /** Per-tool-result cap fed back into the prompt, so big outputs don't blow a local model's context. */
        const val DEFAULT_TOOL_OUTPUT_CHAR_LIMIT = 4000

        /**
         * Consecutive identical tool-call batches tolerated before aborting as
         * [StopReason.REPEATED]; a truncated result can make one repeat legitimate.
         */
        const val DEFAULT_MAX_CONSECUTIVE_REPEATS = 2
    }

    /** Callbacks so the caller can drive UI/state; all no-ops by default. */
    interface Events {
        /**
         * A model turn finished.
         * @param turn 1-based turn index.
         * @param text the model's reply, already streamed into the UI.
         */
        suspend fun onModelTurn(turn: Int, text: String) {}

        /**
         * A tool batch was executed; the caller renders it.
         * @param turn 1-based turn index.
         * @param calls the tool calls that ran.
         * @param results their results, positionally aligned with [calls].
         */
        suspend fun onToolResults(turn: Int, calls: List<ToolCall>, results: List<ToolResult>) {}

        /**
         * The loop stopped after hitting the iteration cap while still calling tools.
         * @param turns total turns run.
         */
        suspend fun onMaxIterationsReached(turns: Int) {}

        /**
         * The loop stopped after the model repeated identical tool calls.
         * @param turns total turns run.
         */
        suspend fun onRepeatedToolCalls(turns: Int) {}

        /**
         * The model called the terminal tool to finish.
         * @param turn 1-based turn index.
         * @param message the model's final answer.
         */
        suspend fun onFinalAnswer(turn: Int, message: String) {}
    }

    /** Why the loop stopped. */
    enum class StopReason { COMPLETED, MAX_ITERATIONS, REPEATED }

    /**
     * Outcome of a run; [completed] is true when the model ended on its own.
     * @property turns model turns executed.
     * @property reason why the loop stopped.
     */
    data class Result(val turns: Int, val reason: StopReason) {
        val completed: Boolean get() = reason == StopReason.COMPLETED
    }

    /**
     * Runs the tool loop until the model stops calling tools or a limit is hit.
     * @param history transcript, mutated in place; seed it with the user message.
     * @param generate renders one model turn from a prompt.
     * @param executeTools runs a batch of tool calls.
     * @param events UI/state callbacks.
     * @return the run [Result].
     */
    suspend fun run(
        history: MutableList<ChatMessage>,
        generate: suspend (prompt: String) -> String,
        executeTools: suspend (List<ToolCall>) -> List<ToolResult>,
        events: Events = object : Events {},
    ): Result {
        var turn = 0
        var previousSignature: String? = null
        var consecutiveRepeats = 0
        while (turn < maxIterations) {
            turn++

            val text = generate(renderTranscript(history))
            history.add(ChatMessage(Role.ASSISTANT, text))
            events.onModelTurn(turn, text)

            val calls = extractToolCalls(text)
            if (calls.isEmpty()) {
                return Result(turn, StopReason.COMPLETED)
            }

            // Terminal tool alone ends the loop; if co-emitted with real tools, run those first.
            val realCalls = terminalTool?.let { tt -> calls.filterNot { it.name == tt } } ?: calls
            terminalTool?.let { tt ->
                val terminal = calls.firstOrNull { it.name == tt }
                if (terminal != null && realCalls.isEmpty()) {
                    events.onFinalAnswer(turn, terminal.args["message"]?.toString().orEmpty())
                    return Result(turn, StopReason.COMPLETED)
                }
            }

            // Stop if the model repeats identical tool calls beyond the tolerated count.
            val signature = signatureOf(realCalls)
            if (signature == previousSignature) {
                consecutiveRepeats++
                if (consecutiveRepeats >= maxConsecutiveRepeats) {
                    events.onRepeatedToolCalls(turn)
                    return Result(turn, StopReason.REPEATED)
                }
            } else {
                consecutiveRepeats = 0
            }
            previousSignature = signature

            val results = executeTools(realCalls)
            events.onToolResults(turn, realCalls, results)
            history.add(ChatMessage(Role.USER, formatToolResults(realCalls, results)))
        }

        events.onMaxIterationsReached(turn)
        return Result(turn, StopReason.MAX_ITERATIONS)
    }

    /**
     * Builds a stable, order-sensitive fingerprint of a tool-call batch (name + args).
     * @param calls the batch to fingerprint.
     * @return the fingerprint string.
     */
    private fun signatureOf(calls: List<ToolCall>): String =
        calls.joinToString("|") { call ->
            call.name + "(" + call.args.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" } + ")"
        }

    /**
     * Flattens the transcript into one prompt string, with no trailing "Assistant:" cue
     * (the backend appends its own; a doubled cue makes local models repeat).
     * @param history the conversation so far.
     * @return the rendered prompt.
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

    /**
     * Renders tool results for feeding back into the next prompt, capping each body.
     * @param calls the tool calls that ran.
     * @param results their results, positionally aligned with [calls].
     * @return the formatted results block.
     */
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
