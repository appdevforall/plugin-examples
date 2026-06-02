package com.appdevforall.contractor.plugin.ui.sessions

import androidx.lifecycle.viewModelScope
import com.appdevforall.contractor.plugin.ContractorServiceLocator
import com.appdevforall.contractor.plugin.data.repository.ProjectRepository
import com.appdevforall.contractor.plugin.data.repository.SessionRepository
import com.appdevforall.contractor.plugin.tracker.SessionTracker
import com.appdevforall.contractor.plugin.ui.common.BaseViewModel
import com.appdevforall.contractor.plugin.ui.common.viewModelFactory
import kotlinx.coroutines.launch

class SessionEditViewModel(
    private val projectRepository: ProjectRepository,
    private val sessionRepository: SessionRepository,
    private val sessionTracker: SessionTracker
) : BaseViewModel<SessionEditState, SessionEditIntent, SessionEditEffect>(SessionEditState()) {

    private var sessionId: String? = null

    init {
        viewModelScope.launch {
            projectRepository.observeAll().collect { projects ->
                reduce { copy(projects = projects) }
            }
        }
    }

    override fun handleIntent(intent: SessionEditIntent) {
        when (intent) {
            is SessionEditIntent.Init -> initialize(intent)
            is SessionEditIntent.SetProject -> reduce { copy(selectedProjectId = intent.projectId) }
            is SessionEditIntent.SetStart -> reduce {
                copy(startMillis = intent.millis, invalidRange = endMillis < intent.millis)
            }
            is SessionEditIntent.SetEnd -> reduce {
                copy(endMillis = intent.millis, invalidRange = intent.millis < startMillis)
            }
            is SessionEditIntent.Submit -> submit(intent.notes)
            SessionEditIntent.DeleteRequested -> {
                if (sessionId != null) emit(SessionEditEffect.ShowDeleteConfirmation)
            }
            SessionEditIntent.Cancel -> emit(SessionEditEffect.Dismiss)
        }
    }

    private fun initialize(intent: SessionEditIntent.Init) {
        sessionId = intent.sessionId
        if (intent.sessionId != null) {
            viewModelScope.launch {
                val s = sessionRepository.getById(intent.sessionId) ?: return@launch
                reduce {
                    copy(
                        mode = SessionEditState.Mode.Edit,
                        selectedProjectId = s.projectId,
                        startMillis = s.startTime,
                        endMillis = s.endTime ?: s.lastHeartbeatAt,
                        notes = s.notes.orEmpty(),
                        isReady = true
                    )
                }
            }
        } else {
            val now = System.currentTimeMillis()
            reduce {
                copy(
                    mode = SessionEditState.Mode.Add,
                    selectedProjectId = intent.preselectedProjectId,
                    startMillis = now - 60 * 60 * 1000L,
                    endMillis = now,
                    notes = "",
                    isReady = true
                )
            }
        }
    }

    private fun submit(notes: String) {
        val s = currentState
        val pid = s.selectedProjectId ?: return
        if (s.endMillis < s.startMillis) {
            reduce { copy(invalidRange = true) }
            return
        }
        val notesValue = notes.takeIf { it.isNotBlank() }
        reduce { copy(isSubmitting = true) }
        viewModelScope.launch {
            val id = sessionId
            if (id == null) {
                sessionRepository.addManual(pid, s.startMillis, s.endMillis, notesValue)
            } else {
                val current = sessionRepository.getById(id) ?: run {
                    reduce { copy(isSubmitting = false) }
                    emit(SessionEditEffect.Dismiss)
                    return@launch
                }
                sessionRepository.update(
                    current.copy(
                        projectId = pid,
                        startTime = s.startMillis,
                        endTime = s.endMillis,
                        lastHeartbeatAt = s.endMillis,
                        notes = notesValue
                    )
                )
            }
            reduce { copy(isSubmitting = false) }
            emit(SessionEditEffect.Dismiss)
        }
    }

    fun confirmDelete() {
        val id = sessionId ?: return
        viewModelScope.launch {
            sessionRepository.delete(id)
            // Tell the tracker so it drops its in-memory pointer to this session, otherwise
            // the next heartbeat would try to write into a row that no longer exists.
            sessionTracker.onSessionDeleted(id)
            emit(SessionEditEffect.Dismiss)
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            val sl = ContractorServiceLocator.get()
            SessionEditViewModel(sl.projectRepository, sl.sessionRepository, sl.sessionTracker)
        }
    }
}
