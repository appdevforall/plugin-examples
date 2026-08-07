package com.appdevforall.pair.plugin

import com.appdevforall.pair.plugin.ui.main.PairMainFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.DocumentationExtension
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.PluginTooltipButton
import com.itsaky.androidide.plugins.extensions.PluginTooltipEntry
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorTabService

class PairPlugin : IPlugin, UIExtension, EditorTabExtension, DocumentationExtension {

    private lateinit var pluginContext: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        this.pluginContext = context
        return runCatching {
            PairServiceLocator.init(context)
            context.logger.info("PairPlugin initialized")
            true
        }.getOrElse {
            context.logger.error("PairPlugin: initialize failed", it)
            false
        }
    }

    override fun activate(): Boolean {
        pluginContext.logger.info("PairPlugin activated")
        return true
    }

    override fun deactivate(): Boolean {
        runCatching { PairServiceLocator.get().broker.stopSession() }
        return true
    }

    override fun dispose() {
        runCatching { PairServiceLocator.shutdown() }
    }

    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(
            EditorTabItem(
                id = TAB_ID,
                title = "Pair",
                icon = R.drawable.ic_pair,
                fragmentFactory = { PairMainFragment() },
                isCloseable = true,
                isPersistent = false,
                order = 0,
                isEnabled = true,
                isVisible = true,
                tooltip = "Real-time pair programming over LAN",
            )
        )
    }

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "pair_open",
                title = "Pair",
                icon = R.drawable.ic_pair,
                isEnabled = true,
                isVisible = true,
                group = "tools",
                order = 0,
                action = { openPairTab() },
                tooltipTag = TOOLTIP_TAG_SIDEBAR,
            )
        )
    }

    override fun getTooltipCategory(): String = "plugin_pair"

    override fun getTooltipEntries(): List<PluginTooltipEntry> = listOf(
        PluginTooltipEntry(
            tag = TOOLTIP_TAG_SIDEBAR,
            summary = "Pair programming over local WiFi: one device hosts, others join, edits and cursors sync live.",
            detail = """
                <p>Opens the <b>Pair</b> tab, which runs a real-time collaboration
                session between devices on the same network &mdash; no server,
                no account.</p>
                <ul>
                  <li><b>Host session</b> &mdash; shows an invite card with your
                  <code>ip:port</code> and a QR code.</li>
                  <li><b>Join</b> &mdash; scan the host's QR code or type the address.</li>
                  <li>Edits, cursor positions, and file opens flow between peers as
                  they happen; each peer appears in the peer list with a colored
                  cursor in the editor.</li>
                  <li>Past sessions are listed on the Home screen to rename, delete,
                  or reconnect.</li>
                </ul>
            """.trimIndent(),
            buttons = listOf(
                PluginTooltipButton(
                    description = "User guide",
                    uri = "index.html",
                    order = 0,
                )
            ),
        ),
    )

    override fun getTier3DocsAssetPath(): String? = "docs"

    private fun openPairTab() {
        val tabService = pluginContext.services.get(IdeEditorTabService::class.java) ?: run {
            pluginContext.logger.error("PairPlugin: editor tab service unavailable")
            return
        }
        if (!tabService.isTabSystemAvailable()) {
            pluginContext.logger.error("PairPlugin: editor tab system not available")
            return
        }
        runCatching { tabService.selectPluginTab(TAB_ID) }
            .onFailure { pluginContext.logger.error("PairPlugin: failed to open tab", it) }
    }

    companion object {
        const val PLUGIN_ID: String = "com.appdevforall.pair.plugin"
        const val TAB_ID: String = "pair_main"
        const val TOOLTIP_TAG_SIDEBAR: String = "pair.sidebar"
    }
}
