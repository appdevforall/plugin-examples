package com.example.pcfinstaller

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeFileService
import com.itsaky.androidide.plugins.services.IdeTemplateService

import android.content.res.AssetManager
import android.util.Log
import java.io.File

class PCFInstaller : IPlugin {

    private lateinit var context: PluginContext
    private var templateService: IdeTemplateService? = null
    private lateinit var fileService: IdeFileService
    private var pcfBuilderFile: File? = null
    private var pcfExampleFile: File? = null

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            templateService = context.services.get(IdeTemplateService::class.java) ?: return false
            fileService = context.services.get(IdeFileService::class.java) ?: return false
            context.logger.info("PCFInstaller initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("PCFInstaller initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        pcfBuilderFile = registerPCFBuilderTemplate()
        pcfExampleFile = registerPCFExampleTemplate()
        context.logger.info("PCFInstaller: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        removePCFInstaller()
        context.logger.info("PCFInstaller: Deactivating plugin")
        return true
    }

    override fun dispose() {
        templateService = null
        pcfBuilderFile = null
        pcfExampleFile = null
        context.logger.info("PCFInstaller: Disposing plugin")
    }

    private fun removePCFInstaller() {
        val ts = templateService ?: return
        for ((name, file) in listOf(PCFBUILDER_NAME to pcfBuilderFile, PCFEXAMPLE_NAME to pcfExampleFile)) {
            val unregistered = ts.unregisterTemplate(name)
            context.logger.info("$name template unregistered: $unregistered")
            if (file != null && file.exists()) {
                val deleted = fileService.delete(file)
                context.logger.info("$name template file removal from ${file.absolutePath}: $deleted")
            }
        }
    }

    private fun registerPCFBuilderTemplate(): File? {
        val ASSETS_PCFBUILDER = "templates/PCFBuilder"

        return runCatching {
            var cgtBuilder = templateService!!.createTemplateBuilder(PCFBUILDER_NAME)
                .description("Build extensions.jar to support Pebble Custom Functions")
                .showPackageNameOption()
                .showLanguageOption()
                .showMinSdkOption()
                .thumbnailFromAssets("$ASSETS_PCFBUILDER/template/thumb.png", context)
                .addTextParameter("Author", "PLUGIN_AUTHOR", default = "Author")
                .addTextParameter("Description", "PLUGIN_DESC", default = "Plugin description")
                .addCheckboxParameter("Include Sample Functions", "EXAMPLE_CODE", default = true)

            for (assetFile in listBundledAssetFiles(ASSETS_PCFBUILDER)) {
                if (assetFile.startsWith("template/")) continue
                cgtBuilder = cgtBuilder.addStaticFromAssets(assetFile, "$ASSETS_PCFBUILDER/$assetFile", context)
            }

            val cgtBuilderFile = cgtBuilder.build(context.resources.getPluginDirectory())
            templateService!!.registerTemplate(cgtBuilderFile)
            Log.i(TAG, "PCFBuilder template registered")
            cgtBuilderFile
        }.onFailure {
            Log.e(TAG, "Failed to register PCFBuilder template", it)
        }.getOrNull()
    }


    private fun registerPCFExampleTemplate(): File? {
        val ASSETS_PCFEXAMPLE = "templates/PCFExample"

        return runCatching {
            var cgtExample = templateService!!.createTemplateBuilder(PCFEXAMPLE_NAME)
                .description("Demonstrate Pebble Custom Functions")
                .showPackageNameOption()
                .showLanguageOption()
                .showMinSdkOption()
                .thumbnailFromAssets("$ASSETS_PCFEXAMPLE/template/thumb.png", context)
                .addTextParameter("Author", "PLUGIN_AUTHOR", default = "Author")
                .addTextParameter("Description", "PLUGIN_DESC", default = "Plugin description")
                .addCheckboxParameter("Include Sample Functions", "EXAMPLE_CODE", default = true)

            for (assetFile in listBundledAssetFiles(ASSETS_PCFEXAMPLE)) {
                if (assetFile.startsWith("template/")) continue
                cgtExample = cgtExample.addStaticFromAssets(assetFile, "$ASSETS_PCFEXAMPLE/$assetFile", context)
            }

            val cgtExampleFile = cgtExample.build(context.resources.getPluginDirectory())
            templateService!!.registerTemplate(cgtExampleFile)
            Log.i(TAG, "PCFExample template registered")
            cgtExampleFile
        }.onFailure {
            Log.e(TAG, "Failed to register PCFExample template", it)
        }.getOrNull()
    }

    private fun listBundledAssetFiles(rootAssetPath: String): List<String> {
        val assets = context.androidContext.assets
        val results = mutableListOf<String>()
        walkAssets(assets, rootAssetPath, "", results)
        return results
    }

    private fun walkAssets(assets: AssetManager, rootPath: String, relativePath: String, out: MutableList<String>) {
        val absolute = if (relativePath.isEmpty()) rootPath else "$rootPath/$relativePath"
        val children = assets.list(absolute) ?: return
        if (children.isEmpty()) {
            out.add(relativePath)
            return
        }
        for (child in children) {
            val nextRelative = if (relativePath.isEmpty()) child else "$relativePath/$child"
            walkAssets(assets, rootPath, nextRelative, out)
        }
    }

    private companion object {
        const val PCFBUILDER_NAME = "PCFBuilder"
        const val PCFEXAMPLE_NAME = "PCFExample"
        const val TAG = "PCFInstallerPlugin"
    }

}
