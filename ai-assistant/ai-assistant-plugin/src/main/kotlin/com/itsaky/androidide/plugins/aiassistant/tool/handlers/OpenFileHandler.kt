package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import com.itsaky.androidide.plugins.services.IdeEditorService

/**
 * Handler for opening files in the IDE editor.
 */
class OpenFileHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "open_file"
    override val description = "Open a file in the IDE editor"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filePath = args["file_path"]?.toString()?.trim()
        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path is required")
        }

        Log.d("OpenFileHandler", "Opening file: $filePath")

        return try {
            val file = PathGuard.resolveWithin(filePath)
                ?: return ToolResult.failure("File path must be within project directory")
            if (!file.exists()) {
                Log.w("OpenFileHandler", "File does not exist: $filePath")
                return ToolResult.failure(
                    "File not found",
                    "File does not exist: $filePath"
                )
            }

            if (!file.isFile) {
                Log.w("OpenFileHandler", "Path is not a file: $filePath")
                return ToolResult.failure(
                    "Not a file",
                    "Path is a directory, not a file: $filePath"
                )
            }

            val editorService = pluginContext.services.get(IdeEditorService::class.java)
            if (editorService == null) {
                Log.w("OpenFileHandler", "IdeEditorService not available")
                return ToolResult.failure(
                    "Editor service not available",
                    "The IDE editor service is not available."
                )
            }

            val success = editorService.openFile(file)
            if (success) {
                Log.d("OpenFileHandler", "File opened successfully: $filePath")
                ToolResult.success(
                    message = "Opened file in editor",
                    data = filePath
                )
            } else {
                Log.w("OpenFileHandler", "Failed to open file: $filePath")
                ToolResult.failure(
                    "Failed to open file",
                    "Could not open $filePath in the editor."
                )
            }
        } catch (e: Exception) {
            Log.e("OpenFileHandler", "Error opening file", e)
            ToolResult.failure(
                "Error opening file",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
