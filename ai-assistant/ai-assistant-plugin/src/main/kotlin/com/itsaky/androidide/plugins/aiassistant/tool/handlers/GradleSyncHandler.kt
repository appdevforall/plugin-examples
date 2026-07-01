package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import com.itsaky.androidide.plugins.services.GradleSyncCallback
import com.itsaky.androidide.plugins.services.IdeBuildService
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.delay

/**
 * Handler for triggering Gradle project sync.
 */
class GradleSyncHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "gradle_sync"
    override val description = "Sync the Gradle project (reload dependencies and rebuild cache)"
    override val requiresApproval = false

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        Log.d("GradleSyncHandler", "Gradle sync requested")

        return try {
            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            if (buildService == null) {
                Log.w("GradleSyncHandler", "IdeBuildService not available")
                return ToolResult.failure(
                    "Build service not available",
                    "The IDE build service is not available."
                )
            }

            // Use CompletableFuture to wait for sync to complete
            val syncComplete = CompletableFuture<String>()

            Log.d("GradleSyncHandler", "Triggering gradle sync...")
            buildService.triggerGradleSync(object : GradleSyncCallback {
                override fun onComplete(success: Boolean, output: String) {
                    Log.d("GradleSyncHandler", "Sync completed: success=$success, output length=${output.length}")
                    if (success) {
                        syncComplete.complete(output.ifEmpty { "Gradle sync completed successfully" })
                    } else {
                        syncComplete.complete("Gradle sync failed:\n$output")
                    }
                }
            })

            // Wait for sync to complete (with timeout of 2 minutes)
            val result: String = try {
                syncComplete.get(120_000, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w("GradleSyncHandler", "Gradle sync timed out or failed: ${e.message}")
                "Gradle sync in progress (may take 1-2 minutes)"
            }

            ToolResult.success(
                message = "Gradle sync triggered",
                data = result
            )
        } catch (e: Exception) {
            Log.e("GradleSyncHandler", "Error triggering gradle sync", e)
            ToolResult.failure(
                "Error triggering sync",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
