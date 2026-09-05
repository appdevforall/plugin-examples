package com.appdevforall.contractor.plugin.ui.sessions

import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.FragmentSessionsBinding
import com.appdevforall.contractor.plugin.domain.model.DurationFormat
import com.appdevforall.contractor.plugin.ui.common.ContractorScreen
import com.appdevforall.contractor.plugin.ui.common.ContractorSharedViewModel
import com.appdevforall.contractor.plugin.ui.common.collectStarted
import com.appdevforall.contractor.plugin.ui.common.openListPicker
import com.google.android.material.datepicker.MaterialDatePicker
import java.time.Instant
import java.time.ZoneId

class SessionsScreen(
    parent: Fragment,
    container: ViewGroup,
    private val sharedVm: ContractorSharedViewModel
) : ContractorScreen(parent, container) {

    private val binding = FragmentSessionsBinding.inflate(inflater, container, true)

    private val viewModel: SessionsViewModel =
        ViewModelProvider(parent, SessionsViewModel.factory())[SessionsViewModel::class.java]

    private val adapter = SessionsAdapter { session ->
        viewModel.dispatch(SessionsIntent.RequestEdit(session.id))
    }

    init {
        binding.recycler.layoutManager = LinearLayoutManager(context)
        binding.recycler.adapter = adapter

        binding.rangeChips.setOnCheckedStateChangeListener { _, ids ->
            val id = ids.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val preset = when (id) {
                R.id.chip_today -> RangePreset.TODAY
                R.id.chip_week -> RangePreset.WEEK
                R.id.chip_month -> RangePreset.MONTH
                R.id.chip_custom -> RangePreset.CUSTOM
                else -> return@setOnCheckedStateChangeListener
            }
            viewModel.dispatch(SessionsIntent.SetPreset(preset))
        }

        val openProjectFilterPicker = { pickProjectFilter() }
        binding.projectFilterInput.setOnClickListener { openProjectFilterPicker() }
        binding.projectFilterLayout.setEndIconOnClickListener { openProjectFilterPicker() }

        binding.btnAddSession.setOnClickListener {
            viewModel.dispatch(SessionsIntent.RequestAddManual)
        }

        viewLifecycleOwner.collectStarted(viewModel.state) { render(it) }
        viewLifecycleOwner.collectStarted(viewModel.effects) { handle(it) }
        viewLifecycleOwner.collectStarted(sharedVm.focusOnProject) { pid ->
            viewModel.dispatch(SessionsIntent.SetProject(pid))
        }
    }

    private fun render(state: SessionsState) {
        val selectedLabel = state.selectedProjectId?.let { id ->
            state.projects.firstOrNull { it.id == id }?.displayName
        } ?: activity.getString(R.string.sessions_filter_project)
        if (binding.projectFilterInput.text?.toString() != selectedLabel) {
            binding.projectFilterInput.setText(selectedLabel)
        }

        binding.totalValue.text = DurationFormat.formatHoursMinutes(state.totalMillis)
        adapter.submitList(state.items)
        binding.emptyState.visibility = if (state.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handle(effect: SessionsEffect) {
        when (effect) {
            SessionsEffect.OpenCustomRangePicker -> openCustomRangePicker()
            is SessionsEffect.OpenSessionEditor -> {
                SessionEditBottomSheet.newInstance(effect.sessionId, effect.preselectedProjectId)
                    .show(fragmentManager, "session_editor")
            }
        }
    }

    private fun pickProjectFilter() {
        val state = viewModel.state.value
        val labels = mutableListOf(activity.getString(R.string.sessions_filter_project))
        labels += state.projects.map { it.displayName }
        val currentSelectionIndex = state.selectedProjectId?.let { id ->
            val idx = state.projects.indexOfFirst { it.id == id }
            if (idx >= 0) idx + 1 else 0
        } ?: 0
        activity.openListPicker(
            title = activity.getString(R.string.sessions_filter_project),
            items = labels,
            selectedIndex = currentSelectionIndex
        ) { idx, _ ->
            val pid = if (idx == 0) null else state.projects.getOrNull(idx - 1)?.id
            viewModel.dispatch(SessionsIntent.SetProject(pid))
        }
    }

    private fun openCustomRangePicker() {
        val builder = MaterialDatePicker.Builder.dateRangePicker()
        builder.setTitleText(R.string.sessions_range_custom)
        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { pair ->
            val zone = ZoneId.systemDefault()
            val start = Instant.ofEpochMilli(pair.first).atZone(zone).toLocalDate()
            val end = Instant.ofEpochMilli(pair.second).atZone(zone).toLocalDate()
            viewModel.dispatch(SessionsIntent.SetCustomRange(start, end))
        }
        picker.show(fragmentManager, "session_range_picker")
    }
}
