package com.appdevforall.contractor.plugin.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentFactory
import androidx.fragment.app.activityViewModels
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.FragmentContractorMainBinding
import com.appdevforall.contractor.plugin.ui.common.ContractorBaseFragment
import com.appdevforall.contractor.plugin.ui.common.ContractorSharedViewModel
import com.appdevforall.contractor.plugin.ui.common.collectStarted
import com.appdevforall.contractor.plugin.ui.invoice.InvoiceScreen
import com.appdevforall.contractor.plugin.ui.projects.ProjectsScreen
import com.appdevforall.contractor.plugin.ui.sessions.SessionsScreen
import com.appdevforall.contractor.plugin.ui.settings.SettingsBottomSheet
import com.google.android.material.tabs.TabLayout

class ContractorMainFragment : ContractorBaseFragment() {

    private var _binding: FragmentContractorMainBinding? = null
    private val binding get() = _binding!!

    private val sharedVm: ContractorSharedViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        childFragmentManager.fragmentFactory = PluginChildFragmentFactory(javaClass.classLoader!!)
        super.onCreate(savedInstanceState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentContractorMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnOverflow.setOnClickListener { showOverflow(it) }

        ProjectsScreen(this, binding.screenProjects, sharedVm)
        SessionsScreen(this, binding.screenSessions, sharedVm)
        InvoiceScreen(this, binding.screenInvoice)

        binding.tabs.removeAllTabs()
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_projects))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_sessions))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_invoice))

        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showTab(tab.position)
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        showTab(0)

        collectStarted(sharedVm.switchToTab) { idx ->
            binding.tabs.getTabAt(idx)?.select()
        }
    }

    private fun showTab(index: Int) {
        binding.screenProjects.visibility = if (index == 0) View.VISIBLE else View.GONE
        binding.screenSessions.visibility = if (index == 1) View.VISIBLE else View.GONE
        binding.screenInvoice.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun showOverflow(anchor: View) {
        val popup = PopupMenu(requireContext(), anchor)
        popup.menu.add(0, MENU_SETTINGS, 0, R.string.settings_title)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SETTINGS -> {
                    SettingsBottomSheet().show(childFragmentManager, "contractor_settings")
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val MENU_SETTINGS = 1
    }
}

/**
 * Routes child-fragment instantiation through the plugin's DexClassLoader. Required because
 * the host activity is recreated on uiMode changes (theme toggle), and FragmentManager
 * restores nested fragments by class name using the parent's classloader — which is the host
 * APK's, not the plugin's. Without this, restoring any plugin bottom sheet that was open at
 * the time of recreate throws ClassNotFoundException.
 *
 * Installed on childFragmentManager before super.onCreate() so it is in place when
 * restoreChildFragmentState() runs.
 */
private class PluginChildFragmentFactory(
    private val pluginClassLoader: ClassLoader
) : FragmentFactory() {
    override fun instantiate(classLoader: ClassLoader, className: String): Fragment {
        return try {
            val cls = pluginClassLoader.loadClass(className)
            cls.getDeclaredConstructor().newInstance() as Fragment
        } catch (_: ClassNotFoundException) {
            super.instantiate(classLoader, className)
        }
    }
}
