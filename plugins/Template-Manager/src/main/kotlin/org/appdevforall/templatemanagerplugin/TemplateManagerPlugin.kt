package org.appdevforall.templatemanagerplugin 

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import org.appdevforall.templatemanagerplugin.fragments.TemplateManagerPluginFragment
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import androidx.fragment.app.Fragment

class TemplateManagerPlugin : IPlugin, UIExtension, EditorTabExtension, DocumentationExtension {

    private companion object {
        const val PLUGIN_ID = "org.appdevforall.templatemanagerplugin"
        // Shared by the sidebar item, the editor tab, and the registered tooltip entry so a
        // long-press / hover anywhere resolves to the same help entry (see getTooltipEntries).
        const val TOOLTIP_TAG = "templatemanager.overview"
    }

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return try {
            this.context = context
            context.logger.info("TemplateManagerPlugin initialized successfully")
            true
        } catch (e: Exception) {
            context.logger.error("TemplateManagerPlugin initialization failed", e)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("TemplateManagerPlugin: Activating plugin")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("TemplateManagerPlugin: Deactivating plugin")
        return true
    }

    override fun dispose() {
        context.logger.info("TemplateManagerPlugin: Disposing plugin")
    }

    override fun getEditorTabs(): List<TabItem> {
        return listOf(
            TabItem(
                id = "org_appdevforall_templatemanagerplugin_tab",
                title = "Template Manager",
                fragmentFactory = { TemplateManagerPluginFragment() },
                isEnabled = true,
                isVisible = true,
                order = 0,
                tooltipTag = TOOLTIP_TAG
            )
        )
    }

    override fun getMainMenuItems(): List<MenuItem> {
        return emptyList()
    }

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "org_appdevforall_templatemanagerplugin_sidebar",
                title = "Template Manager",
                icon = R.drawable.ic_plugin,
                isEnabled = true,
                isVisible = true,
                group = "plugins",
                order = 0,
                tooltipTag = TOOLTIP_TAG,
                action = {
                    openPluginTab()
                }
            )
        )
    }

    private fun openPluginTab() {
        context.logger.info("Opening TemplateManagerPlugin in main editor tab")

        val editorTabService = context.services.get(IdeEditorTabService::class.java) ?: run {
            context.logger.error("Editor tab service not available")
            return
        }

        if (!editorTabService.isTabSystemAvailable()) {
            context.logger.error("Editor tab system not available")
            return
        }

        val tabId = "org_appdevforall_templatemanagerplugin_main"
        try {
            if (editorTabService.selectPluginTab(tabId)) {
                context.logger.info("Successfully opened TemplateManagerPlugin tab")
            }
        } catch (e: Exception) {
            context.logger.error("Error opening TemplateManagerPlugin tab", e)
        }
    }

    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(
            EditorTabItem(
                id = "org_appdevforall_templatemanagerplugin_main",
                title = "Template Manager",
                icon = R.drawable.ic_plugin,
                fragmentFactory = { TemplateManagerPluginFragment() },
                isCloseable = true,
                isPersistent = false,
                order = 0,
                isEnabled = true,
                isVisible = true,
                tooltip = "Browse, install, and manage .cgt project templates"
            )
        )
    }

    override fun onEditorTabSelected(tabId: String, fragment: Fragment) {}

    override fun onEditorTabClosed(tabId: String) {}

    override fun canCloseEditorTab(tabId: String): Boolean = true

    // Must be exactly "plugin_<pluginId>": the host registers this plugin's tooltip entries
    // under this category, and the 2-arg IdeTooltipService.showTooltip(view, tag) call in the
    // fragment derives the same "plugin_<pluginId>" category when looking them up. Any other
    // value makes the lookup miss and the tooltip renders "n/a" (caught in on-device testing).
    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> {
        return listOf(
            PluginTooltipEntry(
                tag = TOOLTIP_TAG,
                summary = "<b>Template Manager</b><br>Install, uninstall, and manage Code On The Go " +
                    "project templates (.cgt files).",
                detail = """
                    <h3>Template Manager</h3>
                    <p>Manage the <code>.cgt</code> project/file templates available to Code On The Go's
                    New Project / New File wizard, all from one screen.</p>

                    <h4>What you see</h4>
                    <ul>
                        <li><b>Installed</b> (green) - templates registered with the IDE.</li>
                        <li><b>Not installed</b> (red) - <code>.cgt</code> files found in your Downloads
                        folder, ready to install.</li>
                    </ul>
                    <p>A <code>.cgt</code> that bundles more than one template shows a "Contains N templates"
                    note; tap it to see a card per bundled template.</p>

                    <h4>Actions (per-card &#8942; menu)</h4>
                    <ol>
                        <li><b>Install</b> - registers the template and moves the file from Downloads into
                        the IDE's template store.</li>
                        <li><b>Uninstall</b> - unregisters the template and moves it back to Downloads.</li>
                        <li><b>Delete</b> - permanently removes the <code>.cgt</code> from Downloads
                        (asks for confirmation first).</li>
                        <li><b>Details</b> - shows the template's version, description, and optional
                        wizard parameters.</li>
                    </ol>
                """.trimIndent(),
                buttons = listOf(
                    PluginTooltipButton(
                        description = "Template Manager guide",
                        uri = "index.html",
                        order = 0
                    )
                )
            )
        )
    }

    // Tier 3 offline help: the host serves everything under src/main/assets/docs/ locally,
    // entry point docs/index.html, reachable from the tooltip button above.
    override fun getTier3DocsAssetPath(): String = "docs"

    override fun onDocumentationInstall(): Boolean {
        context.logger.info("Installing TemplateManagerPlugin documentation")
        return true
    }

    override fun onDocumentationUninstall() {
        context.logger.info("Removing TemplateManagerPlugin documentation")
    }
}
