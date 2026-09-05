package org.appdevforall.composepreview.data.source

import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeProjectService
import org.appdevforall.composepreview.ComposePreviewPlugin
import org.slf4j.LoggerFactory
import java.io.File

data class ProjectContext(
    val modulePath: String?,
    val variantName: String,
    val compileClasspaths: List<File>,
    val intermediateClasspaths: Set<File>,
    val projectDexFiles: List<File>,
    val needsBuild: Boolean,
    val resourceApk: File? = null
)

/**
 * Resolves the build context for a source file through the plugin-api
 * [IdeProjectService.getModuleContext] — keeping the plugin independent of host-internal
 * project types. The classpath/variant/resource-APK resolution lives host-side in the
 * IDE's ModuleContextResolver.
 */
class ProjectContextSource {

    fun resolveContext(filePath: String): ProjectContext {
        val service = PluginFragmentHelper
            .getServiceRegistry(ComposePreviewPlugin.PLUGIN_ID)
            ?.get(IdeProjectService::class.java)

        val moduleContext = if (filePath.isBlank()) null else service?.getModuleContext(filePath)

        if (moduleContext == null) {
            LOG.info("No module context for '{}' (service available: {})", filePath, service != null)
            return EMPTY
        }

        return ProjectContext(
            modulePath = moduleContext.modulePath,
            variantName = moduleContext.variantName,
            compileClasspaths = moduleContext.compileClasspaths,
            intermediateClasspaths = moduleContext.intermediateClasspaths.toSet(),
            projectDexFiles = moduleContext.runtimeDexFiles,
            needsBuild = moduleContext.needsBuild,
            resourceApk = moduleContext.resourceApk
        )
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(ProjectContextSource::class.java)
        private val EMPTY = ProjectContext(
            modulePath = null,
            variantName = "debug",
            compileClasspaths = emptyList(),
            intermediateClasspaths = emptySet(),
            projectDexFiles = emptyList(),
            needsBuild = false
        )
    }
}
