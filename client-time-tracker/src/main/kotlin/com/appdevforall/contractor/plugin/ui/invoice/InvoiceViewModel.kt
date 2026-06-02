package com.appdevforall.contractor.plugin.ui.invoice

import androidx.lifecycle.viewModelScope
import com.appdevforall.contractor.plugin.ContractorServiceLocator
import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import com.appdevforall.contractor.plugin.data.export.InvoiceExporter
import com.appdevforall.contractor.plugin.data.repository.InvoiceRepository
import com.appdevforall.contractor.plugin.data.repository.ProjectRepository
import com.appdevforall.contractor.plugin.domain.model.DateRanges
import com.appdevforall.contractor.plugin.domain.model.InvoiceData
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
        val snapshot = currentState
        if (snapshot.selectedProjectId == null) {
            emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.NoProject))
            return
        }
        if (snapshot.formats.isEmpty()) {
            emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.NoFormat))
            return
        }
        viewModelScope.launch {
            reduce { copy(isGenerating = true) }
            val project = projectRepository.getById(snapshot.selectedProjectId) ?: run {
                reduce { copy(isGenerating = false) }
                emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.ProjectMissing))
                return@launch
            }
            when (val result = buildInvoice(snapshot, project)) {
                GenerateInvoiceUseCase.Result.NoBillableSessions -> {
                    reduce { copy(isGenerating = false) }
                    emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.NoSessions))
                }
                is GenerateInvoiceUseCase.Result.Success -> exportAndPersist(snapshot, project, result.data)
            }
        }
    }

    private suspend fun buildInvoice(
        snapshot: InvoiceState,
        project: TrackedProjectEntity
    ): GenerateInvoiceUseCase.Result =
        generateInvoiceUseCase.build(
            GenerateInvoiceUseCase.Input(
                project = project,
                invoiceNumber = snapshot.invoiceNumber.ifBlank { invoiceNumberGenerator.next() },
                periodStartMillis = snapshot.range.fromMillis,
                periodEndMillisExclusive = snapshot.range.toMillis,
                notes = snapshot.notes.ifBlank { null }
            )
        )

    private suspend fun exportAndPersist(
        snapshot: InvoiceState,
        project: TrackedProjectEntity,
        data: InvoiceData
    ) {
        val outDir = withContext(Dispatchers.IO) { resolveOutputDirectory(project.rootPath) }
        val outFiles = withContext(Dispatchers.IO) { writeExports(snapshot, data, outDir) }
        if (outFiles.isNotEmpty()) {
            persistInvoice(snapshot, project, data, outFiles)
        }
        val nextNumber = invoiceNumberGenerator.next()
        reduce { copy(isGenerating = false, invoiceNumber = nextNumber) }
        if (outFiles.isEmpty()) {
            emit(InvoiceEffect.ShowError(InvoiceEffect.ErrorCode.GenerationFailed))
        } else {
            emit(InvoiceEffect.ShowSuccess(successResult(data, outDir, outFiles)))
        }
    }

    private fun writeExports(
        snapshot: InvoiceState,
        data: InvoiceData,
        outDir: File
    ): List<File> {
        val safeNumber = data.invoiceNumber.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val outFiles = mutableListOf<File>()
        for (format in snapshot.formats) {
            val exporter = exporters.firstOrNull { it.format == format } ?: continue
            val target = File(outDir, "$safeNumber.${format.extension}")
            runCatching { exporter.export(data, target) }
                .onSuccess { outFiles += it }
        }
        return outFiles
    }

    private suspend fun persistInvoice(
        snapshot: InvoiceState,
        project: TrackedProjectEntity,
        data: InvoiceData,
        outFiles: List<File>
    ) {
        invoiceRepository.create(
            projectId = project.id,
            invoiceNumber = data.invoiceNumber,
            periodStart = snapshot.range.fromMillis,
            periodEnd = snapshot.range.toMillis,
            totalSeconds = data.totalDurationMillis / 1000,
            subtotal = data.subtotal,
            taxAmount = data.taxAmount,
            total = data.total,
            currency = data.currency,
            exportedFiles = outFiles.map { it.absolutePath },
            notes = data.notes
        )
    }

    private fun successResult(
        data: InvoiceData,
        outDir: File,
        outFiles: List<File>
    ): GenerationResult =
        GenerationResult(
            invoiceNumber = data.invoiceNumber,
            outDir = outDir.absolutePath,
            outFiles = outFiles.map { it.absolutePath },
            total = data.total,
            currency = data.currency,
            periodStartIsoDate = data.periodStart.toString(),
            periodEndIsoDate = data.periodEnd.toString()
        )

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
