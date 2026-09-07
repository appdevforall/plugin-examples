package com.appdevforall.contractor.plugin.data.export

import com.appdevforall.contractor.plugin.domain.model.InvoiceData
import com.appdevforall.contractor.plugin.domain.model.InvoiceFormat
import java.io.File
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale

interface InvoiceExporter {
    val format: InvoiceFormat
    fun export(data: InvoiceData, outFile: File): File
}

internal object InvoiceFormatting {

    val dateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    val shortDateFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.getDefault())

    fun money(amount: Double, currencyCode: String): String {
        val nf = NumberFormat.getCurrencyInstance(Locale.getDefault())
        return runCatching {
            nf.currency = Currency.getInstance(currencyCode)
            nf.format(amount)
        }.getOrElse { "$currencyCode ${"%,.2f".format(amount)}" }
    }

    fun moneyPlain(amount: Double): String = "%,.2f".format(amount)

    fun hours(value: Double): String = "%.2f".format(value)
}
