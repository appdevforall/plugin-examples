package com.appdevforall.contractor.plugin.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleOwner

abstract class ContractorScreen(
    protected val parent: Fragment,
    protected val container: ViewGroup
) {
    protected val context: Context = container.context
    protected val inflater: LayoutInflater = LayoutInflater.from(context)
    protected val viewLifecycleOwner: LifecycleOwner get() = parent.viewLifecycleOwner
    protected val activity: FragmentActivity get() = parent.requireActivity()
    protected val fragmentManager: FragmentManager get() = parent.childFragmentManager
}
