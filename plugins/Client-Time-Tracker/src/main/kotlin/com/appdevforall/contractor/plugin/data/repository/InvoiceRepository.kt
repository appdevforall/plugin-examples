package com.appdevforall.contractor.plugin.data.repository

import com.appdevforall.contractor.plugin.data.db.dao.InvoiceDao
import com.appdevforall.contractor.plugin.data.db.entities.InvoiceEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class InvoiceRepository(private val dao: InvoiceDao) {

    fun observeForProject(projectId: String): Flow<List<InvoiceEntity>> = dao.observeForProject(projectId)

    suspend fun countForYear(year: String): Int = dao.countForYear(year)

    suspend fun create(
        projectId: String,
        invoiceNumber: String,
        periodStart: Long,
        periodEnd: Long,
        totalSeconds: Long,
        subtotal: Double,
        taxAmount: Double,
        total: Double,
        currency: String,
        exportedFiles: List<String>,
        notes: String?
    ): InvoiceEntity {
        val entity = InvoiceEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            invoiceNumber = invoiceNumber,
            periodStart = periodStart,
            periodEnd = periodEnd,
            totalSeconds = totalSeconds,
            subtotal = subtotal,
            taxAmount = taxAmount,
            total = total,
            currency = currency,
            generatedAt = System.currentTimeMillis(),
            exportedFiles = exportedFiles.joinToString(separator = FILES_DELIM),
            notes = notes?.takeIf { it.isNotBlank() }
        )
        dao.insert(entity)
        return entity
    }

    companion object {
        const val FILES_DELIM = "\n"

        fun parseExportedFiles(raw: String): List<String> =
            if (raw.isBlank()) emptyList() else raw.split(FILES_DELIM).filter { it.isNotBlank() }
    }
}
