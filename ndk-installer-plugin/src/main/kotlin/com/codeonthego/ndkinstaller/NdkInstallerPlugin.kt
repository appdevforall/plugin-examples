package com.codeonthego.ndkinstaller

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.ArchiveFormat
import com.itsaky.androidide.plugins.services.ExtractResult
import com.itsaky.androidide.plugins.services.IdeArchiveService
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeTemplateService

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.util.Log
import java.io.File


class NdkInstallerPlugin : IPlugin {

    private lateinit var context: PluginContext
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var installJob: Job? = null
    private var templateService: IdeTemplateService? = null

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        templateService = context.services.get(IdeTemplateService::class.java)
        Log.i(TAG, "NDK plugin initialized")
        return true
    }

    override fun activate(): Boolean {
        installJob = scope.launch { installNdk() }
        registerNdkTemplate()
        Log.i(TAG, "NDK plugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        installJob?.cancel()
        removeNdk()
        templateService?.unregisterTemplate("NDK.cgt")
        return true
    }

    override fun dispose() {
        scope.cancel()
        templateService = null
    }

    private fun installNdk() {
        val env = context.services.get(IdeEnvironmentService::class.java)
            ?: return fail("IdeEnvironmentService unavailable; plugin must declare ide.environment.write")
        val archive = context.services.get(IdeArchiveService::class.java)
            ?: return fail("IdeArchiveService unavailable")

        val targetDir = File(env.getAndroidHomeDirectory().absolutePath /*, NDK_SUBDIR*/)

        val source = context.resources.openPluginAsset(NDK_ARCHIVE_NAME)
            ?: return fail("Bundled asset not found: $NDK_ARCHIVE_NAME (place it in src/main/assets/)")

        context.logger.info("Installing NDK to ${targetDir.absolutePath}")
        val result = source.use { stream ->
            archive.extract(
                source = stream,
                format = ArchiveFormat.TAR_XZ,
                destination = targetDir
            ) { bytes, entry ->
                val mib = bytes / MIB
                val suffix = entry?.let { " ($it)" } ?: ""
                context.logger.debug("extract progress: ${mib} MiB$suffix")
            }
        }

        when (result) {
            is ExtractResult.Success -> context.logger.info(
                "NDK install complete: ${result.filesExtracted} files, ${result.bytesWritten / MIB} MiB"
            )
            is ExtractResult.Failure -> context.logger.error(
                "NDK install failed: ${result.error.message}",
                result.error
            )
        }
    }

    private fun removeNdk() {
        val env = context.services.get(IdeEnvironmentService::class.java) ?: return
        val file = context.services.get(IdeFileService::class.java) ?: return
        val ndkDir = File(env.getAndroidHomeDirectory(), NDK_SUBDIR)
        if (ndkDir.exists()) {
            val deleted = file.delete(ndkDir)
            context.logger.info("ndk removal from ${ndkDir.absolutePath}: $deleted")
        }
        val cmakeDir = File(env.getAndroidHomeDirectory(), CMAKE_SUBDIR)
        if (cmakeDir.exists()) {
            val deleted = file.delete(cmakeDir)
            context.logger.info("cmake removal from ${cmakeDir.absolutePath}: $deleted")
        }
    }

    private fun registerNdkTemplate() {
        val service = templateService ?: return
        val ctx = context ?: return

        val ASSETS_NDK = "templates/ndk"

        runCatching {
            val cgt = service.createTemplateBuilder("NDK")
                .description("An NDK and CMake binary installer with corresponding NDK Activity template")
                .showPackageNameOption()
                .showLanguageOption()
                .showMinSdkOption()
                .thumbnailFromAssets("$ASSETS_NDK/template/thumb.png", ctx)
                .addStaticFromAssets("gradle.properties", "$ASSETS_NDK/gradle.properties", ctx)
                .addStaticFromAssets("settings.gradle.kts.peb", "$ASSETS_NDK/settings.gradle.kts.peb", ctx)
                .addStaticFromAssets(".gitignore", "$ASSETS_NDK/gitignore", ctx)
                .addStaticFromAssets("gradlew", "$ASSETS_NDK/gradlew", ctx)
                .addStaticFromAssets("gradle/wrapper/gradle-wrapper.jar", "$ASSETS_NDK/gradle/wrapper/gradle-wrapper.jar", ctx)
                .addStaticFromAssets("gradle/wrapper/gradle-wrapper.properties.peb", "$ASSETS_NDK/gradle/wrapper/gradle-wrapper.properties.peb", ctx)
                .addStaticFromAssets("app/src/main/cpp/native-lib.cpp.peb", "$ASSETS_NDK/app/src/main/cpp/native-lib.cpp.peb", ctx)
                .addStaticFromAssets("app/src/main/cpp/CMakeLists.txt", "$ASSETS_NDK/app/src/main/cpp/CMakeLists.txt", ctx)
                .addStaticFromAssets("app/src/main/java/PACKAGE_NAME/MainActivity.kt.peb", "$ASSETS_NDK/app/src/main/java/PACKAGE_NAME/MainActivity.kt.peb", ctx)
                .addStaticFromAssets("app/src/main/java/PACKAGE_NAME/MainActivity.java.peb", "$ASSETS_NDK/app/src/main/java/PACKAGE_NAME/MainActivity.java.peb", ctx)
                .addStaticFromAssets("app/src/main/AndroidManifest.xml", "$ASSETS_NDK/app/src/main/AndroidManifest.xml", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml", "$ASSETS_NDK/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp", "$ASSETS_NDK/app/src/main/res/mipmap-xhdpi/ic_launcher_round.webp", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-xhdpi/ic_launcher.webp", "$ASSETS_NDK/app/src/main/res/mipmap-xhdpi/ic_launcher.webp", ctx)
                .addStaticFromAssets("app/src/main/res/xml/data_extraction_rules.xml", "$ASSETS_NDK/app/src/main/res/xml/data_extraction_rules.xml", ctx)
                .addStaticFromAssets("app/src/main/res/xml/backup_rules.xml", "$ASSETS_NDK/app/src/main/res/xml/backup_rules.xml", ctx)
                .addStaticFromAssets("app/src/main/res/values/themes.xml", "$ASSETS_NDK/app/src/main/res/values/themes.xml", ctx)
                .addStaticFromAssets("app/src/main/res/values/strings.xml.peb", "$ASSETS_NDK/app/src/main/res/values/strings.xml.peb", ctx)
                .addStaticFromAssets("app/src/main/res/values/colors.xml", "$ASSETS_NDK/app/src/main/res/values/colors.xml", ctx)
                .addStaticFromAssets("app/src/main/res/values-night/themes.xml", "$ASSETS_NDK/app/src/main/res/values-night/themes.xml", ctx)
                .addStaticFromAssets("app/src/main/res/values-night/colors.xml", "$ASSETS_NDK/app/src/main/res/values-night/colors.xml", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp", "$ASSETS_NDK/app/src/main/res/mipmap-xxhdpi/ic_launcher_round.webp", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-xxhdpi/ic_launcher.webp", "$ASSETS_NDK/app/src/main/res/mipmap-xxhdpi/ic_launcher.webp", ctx)
                .addStaticFromAssets("app/src/main/res/layout/activity_main.xml", "$ASSETS_NDK/app/src/main/res/layout/activity_main.xml", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp", "$ASSETS_NDK/app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.webp", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp", "$ASSETS_NDK/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp", ctx)
                .addStaticFromAssets("app/src/main/res/drawable/ic_launcher_background.xml", "$ASSETS_NDK/app/src/main/res/drawable/ic_launcher_background.xml", ctx)
                .addStaticFromAssets("app/src/main/res/drawable/ic_launcher_round.webp", "$ASSETS_NDK/app/src/main/res/drawable/ic_launcher_round.webp", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-hdpi/ic_launcher_round.webp", "$ASSETS_NDK/app/src/main/res/mipmap-hdpi/ic_launcher_round.webp", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-hdpi/ic_launcher.webp", "$ASSETS_NDK/app/src/main/res/mipmap-hdpi/ic_launcher.webp", ctx)
                .addStaticFromAssets("app/src/main/res/drawable-v24/ic_launcher_foreground.xml", "$ASSETS_NDK/app/src/main/res/drawable-v24/ic_launcher_foreground.xml", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-mdpi/ic_launcher_round.webp", "$ASSETS_NDK/app/src/main/res/mipmap-mdpi/ic_launcher_round.webp", ctx)
                .addStaticFromAssets("app/src/main/res/mipmap-mdpi/ic_launcher.webp", "$ASSETS_NDK/app/src/main/res/mipmap-mdpi/ic_launcher.webp", ctx)
                .addStaticFromAssets("app/.gitignore", "$ASSETS_NDK/app/gitignore", ctx)
                .addStaticFromAssets("app/proguard-rules.pro", "$ASSETS_NDK/app/proguard-rules.pro", ctx)
                .addStaticFromAssets("app/build.gradle.kts.peb", "$ASSETS_NDK/app/build.gradle.kts.peb", ctx)
                .addStaticFromAssets("build.gradle.kts.peb", "$ASSETS_NDK/build.gradle.kts.peb", ctx)
                .build(ctx.resources.getPluginDirectory())

            service.registerTemplate(cgt)
            Log.i(TAG, "Ndk template registered")

        }.onFailure {
            Log.e(TAG, "Failed to register Ndk template", it)
        }
    }

    private fun fail(message: String) {
        context.logger.error("NDK installer: $message")
    }

    private companion object {
        const val NDK_ARCHIVE_NAME = "ndk-cmake.tar.xz"
        const val CMAKE_SUBDIR = "cmake"
        const val NDK_SUBDIR = "ndk"
        const val MIB = 1024L * 1024L
        const val TAG = "NdkInstallerPlugin"
    }
}
