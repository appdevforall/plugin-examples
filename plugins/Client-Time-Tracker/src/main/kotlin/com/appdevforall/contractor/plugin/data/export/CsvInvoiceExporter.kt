package com.appdevforall.contractor.plugin.data.export

import com.appdevforall.contractor.plugin.domain.model.InvoiceData
import com.appdevforall.contractor.plugin.domain.model.InvoiceFormat
import java.io.File

class CsvInvoiceExporter : InvoiceExporter {
    override val format = InvoiceFormat.CSV

    override fun export(data: InvoiceData, outFile: File): File {
        val sb = StringBuilder()

        sb.appendCsv("Invoice", data.invoiceNumber)
        sb.appendCsv("Issue date", InvoiceFormatting.dateFmt.format(data.issueDate))
        sb.appendCsv("Period start", InvoiceFormatting.dateFmt.format(data.periodStart))
        sb.appendCsv("Period end", InvoiceFormatting.dateFmt.format(data.periodEnd))
        sb.appendCsv("Currency", data.currency)
        sb.appendCsv("Project", data.projectDisplayName)
        sb.append(EOL)

        if (!data.business.isEmpty) {
            sb.appendCsv("From", data.business.name)
            if (data.business.email.isNotBlank()) sb.appendCsv("From email", data.business.email)
            if (data.business.address.isNotBlank()) sb.appendCsv("From address", data.business.address)
            sb.append(EOL)
        }

        sb.appendCsv("Bill to", data.client.name)
        data.client.email?.let { sb.appendCsv("Client email", it) }
        data.client.address?.let { sb.appendCsv("Client address", it) }
        sb.append(EOL)

        sb.appendRow("Date", "Hours", "Rate", "Amount")
        data.lineItems.forEach { item ->
            sb.appendRow(
                InvoiceFormatting.shortDateFmt.format(item.date),
                InvoiceFormatting.hours(item.hours),
                InvoiceFormatting.moneyPlain(item.rate),
                InvoiceFormatting.moneyPlain(item.amount)
            )
        }
        sb.append(EOL)

        sb.appendRow("Subtotal", "", "", InvoiceFormatting.moneyPlain(data.subtotal))
        if (data.taxRatePercent > 0.0) {
            sb.appendRow("Tax (${"%.2f".format(data.taxRatePercent)}%)", "", "", InvoiceFormatting.moneyPlain(data.taxAmount))
        }
        sb.appendRow("Total", "", "", InvoiceFormatting.moneyPlain(data.total))

        if (!data.notes.isNullOrBlank()) {
            sb.append(EOL)
            sb.appendCsv("Notes", data.notes)
        }

        outFile.parentFile?.mkdirs()
        outFile.writeText(sb.toString(), Charsets.UTF_8)
        return outFile
    }

    private fun StringBuilder.appendRow(vararg cells: String) {
        cells.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append(escape(c))
        }
        append(EOL)
    }

    private fun StringBuilder.appendCsv(label: String, value: String) {
        append(escape(label)).append(',').append(escape(value)).append(EOL)
    }

    private fun escape(value: String): String {
        val needsQuotes = value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
        if (!needsQuotes) return value
        val escaped = value.replace("\"", "\"\"")
        return "\"$escaped\""
    }

    companion object {
        private const val EOL = "\r\n"
    }
}
