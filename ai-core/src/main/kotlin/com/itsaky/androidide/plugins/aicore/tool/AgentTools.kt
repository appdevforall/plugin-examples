package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.tool.sources.ContributedToolHandler
import com.itsaky.androidide.plugins.aicore.tool.sources.ToolSourceStore

/**
 * One immutable snapshot of the agent's tool set: the router, the executor built from it, and the
 * grammar derived from it.
 *
 * They are swapped together, behind a single reference, because a rebuild that replaced the router
 * without the grammar would leave a token mask that forbids every newly contributed tool — a green
 * build whose only symptom is "the model ignores the tools", visible on device alone.
 *
 * @property router routes a tool call to its handler.
 * @property executor runs a batch of calls against [router].
 * @property grammar the local backend's GBNF for exactly these tools.
 */
class AgentTools private constructor(
    val router: ToolRouter,
    val executor: Executor,
    val grammar: String,
) {

    /** The handlers contributed by plugins, as opposed to this plugin's own. */
    val contributedHandlers: List<ContributedToolHandler> =
        router.getAllHandlers().filterIsInstance<ContributedToolHandler>()

    companion object {

        /**
         * Builds a tool set from the built-in handlers plus everything currently contributed.
         *
         * Built-ins are reserved first, so a source can never shadow one. An in-flight run holds
         * its own snapshot and finishes against the tool set it started with — that is deliberate,
         * not a leak: swapping a run's executor mid-batch is what would need explaining.
         *
         * @param builtInHandlers this plugin's own handlers.
         * @param store the registered tool sources.
         * @param approvalManager the approval gate the executor consults.
         * @param toolExecutionTracker optional per-tool timing sink.
         * @param terminalTool the tool the model answers with; reserved but not routed.
         * @return the new snapshot.
         */
        fun build(
            builtInHandlers: List<ToolHandler>,
            store: ToolSourceStore,
            approvalManager: ToolApprovalManager,
            toolExecutionTracker: ToolExecutionTracker? = null,
            terminalTool: String,
        ): AgentTools {
            val reserved = builtInHandlers.mapTo(mutableSetOf()) { it.toolName }
            reserved += terminalTool

            val handlers = builtInHandlers + store.handlers(reserved)
            val router = ToolRouter(handlers)
            return AgentTools(
                router = router,
                executor = Executor(router, approvalManager, toolExecutionTracker),
                grammar = ToolCallGrammar.build(handlers.map { it.toolName } + terminalTool),
            )
        }
    }
}
