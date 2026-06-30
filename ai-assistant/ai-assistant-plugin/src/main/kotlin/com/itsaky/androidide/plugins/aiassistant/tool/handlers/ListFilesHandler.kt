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
        var directory = args["directory"]?.toString()?.trim()?.takeIf { it.isNotBlank() }

        // If no directory specified, try to find a sensible default
        if (directory.isNullOrBlank()) {
            directory = findDefaultDirectory()
            Log.d("ListFilesHandler", "No directory specified, using default: $directory")
        }

        Log.d("ListFilesHandler", "Listing files in directory: $directory")

        return try {
            val dir = File(directory).absoluteFile
            Log.d("ListFilesHandler", "Absolute path: ${dir.absolutePath}")

            if (!dir.exists()) {
                Log.w("ListFilesHandler", "Directory does not exist: ${dir.absolutePath}")
                return ToolResult.failure("Directory does not exist: ${dir.absolutePath}")
            }

            if (!dir.isDirectory) {
                Log.w("ListFilesHandler", "Path is not a directory: ${dir.absolutePath}")
                return ToolResult.failure("Path is not a directory: ${dir.absolutePath}")
            }

            val files = dir.listFiles()?.map { file ->
                val prefix = if (file.isDirectory) "[DIR] " else "[FILE] "
                val size = if (file.isFile) " (${formatSize(file.length())})" else ""
                prefix + file.name + size
            }?.sorted() ?: emptyList()

            Log.d("ListFilesHandler", "Found ${files.size} items")

            if (files.isEmpty()) {
                Log.w("ListFilesHandler", "Directory is empty: ${dir.absolutePath}")
                return ToolResult.success(
                    message = "Directory is empty: ${dir.absolutePath}",
                    data = "(no files)"
                )
            }

            ToolResult.success(
                message = "Found ${files.size} items in ${dir.absolutePath}",
                data = files.joinToString("\n")
            )
        } catch (e: Exception) {
            Log.e("ListFilesHandler", "Error listing files in $directory", e)
            ToolResult.failure("Error listing files: ${e.message}", e.stackTraceToString())
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes <= 0 -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    private fun findDefaultDirectory(): String {
        // Try common Android IDE project locations
        val possibleLocations = listOf(
            "/storage/emulated/0/AndroidIDEProjects",
            "/storage/emulated/0/AndroidIDEProjects/MyApplication",
            System.getProperty("user.home") + "/AndroidIDEProjects",
            System.getProperty("user.home") + "/AndroidIDEProjects/MyApplication",
            System.getProperty("user.home"),
            "/sdcard/AndroidIDEProjects"
        )

        for (location in possibleLocations) {
            val dir = File(location)
            if (dir.exists() && dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) {
                Log.d("ListFilesHandler", "Found default directory: $location")
                return location
            }
        }

        // Fallback to home directory
        val homeDir = System.getProperty("user.home") ?: "/storage/emulated/0"
        Log.d("ListFilesHandler", "Using fallback home directory: $homeDir")
        return homeDir
    }
}
