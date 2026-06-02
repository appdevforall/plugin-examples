package com.appdevforall.contractor.plugin.domain.model

import java.time.LocalDate

data class BusinessInfo(
    val name: String,
    val email: String,
    val address: String
) {
    val isEmpty: Boolean get() = name.isBlank() && email.isBlank() && address.isBlank()
}

data class ClientInfo(
    val name: String,
    val email: String?,
    val address: String?
)

data class InvoiceLineItem(
    val date: LocalDate,
    val durationMillis: Long,
    val hours: Double,
    val rate: Double,
    val amount: Double,
    val description: String?
)

data class InvoiceData(
    val invoiceNumber: String,
    val issueDate: LocalDate,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val business: BusinessInfo,
    val client: ClientInfo,
    val projectDisplayName: String,
    val lineItems: List<InvoiceLineItem>,
    val subtotal: Double,
    val taxRatePercent: Double,
    val taxAmount: Double,
    val total: Double,
    val currency: String,
    val totalDurationMillis: Long,
    val notes: String?
)

enum class InvoiceFormat(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("csv", "text/csv")
}
