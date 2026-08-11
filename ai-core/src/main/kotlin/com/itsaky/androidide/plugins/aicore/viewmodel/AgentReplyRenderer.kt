package com.itsaky.androidide.plugins.aicore.viewmodel

import com.itsaky.androidide.plugins.aicore.tool.ToolCall
import com.itsaky.androidide.plugins.aicore.tool.ToolCallExtractor
import com.itsaky.androidide.plugins.aicore.tool.respondMessageOf

/**
 * Decides what a model turn looks like in the transcript. Pure and string-injected so the precedence
 * is testable: it exists to stop a finished answer becoming "(no response)" because the model filed
 * it under an odd key, or wrote it as prose beside an empty `respond` envelope.
 */
object AgentReplyRenderer {

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
        val respondCall = toolCalls.firstOrNull { it.name == terminalTool }
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
