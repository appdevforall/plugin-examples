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
 *
 * ## Resolving the project root
 *
 * The root must be the **open AndroidIDE project**, not the process working
 * directory. The old implementation relied on the `project.dir` system property
 * — which nothing ever sets — and fell back to `user.dir`, which on Android is
 * `"/"`. That made every *relative* path (e.g. `.gitignore`) resolve against the
 * filesystem root and get rejected as "outside the project directory".
 *
 * The root is now sourced, in priority order, from:
 *  1. [projectRootProvider] — wired at plugin activation to the host's
 *     `IdeProjectService.currentProject.rootDir`; queried lazily so switching
 *     projects is picked up automatically.
 *  2. The `project.dir` / `user.dir` system properties (legacy fallback).
 *  3. [DEFAULT_ROOT] as a last resort.
 */
object PathGuard {

    private const val TAG = "PathGuard"
    private const val DEFAULT_ROOT = "/storage/emulated/0/AndroidIDEProjects"

    /**
     * Host-backed supplier of the current project root, installed by
     * `AiAssistantPlugin.activate()`. Invoked on every resolution so a
     * mid-session project switch is reflected without re-wiring.
     */
    @Volatile
    private var projectRootProvider: (() -> String?)? = null

    /** Test-only override; when non-null it wins over everything else. */
    @Volatile
    private var projectRootOverride: String? = null

    /** Install (or clear, with `null`) the host-backed project-root supplier. */
    fun setProjectRootProvider(provider: (() -> String?)?) {
        projectRootProvider = provider
    }

    /**
     * Force a specific root, or clear it with `null`. For tests only — production
     * code must go through [setProjectRootProvider].
     */
    fun setProjectRootForTesting(root: String?) {
        projectRootOverride = root
    }

    /** Best-effort resolution of the active project root. */
    fun projectRoot(): String =
        projectRootOverride
            ?: projectRootProvider?.invoke()?.takeIf { it.isNotBlank() }
            ?: System.getProperty("project.dir")
            ?: System.getProperty("user.dir")
            ?: DEFAULT_ROOT

    /**
     * Resolve [path] against the project root and verify the canonical result is
     * inside that root.
     *
     * @return the resolved [File] when it is contained in the project root, or
     *         `null` when [path] escapes it (caller should reject the request).
     */
    fun resolveWithin(path: String): File? {
        val rootFile = File(projectRoot())
        val root = rootFile.canonicalPath
        val file = if (path.startsWith("/")) File(path) else File(rootFile, path)
        val canonical = file.canonicalPath

        val rootWithSep = if (root.endsWith(File.separator)) root else root + File.separator
        val contained = canonical == root || canonical.startsWith(rootWithSep)
        if (!contained) {
            Log.e(TAG, "Path escape attempt: $canonical is outside project root $root")
        }
        return if (contained) file else null
    }

    /** Directories skipped when searching for a file by name — large/generated/noise. */
    private val SKIP_DIRS = setOf("build", ".git", ".gradle", ".idea", "node_modules", ".cxx")

    /**
     * Find files under the project root whose name equals [fileName]
     * (case-insensitive), so a bare filename like "MainActivity.java" can be
     * resolved to its real path. Confined to the project root, skips
     * generated/noise directories and hidden dirs, and caps the result count.
     *
     * @return matching files (possibly empty); more than one means ambiguous.
     */
    fun findByName(fileName: String, limit: Int = 20): List<File> {
        val target = baseNameOf(fileName).trim()
        if (target.isEmpty()) return emptyList()

        val root = File(projectRoot())
        if (!root.isDirectory) return emptyList()

        return root.walkTopDown()
            .onEnter { dir -> dir == root || (dir.name !in SKIP_DIRS && !dir.name.startsWith(".")) }
            .filter { it.isFile && it.name.equals(target, ignoreCase = true) }
            .take(limit)
            .toList()
    }

    /** The last path segment of [path], tolerating both '/' and '\' separators. */
    private fun baseNameOf(path: String): String =
        path.substringAfterLast('/').substringAfterLast('\\')

    /** Outcome of [resolve]; callers match on it to act or to explain the miss. */
    sealed interface Resolution {
        /** [path] resolved to an existing entry (file or directory) inside the root. */
        data class Resolved(val file: File) : Resolution

        /** A bare/hallucinated name matched several files; ask the user to disambiguate. */
        data class Ambiguous(val baseName: String, val matches: List<File>) : Resolution

        /** No existing match, but the path stayed in-root — it simply doesn't exist. */
        object NotFound : Resolution

        /** The path had no in-root interpretation at all (a containment escape). */
        object Escaped : Resolution
    }

    /**
     * Resolve a model-supplied path to a project entry, enforcing root
     * containment so an out-of-root path never resolves to a real entry. Tries
     * the path within the root, then (if slash-prefixed) as relative, then a
     * project-wide basename search.
     *
     * @param path a relative, absolute, or bare file name from the model.
     * @return the matching [Resolution].
     */
    fun resolve(path: String): Resolution {
        val primary = resolveWithin(path)
        val relative = if (path.startsWith("/")) resolveWithin(path.removePrefix("/")) else null

        (primary?.takeIf { it.exists() } ?: relative?.takeIf { it.exists() })?.let {
            return Resolution.Resolved(it)
        }

        val matches = findByName(path)
        when (matches.size) {
            1 -> return Resolution.Resolved(matches[0])
            0 -> {}
            else -> return Resolution.Ambiguous(baseNameOf(path), matches)
        }

        return if (primary == null && relative == null) Resolution.Escaped else Resolution.NotFound
    }
}
