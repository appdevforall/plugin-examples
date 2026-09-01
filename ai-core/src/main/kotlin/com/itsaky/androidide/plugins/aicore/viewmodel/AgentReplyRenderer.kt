package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolCallExtractor
import com.itsaky.androidide.plugins.aicore.tool.isTerminalToolName
import com.itsaky.androidide.plugins.aicore.tool.respondMessageOf

/**
 * Decides what a model turn looks like in the transcript. Pure and string-injected so the precedence
 * is testable: it exists to stop a finished answer becoming "(no response)" because the model filed
 * it under an odd key, or wrote it as prose beside an empty `respond` envelope.
 */
object AgentReplyRenderer {

    /**
     * Whether this turn only repeats the calls that already succeeded, so its bubble is dropped.
     *
     * The terminal call is filtered out by the same loose match a handler is routed by, not by
     * equality: a backend answering `Respond` would otherwise leave it among the real calls, so an
     * identical repeat never compares equal and the duplicate bubble is never dropped.
     *
     * @param toolCalls the calls parsed out of this turn.
     * @param lastSucceededCalls the calls this run last executed successfully, null when none did.
     * @param terminalTool the name of the answer-carrying pseudo-tool (`respond`).
     * @return true when the turn adds nothing and should not reach the transcript.
     */
    fun isDuplicateTurn(
        toolCalls: List<ToolCall>,
        lastSucceededCalls: List<ToolCall>?,
        terminalTool: String,
    ): Boolean {
        val realCalls = toolCalls.filterNot { isTerminalToolName(it.name, terminalTool) }
        return realCalls.isNotEmpty() && realCalls == lastSucceededCalls
    }

    /**
     * Renders one model turn.
     * @param rawText the model's raw reply.
     * @param toolCalls the calls parsed out of it.
     * @param terminalTool the name of the answer-carrying pseudo-tool (`respond`).
     * @param lastToolFailed whether this run's most recent tool call failed.
     * @param actionFailedText what to show when the model claims success after a failed tool.
     * @param noResponseText last-resort text when the turn carries nothing to show.
     * @param renderToolCall renders one tool call as a badge line.
     * @return the text to display for this turn.
     */
    fun render(
        rawText: String,
        toolCalls: List<ToolCall>,
        terminalTool: String,
        lastToolFailed: Boolean,
        actionFailedText: String,
        noResponseText: String,
        renderToolCall: (ToolCall) -> String,
    ): String {
        val respondCall = toolCalls.firstOrNull { isTerminalToolName(it.name, terminalTool) }
        return when {
            respondCall != null && lastToolFailed -> actionFailedText
            // The answer wherever the model put it, then the prose beside an empty envelope.
            respondCall != null ->
                respondMessageOf(respondCall.args)
                    ?: ToolCallExtractor.proseOutsideToolCalls(rawText)
                    ?: noResponseText
            toolCalls.isNotEmpty() -> toolCalls.joinToString("\n", transform = renderToolCall)
            else -> rawText.ifBlank { noResponseText }
        }
    }
}
