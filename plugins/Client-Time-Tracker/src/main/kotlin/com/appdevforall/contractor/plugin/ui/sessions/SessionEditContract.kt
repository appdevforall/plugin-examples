package com.appdevforall.contractor.plugin.ui.sessions

import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import com.appdevforall.contractor.plugin.ui.common.UiEffect
import com.appdevforall.contractor.plugin.ui.common.UiIntent
import com.appdevforall.contractor.plugin.ui.common.UiState

data class SessionEditState(
    val mode: Mode = Mode.Add,
    val projects: List<TrackedProjectEntity> = emptyList(),
    val selectedProjectId: String? = null,
    val startMillis: Long = 0L,
    val endMillis: Long = 0L,
    val notes: String = "",
    val isReady: Boolean = false,
    val isSubmitting: Boolean = false,
    val invalidRange: Boolean = false
) : UiState {
    enum class Mode { Add, Edit }
}

sealed interface SessionEditIntent : UiIntent {
    data class Init(val sessionId: String?, val preselectedProjectId: String?) : SessionEditIntent
    data class SetProject(val projectId: String) : SessionEditIntent
    data class SetStart(val millis: Long) : SessionEditIntent
    data class SetEnd(val millis: Long) : SessionEditIntent
    data class Submit(val notes: String) : SessionEditIntent
    data object DeleteRequested : SessionEditIntent
    data object Cancel : SessionEditIntent
}

sealed interface SessionEditEffect : UiEffect {
    data object Dismiss : SessionEditEffect
    data object ShowDeleteConfirmation : SessionEditEffect
}
