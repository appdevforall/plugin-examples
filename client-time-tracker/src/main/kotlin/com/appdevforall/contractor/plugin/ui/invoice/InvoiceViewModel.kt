package com.appdevforall.contractor.plugin.ui.invoice

import androidx.lifecycle.viewModelScope
import com.appdevforall.contractor.plugin.ContractorServiceLocator
import com.appdevforall.contractor.plugin.data.export.InvoiceExporter
import com.appdevforall.contractor.plugin.data.repository.InvoiceRepository
import com.appdevforall.contractor.plugin.data.repository.ProjectRepository
import com.appdevforall.contractor.plugin.domain.model.DateRanges
import com.appdevforall.contractor.plugin.domain.usecase.GenerateInvoiceUseCase
import com.appdevforall.contractor.plugin.domain.usecase.InvoiceNumberGenerator
import com.appdevforall.contractor.plugin.ui.common.BaseViewModel
import com.appdevforall.contractor.plugin.ui.common.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

class InvoiceViewModel(
    private val projectRepository: ProjectRepository,
    private val invoiceRepository: InvoiceRepository,
    private val generateInvoiceUseCase: GenerateInvoiceUseCase,
    private val invoiceNumberGenerator: InvoiceNumberGenerator,
    private val exporters: List<InvoiceExporter>,
    private val pluginDataDir: File
) : BaseViewModel<InvoiceState, InvoiceIntent, InvoiceEffect>(InvoiceState()) {

    init {
        viewModelScope.launch {
            projectRepository.observeActive().collect { projects ->
                reduce { copy(projects = projects) }
            }
        }
        viewModelScope.launch {
            val number = invoiceNumberGenerator.next()
            reduce { copy(invoiceNumber = number) }
        }
    }

    override fun handleIntent(intent: InvoiceIntent) {
        when (intent) {
            is InvoiceIntent.SelectProject -> reduce { copy(selectedProjectId = intent.id) }
            is InvoiceIntent.SetPreset -> applyPreset(intent.preset)
            is InvoiceIntent.SetCustomRange -> reduce {
                copy(rangePreset = RangePresetForInvoice.CUSTOM, range = DateRanges.custom(intent.start, intent.end))
            }
            is InvoiceIntent.ToggleFormat -> reduce {
                val cur = formats.toMutableSet()
                if (cur.contains(intent.format)) cur.remove(intent.format) else cur.add(intent.format)
                copy(formats = cur)
            }
            is InvoiceIntent.SetInvoiceNumber -> reduce { copy(invoiceNumber = intent.value) }
            is InvoiceIntent.SetNotes -> reduce { copy(notes = intent.value) }
            InvoiceIntent.Generate -> generate()
            InvoiceIntent.RequestCustomRange -> emit(InvoiceEffect.OpenCustomRangePicker)
        }
    }

    private fun applyPreset(preset: RangePresetForInvoice) {
        if (preset == RangePresetForInvoice.CUSTOM) {
            emit(InvoiceEffect.OpenCustomRangePicker)
            return
        }
        val range = when (preset) {
            RangePresetForInvoice.WEEK -> DateRanges.thisWeek()
            RangePresetForInvoice.MONTH -> DateRanges.thisMonth()
            RangePresetForInvoice.LAST_MONTH -> {
                val today = LocalDate.now(DateRanges.zone())
                val firstThisMonth = today.withDayOfMonth(1)
                val firstLastMonth = firstThisMonth.minusMonths(1)
                DateRanges.custom(firstLastMonth, firstThisMonth.minusDays(1))
            }
            RangePresetForInvoice.CUSTOM -> currentState.range
        }
        reduce { copy(rangePreset = preset, range = range) }
    }

    private fun generate() {
        val s = currentState
        if (s.selectedProjectId == null) {
            emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.NoProject))
            return
        }
        if (s.formats.isEmpty()) {
            emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.NoFormat))
            return
        }
        viewModelScope.launch {
            reduce { copy(isGenerating = true) }
            val project = projectRepository.getById(s.selectedProjectId) ?: run {
                reduce { copy(isGenerating = false) }
                emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.ProjectMissing))
                return@launch
            }
            val result = generateInvoiceUseCase.build(
                GenerateInvoiceUseCase.Input(
                    project = project,
                    invoiceNumber = s.invoiceNumber.ifBlank { invoiceNumberGenerator.next() },
                    periodStartMillis = s.range.fromMillis,
                    periodEndMillisExclusive = s.range.toMillis,
                    notes = s.notes.ifBlank { null }
                )
            )
            when (result) {
                GenerateInvoiceUseCase.Result.NoBillableSessions -> {
                    reduce { copy(isGenerating = false) }
                    emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.NoSessions))
                }
                is GenerateInvoiceUseCase.Result.Success -> {
                    val outDir = withContext(Dispatchers.IO) { resolveOutputDirectory(project.rootPath) }
                    val safeNumber = result.data.invoiceNumber.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val outFiles = mutableListOf<File>()
                    withContext(Dispatchers.IO) {
                        for (format in s.formats) {
                            val exporter = exporters.firstOrNull { it.format == format } ?: continue
                            val target = File(outDir, "$safeNumber.${format.extension}")
                            runCatching { exporter.export(result.data, target) }
                                .onSuccess { outFiles += it }
                        }
                        if (outFiles.isNotEmpty()) {
                            invoiceRepository.create(
                                projectId = project.id,
                                invoiceNumber = result.data.invoiceNumber,
                                periodStart = s.range.fromMillis,
                                periodEnd = s.range.toMillis,
                                totalSeconds = result.data.totalDurationMillis / 1000,
                                subtotal = result.data.subtotal,
                                taxAmount = result.data.taxAmount,
                                total = result.data.total,
                                currency = result.data.currency,
                                exportedFiles = outFiles.map { it.absolutePath },
                                notes = result.data.notes
                            )
                        }
                    }
                    val nextNumber = invoiceNumberGenerator.next()
                    reduce { copy(isGenerating = false, invoiceNumber = nextNumber) }
                    if (outFiles.isEmpty()) {
                        emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.GenerationFailed))
                    } else {
                        emit(
                            InvoiceEffect.ShowSuccess(
                                GenerationResult(
                                    invoiceNumber = result.data.invoiceNumber,
                                    outDir = outDir.absolutePath,
                                    outFiles = outFiles.map { it.absolutePath },
                                    total = result.data.total,
                                    currency = result.data.currency,
                                    periodStartIsoDate = result.data.periodStart.toString(),
                                    periodEndIsoDate = result.data.periodEnd.toString()
                                )
                            )
                        )
                    }
                }
            }
        }
    }

    private fun resolveOutputDirectory(rootPath: String): File {
        val projectFolder = File(rootPath, "invoices")
        return if (runCatching { projectFolder.mkdirs(); projectFolder.canWrite() }.getOrDefault(false)) {
            projectFolder
        } else {
            val fallback = File(pluginDataDir, "invoices")
            fallback.mkdirs()
            fallback
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            val sl = ContractorServiceLocator.get()
            InvoiceViewModel(
                sl.projectRepository,
                sl.invoiceRepository,
                sl.generateInvoice,
                sl.invoiceNumberGenerator,
                listOf(sl.pdfExporter, sl.xlsxExporter, sl.csvExporter),
                sl.dataDirectory
            )
        }
    }
}
