package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import java.io.File

/**
 * Handler for reading file contents.
 */
class ReadFileHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "read_file"
    override val description = "Read the contents of a file"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filePath = args["file_path"]?.toString()?.trim()
        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path is required")
        }

        return try {
            val file = File(filePath)
            if (!file.exists()) {
                ToolResult.failure("File does not exist: $filePath")
            } else if (!file.isFile) {
                ToolResult.failure("Path is not a file: $filePath")
            } else if (!file.canRead()) {
                ToolResult.failure("Cannot read file: $filePath")
            } else {
                val content = file.readText()
                ToolResult.success(
                    message = "Read ${content.length} characters from $filePath",
                    data = content
                )
            }
        } catch (e: Exception) {
            Log.e("ReadFileHandler", "Error reading file", e)
            ToolResult.failure("Error reading file: ${e.message}", e.stackTraceToString())
        }
    }
}
