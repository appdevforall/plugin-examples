package com.itsaky.androidide.plugins.aiassistant.tool

import android.util.Log
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
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
                "list_files" -> listOf("directory")
                "search_project" -> listOf("query")
                "create_file" -> listOf("file_path", "content")
                "update_file" -> listOf("file_path", "content")
                "add_dependency" -> listOf("dependency_string")
                "add_string_resource" -> listOf("name", "value")
                "run_app" -> emptyList() // No required args
                "get_build_output" -> emptyList() // No required args
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

        val (parallelCalls, sequentialCalls) = toolCalls.partition { call ->
            PARALLEL_SAFE_TOOLS.contains(call.name)
        }

        // Execute parallel calls concurrently
        val parallelResults = parallelCalls.map { call ->
            async {
                executeCall(call, "Parallel")
            }
        }

        // Execute sequential calls one by one
        val sequentialResults = mutableListOf<ToolResult>()
        for (call in sequentialCalls) {
            sequentialResults.add(executeCall(call, "Sequential"))
        }

        sequentialResults + parallelResults.awaitAll()
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

        // Check required arguments
        val missingArgs = requiredArgsForTool(toolName).filter { key ->
            val value = args[key]?.toString()?.trim().orEmpty()
            value.isBlank()
        }
        if (missingArgs.isNotEmpty()) {
            val message = "Missing required argument(s): ${missingArgs.joinToString(", ")}"
            Log.i(TAG, "($executionMode): Tool '$toolName' missing args: $missingArgs")
            return ToolResult.failure(message)
        }

        // Check approval
        val approvalResponse = approvalManager.ensureApproved(toolName, handler, args)
        if (!approvalResponse.approved) {
            val message = approvalResponse.denialMessage ?: "Action denied by user."
            Log.i(TAG, "($executionMode): Tool '$toolName' denied. $message")
            return ToolResult.failure(message)
        }

        Log.d(TAG, "($executionMode): Dispatching '$toolName' with args: $args")

        // Before tool execution
        val toolStartTime = System.currentTimeMillis()

        val result = toolRouter.dispatch(toolName, args)

        // After tool execution
        val toolDuration = System.currentTimeMillis() - toolStartTime
        toolExecutionTracker?.logToolCall(toolName, toolDuration)

        Log.i(TAG, "($executionMode): Result: ${result.toResultMap()}")
        return result
    }
}

/**
 * Represents a tool call to be executed.
 */
data class ToolCall(
    val name: String,
    val args: Map<String, Any?>
)
