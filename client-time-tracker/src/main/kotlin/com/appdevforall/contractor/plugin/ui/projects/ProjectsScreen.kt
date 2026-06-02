package com.appdevforall.contractor.plugin.ui.projects

import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.appdevforall.contractor.plugin.R
import com.appdevforall.contractor.plugin.databinding.FragmentProjectsBinding
import com.appdevforall.contractor.plugin.ui.common.ContractorScreen
import com.appdevforall.contractor.plugin.ui.common.ContractorSharedViewModel
import com.appdevforall.contractor.plugin.ui.common.collectStarted
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ProjectsScreen(
    parent: Fragment,
    container: ViewGroup,
    sharedVm: ContractorSharedViewModel
) : ContractorScreen(parent, container) {

    private val binding = FragmentProjectsBinding.inflate(inflater, container, true)

    private val viewModel: ProjectsViewModel =
        ViewModelProvider(parent, ProjectsViewModel.factory(sharedVm))[ProjectsViewModel::class.java]

    private val adapter = ProjectsAdapter(
        onClick = { row -> viewModel.dispatch(ProjectsIntent.FocusOnSessions(row.project.id)) },
        onMore = { row, anchor -> showOverflow(row, anchor) }
    )

    init {
        binding.recycler.layoutManager = LinearLayoutManager(context)
        binding.recycler.adapter = adapter

        binding.btnRegisterEmpty.setOnClickListener {
            viewModel.dispatch(ProjectsIntent.RequestRegisterCurrent)
        }

        viewLifecycleOwner.collectStarted(viewModel.state) { render(it) }
        viewLifecycleOwner.collectStarted(viewModel.effects) { handle(it) }
    }

    private fun render(state: ProjectsState) {
        adapter.submitList(state.rows)
        binding.emptyState.visibility = if (state.isLoaded && state.rows.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun handle(effect: ProjectsEffect) {
        when (effect) {
            is ProjectsEffect.OpenRegisterForm -> {
                if (effect.alreadyRegistered) {
                    MaterialAlertDialogBuilder(activity)
                        .setMessage(R.string.form_already_registered)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                } else {
                    ProjectFormBottomSheet
                        .newInstanceForRegister(effect.prefillRootPath, effect.prefillDisplayName)
                        .show(fragmentManager, "project_form")
                }
            }
            is ProjectsEffect.OpenEditForm -> {
                ProjectFormBottomSheet.newInstanceForEdit(effect.projectId)
                    .show(fragmentManager, "project_form")
            }
        }
    }

    private fun showOverflow(row: ProjectRow, anchor: View) {
        val popup = PopupMenu(context, anchor)
        popup.menu.add(0, MENU_EDIT, 0, R.string.project_overflow_edit)
        if (row.project.isArchived) {
            popup.menu.add(0, MENU_UNARCHIVE, 1, R.string.project_overflow_unarchive)
        } else {
            popup.menu.add(0, MENU_ARCHIVE, 1, R.string.project_overflow_archive)
        }
        popup.menu.add(0, MENU_DELETE, 2, R.string.project_overflow_delete)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_EDIT -> {
                    viewModel.dispatch(ProjectsIntent.RequestEdit(row.project.id))
                    true
                }
                MENU_ARCHIVE -> {
                    viewModel.dispatch(ProjectsIntent.SetArchived(row.project.id, true))
                    true
                }
                MENU_UNARCHIVE -> {
                    viewModel.dispatch(ProjectsIntent.SetArchived(row.project.id, false))
                    true
                }
                MENU_DELETE -> {
                    confirmDelete(row)
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmDelete(row: ProjectRow) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.project_delete_confirm_title)
            .setMessage(activity.getString(R.string.project_delete_confirm_body, 0))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                viewModel.dispatch(ProjectsIntent.Delete(row.project.id))
            }
            .show()
    }

    companion object {
        private const val MENU_EDIT = 1
        private const val MENU_ARCHIVE = 2
        private const val MENU_UNARCHIVE = 3
        private const val MENU_DELETE = 4
    }
}
