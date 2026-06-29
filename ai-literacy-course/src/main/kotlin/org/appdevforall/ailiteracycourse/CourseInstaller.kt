package org.appdevforall.ailiteracycourse

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.ArchiveFormat
import com.itsaky.androidide.plugins.services.ExtractResult
import com.itsaky.androidide.plugins.services.IdeArchiveService
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import java.io.File

/**
 * One-time extraction of the bundled course ZIP into the plugin's data
 * directory, plus generation of the navigation shell.
 *
 * Extraction goes to [IdeEnvironmentService.getPluginDataDirectory] — NOT
 * `ResourceManager.getPluginDirectory()`. They are different paths, and only
 * the former sits inside the directory `IdeArchiveService`'s write policy
 * allows (granted by the `ide.environment.write` permission). Extracting into
 * the resource dir fails the host's path allowlist.
 *
 * Idempotent: a marker file records a successful install for [INSTALL_VERSION];
 * bump that constant to force a clean re-extract when the bundled content changes.
 */
object CourseInstaller {

    private const val BUNDLE_ASSET = "ai-literacy-course.zip"
    private const val INSTALL_VERSION = 1
    private const val MARKER = ".installed-v$INSTALL_VERSION"

    private val installLock = Any()

    /**
     * Ensures the course is extracted and returns its content root (the folder
     * holding `START-HERE.md`, the lesson folders, and the generated index.html).
     *
     * Blocking + CPU/IO-bound — call from a background dispatcher.
     */
    fun ensureInstalled(context: PluginContext, onStatus: (String) -> Unit): File =
        synchronized(installLock) {
            val env = context.services.get(IdeEnvironmentService::class.java)
                ?: error("IdeEnvironmentService unavailable")
            val courseDir = File(env.getPluginDataDirectory(), "course")
            val marker = File(courseDir, MARKER)
            if (marker.exists()) {
                return contentRootOf(courseDir)
            }

            // Clean any partial/older install before re-extracting.
            if (courseDir.exists()) courseDir.deleteRecursively()
            courseDir.mkdirs()

            val archive = context.services.get(IdeArchiveService::class.java)
                ?: error("IdeArchiveService unavailable")

            onStatus("Unpacking videos…")
            val source = context.resources.openPluginAsset(BUNDLE_ASSET)
                ?: error("Bundled asset not found: $BUNDLE_ASSET")
            val result = source.use {
                archive.extract(it, ArchiveFormat.ZIP, courseDir, null)
            }
            if (result is ExtractResult.Failure) throw result.error

            val root = contentRootOf(courseDir)

            // Each interactive activity ships as its own zip inside the bundle.
            // Unpack them in place so the shell can link straight to their HTML.
            onStatus("Unpacking activities…")
            extractNestedZips(archive, root)

            onStatus("Building course…")
            CourseShell.generate(root)

            marker.writeText("ok")
            root
        }

    /**
     * The bundle wraps everything in a single top folder; descend into it so
     * callers see `START-HERE.md` at the root they're handed.
     */
    private fun contentRootOf(courseDir: File): File {
        if (File(courseDir, "START-HERE.md").exists()) return courseDir
        val wrapper = courseDir.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "START-HERE.md").exists() }
        return wrapper ?: courseDir
    }

    /**
     * Extract every `*.zip` found under [root] into a sibling folder of the same
     * base name, then delete the zip. Best-effort per activity: a single bad zip
     * is skipped rather than failing the whole install (videos are the priority).
     */
    private fun extractNestedZips(archive: IdeArchiveService, root: File) {
        val zips = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("zip", ignoreCase = true) }
            .toList()
        for (zip in zips) {
            val dest = File(zip.parentFile, zip.nameWithoutExtension)
            try {
                dest.mkdirs()
                val outcome = zip.inputStream().use {
                    archive.extract(it, ArchiveFormat.ZIP, dest, null)
                }
                if (outcome is ExtractResult.Failure) throw outcome.error
                zip.delete()
            } catch (_: Exception) {
                dest.deleteRecursively()
            }
        }
    }
}
