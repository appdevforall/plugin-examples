package com.itsaky.androidide.plugins.aicore.tool

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aicore.models.ToolResult
import com.itsaky.androidide.plugins.aicore.tool.ToolExecutionTracker
import com.itsaky.androidide.plugins.aicore.tool.handlers.PathGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Executes tool calls, handling parallel vs sequential execution.
 */
class Executor(
    private val toolRouter: ToolRouter,
    private val approvalManager: ToolApprovalManager,
    private val toolExecutionTracker: ToolExecutionTracker? = null
) {
    private val TAG = "$LOG_PREFIX.Executor"

    companion object {
        // Tools that only read state and can be executed in parallel safely
        private val PARALLEL_SAFE_TOOLS = setOf(
            "read_file",
            "list_files",
            "search_project",
            "get_current_datetime"
        )

        /**
         * Arguments a tool cannot run without.
         * @param toolName the tool being dispatched.
         * @return the required argument names, empty when the tool has none.
         */
        fun requiredArgsForTool(toolName: String): List<String> {
            return when (toolName) {
                "read_file" -> listOf("file_path")
                "open_file" -> listOf("file_path")
                "list_files" -> emptyList()  // directory is optional, defaults to "."
                "search_project" -> listOf("query")
                "create_file" -> listOf("file_path", "content")
                "update_file" -> listOf("file_path", "content")
                // new_string omitted: empty is a legal edit (deletion), so EditFileHandler checks it.
                "edit_file" -> listOf("file_path", "old_string")
                else -> emptyList()
            }
        }

        /**
         * Required args holding verbatim file text, where whitespace is content: checked for
         * emptiness rather than blankness, since `old_string = "    "` is a legal edit and
         * rejecting it would contradict `EditFileHandler.validate`.
         */
        private val VERBATIM_TEXT_ARGS = setOf("old_string")

        /**
         * Whether [value] fails the required-argument check for [key].
         * @param key the required argument's name.
         * @param value the supplied value, or null when absent.
         * @return true when the argument must be reported missing.
         */
        private fun isMissing(key: String, value: Any?): Boolean {
            val text = value?.toString()
            return if (key in VERBATIM_TEXT_ARGS) text.isNullOrEmpty() else text?.trim().isNullOrEmpty()
        }
    }

    /**
     * Executes a batch of tool calls, treating its order as a dependency graph: consecutive
     * read-only calls ([PARALLEL_SAFE_TOOLS]) run concurrently, every write runs alone between such
     * runs. Hoisting all reads broke `create_file` + `read_file`; mixing broke `search` + `edit`.
     * @param toolCalls the calls to run, in the order the model emitted them.
     * @return one result per call, positionally aligned with [toolCalls].
     */
    suspend fun execute(toolCalls: List<ToolCall>): List<ToolResult> = coroutineScope {
        Log.i(TAG, "Executing ${toolCalls.size} tool call(s)...")

        val results = arrayOfNulls<ToolResult>(toolCalls.size)

        var index = 0
        while (index < toolCalls.size) {
            if (toolCalls[index].name !in PARALLEL_SAFE_TOOLS) {
                results[index] = executeCall(toolCalls[index], "Sequential")
                index++
                continue
            }
            // Extend over every read-only call that follows, stopping at the first write.
            var end = index
            while (end < toolCalls.size && toolCalls[end].name in PARALLEL_SAFE_TOOLS) end++
            (index until end).map { i ->
                async { results[i] = executeCall(toolCalls[i], "Parallel") }
            }.awaitAll()
            index = end
        }

        results.requireNoNulls().toList()
    }

    /**
     * Runs one call end to end: alias remapping, required-argument and containment checks,
     * pre-approval validation, the approval gate, then dispatch.
     * @param call the tool call to run.
     * @param executionMode "Parallel"/"Sequential", for logging.
     * @return the tool's result, or the failure that stopped it short of running.
     */
    private suspend fun executeCall(call: ToolCall, executionMode: String): ToolResult {
        val toolName = call.name
        val args = call.args

        if (toolName.isBlank()) {
            Log.e(TAG, "($executionMode): Encountered unnamed function call.")
            return ToolResult.failure("Unnamed function call")
        }

        val handler = toolRouter.getHandler(toolName)
        if (handler == null) {
            Log.e(TAG, "($executionMode): Unknown function requested: $toolName")
            return ToolResult.failure("Unknown function '$toolName'")
        }

        // Alias "path" → "file_path" for any tool that requires "file_path".
        val normalizedArgs = args.toMutableMap()
        if ("file_path" in requiredArgsForTool(toolName) &&
            normalizedArgs.containsKey("path") && !normalizedArgs.containsKey("file_path")
        ) {
            normalizedArgs["path"]?.let { normalizedArgs["file_path"] = it }
            if (normalizedArgs.containsKey("file_path")) {
                Log.d(TAG, "($executionMode): Remapped 'path' → 'file_path' for $toolName tool")
            }
        }

        // Handler-declared aliases; a canonical key the model did supply always wins.
        handler.argAliases.forEach { (alias, canonical) ->
            if (!normalizedArgs.containsKey(canonical) && normalizedArgs.containsKey(alias)) {
                normalizedArgs[canonical] = normalizedArgs[alias]
                Log.d(TAG, "($executionMode): Remapped '$alias' → '$canonical' for $toolName tool")
                AgentTrace.detail("ARGS", "$toolName remapped $alias→$canonical")
            }
        }

        // Check required arguments
        val missingArgs = requiredArgsForTool(toolName).filter { key ->
            isMissing(key, normalizedArgs[key])
        }
        if (missingArgs.isNotEmpty()) {
            val message = "Missing required argument(s): ${missingArgs.joinToString(", ")}"
            Log.i(TAG, "($executionMode): Tool '$toolName' missing args: $missingArgs")
            AgentTrace.refusal("ARGS", "$toolName mode=$executionMode", message)
            return ToolResult.failure(message)
        }

        pathContainmentFailure(toolName, handler, normalizedArgs, executionMode)?.let {
            AgentTrace.refusal("GUARD", "$toolName containment", it.message)
            return it
        }

        // Rejects a doomed call before it costs a dialog; guarded since validate() can throw.
        val validation = try {
            handler.validate(normalizedArgs)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.e(TAG, "($executionMode): Pre-approval validation of '$toolName' threw", e)
            AgentTrace.refusal(
                "PRECHECK",
                "$toolName validation threw",
                e.message ?: e.javaClass.simpleName,
            )
            return ToolResult.failure("Error validating $toolName: ${e.message}", e.stackTraceToString())
        }

        val validatedArgs = when (validation) {
            is Validation.Rejected -> {
                val failure = validation.result
                Log.i(TAG, "($executionMode): Tool '$toolName' failed pre-approval validation: ${failure.message}")
                AgentTrace.refusal("PRECHECK", "$toolName rejected before approval", failure.message)
                return failure
            }
            is Validation.Accepted -> {
                // Model-supplied keys only: handler bookkeeping is not an "args corrected" event.
                if (normalizedArgs.any { (key, value) -> validation.args[key] != value }) {
                    AgentTrace.stage("PRECHECK", "$toolName args corrected", AgentTrace.previewArgs(validation.args))
                }
                validation.args
            }
        }

        // Check approval
        val approvalStart = System.currentTimeMillis()
        val approvalResponse = approvalManager.ensureApproved(toolName, handler, validatedArgs)
        val approvalMs = System.currentTimeMillis() - approvalStart
        if (!approvalResponse.approved) {
            val message = approvalResponse.denialMessage ?: "Action denied by user."
            Log.i(TAG, "($executionMode): Tool '$toolName' denied. $message")
            AgentTrace.stage("APPROVAL", "$toolName approved=false waitedMs=$approvalMs", AgentTrace.preview(message))
            return ToolResult.failure(message)
        }
        AgentTrace.stage("APPROVAL", "$toolName approved=true waitedMs=$approvalMs")

        Log.d(TAG, "($executionMode): Dispatching '$toolName' with args: $validatedArgs")
        AgentTrace.stage("EXEC", "$toolName mode=$executionMode", AgentTrace.previewArgs(validatedArgs))

        // Before tool execution
        val toolStartTime = System.currentTimeMillis()

        val result = toolRouter.dispatch(toolName, validatedArgs)

        // After tool execution
        val toolDuration = System.currentTimeMillis() - toolStartTime
        toolExecutionTracker?.logToolCall(toolName, toolDuration)

        Log.i(TAG, "($executionMode): Result: ${result.toResultMap()}")
        AgentTrace.stage(
            "EXEC",
            "$toolName done success=${result.success} tookMs=$toolDuration",
            AgentTrace.preview(result.message),
        )
        return result
    }

    /**
     * Confines model-supplied path args to the project root; handlers that resolve
     * paths themselves opt out via [ToolHandler.resolvesPathsInternally].
     * @param toolName the tool being dispatched (for logging).
     * @param handler the tool's handler, source of [ToolHandler.pathArgs].
     * @param args the normalized call arguments.
     * @param executionMode "Parallel"/"Sequential", for logging.
     * @return a failure [ToolResult] for the first escaping arg, or null if all are safe.
     */
    private fun pathContainmentFailure(
        toolName: String,
        handler: ToolHandler,
        args: Map<String, Any?>,
        executionMode: String,
    ): ToolResult? {
        if (handler.resolvesPathsInternally) return null
        for (key in handler.pathArgs) {
            val raw = args[key]?.toString()?.trim()
            if (raw.isNullOrEmpty()) continue
            if (PathGuard.resolveWithin(raw) != null) continue
            Log.w(TAG, "($executionMode): '$toolName' arg '$key' escapes project root: $raw")
            return ToolResult.failure("Path '$raw' is outside the project directory")
        }
        return null
    }
}

/**
 * Represents a tool call to be executed.
 */
data class ToolCall(
    val name: String,
    val args: Map<String, Any?>
)
