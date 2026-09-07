package com.appdevforall.contractor.plugin.ui.settings

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.appdevforall.contractor.plugin.ContractorPlugin
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.SheetSettingsBinding
import com.appdevforall.contractor.plugin.domain.model.MoneyFormat
import com.appdevforall.contractor.plugin.ui.common.collectStarted
import com.appdevforall.contractor.plugin.ui.common.openListPicker
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels { SettingsViewModel.factory() }

    override fun getTheme(): Int = R.style.PluginBottomSheetDialog

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        return PluginFragmentHelper.getPluginInflater(ContractorPlugin.PLUGIN_ID, super.onGetLayoutInflater(savedInstanceState))
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.idleSlider.addOnChangeListener { _, value, _ ->
            binding.idleValue.text = getString(R.string.settings_idle_threshold_value, value.toInt())
        }
        binding.mergeSlider.addOnChangeListener { _, value, _ ->
            binding.mergeValue.text = getString(R.string.settings_merge_gap_value, value.toInt())
        }

        val currencies = MoneyFormat.commonCurrencies()
        val openCurrencyPicker = {
            val current = binding.defaultCurrencyInput.text?.toString().orEmpty()
            openListPicker(
                title = getString(R.string.settings_default_currency),
                items = currencies,
                selectedIndex = currencies.indexOf(current)
            ) { _, value -> binding.defaultCurrencyInput.setText(value) }
        }
        binding.defaultCurrencyInput.setOnClickListener { openCurrencyPicker() }
        binding.defaultCurrencyLayout.setEndIconOnClickListener { openCurrencyPicker() }

        binding.btnDone.setOnClickListener { onSavePressed() }

        collectStarted(viewModel.state) { render(it) }
        collectStarted(viewModel.effects) { handle(it) }

        viewModel.dispatch(SettingsIntent.Init)
    }

    private var prefillApplied = false
    private fun render(state: SettingsState) {
        val initial = state.initial ?: return
        if (prefillApplied) return
        prefillApplied = true
        binding.idleSlider.value = initial.idleMinutes.toFloat()
        binding.idleValue.text = getString(R.string.settings_idle_threshold_value, initial.idleMinutes)
        binding.mergeSlider.value = initial.mergeMinutes.toFloat()
        binding.mergeValue.text = getString(R.string.settings_merge_gap_value, initial.mergeMinutes)
        binding.defaultTaxInput.setText(initial.defaultTaxPercent.toString())
        binding.defaultCurrencyInput.setText(initial.defaultCurrency)
        binding.invoicePrefixInput.setText(initial.invoicePrefix)
        binding.businessNameInput.setText(initial.businessName)
        binding.businessEmailInput.setText(initial.businessEmail)
        binding.businessAddressInput.setText(initial.businessAddress)
    }

    private fun handle(effect: SettingsEffect) {
        when (effect) {
            SettingsEffect.Dismiss -> dismiss()
        }
    }

    private fun onSavePressed() {
        val values = SettingsValues(
            idleMinutes = binding.idleSlider.value.toInt(),
            mergeMinutes = binding.mergeSlider.value.toInt(),
            defaultTaxPercent = binding.defaultTaxInput.text?.toString()?.toDoubleOrNull() ?: 0.0,
            defaultCurrency = binding.defaultCurrencyInput.text?.toString()?.uppercase().orEmpty(),
            invoicePrefix = binding.invoicePrefixInput.text?.toString().orEmpty(),
            businessName = binding.businessNameInput.text?.toString().orEmpty(),
            businessEmail = binding.businessEmailInput.text?.toString().orEmpty(),
            businessAddress = binding.businessAddressInput.text?.toString().orEmpty()
        )
        viewModel.dispatch(SettingsIntent.Save(values))
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
