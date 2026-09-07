package com.appdevforall.contractor.plugin

import com.appdevforall.contractor.plugin.ui.main.ContractorMainFragment
import com.itsaky.androidide.plugins.IPlugin
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.extensions.EditorTabExtension
import com.itsaky.androidide.plugins.extensions.EditorTabItem
import com.itsaky.androidide.plugins.extensions.NavigationItem
import com.itsaky.androidide.plugins.extensions.UIExtension
import com.itsaky.androidide.plugins.services.IdeEditorTabService

class ContractorPlugin : IPlugin, UIExtension, EditorTabExtension {

    private lateinit var pluginContext: PluginContext

    override fun initialize(context: PluginContext): Boolean {
        return runCatching {
            this.pluginContext = context
            ContractorServiceLocator.init(context)
            context.logger.info("ContractorPlugin initialized")
            true
        }.getOrElse {
            context.logger.error("ContractorPlugin: initialize failed", it)
            false
        }
    }

    override fun activate(): Boolean {
        return runCatching {
            ContractorServiceLocator.get().sessionTracker.start()
            pluginContext.logger.info("ContractorPlugin activated")
            true
        }.getOrElse {
            pluginContext.logger.error("ContractorPlugin: activate failed", it)
            false
        }
    }

    override fun deactivate(): Boolean {
        runCatching {
            ContractorServiceLocator.get().sessionTracker.stop()
        }
        return true
    }

    override fun dispose() {
        runCatching { ContractorServiceLocator.shutdown() }
    }

    override fun getMainEditorTabs(): List<EditorTabItem> {
        return listOf(
            EditorTabItem(
                id = TAB_ID,
                title = "Client Time Tracker",
                icon = com.appdevforall.contractor.plugin.R.drawable.ic_contractor,
                fragmentFactory = { ContractorMainFragment() },
                isCloseable = true,
                isPersistent = false,
                order = 0,
                isEnabled = true,
                isVisible = true,
                tooltip = "Track billable time and generate invoices"
            )
        )
    }

    override fun getSideMenuItems(): List<NavigationItem> {
        return listOf(
            NavigationItem(
                id = "contractor_open",
                title = "Client Time Tracker",
                icon = com.appdevforall.contractor.plugin.R.drawable.ic_contractor,
                isEnabled = true,
                isVisible = true,
                group = "tools",
                order = 0,
                action = { openContractorTab() }
            )
        )
    }

    private fun openContractorTab() {
        val tabService = pluginContext.services.get(IdeEditorTabService::class.java) ?: run {
            pluginContext.logger.error("ContractorPlugin: editor tab service unavailable")
            return
        }
        if (!tabService.isTabSystemAvailable()) {
            pluginContext.logger.error("ContractorPlugin: editor tab system not available")
            return
        }
        runCatching { tabService.selectPluginTab(TAB_ID) }
            .onFailure { pluginContext.logger.error("ContractorPlugin: failed to open tab", it) }
    }

    companion object {
        const val PLUGIN_ID = "com.appdevforall.contractor.plugin"
        const val TAB_ID = "contractor_main"
    }
}
