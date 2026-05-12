package com.codeonthego.xkcdrandom

import com.codeonthego.xkcdrandom.fragments.XkcdPanelFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.TabItem
import com.itsaky.androidide.plugins.extensions.UIExtension

/**
 * Random-xkcd demo plugin.
 *
 * Reading order:
 *   - this file: lifecycle + tab registration
 *   - [XkcdPanelFragment]: the bottom-sheet UI
 */
class XkcdRandomPlugin : IPlugin, UIExtension {

    private lateinit var context: PluginContext

    companion object {
        const val PLUGIN_ID = "com.codeonthego.xkcdrandom"
        const val TAB_ID = "xkcd_bottom_tab"
    }

    override fun initialize(context: PluginContext): Boolean {
        // initialize() returns Boolean — the IDE skips activate() if this
        // returns false. Wrap in try/catch so a stray exception in our
        // setup can't crash the host IDE.
        return try {
            this.context = context
            context.logger.info("XkcdRandomPlugin initialized")
            true
        } catch (t: Throwable) {
            context.logger.error("XkcdRandomPlugin initialization failed", t)
            false
        }
    }

    override fun activate(): Boolean {
        context.logger.info("XkcdRandomPlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        context.logger.info("XkcdRandomPlugin deactivated")
        return true
    }

    override fun dispose() {
        context.logger.info("XkcdRandomPlugin disposed")
    }

    // --- UIExtension: register the bottom-sheet tab ---

    /**
     * Register one bottom-sheet tab. The IDE shows it next to the eight
     * built-in tabs (Build Output, App Logs, …) plus tabs from other
     * plugins. `order` controls our position among plugin tabs only.
     *
     * The fragmentFactory returns a *new* fragment each time the tab is
     * shown — never reuse a single Fragment instance, fragments have
     * lifecycle expectations the IDE manages.
     */
    override fun getEditorTabs(): List<TabItem> = listOf(
        TabItem(
            id = TAB_ID,
            title = "XKCD",
            fragmentFactory = { XkcdPanelFragment() },
            order = 200
        )
    )
}
