package org.appdevforall.codeonthego.layouteditor

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.LayoutInflater
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

fun pluginDialogContext(fallback: Context): Context {
    val activity = PluginFragmentHelper.getCurrentActivityContext(LayoutEditorPlugin.PLUGIN_ID)
    return if (activity != null) PluginDialogContext(activity, fallback) else fallback
}

class PluginDialogContext(
    activityContext: Context,
    private val pluginContext: Context,
) : ContextWrapper(activityContext) {

    private var inflater: LayoutInflater? = null

    override fun getResources(): Resources = pluginContext.resources

    override fun getAssets(): AssetManager = pluginContext.assets

    override fun getTheme(): Resources.Theme = pluginContext.theme

    override fun getClassLoader(): ClassLoader = pluginContext.classLoader

    override fun getSystemService(name: String): Any? {
        if (LAYOUT_INFLATER_SERVICE == name) {
            return inflater ?: (pluginContext.getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater)
                .cloneInContext(this)
                .also { inflater = it }
        }
        return super.getSystemService(name)
    }
}
