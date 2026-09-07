package com.appdevforall.contractor.plugin.ui.projects

import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import com.appdevforall.contractor.plugin.ui.common.UiEffect
import com.appdevforall.contractor.plugin.ui.common.UiIntent
import com.appdevforall.contractor.plugin.ui.common.UiState

data class ProjectRow(
    val project: TrackedProjectEntity,
    val monthMillis: Long,
    val isActiveTracked: Boolean
)

data class ProjectsState(
    val rows: List<ProjectRow> = emptyList(),
    val isLoaded: Boolean = false
) : UiState

sealed interface ProjectsIntent : UiIntent {
    /** User tapped "Register project" in the empty state. */
    data object RequestRegisterCurrent : ProjectsIntent

    /** User tapped "Edit" in the row overflow. */
    data class RequestEdit(val projectId: String) : ProjectsIntent

    /** User tapped a project row — focus it on the Sessions tab. */
    data class FocusOnSessions(val projectId: String) : ProjectsIntent

    /** User confirmed delete after the dialog. */
    data class Delete(val projectId: String) : ProjectsIntent

    /** Toggle archived flag from the overflow menu. */
    data class SetArchived(val projectId: String, val archived: Boolean) : ProjectsIntent
}

sealed interface ProjectsEffect : UiEffect {
    /** Open the form bottom sheet for a new registration. Prefill from the IDE's current
     *  project if available. If [alreadyRegistered] is true, the fragment should show a
     *  notice instead and not open the form. */
    data class OpenRegisterForm(
        val prefillRootPath: String?,
        val prefillDisplayName: String?,
        val alreadyRegistered: Boolean
    ) : ProjectsEffect

    /** Open the form bottom sheet in edit mode for the given project. */
    data class OpenEditForm(val projectId: String) : ProjectsEffect
}
