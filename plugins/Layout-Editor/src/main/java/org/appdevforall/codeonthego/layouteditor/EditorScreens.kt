package org.appdevforall.codeonthego.layouteditor

import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import com.itsaky.androidide.plugins.services.IdeUIService

/**
 * Opens one of the layout editor's sub-screens full-screen via the host's PluginScreenActivity
 * (IdeUIService.openPluginScreen), resolved from the plugin service registry. Used from fragments
 * and adapters alike. Data is handed over through [EditorSubScreenState] (and per-screen callbacks).
 */
fun openLayoutEditorScreen(fragmentClassName: String, title: String) {
    PluginFragmentHelper.getServiceRegistry(LayoutEditorPlugin.PLUGIN_ID)
        ?.get(IdeUIService::class.java)
        ?.openPluginScreen(LayoutEditorPlugin.PLUGIN_ID, fragmentClassName, title)
}
