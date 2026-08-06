package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import com.itsaky.androidide.plugins.services.IdeEditorService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handler for opening files in the IDE editor.
 *
 * @param mainDispatcher dispatcher for editor-UI calls; overridden in unit tests.
 */
class OpenFileHandler(
    private val pluginContext: PluginContext,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : ToolHandler {
    override val toolName = "open_file"
    override val description = "Open a file in the IDE editor"
    override val requiresApproval = false
    override val pathArgs = listOf("file_path")
    override val resolvesPathsInternally = true

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val filePath = args["file_path"]?.toString()?.trim()
        if (filePath.isNullOrBlank()) {
            return ToolResult.failure("file_path is required")
        }

        Log.d("OpenFileHandler", "Opening file: $filePath")

        return try {
            val file = when (val resolution = PathGuard.resolve(filePath)) {
                is PathGuard.Resolution.Resolved -> {
                    Log.d("OpenFileHandler", "Resolved '$filePath' -> ${resolution.file.path}")
                    resolution.file
                }
                is PathGuard.Resolution.Ambiguous -> {
                    val root = java.io.File(PathGuard.projectRoot())
                    return ToolResult.failure(
                        "Multiple files named '${resolution.baseName}' — specify a path",
                        resolution.matches.joinToString("\n") { it.relativeToOrSelf(root).path }
                    )
                }
                PathGuard.Resolution.Escaped -> {
                    Log.w("OpenFileHandler", "Path outside project and no match found: $filePath")
                    return ToolResult.failure("File path must be within project directory")
                }
                PathGuard.Resolution.NotFound -> {
                    Log.w("OpenFileHandler", "File does not exist: $filePath")
                    return ToolResult.failure("File not found", "File does not exist: $filePath")
                }
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

            // Opening a tab touches UI; execute() runs on Dispatchers.IO.
            val success = withContext(mainDispatcher) { editorService.openFile(file) }
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
            ToolResult.failure("Error opening file", e.message ?: "Unknown error")
        }
    }
}
