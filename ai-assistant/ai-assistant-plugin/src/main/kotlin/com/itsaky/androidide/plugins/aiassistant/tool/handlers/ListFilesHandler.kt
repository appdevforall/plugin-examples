package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import java.io.File

/**
 * Handler for listing files in a directory.
 */
class ListFilesHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "list_files"
    override val description = "List files and directories in a given path"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val directory = args["directory"]?.toString()?.trim()
        if (directory.isNullOrBlank()) {
            return ToolResult.failure("directory is required")
        }

        return try {
            val dir = File(directory)
            if (!dir.exists()) {
                ToolResult.failure("Directory does not exist: $directory")
            } else if (!dir.isDirectory) {
                ToolResult.failure("Path is not a directory: $directory")
            } else {
                val files = dir.listFiles()?.map { file ->
                    val prefix = if (file.isDirectory) "[DIR] " else "[FILE] "
                    prefix + file.name
                }?.sorted() ?: emptyList()

                ToolResult.success(
                    message = "Found ${files.size} items in $directory",
                    data = files.joinToString("\n")
                )
            }
        } catch (e: Exception) {
            Log.e("ListFilesHandler", "Error listing files", e)
            ToolResult.failure("Error listing files: ${e.message}", e.stackTraceToString())
        }
    }
}
