package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
import com.itsaky.androidide.plugins.aiassistant.AiAssistantPlugin
import com.itsaky.androidide.plugins.services.IdeProjectService
import java.io.File

/**
 * Shared path-containment guard for filesystem tool handlers.
 *
 * The LLM-driven tools resolve paths supplied by the model, so every handler
 * that touches the filesystem must confine those paths to the current project
 * root to prevent a prompt-injected model from reading or writing arbitrary
 * files. Previously this check lived (duplicated) only in CreateFileHandler and
 * ReadFileHandler; it is now centralized here and applied to every handler.
 */
object PathGuard {

    private const val TAG = "PathGuard"

    /**
     * Resolve the active project root, preferring the IDE's current project.
     * @return absolute project-root path, or null when none can be determined.
     */
    fun projectRoot(): String? {
        // Prefer the IDE-provided current project root when the service is reachable.
        val ideRoot = runCatching {
            val service: IdeProjectService? =
                AiAssistantPlugin.getContext()?.services?.get(IdeProjectService::class.java)
            service?.getCurrentProject()?.rootDir?.absolutePath
        }.getOrNull()
        if (ideRoot != null) return ideRoot
        // Fall back to JVM system properties; no hardcoded root, so an unknown root fails safe.
        return System.getProperty("project.dir") ?: System.getProperty("user.dir")
    }

    /**
     * Resolve [path] against the project root and verify it stays inside it.
     * @param path model-supplied path, absolute or project-relative.
     * @return contained [File], or null when the root is unknown or [path] escapes.
     */
    fun resolveWithin(path: String): File? {
        val rootPath = projectRoot()
        if (rootPath == null) {
            Log.e(TAG, "Denying path access: project root could not be determined")
            return null
        }
        val root = File(rootPath).canonicalPath
        val file = if (path.startsWith("/")) File(path) else File(rootPath, path)
        val canonical = file.canonicalPath
        val contained = canonical == root || canonical.startsWith(root + File.separator)
        if (!contained) {
            Log.e(TAG, "Path escape attempt: $canonical is outside project root $root")
        }
        return if (contained) file else null
    }
}
