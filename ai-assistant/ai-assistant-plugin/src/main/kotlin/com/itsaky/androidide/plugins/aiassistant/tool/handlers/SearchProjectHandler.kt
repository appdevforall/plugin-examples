package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import java.io.File

/**
 * Handler for searching files in the project.
 */
class SearchProjectHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "search_project"
    override val description = "Search for files by name or content in the project"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val query = args["query"]?.toString()?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult.failure("query is required")
        }

        val projectDir = args["project_dir"]?.toString()?.trim()
        val searchRoot = if (projectDir.isNullOrBlank()) {
            File("/storage/emulated/0")  // Default to storage root
        } else {
            File(projectDir)
        }

        return try {
            if (!searchRoot.exists() || !searchRoot.isDirectory) {
                return ToolResult.failure("Invalid project directory: ${searchRoot.absolutePath}")
            }

            val results = mutableListOf<String>()
            searchFilesRecursive(searchRoot, query, results, maxResults = 50)

            if (results.isEmpty()) {
                ToolResult.success("No files found matching '$query'", "")
            } else {
                ToolResult.success(
                    message = "Found ${results.size} file(s) matching '$query'",
                    data = results.joinToString("\n")
                )
            }
        } catch (e: Exception) {
            Log.e("SearchProjectHandler", "Error searching project", e)
            ToolResult.failure("Error searching project: ${e.message}", e.stackTraceToString())
        }
    }

    private fun searchFilesRecursive(
        dir: File,
        query: String,
        results: MutableList<String>,
        maxResults: Int,
        depth: Int = 0
    ) {
        if (depth > 5 || results.size >= maxResults) return

        dir.listFiles()?.forEach { file ->
            if (results.size >= maxResults) return

            if (file.isDirectory && !file.name.startsWith(".")) {
                searchFilesRecursive(file, query, results, maxResults, depth + 1)
            } else if (file.isFile && file.name.contains(query, ignoreCase = true)) {
                results.add(file.absolutePath)
            }
        }
    }
}
