package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import java.io.File

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
        val filePath = args["file_path"]?.toString()?.trim()
        val content = args["content"]?.toString() ?: ""

        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path is required")
        }

        return try {
            val file = File(filePath)
            if (file.exists()) {
                ToolResult.failure("File already exists: $filePath")
            } else {
                // Create parent directories if needed
                file.parentFile?.mkdirs()

                // Write content
                file.writeText(content)

                ToolResult.success(
                    message = "Created file: $filePath (${content.length} characters)",
                    data = filePath
                )
            }
        } catch (e: Exception) {
            Log.e("CreateFileHandler", "Error creating file", e)
            ToolResult.failure("Error creating file: ${e.message}", e.stackTraceToString())
        }
    }
}
