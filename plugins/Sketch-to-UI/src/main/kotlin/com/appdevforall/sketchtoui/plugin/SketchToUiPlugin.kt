package com.appdevforall.sketchtoui.plugin

import android.content.Context
import android.widget.Toast
import com.appdevforall.sketchtoui.plugin.fragments.SketchToUiFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.ToolbarAction
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorService
import com.itsaky.androidide.plugins.services.IdeUIService
import java.io.File

class SketchToUiPlugin : IPlugin, UIExtension, DocumentationExtension {

    private lateinit var context: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        this.context = context
        pluginAndroidContext = context.androidContext
        context.logger.info("SketchToUiPlugin initialized")
        return true
    }

    override fun activate(): Boolean = true

    override fun deactivate(): Boolean = true

    override fun dispose() = Unit

    override fun getMainMenuItems(): List<MenuItem> {
        return listOf(
            MenuItem(
                id = "sketch_to_ui",
                title = "Sketch to UI",
                isEnabled = true,
                isVisible = true,
                shortcut = null,
                subItems = emptyList(),
                action = { openSketchToUiIfValid() },
                tooltipTag = TOOLTIP_SKETCH_TO_UI,
                icon = R.drawable.ic_computer_vision,
            ).apply {
                isEnabledProvider = { hasCurrentLayoutXml() }
            }
        )
    }

    override fun getSideMenuItems(): List<NavigationItem> = emptyList()

    override fun getToolbarActions(): List<ToolbarAction> = emptyList()

    override fun getTooltipCategory(): String = "plugin_$PLUGIN_ID"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_SKETCH_TO_UI,
            summary = "<b>Sketch to UI</b><br>Convert a layout sketch or screenshot into Android XML.",
            detail = """
                <h3>Sketch to UI</h3>
                <p>Use this action from an Android layout XML file to open the sketch conversion workflow.</p>

                <h4>Availability</h4>
                <p>The action is enabled only when the current editor file is a layout XML resource.</p>
            """.trimIndent()
        )
    )

    private fun openSketchToUiIfValid() {
        val currentFile = getCurrentLayoutXml()
        if (currentFile == null) {
            context.logger.warn("Sketch to UI requires an Android layout XML file")
            SketchToUiState.setLayoutFile(null, null)
            showToast("Open an Android layout XML file before generating XML.")
            return
        }

        SketchToUiState.setLayoutFile(currentFile.absolutePath, currentFile.name)
        openPluginScreen()
    }

    private fun openPluginScreen() {
        val opened = context.services
            .get(IdeUIService::class.java)
            ?.openPluginScreen(
                pluginId = PLUGIN_ID,
                fragmentClassName = SketchToUiFragment::class.java.name,
                title = "Generate XML"
            ) == true

        if (!opened) {
            context.logger.warn("Unable to open Sketch to UI plugin screen")
        }
    }

    private fun hasCurrentLayoutXml(): Boolean {
        val currentFile = context.services
            .get(IdeEditorService::class.java)
            ?.getCurrentFile()
            ?: return false

        return currentFile.isLayoutXml()
    }

    private fun getCurrentLayoutXml(): File? {
        val currentFile = context.services
            .get(IdeEditorService::class.java)
            ?.getCurrentFile()
            ?: return null

        return currentFile.takeIf { it.isLayoutXml() }
    }

    private fun File.isLayoutXml(): Boolean =
        extension.equals("xml", ignoreCase = true) &&
            parentFile?.name == "layout"

    private fun showToast(message: String) {
        Toast.makeText(context.androidContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val PLUGIN_ID = "com.appdevforall.sketchtoui.plugin"
        private const val TOOLTIP_SKETCH_TO_UI = "sketch_to_ui.main_feature"
        private var pluginAndroidContext: Context? = null

        fun getPluginAndroidContext(): Context? = pluginAndroidContext
    }
}
