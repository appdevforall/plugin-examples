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
        // Accept both "file_path" (standardized) and "path" (legacy LLM responses)
        val filePath = (args["file_path"] ?: args["path"])?.toString()?.trim()
        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path argument is required (or 'path' as fallback)")
        }

        return try {
            // Get project root for containment check
            val projectRoot = System.getProperty("project.dir")
                ?: System.getProperty("user.dir")
                ?: "/storage/emulated/0/AndroidIDEProjects"
            val projectRootCanonical = File(projectRoot).canonicalPath

            // Resolve path against project root if relative
            val file = if (filePath.startsWith("/")) {
                File(filePath)
            } else {
                File(projectRoot, filePath)
            }

            // Security: Verify file is within project root
            val fileCanonical = file.canonicalPath
            if (!fileCanonical.startsWith(projectRootCanonical + File.separator) && fileCanonical != projectRootCanonical) {
                Log.e("ReadFileHandler", "Path escape attempt: $fileCanonical is outside project root $projectRootCanonical")
                return ToolResult.failure("File path must be within project directory")
            }

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
