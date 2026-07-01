package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import java.io.File

/**
 * Handler for updating existing files.
 */
class UpdateFileHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "update_file"
    override val description = "Update an existing file with new content"
    override val requiresApproval = true  // Requires approval for file modification

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filePath = args["file_path"]?.toString()?.trim()
        val content = args["content"]?.toString() ?: ""

        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path is required")
        }

        return try {
            val file = File(filePath)
            if (!file.exists()) {
                ToolResult.failure("File does not exist: $filePath")
            } else if (!file.isFile) {
                ToolResult.failure("Path is not a file: $filePath")
            } else {
                // Backup existing content
                val backup = file.readText()

                // Write new content
                file.writeText(content)

                ToolResult.success(
                    message = "Updated file: $filePath (${content.length} characters)",
                    data = filePath
                )
            }
        } catch (e: Exception) {
            Log.e("UpdateFileHandler", "Error updating file", e)
            ToolResult.failure("Error updating file: ${e.message}", e.stackTraceToString())
        }
    }
}
