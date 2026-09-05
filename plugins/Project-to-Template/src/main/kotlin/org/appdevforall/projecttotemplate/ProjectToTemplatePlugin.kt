package org.appdevforall.projecttotemplate

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import androidx.fragment.app.Fragment
import org.appdevforall.projecttotemplate.fragments.ProjectToTemplateFragment

/**
 * Converts the Android project currently open in Code On The Go into a
 * Code On The Go (.cgt) template bundle, and optionally installs it directly
 * into the IDE via IdeTemplateService.
 */
class ProjectToTemplatePlugin : IPlugin, UIExtension, EditorTabExtension, DocumentationExtension {

    companion object {
        private const val TAB_ID = "org_appdevforall_projecttotemplate_main"
        private const val TOOLTIP_TAG = "projecttotemplate.overview"
    }

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("ProjectToTemplatePlugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("ProjectToTemplatePlugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("ProjectToTemplatePlugin: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("ProjectToTemplatePlugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("ProjectToTemplatePlugin: Disposing plugin")
    }

    // -- UIExtension --

    override fun getMainMenuItems(): List<MenuItem> = emptyList()

    override fun getEditorTabs(): List<TabItem> = emptyList()

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "org_appdevforall_projecttotemplate_sidebar",
                title = "Project to Template",
                icon = R.drawable.ic_plugin,
                isEnabled = true,
                isVisible = true,
                group = "tools",
                order = 0,
                action = { openPluginTab() },
                tooltipTag = TOOLTIP_TAG,
            )
        )
    }

    private fun openPluginTab() {
        val editorTabService = context.services.get(IdeEditorTabService::class.java) ?: run {
            context.logger.error("Editor tab service not available")
            return
        }
        if (!editorTabService.isTabSystemAvailable()) {
            context.logger.error("Editor tab system not available")
            return
        }
        try {
            editorTabService.selectPluginTab(TAB_ID)
        } catch (e: Exception) {
            context.logger.error("Error opening Project to Template tab", e)
        }
    }

    // -- EditorTabExtension --

    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(
            EditorTabItem(
                id = TAB_ID,
                title = "Project to Template",
                icon = R.drawable.ic_plugin,
                fragmentFactory = { ProjectToTemplateFragment() },
                isCloseable = true,
                isPersistent = false,
                order = 0,
                isEnabled = true,
                isVisible = true,
                tooltip = "Convert the open Android project into a Code On The Go template",
            )
        )
    }

    override fun onEditorTabSelected(tabId: String, fragment: Fragment) {}

    override fun onEditorTabClosed(tabId: String) {}

    override fun canCloseEditorTab(tabId: String): Boolean = true

    // -- DocumentationExtension --

    // Must be "plugin_" + the full plugin.id, or the host renders the tooltip as
    // the literal string "n/a" at runtime (the build stays green regardless).
    override fun getTooltipCategory(): String = "plugin_org.appdevforall.projecttotemplate"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return listOf(
            PluginTooltipEntry(
                tag = TOOLTIP_TAG,
                summary = "<b>Project to Template</b><br>Convert the project currently open in Code On The Go into a reusable (.cgt) template.",
                detail = """
                    <h3>Project to Template</h3>
                    <p>Enter a template name and tap <b>Convert to Template</b>. The plugin copies the
                    open project, substitutes concrete values (Gradle/AGP/Kotlin versions, package name,
                    app name, SDK levels) with Pebble tokens, writes <code>template.json</code> and a
                    thumbnail, and zips the result into a <code>.cgt</code> file. You can then install it
                    directly into Code On The Go's New Project template picker.</p>
                """.trimIndent(),
                buttons = listOf(
                    PluginTooltipButton(
                        description = "Open the Project to Template guide",
                        uri = "index.html",
                        order = 0,
                    )
                ),
            )
        )
    }

    override fun getTier3DocsAssetPath(): String? = "docs"

    override fun onDocumentationInstall(): Boolean = true

    override fun onDocumentationUninstall() {}
}
