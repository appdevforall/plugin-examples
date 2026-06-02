package com.appdevforall.contractor.plugin.ui.projects

import androidx.lifecycle.viewModelScope
import com.appdevforall.contractor.plugin.ContractorServiceLocator
import com.appdevforall.contractor.plugin.data.prefs.SettingsStore
import com.appdevforall.contractor.plugin.data.repository.ProjectRepository
import com.appdevforall.contractor.plugin.tracker.SessionTracker
import com.appdevforall.contractor.plugin.ui.common.BaseViewModel
import com.appdevforall.contractor.plugin.ui.common.viewModelFactory
import kotlinx.coroutines.launch

class ProjectFormViewModel(
    private val projectRepository: ProjectRepository,
    private val sessionTracker: SessionTracker,
    private val settings: SettingsStore
) : BaseViewModel<ProjectFormState, ProjectFormIntent, ProjectFormEffect>(ProjectFormState()) {

    private var editingId: String? = null

    override fun handleIntent(intent: ProjectFormIntent) {
        when (intent) {
            is ProjectFormIntent.Init -> initialize(intent)
            is ProjectFormIntent.Submit -> submit(intent.values)
            ProjectFormIntent.Cancel -> emit(ProjectFormEffect.Dismiss)
        }
    }

    private fun initialize(intent: ProjectFormIntent.Init) {
        editingId = intent.editingId
        if (intent.editingId != null) {
            viewModelScope.launch {
                val project = projectRepository.getById(intent.editingId) ?: return@launch
                reduce {
                    copy(
                        mode = ProjectFormState.Mode.Edit,
                        prefill = ProjectFormPrefill(
                            rootPath = project.rootPath,
                            rootEditable = false,
                            displayName = project.displayName,
                            clientName = project.clientName,
                            clientEmail = project.clientEmail.orEmpty(),
                            clientAddress = project.clientAddress.orEmpty(),
                            rate = project.hourlyRate.toString(),
                            currency = project.currency,
                            tax = project.taxRatePercent.toString(),
                            notes = project.notes.orEmpty()
                        ),
                        isReady = true
                    )
                }
            }
        } else {
            reduce {
                copy(
                    mode = ProjectFormState.Mode.Register,
                    prefill = ProjectFormPrefill(
                        rootPath = intent.prefillRoot.orEmpty(),
                        rootEditable = true,
                        displayName = intent.prefillName.orEmpty(),
                        currency = settings.defaultCurrency,
                        tax = settings.defaultTaxPercent.toString()
                    ),
                    isReady = true
                )
            }
        }
    }

    private fun submit(values: ProjectFormValues) {
        val errors = validate(values)
        if (errors.any) {
            reduce { copy(errors = errors) }
            return
        }
        reduce { copy(isSubmitting = true, errors = ProjectFormErrors()) }
        viewModelScope.launch {
            val id = editingId
            if (id == null) {
                val existing = projectRepository.getByRootPath(values.rootPath)
                if (existing != null) {
                    reduce { copy(isSubmitting = false) }
                    emit(ProjectFormEffect.ShowAlreadyRegistered)
                    return@launch
                }
                projectRepository.register(
                    rootPath = values.rootPath,
                    displayName = values.displayName,
                    clientName = values.clientName,
                    clientEmail = values.clientEmail,
                    clientAddress = values.clientAddress,
                    hourlyRate = values.hourlyRate,
                    currency = values.currency,
                    taxRatePercent = values.taxPercent,
                    notes = values.notes
                )
            } else {
                val current = projectRepository.getById(id) ?: run {
                    reduce { copy(isSubmitting = false) }
                    emit(ProjectFormEffect.Dismiss)
                    return@launch
                }
                projectRepository.update(
                    current.copy(
                        displayName = values.displayName,
                        clientName = values.clientName,
                        clientEmail = values.clientEmail?.takeIf { it.isNotBlank() },
                        clientAddress = values.clientAddress?.takeIf { it.isNotBlank() },
                        hourlyRate = values.hourlyRate,
                        currency = values.currency,
                        taxRatePercent = values.taxPercent,
                        notes = values.notes?.takeIf { it.isNotBlank() }
                    )
                )
            }
            sessionTracker.onProjectRegistrationChanged()
            reduce { copy(isSubmitting = false) }
            emit(ProjectFormEffect.Dismiss)
        }
    }

    private fun validate(values: ProjectFormValues): ProjectFormErrors = ProjectFormErrors(
        rootPath = values.rootPath.isBlank(),
        displayName = values.displayName.isBlank(),
        clientName = values.clientName.isBlank(),
        rate = values.hourlyRate < 0.0,
        currency = values.currency.isBlank(),
        tax = values.taxPercent < 0.0
    )

    companion object {
        fun factory() = viewModelFactory {
            val sl = ContractorServiceLocator.get()
            ProjectFormViewModel(
                sl.projectRepository,
                sl.sessionTracker,
                sl.settings
            )
        }
    }
}
