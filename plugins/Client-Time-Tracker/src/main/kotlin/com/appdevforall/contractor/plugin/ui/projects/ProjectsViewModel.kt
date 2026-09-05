package com.appdevforall.contractor.plugin.ui.projects

import androidx.lifecycle.viewModelScope
import com.appdevforall.contractor.plugin.ContractorServiceLocator
import com.appdevforall.contractor.plugin.data.repository.ProjectRepository
import com.appdevforall.contractor.plugin.data.repository.SessionRepository
import com.appdevforall.contractor.plugin.domain.model.DateRanges
import com.appdevforall.contractor.plugin.domain.usecase.SessionMerger
import com.appdevforall.contractor.plugin.tracker.SessionTracker
import com.appdevforall.contractor.plugin.ui.common.ContractorSharedViewModel
import com.appdevforall.contractor.plugin.ui.common.BaseViewModel
import com.appdevforall.contractor.plugin.ui.common.viewModelFactory
import com.itsaky.androidide.plugins.services.IdeProjectService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class ProjectsViewModel(
    private val projectRepository: ProjectRepository,
    private val sessionRepository: SessionRepository,
    private val sessionTracker: SessionTracker,
    private val ideProjectService: IdeProjectService?,
    private val sharedViewModel: ContractorSharedViewModel
) : BaseViewModel<ProjectsState, ProjectsIntent, ProjectsEffect>(ProjectsState()) {

    init {
        viewModelScope.launch {
            val month = DateRanges.thisMonth()
            combine(
                projectRepository.observeAll(),
                sessionRepository.observeOpenSessionProjectIds(),
                // Subscribing to the current-month sessions flow means manual additions,
                // edits, or deletions immediately re-emit and the card's "This month"
                // total refreshes without having to close and reopen the plugin tab.
                sessionRepository.observeInRange(month.fromMillis, month.toMillis)
            ) { projects, openProjectIds, sessionsThisMonth ->
                val openSet = openProjectIds.toSet()
                val durationByProject = sessionsThisMonth
                    .groupBy { it.projectId }
                    .mapValues { entry -> entry.value.sumOf { SessionMerger.durationOf(it) } }
                val rows = projects.map { p ->
                    ProjectRow(
                        project = p,
                        monthMillis = durationByProject[p.id] ?: 0L,
                        // DB-driven Tracking flag: any open session row for this project.
                        isActiveTracked = openSet.contains(p.id)
                    )
                }
                ProjectsState(rows = rows, isLoaded = true)
            }.collect { newState -> reduce { newState } }
        }
    }

    override fun handleIntent(intent: ProjectsIntent) {
        when (intent) {
            ProjectsIntent.RequestRegisterCurrent -> openRegisterFromCurrent()
            is ProjectsIntent.RequestEdit -> emit(ProjectsEffect.OpenEditForm(intent.projectId))
            is ProjectsIntent.FocusOnSessions -> {
                sharedViewModel.requestFocus(intent.projectId)
                sharedViewModel.requestTab(1)
            }
            is ProjectsIntent.Delete -> viewModelScope.launch {
                projectRepository.delete(intent.projectId)
                sessionTracker.onProjectRegistrationChanged()
            }
            is ProjectsIntent.SetArchived -> viewModelScope.launch {
                projectRepository.setArchived(intent.projectId, intent.archived)
                sessionTracker.onProjectRegistrationChanged()
            }
        }
    }

    private fun openRegisterFromCurrent() {
        viewModelScope.launch {
            val ideProject = runCatching { ideProjectService?.getCurrentProject() }.getOrNull()
            val rootPath = ideProject?.rootDir?.absolutePath
            val name = ideProject?.name
            val alreadyRegistered = rootPath?.let { projectRepository.getByRootPath(it) != null } ?: false
            emit(
                ProjectsEffect.OpenRegisterForm(
                    prefillRootPath = rootPath,
                    prefillDisplayName = name,
                    alreadyRegistered = alreadyRegistered
                )
            )
        }
    }

    companion object {
        fun factory(sharedViewModel: ContractorSharedViewModel) = viewModelFactory {
            val sl = ContractorServiceLocator.get()
            val ideProj = runCatching {
                sl.pluginContext.services.get(IdeProjectService::class.java)
            }.getOrNull()
            ProjectsViewModel(
                sl.projectRepository,
                sl.sessionRepository,
                sl.sessionTracker,
                ideProj,
                sharedViewModel
            )
        }
    }
}
