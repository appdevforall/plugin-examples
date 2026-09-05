package com.appdevforall.contractor.plugin.ui.sessions

import androidx.lifecycle.viewModelScope
import com.appdevforall.contractor.plugin.ContractorServiceLocator
import com.appdevforall.contractor.plugin.data.repository.ProjectRepository
import com.appdevforall.contractor.plugin.data.repository.SessionRepository
import com.appdevforall.contractor.plugin.domain.model.DateRanges
import com.appdevforall.contractor.plugin.domain.usecase.SessionMerger
import com.appdevforall.contractor.plugin.ui.common.BaseViewModel
import com.appdevforall.contractor.plugin.ui.common.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(
    private val projectRepository: ProjectRepository,
    private val sessionRepository: SessionRepository
) : BaseViewModel<SessionsState, SessionsIntent, SessionsEffect>(SessionsState()) {

    /** Internal trigger for the data pipeline. */
    private val filter = MutableStateFlow(
        InternalFilter(
            projectId = null,
            preset = RangePreset.MONTH,
            range = DateRanges.thisMonth()
        )
    )

    init {
        viewModelScope.launch {
            combine(projectRepository.observeAll(), filter) { projects, f -> projects to f }
                .flatMapLatest { (projects, f) ->
                    val sessionsFlow = if (f.projectId == null) {
                        sessionRepository.observeInRange(f.range.fromMillis, f.range.toMillis)
                    } else {
                        sessionRepository.observeForProjectInRange(f.projectId, f.range.fromMillis, f.range.toMillis)
                    }
                    combine(sessionsFlow, flowOf(projects)) { sessions, p ->
                        val total = sessions.sumOf { SessionMerger.durationOf(it) }
                        val items = SessionsAdapter.build(sessions, p)
                        SessionsState(
                            projects = p,
                            sessions = sessions,
                            items = items,
                            totalMillis = total,
                            selectedProjectId = f.projectId,
                            rangePreset = f.preset,
                            range = f.range
                        )
                    }
                }
                .collect { newState -> reduce { newState } }
        }
    }

    override fun handleIntent(intent: SessionsIntent) {
        when (intent) {
            is SessionsIntent.SetProject -> filter.value = filter.value.copy(projectId = intent.projectId)
            is SessionsIntent.SetPreset -> applyPreset(intent.preset)
            is SessionsIntent.SetCustomRange -> filter.value = filter.value.copy(
                preset = RangePreset.CUSTOM,
                range = DateRanges.custom(intent.start, intent.end)
            )
            SessionsIntent.RequestCustomRange -> emit(SessionsEffect.OpenCustomRangePicker)
            SessionsIntent.RequestAddManual -> {
                val state = currentState
                if (state.projects.isEmpty()) return
                emit(SessionsEffect.OpenSessionEditor(sessionId = null, preselectedProjectId = state.selectedProjectId))
            }
            is SessionsIntent.RequestEdit -> emit(SessionsEffect.OpenSessionEditor(sessionId = intent.sessionId, preselectedProjectId = null))
        }
    }

    private fun applyPreset(preset: RangePreset) {
        if (preset == RangePreset.CUSTOM) {
            emit(SessionsEffect.OpenCustomRangePicker)
            return
        }
        val range = when (preset) {
            RangePreset.TODAY -> DateRanges.today()
            RangePreset.WEEK -> DateRanges.thisWeek()
            RangePreset.MONTH -> DateRanges.thisMonth()
            RangePreset.CUSTOM -> filter.value.range
        }
        filter.value = filter.value.copy(preset = preset, range = range)
    }

    private data class InternalFilter(
        val projectId: String?,
        val preset: RangePreset,
        val range: com.appdevforall.contractor.plugin.domain.model.DateRange
    )

    companion object {
        fun factory() = viewModelFactory {
            val sl = ContractorServiceLocator.get()
            SessionsViewModel(sl.projectRepository, sl.sessionRepository)
        }
    }
}
