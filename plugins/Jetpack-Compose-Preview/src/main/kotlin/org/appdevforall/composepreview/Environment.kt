package org.appdevforall.composepreview

import android.content.Context
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import java.io.File

/**
 * Plugin-local replacement for the host `com.itsaky.androidide.utils.Environment`.
 *
 * Keeps the renderer independent of host-internal classes: the plugin runs inside the
 * host process, so [Context.getFilesDir] is the host data dir (`/data/data/<host>/files`)
 * where the bundled SDK + JDK live, and the SDK root is also available through the
 * plugin-api [IdeEnvironmentService]. Initialized once from [ComposePreviewPlugin].
 */
object Environment {

    private lateinit var appContext: Context
    private var ideEnv: IdeEnvironmentService? = null

    fun init(context: Context, ideEnvironment: IdeEnvironmentService?) {
        appContext = context.applicationContext ?: context
        ideEnv = ideEnvironment
    }

    val HOME: File
        get() = File(appContext.filesDir, "home")

    val ANDROID_HOME: File
        get() = ideEnv?.getAndroidHomeDirectory() ?: File(HOME, "android-sdk")

    /** `<filesDir>/usr/lib/jvm/java-21-openjdk/bin/java` — matches host DEFAULT_JAVA_HOME. */
    val JAVA: File
        get() = File(appContext.filesDir, "usr/lib/jvm/java-21-openjdk/bin/java")

    /** Highest android.jar found under the SDK platforms directory. */
    val ANDROID_JAR: File
        get() {
            val platforms = File(ANDROID_HOME, "platforms")
            val resolved = platforms.listFiles()
                ?.filter { it.isDirectory }
                ?.sortedByDescending { it.name }
                ?.firstNotNullOfOrNull { dir -> File(dir, "android.jar").takeIf { it.exists() } }
            return resolved ?: File(platforms, "android-34/android.jar")
        }

    /** Plugin-writable dir the bundled compose-jars.zip is extracted into. */
    val COMPOSE_HOME: File
        get() {
            val base = runCatching { ideEnv?.getPluginDataDirectory() }.getOrNull()
                ?: appContext.cacheDir
            return File(base, "compose").apply { mkdirs() }
        }
}
