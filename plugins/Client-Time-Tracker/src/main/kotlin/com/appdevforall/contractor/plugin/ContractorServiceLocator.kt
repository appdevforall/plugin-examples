package com.appdevforall.contractor.plugin

import android.content.Context
import com.appdevforall.contractor.plugin.data.db.ContractorDatabase
import com.appdevforall.contractor.plugin.data.export.CsvInvoiceExporter
import com.appdevforall.contractor.plugin.data.export.PdfInvoiceExporter
import com.appdevforall.contractor.plugin.data.export.XlsxInvoiceExporter
import com.appdevforall.contractor.plugin.data.prefs.SettingsStore
import com.appdevforall.contractor.plugin.data.repository.InvoiceRepository
import com.appdevforall.contractor.plugin.data.repository.ProjectRepository
import com.appdevforall.contractor.plugin.data.repository.SessionRepository
import com.appdevforall.contractor.plugin.domain.usecase.GenerateInvoiceUseCase
import com.appdevforall.contractor.plugin.domain.usecase.InvoiceNumberGenerator
import com.appdevforall.contractor.plugin.tracker.SessionTracker
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.services.IdeEnvironmentService
import java.io.File

object ContractorServiceLocator {

    @Volatile private var instance: Holder? = null

    fun init(pluginContext: PluginContext): Holder = synchronized(this) {
        instance?.let { return@synchronized it }
        val androidContext: Context = pluginContext.androidContext
        val dataDir: File = runCatching {
            pluginContext.services.get(IdeEnvironmentService::class.java)?.getPluginDataDirectory()
        }.getOrNull() ?: File(androidContext.filesDir, "contractor")
        val db = ContractorDatabase.build(androidContext, dataDir)

        val settings = SettingsStore(androidContext)
        val projectRepo = ProjectRepository(db.projectDao())
        val sessionRepo = SessionRepository(db.sessionDao())
        val invoiceRepo = InvoiceRepository(db.invoiceDao())

        val generateInvoice = GenerateInvoiceUseCase(sessionRepo, settings)
        val invoiceNumberGen = InvoiceNumberGenerator(invoiceRepo, settings)

        val csvExporter = CsvInvoiceExporter()
        val xlsxExporter = XlsxInvoiceExporter()
        val pdfExporter = PdfInvoiceExporter()

        val tracker = SessionTracker(pluginContext, projectRepo, sessionRepo, settings)

        val holder = Holder(
            pluginContext = pluginContext,
            db = db,
            settings = settings,
            projectRepository = projectRepo,
            sessionRepository = sessionRepo,
            invoiceRepository = invoiceRepo,
            generateInvoice = generateInvoice,
            invoiceNumberGenerator = invoiceNumberGen,
            csvExporter = csvExporter,
            xlsxExporter = xlsxExporter,
            pdfExporter = pdfExporter,
            sessionTracker = tracker,
            dataDirectory = dataDir
        )
        instance = holder
        holder
    }

    fun get(): Holder = instance ?: error("ContractorServiceLocator not initialized")

    fun isReady(): Boolean = instance != null

    fun shutdown() = synchronized(this) {
        instance?.sessionTracker?.stop()
        instance?.db?.close()
        instance = null
    }

    data class Holder(
        val pluginContext: PluginContext,
        val db: ContractorDatabase,
        val settings: SettingsStore,
        val projectRepository: ProjectRepository,
        val sessionRepository: SessionRepository,
        val invoiceRepository: InvoiceRepository,
        val generateInvoice: GenerateInvoiceUseCase,
        val invoiceNumberGenerator: InvoiceNumberGenerator,
        val csvExporter: CsvInvoiceExporter,
        val xlsxExporter: XlsxInvoiceExporter,
        val pdfExporter: PdfInvoiceExporter,
        val sessionTracker: SessionTracker,
        val dataDirectory: File
    )
}
