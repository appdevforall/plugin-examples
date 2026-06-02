package com.appdevforall.contractor.plugin.ui.projects

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.appdevforall.contractor.plugin.ContractorPlugin
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.SheetProjectFormBinding
import com.appdevforall.contractor.plugin.domain.model.MoneyFormat
import com.appdevforall.contractor.plugin.ui.common.collectStarted
import com.appdevforall.contractor.plugin.ui.common.openListPicker
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.itsaky.androidide.plugins.base.PluginFragmentHelper

class ProjectFormBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetProjectFormBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProjectFormViewModel by viewModels { ProjectFormViewModel.factory() }

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
        _binding = SheetProjectFormBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currencies = MoneyFormat.commonCurrencies()
        val openCurrencyPicker = {
            val current = binding.currencyInput.text?.toString().orEmpty()
            openListPicker(
                title = getString(R.string.form_label_currency),
                items = currencies,
                selectedIndex = currencies.indexOf(current)
            ) { _, value -> binding.currencyInput.setText(value) }
        }
        binding.currencyInput.setOnClickListener { openCurrencyPicker() }
        binding.currencyLayout.setEndIconOnClickListener { openCurrencyPicker() }

        binding.btnCancel.setOnClickListener { viewModel.dispatch(ProjectFormIntent.Cancel) }
        binding.btnSave.setOnClickListener { onSavePressed() }

        collectStarted(viewModel.state) { render(it) }
        collectStarted(viewModel.effects) { handle(it) }

        if (viewModel.state.value.isReady.not()) {
            viewModel.dispatch(
                ProjectFormIntent.Init(
                    editingId = arguments?.getString(ARG_EDIT_ID),
                    prefillRoot = arguments?.getString(ARG_ROOT_PATH),
                    prefillName = arguments?.getString(ARG_DISPLAY_NAME)
                )
            )
        }
    }

    private fun render(state: ProjectFormState) {
        binding.title.text = getString(
            if (state.mode == ProjectFormState.Mode.Edit) R.string.form_edit_project else R.string.form_register_project
        )
        if (state.isReady) applyPrefillOnce(state)

        binding.rootInput.isEnabled = state.prefill.rootEditable

        binding.rootLayout.error = state.errors.rootPath.errOrNull()
        binding.nameLayout.error = state.errors.displayName.errOrNull()
        binding.clientNameLayout.error = state.errors.clientName.errOrNull()
        binding.rateLayout.error = state.errors.rate.errInvalidNumberOrNull()
        binding.currencyLayout.error = state.errors.currency.errOrNull()
        binding.taxLayout.error = state.errors.tax.errInvalidNumberOrNull()

        binding.btnSave.isEnabled = !state.isSubmitting
    }

    private fun handle(effect: ProjectFormEffect) {
        when (effect) {
            ProjectFormEffect.Dismiss -> dismiss()
            ProjectFormEffect.ShowAlreadyRegistered -> {
                MaterialAlertDialogBuilder(requireActivity())
                    .setMessage(R.string.form_already_registered)
                    .setPositiveButton(android.R.string.ok) { _, _ -> dismiss() }
                    .show()
            }
        }
    }

    private var prefillApplied = false
    private fun applyPrefillOnce(state: ProjectFormState) {
        if (prefillApplied) return
        prefillApplied = true
        val p = state.prefill
        binding.rootInput.setText(p.rootPath)
        binding.nameInput.setText(p.displayName)
        binding.clientNameInput.setText(p.clientName)
        binding.clientEmailInput.setText(p.clientEmail)
        binding.clientAddressInput.setText(p.clientAddress)
        binding.rateInput.setText(p.rate)
        binding.currencyInput.setText(p.currency)
        binding.taxInput.setText(p.tax)
        binding.notesInput.setText(p.notes)
    }

    private fun onSavePressed() {
        val values = ProjectFormValues(
            rootPath = binding.rootInput.text?.toString()?.trim().orEmpty(),
            displayName = binding.nameInput.text?.toString()?.trim().orEmpty(),
            clientName = binding.clientNameInput.text?.toString()?.trim().orEmpty(),
            clientEmail = binding.clientEmailInput.text?.toString()?.trim(),
            clientAddress = binding.clientAddressInput.text?.toString()?.trim(),
            hourlyRate = binding.rateInput.text?.toString()?.trim()?.toDoubleOrNull() ?: -1.0,
            currency = binding.currencyInput.text?.toString()?.trim()?.uppercase().orEmpty(),
            taxPercent = binding.taxInput.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0,
            notes = binding.notesInput.text?.toString()?.trim()
        )
        viewModel.dispatch(ProjectFormIntent.Submit(values))
    }

    private fun Boolean.errOrNull(): String? =
        if (this) getString(R.string.form_required) else null

    private fun Boolean.errInvalidNumberOrNull(): String? =
        if (this) getString(R.string.form_invalid_number) else null

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_ROOT_PATH = "root_path"
        private const val ARG_DISPLAY_NAME = "display_name"
        private const val ARG_EDIT_ID = "edit_id"

        fun newInstanceForRegister(rootPath: String?, displayName: String?): ProjectFormBottomSheet {
            return ProjectFormBottomSheet().apply {
                arguments = Bundle().apply {
                    rootPath?.let { putString(ARG_ROOT_PATH, it) }
                    displayName?.let { putString(ARG_DISPLAY_NAME, it) }
                }
            }
        }

        fun newInstanceForEdit(id: String): ProjectFormBottomSheet {
            return ProjectFormBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_EDIT_ID, id) }
            }
        }
    }
}
