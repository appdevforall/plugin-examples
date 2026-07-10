package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler

/**
 * Handler for creating new files.
 */
class CreateFileHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "create_file"
    override val description = "Create a new file with given content"
    override val requiresApproval = true  // Requires approval for file creation

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        var filePath = args["file_path"]?.toString()?.trim()
        val content = args["content"]?.toString() ?: ""

        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path is required")
        }

        return try {
            Log.d("CreateFileHandler", "Creating file: $filePath")

            // Security: resolve against the project root and reject any escape.
            val file = PathGuard.resolveWithin(filePath)
                ?: return ToolResult.failure("File path must be within project directory")

            Log.d("CreateFileHandler", "Resolved path: ${file.absolutePath}")

            if (file.exists()) {
                Log.w("CreateFileHandler", "File already exists: ${file.absolutePath}")
                return ToolResult.failure("File already exists: $filePath")
            }

            // Create parent directories if needed
            val parentDir = file.parentFile
            if (parentDir != null && !parentDir.exists()) {
                Log.d("CreateFileHandler", "Creating parent directories: ${parentDir.absolutePath}")
                val created = parentDir.mkdirs()
                Log.d("CreateFileHandler", "Parent directories creation result: $created")
            }

            // Write content
            Log.d("CreateFileHandler", "Writing ${content.length} characters to file")
            file.writeText(content)

            Log.d("CreateFileHandler", "File created successfully: ${file.absolutePath}")

            ToolResult.success(
                message = "Created file: $filePath (${content.length} characters)",
                data = file.absolutePath
            )
        } catch (e: Exception) {
            Log.e("CreateFileHandler", "Error creating file at $filePath", e)
            ToolResult.failure(
                "Error creating file: ${e.message}",
                "Path: $filePath\nError: ${e.stackTraceToString()}"
            )
        }
    }
}
