package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import com.itsaky.androidide.plugins.services.IdeProjectManipulationService

/**
 * Handler for adding dependencies to the project build file.
 */
class AddDependencyHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "add_dependency"
    override val description = "Add a Maven dependency to the project build file"
    override val requiresApproval = true

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        val dependency = args["dependency"]?.toString()?.trim()
        if (dependency.isNullOrBlank()) {
            return ToolResult.failure("dependency is required (e.g., 'com.squareup.retrofit2:retrofit:2.9.0')")
        }

        val buildFile = args["build_file"]?.toString()?.trim()
            ?: "app/build.gradle.kts"  // Default to app module

        Log.d("AddDependencyHandler", "Adding dependency: $dependency to $buildFile")

        return try {
            val service = pluginContext.services.get(IdeProjectManipulationService::class.java)
            if (service == null) {
                Log.w("AddDependencyHandler", "IdeProjectManipulationService not available")
                return ToolResult.failure("Project manipulation service not available")
            }

            val success = service.addDependency(dependency, buildFile)
            if (success) {
                Log.d("AddDependencyHandler", "Dependency added successfully: $dependency")
                ToolResult.success(
                    message = "Added dependency: $dependency",
                    data = "Dependency added to $buildFile. Run gradle_sync to reload."
                )
            } else {
                Log.w("AddDependencyHandler", "Failed to add dependency: $dependency")
                ToolResult.failure(
                    "Failed to add dependency",
                    "Could not add $dependency to $buildFile. Check the build file format."
                )
            }
        } catch (e: Exception) {
            Log.e("AddDependencyHandler", "Error adding dependency", e)
            ToolResult.failure(
                "Error adding dependency",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
