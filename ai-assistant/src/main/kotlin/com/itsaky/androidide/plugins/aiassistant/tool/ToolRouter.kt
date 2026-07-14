package com.itsaky.androidide.plugins.aiassistant.tool

import android.util.Log
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult

/**
 * Routes tool calls to appropriate handlers.
 */
class ToolRouter(
    private val handlers: List<ToolHandler>
) {
    private val TAG = "ToolRouter"
    private val handlerMap: Map<String, ToolHandler> = handlers.associateBy { it.toolName }

    /**
     * Get the handler for a given tool name.
     */
    fun getHandler(toolName: String): ToolHandler? {
        return handlerMap[toolName]
    }

    /**
     * Dispatch a tool call to its handler.
     */
    suspend fun dispatch(toolName: String, args: Map<String, Any?>): ToolResult {
        val handler = getHandler(toolName)
        if (handler == null) {
            Log.e(TAG, "No handler found for tool: $toolName")
            return ToolResult.failure("Unknown tool: $toolName")
        }

        return try {
            Log.d(TAG, "Dispatching $toolName with args: $args")
            handler.execute(args)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing tool $toolName", e)
            ToolResult.failure("Error executing $toolName: ${e.message}", e.stackTraceToString())
        }
    }

    /**
     * Get all available tool names.
     */
    fun getAvailableTools(): List<String> {
        return handlerMap.keys.toList()
    }

    /**
     * Get all registered tool handlers.
     */
    fun getAllHandlers(): List<ToolHandler> {
        return handlers
    }
}
