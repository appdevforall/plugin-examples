package com.itsaky.androidide.plugins.aicore.tool.sources

import com.itsaky.androidide.plugins.aicore.tool.ToolHandler
import com.itsaky.androidide.plugins.services.LlmInferenceService

/**
 * Decides how much of the contributed tool list is allowed into the system prompt.
 *
 * Every backend renders the tool list itself, so the cap has to happen once here, before the list
 * crosses the plugin boundary — including into backends nobody here has written. One MCP server can
 * advertise ninety tools and exhaust a phone-sized context window on its own.
 */
object PromptToolBudget {

    /** Contributed tools admitted into the prompt. Built-ins are never counted or dropped. */
    const val MAX_CONTRIBUTED_TOOLS = 12

    /** Per-description cap, applied to contributed descriptions only. */
    const val MAX_DESCRIPTION_CHARS = 200

    /**
     * The tool list as the prompt will see it.
     * @property definitions what to hand the backend.
     * @property droppedTools names left out by [MAX_CONTRIBUTED_TOOLS], for the caller to log.
     * @property truncatedDescriptions how many descriptions were shortened.
     */
    data class Budgeted(
        val definitions: List<LlmInferenceService.ToolDefinition>,
        val droppedTools: List<String>,
        val truncatedDescriptions: Int,
    )

    /**
     * Applies the budget to a tool set.
     *
     * Contributed descriptions are also flattened to a single line: they are provider-supplied text
     * that lands verbatim in a system prompt assembled inside a third-party plugin, where a
     * newline is enough to forge structure.
     *
     * @param handlers the registered handlers, built-in and contributed.
     * @return the definitions to send plus what the budget cost.
     */
    fun apply(handlers: List<ToolHandler>): Budgeted {
        val definitions = mutableListOf<LlmInferenceService.ToolDefinition>()
        val dropped = mutableListOf<String>()
        var admitted = 0
        var truncated = 0

        for (handler in handlers) {
            if (handler !is ContributedToolHandler) {
                definitions += LlmInferenceService.ToolDefinition(
                    handler.toolName,
                    handler.description,
                    handler.parametersSchema,
                )
                continue
            }

            if (admitted >= MAX_CONTRIBUTED_TOOLS) {
                dropped += handler.toolName
                continue
            }
            admitted++

            val flattened = flatten(handler.description)
            val description = if (flattened.length > MAX_DESCRIPTION_CHARS) {
                truncated++
                flattened.take(MAX_DESCRIPTION_CHARS).trimEnd() + "…"
            } else {
                flattened
            }
            definitions += LlmInferenceService.ToolDefinition(
                handler.toolName,
                description,
                handler.parametersSchema,
            )
        }

        return Budgeted(definitions, dropped, truncated)
    }

    /** Collapses whitespace and control characters onto one line. */
    private fun flatten(description: String): String = description
        .map { if (it.isWhitespace() || it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(Regex(" +"), " ")
        .trim()
}
