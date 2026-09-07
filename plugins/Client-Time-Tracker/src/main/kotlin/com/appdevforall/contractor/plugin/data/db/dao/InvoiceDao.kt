package com.appdevforall.contractor.plugin.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.appdevforall.contractor.plugin.data.db.entities.InvoiceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InvoiceDao {

    @Query("SELECT * FROM invoices WHERE projectId = :projectId ORDER BY generatedAt DESC")
    fun observeForProject(projectId: String): Flow<List<InvoiceEntity>>

    @Query("SELECT COUNT(*) FROM invoices WHERE strftime('%Y', generatedAt / 1000, 'unixepoch') = :year")
    suspend fun countForYear(year: String): Int

    suspend fun insert(invoice: InvoiceEntity) = insertRow(
        invoice.id, invoice.projectId, invoice.invoiceNumber, invoice.periodStart, invoice.periodEnd,
        invoice.totalSeconds, invoice.subtotal, invoice.taxAmount, invoice.total, invoice.currency,
        invoice.generatedAt, invoice.exportedFiles, invoice.notes
    )

    @Query(
        "INSERT INTO invoices " +
            "(id, projectId, invoiceNumber, periodStart, periodEnd, totalSeconds, subtotal, taxAmount, total, currency, generatedAt, exportedFiles, notes) " +
            "VALUES (:id, :projectId, :invoiceNumber, :periodStart, :periodEnd, :totalSeconds, :subtotal, :taxAmount, :total, :currency, :generatedAt, :exportedFiles, :notes)"
    )
    suspend fun insertRow(
        id: String, projectId: String, invoiceNumber: String, periodStart: Long, periodEnd: Long,
        totalSeconds: Long, subtotal: Double, taxAmount: Double, total: Double, currency: String,
        generatedAt: Long, exportedFiles: String, notes: String?
    )

    @Query("DELETE FROM invoices WHERE id = :id")
    suspend fun deleteById(id: String)
}
