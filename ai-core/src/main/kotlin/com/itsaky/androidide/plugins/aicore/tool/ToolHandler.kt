package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult

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

    /**
     * Alternative argument names, alias → canonical. Small models reliably invent near-miss keys
     * ("old" for "old_string") and the grammar constrains none of them, so the choice is remapping
     * or burning a turn. Applied before the required-argument check; a supplied canonical key wins.
     */
    val argAliases: Map<String, String>
        get() = emptyMap()

    /**
     * Cheap, side-effect-free check run **before** the user is asked to approve the call, so no
     * dialog is spent on an edit that cannot succeed — which wastes the one moment of attention the
     * safety design rests on. Must not mutate: the call may still be denied, and [execute] re-checks.
     * @param args the normalized call arguments.
     * @return acceptance, carrying the arguments to run, or the failure to report instead.
     */
    suspend fun validate(args: Map<String, Any?>): Validation = Validation.Accepted(args)
}

/** Outcome of [ToolHandler.validate]. */
sealed interface Validation {
    /**
     * The call is applicable and should be approved and run.
     * @property args the arguments to actually use; a handler may return a corrected copy, and these
     *   are what the approval dialog shows, so the user reviews what will run.
     */
    data class Accepted(val args: Map<String, Any?>) : Validation

    /** The call cannot succeed; [result] goes back to the model and the user is never prompted. */
    data class Rejected(val result: ToolResult) : Validation
}
