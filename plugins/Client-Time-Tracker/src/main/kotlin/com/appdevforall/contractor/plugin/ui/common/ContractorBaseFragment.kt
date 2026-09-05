package com.appdevforall.contractor.plugin.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import androidx.fragment.app.Fragment
import com.appdevforall.contractor.plugin.ContractorPlugin
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

abstract class ContractorBaseFragment : Fragment() {

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(ContractorPlugin.PLUGIN_ID, inflater)
    }
}
