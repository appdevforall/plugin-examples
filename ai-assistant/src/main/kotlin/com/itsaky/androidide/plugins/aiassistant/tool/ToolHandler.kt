package com.itsaky.androidide.plugins.aiassistant.tool

import com.itsaky.androidide.plugins.aiassistant.models.ToolResult

/**
 * Interface for handling tool execution.
 */
interface ToolHandler {
    /**
     * The unique name of this tool.
     */
    val toolName: String

    /**
     * Description of what this tool does.
     */
    val description: String

    /**
     * Execute the tool with the given arguments.
     *
     * @param args Map of argument names to values
     * @return ToolResult indicating success or failure
     */
    suspend fun execute(args: Map<String, Any?>): ToolResult

    /**
     * Whether this tool requires user approval before execution.
     */
    val requiresApproval: Boolean
        get() = false

    /**
     * Arg keys whose values are filesystem paths. The Executor verifies each of
     * these resolves within the project root before the tool runs, so no handler
     * can forget the containment check. Empty by default (non-filesystem tools).
     */
    val pathArgs: List<String>
        get() = emptyList()

    val resolvesPathsInternally: Boolean
        get() = false
}
