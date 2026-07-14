package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import com.itsaky.androidide.plugins.services.IdeBuildService

/**
 * Handler for reading the current build output.
 */
class ReadBuildOutputHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "read_build_output"
    override val description = "Read the current build output and status"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        Log.d("ReadBuildOutputHandler", "Reading build output")

        return try {
            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            if (buildService == null) {
                Log.w("ReadBuildOutputHandler", "IdeBuildService not available")
                return ToolResult.failure(
                    "Build service not available",
                    "The IDE build service is not available."
                )
            }

            val output = buildService.getBuildOutput()
            if (output.isNullOrBlank()) {
                Log.d("ReadBuildOutputHandler", "No build output available")
                ToolResult.success(
                    message = "No build output available",
                    data = "(No recent build output)"
                )
            } else {
                // Truncate to last 2000 chars to avoid overwhelming the LLM
                val truncated = if (output.length > 2000) {
                    "...[truncated]...\n" + output.takeLast(2000)
                } else {
                    output
                }

                Log.d("ReadBuildOutputHandler", "Read ${truncated.length} chars of build output")
                ToolResult.success(
                    message = "Build output (last ${truncated.length} characters)",
                    data = truncated
                )
            }
        } catch (e: Exception) {
            Log.e("ReadBuildOutputHandler", "Error reading build output", e)
            ToolResult.failure(
                "Error reading build output",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
