package com.appdevforall.pair.plugin.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import com.appdevforall.pair.plugin.PairPlugin
import com.appdevforall.pair.plugin.ui.theme.PluginTheme
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

class PairMainFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val pluginInflater = PluginFragmentHelper.getPluginInflater(PairPlugin.PLUGIN_ID, inflater)
        val pluginContext = pluginInflater.context
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                CompositionLocalProvider(
                    LocalContext provides pluginContext,
                ) {
                    PluginTheme {
                        PairRoot()
                    }
                }
            }
        }
    }
}
