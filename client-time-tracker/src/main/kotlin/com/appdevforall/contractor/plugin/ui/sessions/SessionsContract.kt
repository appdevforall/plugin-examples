package com.appdevforall.contractor.plugin.ui.sessions

import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import com.appdevforall.contractor.plugin.data.db.entities.WorkSessionEntity
import com.appdevforall.contractor.plugin.domain.model.DateRange
import com.appdevforall.contractor.plugin.domain.model.DateRanges
import com.appdevforall.contractor.plugin.ui.common.UiEffect
import com.appdevforall.contractor.plugin.ui.common.UiIntent
import com.appdevforall.contractor.plugin.ui.common.UiState
import java.time.LocalDate

enum class RangePreset { TODAY, WEEK, MONTH, CUSTOM }

data class SessionsState(
    val projects: List<TrackedProjectEntity> = emptyList(),
    val sessions: List<WorkSessionEntity> = emptyList(),
    val items: List<SessionListItem> = emptyList(),
    val totalMillis: Long = 0L,
    val selectedProjectId: String? = null,
    val rangePreset: RangePreset = RangePreset.MONTH,
    val range: DateRange = DateRanges.thisMonth()
) : UiState

sealed interface SessionsIntent : UiIntent {
    data class SetProject(val projectId: String?) : SessionsIntent
    data class SetPreset(val preset: RangePreset) : SessionsIntent
    data class SetCustomRange(val start: LocalDate, val end: LocalDate) : SessionsIntent
    data object RequestCustomRange : SessionsIntent
    data object RequestAddManual : SessionsIntent
    data class RequestEdit(val sessionId: String) : SessionsIntent
}

sealed interface SessionsEffect : UiEffect {
    data object OpenCustomRangePicker : SessionsEffect
    data class OpenSessionEditor(val sessionId: String?, val preselectedProjectId: String?) : SessionsEffect
}
