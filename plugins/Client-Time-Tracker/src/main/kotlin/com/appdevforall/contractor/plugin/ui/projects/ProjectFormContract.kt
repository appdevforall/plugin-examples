package com.appdevforall.contractor.plugin.ui.projects

import com.appdevforall.contractor.plugin.ui.common.UiEffect
import com.appdevforall.contractor.plugin.ui.common.UiIntent
import com.appdevforall.contractor.plugin.ui.common.UiState

data class ProjectFormValues(
    val rootPath: String,
    val displayName: String,
    val clientName: String,
    val clientEmail: String?,
    val clientAddress: String?,
    val hourlyRate: Double,
    val currency: String,
    val taxPercent: Double,
    val notes: String?
)

data class ProjectFormErrors(
    val rootPath: Boolean = false,
    val displayName: Boolean = false,
    val clientName: Boolean = false,
    val rate: Boolean = false,
    val currency: Boolean = false,
    val tax: Boolean = false
) {
    val any: Boolean get() = rootPath || displayName || clientName || rate || currency || tax
}

/**
 * Initial values pushed once into the form on open. Fields manage their own state
 * after that; the State only carries validation errors and submission flags.
 */
data class ProjectFormPrefill(
    val rootPath: String = "",
    val rootEditable: Boolean = true,
    val displayName: String = "",
    val clientName: String = "",
    val clientEmail: String = "",
    val clientAddress: String = "",
    val rate: String = "",
    val currency: String = "",
    val tax: String = "",
    val notes: String = ""
)

data class ProjectFormState(
    val mode: Mode = Mode.Register,
    val prefill: ProjectFormPrefill = ProjectFormPrefill(),
    val isReady: Boolean = false,
    val isSubmitting: Boolean = false,
    val errors: ProjectFormErrors = ProjectFormErrors()
) : UiState {
    enum class Mode { Register, Edit }
}

sealed interface ProjectFormIntent : UiIntent {
    /** First call after onCreateView. Loads project for edit (if id != null) or sets defaults. */
    data class Init(val editingId: String?, val prefillRoot: String?, val prefillName: String?) : ProjectFormIntent

    /** Submit with all field values gathered from the View. */
    data class Submit(val values: ProjectFormValues) : ProjectFormIntent

    /** User dismissed without submitting. */
    data object Cancel : ProjectFormIntent
}

sealed interface ProjectFormEffect : UiEffect {
    data object Dismiss : ProjectFormEffect
    data object ShowAlreadyRegistered : ProjectFormEffect
}
