package com.appdevforall.contractor.plugin.ui.invoice

import android.os.Parcelable
import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import com.appdevforall.contractor.plugin.domain.model.DateRange
import com.appdevforall.contractor.plugin.domain.model.DateRanges
import com.appdevforall.contractor.plugin.domain.model.InvoiceFormat
import com.appdevforall.contractor.plugin.ui.common.UiEffect
import com.appdevforall.contractor.plugin.ui.common.UiIntent
import com.appdevforall.contractor.plugin.ui.common.UiState
import kotlinx.parcelize.Parcelize
import java.time.LocalDate

enum class RangePresetForInvoice { WEEK, MONTH, LAST_MONTH, CUSTOM }

@Parcelize
data class GenerationResult(
    val invoiceNumber: String,
    val outDir: String,
    val outFiles: List<String>,
    val total: Double,
    val currency: String,
    val periodStartIsoDate: String,
    val periodEndIsoDate: String
) : Parcelable

data class InvoiceState(
    val projects: List<TrackedProjectEntity> = emptyList(),
    val selectedProjectId: String? = null,
    val rangePreset: RangePresetForInvoice = RangePresetForInvoice.MONTH,
    val range: DateRange = DateRanges.thisMonth(),
    val invoiceNumber: String = "",
    val notes: String = "",
    val formats: Set<InvoiceFormat> = setOf(InvoiceFormat.PDF, InvoiceFormat.CSV),
    val isGenerating: Boolean = false
) : UiState

sealed interface InvoiceIntent : UiIntent {
    data class SelectProject(val id: String?) : InvoiceIntent
    data class SetPreset(val preset: RangePresetForInvoice) : InvoiceIntent
    data class SetCustomRange(val start: LocalDate, val end: LocalDate) : InvoiceIntent
    data class ToggleFormat(val format: InvoiceFormat) : InvoiceIntent
    data class SetInvoiceNumber(val value: String) : InvoiceIntent
    data class SetNotes(val value: String) : InvoiceIntent
    data object Generate : InvoiceIntent
    data object RequestCustomRange : InvoiceIntent
}

sealed interface InvoiceEffect : UiEffect {
    data class ShowSuccess(val result: GenerationResult) : InvoiceEffect
    data class ShowError(val code: ErrorCode) : InvoiceEffect
    data object OpenCustomRangePicker : InvoiceEffect

    enum class ErrorCode {
        NoProject,
        NoFormat,
        NoSessions,
        ProjectMissing,
        GenerationFailed
    }
}
