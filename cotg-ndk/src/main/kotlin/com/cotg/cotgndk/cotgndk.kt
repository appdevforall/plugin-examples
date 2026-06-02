package com.cotg.cotgndk

import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.MenuItem
import com.itsaky.androidide.plugins.services.IdeEditorTabService
import com.cotg.cotgndk.fragments.cotgndkFragment
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import androidx.fragment.app.Fragment

class cotgndk : IPlugin, UIExtension, EditorTabExtension {

    companion object {
        // GLOBAL BRIDGE: Makes the plugin context globally accessible across the engine
        lateinit var pluginCtx: PluginContext
    }

    override fun initialize(context: PluginContext): Boolean {
        pluginCtx = context
        context.logger.info("cotgndk: Extreme Engine Initialized")
        return true
    }

    override fun activate(): Boolean = true
    override fun deactivate(): Boolean = true
    override fun dispose() {}

    override fun getEditorTabs(): List<TabItem> = emptyList()
    override fun getMainMenuItems(): List<MenuItem> = emptyList()

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "com_cotg_cotgndk_sidebar",
                title = "COTG C++ Engine",
                icon = R.drawable.ic_plugin, 
                isEnabled = true,
                isVisible = true,
                group = "plugins",
                // FIX: Changed order from 0 to 1 to avoid clash with COTG KIT
                order = 1, 
                action = { openPluginTab() }
            )
        )
    }

    private fun openPluginTab() {
        val editorTabService = pluginCtx.services.get(IdeEditorTabService::class.java) ?: return
        if (editorTabService.isTabSystemAvailable()) {
            editorTabService.selectPluginTab("com_cotg_cotgndk_main")
        }
    }

    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(
            EditorTabItem(
                id = "com_cotg_cotgndk_main",
                title = "C++ Compiler",
                icon = R.drawable.ic_plugin,
                fragmentFactory = { cotgndkFragment() },
                isCloseable = true,
                isPersistent = false,
                // FIX: Changed order from 0 to 1 to match sidebar sorting priority
                order = 1,
                isEnabled = true,
                isVisible = true,
                tooltip = "Advanced NDK Engine"
            )
        )
    }

    override fun onEditorTabSelected(tabId: String, fragment: Fragment) {}
    override fun onEditorTabClosed(tabId: String) {}
    override fun canCloseEditorTab(tabId: String): Boolean = true
}
