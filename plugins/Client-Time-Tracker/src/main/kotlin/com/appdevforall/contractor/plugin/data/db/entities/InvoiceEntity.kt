package com.appdevforall.contractor.plugin.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "invoices",
    foreignKeys = [
        ForeignKey(
            entity = TrackedProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId"), Index("generatedAt")]
)
data class InvoiceEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val invoiceNumber: String,
    val periodStart: Long,
    val periodEnd: Long,
    val totalSeconds: Long,
    val subtotal: Double,
    val taxAmount: Double,
    val total: Double,
    val currency: String,
    val generatedAt: Long,
    val exportedFiles: String,
    val notes: String?
)
