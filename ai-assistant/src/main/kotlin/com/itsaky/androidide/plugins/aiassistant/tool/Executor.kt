package com.itsaky.androidide.plugins.aiassistant.tool

import android.util.Log
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.handlers.PathGuard
import com.itsaky.androidide.plugins.aiassistant.utils.ToolExecutionTracker
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
    private val TAG = "Executor"

    companion object {
        // Tools that only read state and can be executed in parallel safely
        private val PARALLEL_SAFE_TOOLS = setOf(
            "read_file",
            "list_files",
            "search_project",
            "get_current_datetime"
        )

        /**
         * Get required arguments for a tool.
         */
        fun requiredArgsForTool(toolName: String): List<String> {
            return when (toolName) {
                "read_file" -> listOf("file_path")
                "open_file" -> listOf("file_path")
                "list_files" -> emptyList()  // directory is optional, defaults to "."
                "search_project" -> listOf("query")
                "create_file" -> listOf("file_path", "content")
                "update_file" -> listOf("file_path", "content")
                else -> emptyList()
            }
        }
    }

    /**
     * Execute a list of tool calls.
     * Read-only tools are executed in parallel, write tools sequentially.
     */
    suspend fun execute(toolCalls: List<ToolCall>): List<ToolResult> = coroutineScope {
        Log.i(TAG, "Executing ${toolCalls.size} tool call(s)...")

        val results = arrayOfNulls<ToolResult>(toolCalls.size)

        // Read-only tools run concurrently.
        val parallelJobs = toolCalls.mapIndexedNotNull { index, call ->
            if (call.name in PARALLEL_SAFE_TOOLS) {
                async { results[index] = executeCall(call, "Parallel") }
            } else null
        }

        // Write tools run one at a time, in input order.
        toolCalls.forEachIndexed { index, call ->
            if (call.name !in PARALLEL_SAFE_TOOLS) {
                results[index] = executeCall(call, "Sequential")
            }
        }

        parallelJobs.awaitAll()
        results.requireNoNulls().toList()
    }

    /**
     * Execute a single tool call.
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

        // Check required arguments
        val missingArgs = requiredArgsForTool(toolName).filter { key ->
            val value = normalizedArgs[key]?.toString()?.trim().orEmpty()
            value.isBlank()
        }
        if (missingArgs.isNotEmpty()) {
            val message = "Missing required argument(s): ${missingArgs.joinToString(", ")}"
            Log.i(TAG, "($executionMode): Tool '$toolName' missing args: $missingArgs")
            return ToolResult.failure(message)
        }

        pathContainmentFailure(toolName, handler, normalizedArgs, executionMode)?.let { return it }

        // Check approval
        val approvalResponse = approvalManager.ensureApproved(toolName, handler, normalizedArgs)
        if (!approvalResponse.approved) {
            val message = approvalResponse.denialMessage ?: "Action denied by user."
            Log.i(TAG, "($executionMode): Tool '$toolName' denied. $message")
            return ToolResult.failure(message)
        }

        Log.d(TAG, "($executionMode): Dispatching '$toolName' with args: $normalizedArgs")

        // Before tool execution
        val toolStartTime = System.currentTimeMillis()

        val result = toolRouter.dispatch(toolName, normalizedArgs)

        // After tool execution
        val toolDuration = System.currentTimeMillis() - toolStartTime
        toolExecutionTracker?.logToolCall(toolName, toolDuration)

        Log.i(TAG, "($executionMode): Result: ${result.toResultMap()}")
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
