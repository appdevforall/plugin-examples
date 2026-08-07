package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aiassistant.models.ToolResult
import com.itsaky.androidide.plugins.aiassistant.tool.ToolHandler
import com.itsaky.androidide.plugins.services.BuildAndLaunchCallback
import com.itsaky.androidide.plugins.services.IdeBuildService
import kotlinx.coroutines.delay

/**
 * Handler for running/building the app.
 */
class RunAppHandler(
    private val pluginContext: PluginContext
) : ToolHandler {
    override val toolName = "run_app"
    override val description = "Build and run the Android app on the connected device or emulator"
    // Build operation requires approval for safety
    override val requiresApproval = true

    override suspend fun execute(args: Map<String, Any?>): ToolResult {
        return try {
            Log.d("RunAppHandler", "Run app tool called - v3 with retry")

            val buildService = pluginContext.services.get(IdeBuildService::class.java)
            if (buildService == null) {
                Log.w("RunAppHandler", "IdeBuildService not available - service is null")
                return ToolResult.failure(
                    "Build service not available",
                    "The IDE build service is not available. This IDE instance may not support build operations."
                )
            }

            Log.d("RunAppHandler", "BuildService obtained successfully")

            val buildInProgress = buildService.isBuildInProgress()
            Log.d("RunAppHandler", "Build in progress: $buildInProgress")

            if (buildInProgress) {
                return ToolResult.failure(
                    "Build already running",
                    "A build is already in progress. Please wait for it to complete before running again."
                )
            }

            // Retry waiting for tooling server with exponential backoff
            val maxRetries = 10
            var toolingStarted = false
            var totalWaitTime = 0L
            for (attempt in 1..maxRetries) {
                toolingStarted = buildService.isToolingServerStarted()
                Log.d("RunAppHandler", "Tooling server check (attempt $attempt/$maxRetries): $toolingStarted")

                if (toolingStarted) {
                    Log.d("RunAppHandler", "Tooling server is now ready after $totalWaitTime ms")
                    break
                }

                if (attempt < maxRetries) {
                    val delayMs = 300L * attempt // 300ms, 600ms, 900ms, 1.2s, 1.5s, 1.8s, 2.1s, 2.4s, 2.7s
                    Log.d("RunAppHandler", "Tooling server not ready, waiting ${delayMs}ms before retry...")
                    delay(delayMs)
                    totalWaitTime += delayMs
                }
            }

            if (!toolingStarted) {
                Log.w("RunAppHandler", "Tooling server did not initialize within ${totalWaitTime + 300}ms, proceeding anyway (may fail)")
                // Try to proceed anyway - the service might initialize during the build
                Log.d("RunAppHandler", "Attempting to run app despite tooling server not being ready...")
            }

            Log.d("RunAppHandler", "Triggering app build and launch...")
            return try {
                // Trigger the build - fire and forget pattern
                buildService.runApp(object : BuildAndLaunchCallback {
                    override fun onComplete(success: Boolean, message: String) {
                        Log.i("RunAppHandler", "Build callback: success=$success, message=$message")
                    }
                })

                Log.d("RunAppHandler", "Build triggered, returning success")
                ToolResult.success(
                    message = "Build triggered successfully",
                    data = "The app build is now running in the background. Output will appear in the IDE's build panel."
                )
            } catch (e: Exception) {
                Log.e("RunAppHandler", "Failed to trigger build: ${e.message}", e)
                ToolResult.failure(
                    "Failed to trigger build",
                    "Error: ${e.message ?: "Unknown error"}"
                )
            }
        } catch (e: Exception) {
            Log.e("RunAppHandler", "Exception in run app tool", e)
            return ToolResult.failure(
                "Error: ${e.javaClass.simpleName}",
                "${e.message ?: "Unknown error"}\n\n${e.stackTraceToString()}"
            )
        }
    }
}
