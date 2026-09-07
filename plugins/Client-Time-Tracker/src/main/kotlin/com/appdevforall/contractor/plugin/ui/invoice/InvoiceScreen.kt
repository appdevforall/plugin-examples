package com.appdevforall.contractor.plugin.ui.invoice

import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.FragmentInvoiceBinding
import com.appdevforall.contractor.plugin.domain.model.InvoiceFormat
import com.appdevforall.contractor.plugin.ui.common.ContractorScreen
import com.appdevforall.contractor.plugin.ui.common.collectStarted
import com.appdevforall.contractor.plugin.ui.common.openListPicker
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class InvoiceScreen(
    parent: Fragment,
    container: ViewGroup
) : ContractorScreen(parent, container) {

    private val binding = FragmentInvoiceBinding.inflate(inflater, container, true)

    private val viewModel: InvoiceViewModel =
        ViewModelProvider(parent, InvoiceViewModel.factory())[InvoiceViewModel::class.java]

    init {
        val openProjectPicker = { pickProject() }
        binding.projectInput.setOnClickListener { openProjectPicker() }
        binding.projectLayout.setEndIconOnClickListener { openProjectPicker() }

        binding.rangeChips.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val preset = when (id) {
                R.id.chip_inv_week -> RangePresetForInvoice.WEEK
                R.id.chip_inv_month -> RangePresetForInvoice.MONTH
                R.id.chip_inv_lastmonth -> RangePresetForInvoice.LAST_MONTH
                R.id.chip_inv_custom -> RangePresetForInvoice.CUSTOM
                else -> return@setOnCheckedStateChangeListener
            }
            viewModel.dispatch(InvoiceIntent.SetPreset(preset))
        }

        binding.formatPdf.setOnClickListener { viewModel.dispatch(InvoiceIntent.ToggleFormat(InvoiceFormat.PDF)) }
        binding.formatXlsx.setOnClickListener { viewModel.dispatch(InvoiceIntent.ToggleFormat(InvoiceFormat.XLSX)) }
        binding.formatCsv.setOnClickListener { viewModel.dispatch(InvoiceIntent.ToggleFormat(InvoiceFormat.CSV)) }

        binding.invoiceNumberInput.addTextChangedListener(simpleWatcher { viewModel.dispatch(InvoiceIntent.SetInvoiceNumber(it)) })
        binding.notesInput.addTextChangedListener(simpleWatcher { viewModel.dispatch(InvoiceIntent.SetNotes(it)) })

        binding.btnGenerate.setOnClickListener { viewModel.dispatch(InvoiceIntent.Generate) }

        viewLifecycleOwner.collectStarted(viewModel.state) { render(it) }
        viewLifecycleOwner.collectStarted(viewModel.effects) { handle(it) }
    }

    private fun render(state: InvoiceState) {
        val selectedLabel = state.selectedProjectId?.let { id ->
            state.projects.firstOrNull { it.id == id }?.displayName.orEmpty()
        } ?: ""
        if (binding.projectInput.text?.toString() != selectedLabel) {
            binding.projectInput.setText(selectedLabel)
        }

        binding.rangeSummary.text = formatRange(state.range.fromMillis, state.range.toMillis)
        if (binding.invoiceNumberInput.text?.toString() != state.invoiceNumber) {
            binding.invoiceNumberInput.setText(state.invoiceNumber)
            binding.invoiceNumberInput.setSelection(state.invoiceNumber.length)
        }

        binding.formatPdf.isChecked = state.formats.contains(InvoiceFormat.PDF)
        binding.formatXlsx.isChecked = state.formats.contains(InvoiceFormat.XLSX)
        binding.formatCsv.isChecked = state.formats.contains(InvoiceFormat.CSV)

        binding.btnGenerate.isEnabled = !state.isGenerating
        binding.progress.visibility = if (state.isGenerating) View.VISIBLE else View.GONE
    }

    private fun handle(effect: InvoiceEffect) {
        when (effect) {
            is InvoiceEffect.ShowSuccess -> {
                InvoiceSuccessSheet.newInstance(effect.result)
                    .show(fragmentManager, "invoice_success")
            }
            is InvoiceEffect.ShowError -> showErrorSheet(effect.code)
            InvoiceEffect.OpenCustomRangePicker -> openCustomRangePicker()
        }
    }

    private fun showErrorSheet(code: InvoiceEffect.ErrorCode) {
        val (titleRes, bodyRes) = when (code) {
            InvoiceEffect.ErrorCode.NoProject -> R.string.error_no_project_title to R.string.invoice_pick_project_prompt
            InvoiceEffect.ErrorCode.NoFormat -> R.string.error_no_format_title to R.string.invoice_pick_format_prompt
            InvoiceEffect.ErrorCode.NoSessions -> R.string.error_no_sessions_title to R.string.invoice_no_sessions
            InvoiceEffect.ErrorCode.ProjectMissing -> R.string.error_project_missing_title to R.string.error_project_missing_body
            InvoiceEffect.ErrorCode.GenerationFailed -> R.string.error_generation_title to R.string.error_generation_body
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(titleRes)
            .setMessage(bodyRes)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun pickProject() {
        val state = viewModel.state.value
        if (state.projects.isEmpty()) return
        val labels = state.projects.map { it.displayName }
        val currentIndex = state.selectedProjectId?.let { id ->
            state.projects.indexOfFirst { it.id == id }
        } ?: -1
        activity.openListPicker(
            title = activity.getString(R.string.invoice_picker_project),
            items = labels,
            selectedIndex = currentIndex
        ) { idx, _ ->
            viewModel.dispatch(InvoiceIntent.SelectProject(state.projects.getOrNull(idx)?.id))
        }
    }

    private fun openCustomRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
        builder.setTitleText(R.string.invoice_picker_range)
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { pair ->
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(pair.first).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(pair.second).atZone(zone).toLocalDate()
            viewModel.dispatch(InvoiceIntent.SetCustomRange(start, end))
        }
        picker.show(fragmentManager, "invoice_range_picker")
    }

    private fun formatRange(fromMs: Long, toMsExclusive: Long): String {
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(fromMs).atZone(zone).toLocalDate()
        val endInclusive = Instant.ofEpochMilli(toMsExclusive - 1).atZone(zone).toLocalDate()
        return "${RANGE_FMT.format(start)} -> ${RANGE_FMT.format(endInclusive)}"
    }

    private fun simpleWatcher(onText: (String) -> Unit): TextWatcher = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { onText(s?.toString().orEmpty()) }
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    }

    companion object {
        private val RANGE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    }
}
