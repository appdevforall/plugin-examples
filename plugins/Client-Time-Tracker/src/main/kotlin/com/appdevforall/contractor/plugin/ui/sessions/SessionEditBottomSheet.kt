package com.appdevforall.contractor.plugin.ui.sessions

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.appdevforall.contractor.plugin.ContractorPlugin
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.SheetSessionEditBinding
import com.appdevforall.contractor.plugin.ui.common.collectStarted
import com.appdevforall.contractor.plugin.ui.common.openListPicker
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SessionEditBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSessionEditBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SessionEditViewModel by viewModels { SessionEditViewModel.factory() }

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
        _binding = SheetSessionEditBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val openProjectPicker = { pickProject() }
        binding.projectInput.setOnClickListener { openProjectPicker() }
        binding.projectLayout.setEndIconOnClickListener { openProjectPicker() }

        val openStart = { pickDateTime(isStart = true) }
        val openEnd = { pickDateTime(isStart = false) }
        binding.startInput.setOnClickListener { openStart() }
        binding.startLayout.setEndIconOnClickListener { openStart() }
        binding.endInput.setOnClickListener { openEnd() }
        binding.endLayout.setEndIconOnClickListener { openEnd() }
        binding.cancelButton.setOnClickListener { viewModel.dispatch(SessionEditIntent.Cancel) }
        binding.saveButton.setOnClickListener {
            viewModel.dispatch(SessionEditIntent.Submit(binding.notesInput.text?.toString().orEmpty()))
        }
        binding.deleteButton.setOnClickListener { viewModel.dispatch(SessionEditIntent.DeleteRequested) }

        collectStarted(viewModel.state) { render(it) }
        collectStarted(viewModel.effects) { handle(it) }

        if (!viewModel.state.value.isReady) {
            viewModel.dispatch(
                SessionEditIntent.Init(
                    sessionId = arguments?.getString(ARG_ID),
                    preselectedProjectId = arguments?.getString(ARG_PROJECT_ID)
                )
            )
        }
    }

    private fun render(state: SessionEditState) {
        binding.title.text = getString(
            if (state.mode == SessionEditState.Mode.Edit) R.string.sessions_edit_title else R.string.sessions_add_title
        )
        binding.deleteButton.visibility = if (state.mode == SessionEditState.Mode.Edit) View.VISIBLE else View.GONE

        if (state.isReady) applyNotesOnce(state.notes)

        val current = state.projects.firstOrNull { it.id == state.selectedProjectId }
        binding.projectInput.setText(current?.displayName.orEmpty())

        binding.startInput.setText(formatDateTime(state.startMillis))
        binding.endInput.setText(formatDateTime(state.endMillis))

        binding.saveButton.isEnabled = !state.isSubmitting && !state.invalidRange && state.selectedProjectId != null
    }

    private fun handle(effect: SessionEditEffect) {
        when (effect) {
            SessionEditEffect.Dismiss -> dismiss()
            SessionEditEffect.ShowDeleteConfirmation -> {
                MaterialAlertDialogBuilder(requireActivity())
                    .setMessage(R.string.sessions_delete_confirm)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.confirmDelete() }
                    .show()
            }
        }
    }

    private var notesApplied = false
    private fun applyNotesOnce(notes: String) {
        if (notesApplied) return
        notesApplied = true
        binding.notesInput.setText(notes)
    }

    private fun pickProject() {
        val state = viewModel.state.value
        if (state.projects.isEmpty()) return
        val labels = state.projects.map { it.displayName }
        val currentIndex = state.projects.indexOfFirst { it.id == state.selectedProjectId }
        openListPicker(
            title = getString(R.string.sessions_filter_project),
            items = labels,
            selectedIndex = currentIndex
        ) { idx, _ ->
            state.projects.getOrNull(idx)?.id?.let {
                viewModel.dispatch(SessionEditIntent.SetProject(it))
            }
        }
    }

    private fun pickDateTime(isStart: Boolean) {
        val state = viewModel.state.value
        val initialMs = if (isStart) state.startMillis else state.endMillis
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setSelection(initialMs)
            .build()
        datePicker.addOnPositiveButtonClickListener { dateMs ->
            val date = Instant.ofEpochMilli(dateMs).atZone(ZoneId.systemDefault()).toLocalDate()
            val initialTime = Instant.ofEpochMilli(initialMs).atZone(ZoneId.systemDefault()).toLocalTime()
            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_24H)
                .setHour(initialTime.hour)
                .setMinute(initialTime.minute)
                .build()
            timePicker.addOnPositiveButtonClickListener {
                val newDt = LocalDateTime.of(date, LocalTime.of(timePicker.hour, timePicker.minute))
                val newMs = newDt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                viewModel.dispatch(
                    if (isStart) SessionEditIntent.SetStart(newMs) else SessionEditIntent.SetEnd(newMs)
                )
            }
            // parentFragmentManager (the host SessionsFragment's child manager) is more stable
            // than the bottom sheet's own childFragmentManager when chaining dialogs; the bottom
            // sheet's FragmentManager can race with the date picker's dismissal. Posting the
            // show ensures the date picker has fully dismissed before the time picker opens.
            binding.root.post {
                timePicker.show(parentFragmentManager, "session_time_picker")
            }
        }
        datePicker.show(parentFragmentManager, "session_date_picker")
    }

    private fun formatDateTime(ms: Long): String =
        if (ms <= 0L) "" else FMT.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()))

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_ID = "session_id"
        private const val ARG_PROJECT_ID = "project_id"
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun newInstance(sessionId: String?, preselectedProjectId: String?): SessionEditBottomSheet =
            SessionEditBottomSheet().apply {
                arguments = Bundle().apply {
                    sessionId?.let { putString(ARG_ID, it) }
                    preselectedProjectId?.let { putString(ARG_PROJECT_ID, it) }
                }
            }
    }
}
