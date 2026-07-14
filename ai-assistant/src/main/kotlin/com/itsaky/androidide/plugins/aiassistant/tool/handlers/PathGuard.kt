package com.itsaky.androidide.plugins.aiassistant.tool.handlers

import android.util.Log
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

    /** Best-effort resolution of the active project root. */
    fun projectRoot(): String =
        System.getProperty("project.dir")
            ?: System.getProperty("user.dir")
            ?: "/storage/emulated/0/AndroidIDEProjects"

    /**
     * Resolve [path] against the project root and verify the canonical result is
     * inside that root.
     *
     * @return the resolved [File] when it is contained in the project root, or
     *         `null` when [path] escapes it (caller should reject the request).
     */
    fun resolveWithin(path: String): File? {
        val root = File(projectRoot()).canonicalPath
        val file = if (path.startsWith("/")) File(path) else File(projectRoot(), path)
        val canonical = file.canonicalPath
        val contained = canonical == root || canonical.startsWith(root + File.separator)
        if (!contained) {
            Log.e(TAG, "Path escape attempt: $canonical is outside project root $root")
        }
        return if (contained) file else null
    }
}
