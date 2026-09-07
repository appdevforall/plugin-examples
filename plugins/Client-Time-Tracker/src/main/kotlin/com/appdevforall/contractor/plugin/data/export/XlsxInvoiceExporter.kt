package com.appdevforall.contractor.plugin.data.export

import com.appdevforall.contractor.plugin.domain.model.InvoiceData
import com.appdevforall.contractor.plugin.domain.model.InvoiceFormat
import org.dhatim.fastexcel.Workbook
import org.dhatim.fastexcel.Worksheet
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

class XlsxInvoiceExporter : InvoiceExporter {
    override val format = InvoiceFormat.XLSX

    override fun export(data: InvoiceData, outFile: File): File {
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            val workbook = Workbook(fos, "Client Time Tracker", "1.0")
        val ws: Worksheet = workbook.newWorksheet("Invoice")

            ws.width(0, 22.0)
            ws.width(1, 14.0)
            ws.width(2, 14.0)
            ws.width(3, 16.0)

            var row = 0

            ws.value(row, 0, "INVOICE")
            ws.style(row, 0).fontSize(10).fontColor("737373").bold().set()
            row++

            ws.value(row, 0, data.invoiceNumber)
            ws.style(row, 0).fontSize(20).bold().set()
            row += 2

            ws.value(row, 0, "Issued")
            ws.value(row, 1, fmtLocalDate(data.issueDate))
            ws.style(row, 0).fontColor("525252").set()
            row++

            ws.value(row, 0, "Period")
            ws.value(row, 1, "${fmtLocalDate(data.periodStart)} — ${fmtLocalDate(data.periodEnd)}")
            ws.style(row, 0).fontColor("525252").set()
            row++

            ws.value(row, 0, "Currency")
            ws.value(row, 1, data.currency)
            ws.style(row, 0).fontColor("525252").set()
            row += 2

            ws.value(row, 0, "FROM")
            ws.style(row, 0).fontSize(9).fontColor("737373").bold().set()
            row++
            if (!data.business.isEmpty) {
                if (data.business.name.isNotBlank()) { ws.value(row++, 0, data.business.name) }
                if (data.business.email.isNotBlank()) {
                    ws.value(row, 0, data.business.email)
                    ws.style(row, 0).fontColor("525252").set()
                    row++
                }
                if (data.business.address.isNotBlank()) {
                    ws.value(row, 0, data.business.address)
                    ws.style(row, 0).fontColor("525252").set()
                    row++
                }
            } else {
                ws.value(row, 0, "(set in Settings)")
                ws.style(row, 0).fontColor("A3A3A3").italic().set()
                row++
            }
            row++

            ws.value(row, 0, "BILL TO")
            ws.style(row, 0).fontSize(9).fontColor("737373").bold().set()
            row++
            ws.value(row++, 0, data.client.name)
            data.client.email?.takeIf { it.isNotBlank() }?.let {
                ws.value(row, 0, it)
                ws.style(row, 0).fontColor("525252").set()
                row++
            }
            data.client.address?.takeIf { it.isNotBlank() }?.let {
                ws.value(row, 0, it)
                ws.style(row, 0).fontColor("525252").set()
                row++
            }
            row++

            ws.value(row, 0, "PROJECT")
            ws.style(row, 0).fontSize(9).fontColor("737373").bold().set()
            row++
            ws.value(row++, 0, data.projectDisplayName)
            row++

            ws.value(row, 0, "Date")
            ws.value(row, 1, "Hours")
            ws.value(row, 2, "Rate")
            ws.value(row, 3, "Amount")
            for (c in 0..3) {
                ws.style(row, c).fontSize(9).fontColor("737373").bold().set()
            }
            row++

            data.lineItems.forEach { item ->
                ws.value(row, 0, fmtLocalDate(item.date))
                ws.value(row, 1, item.hours)
                ws.style(row, 1).format("0.00").set()
                ws.value(row, 2, item.rate)
                ws.style(row, 2).format("#,##0.00").fontColor("525252").set()
                ws.value(row, 3, item.amount)
                ws.style(row, 3).format("#,##0.00").set()
                row++
            }
            row++

            ws.value(row, 2, "Subtotal")
            ws.value(row, 3, data.subtotal)
            ws.style(row, 2).fontColor("525252").set()
            ws.style(row, 3).format("#,##0.00").set()
            row++
            if (data.taxRatePercent > 0.0) {
                ws.value(row, 2, "Tax (${"%.2f".format(data.taxRatePercent)}%)")
                ws.value(row, 3, data.taxAmount)
                ws.style(row, 2).fontColor("525252").set()
                ws.style(row, 3).format("#,##0.00").set()
                row++
            }
            ws.value(row, 2, "Total")
            ws.value(row, 3, data.total)
            ws.style(row, 2).bold().fontSize(13).set()
            ws.style(row, 3).bold().fontSize(13).format("#,##0.00").set()
            row += 2

            if (!data.notes.isNullOrBlank()) {
                ws.value(row, 0, "NOTES")
                ws.style(row, 0).fontSize(9).fontColor("737373").bold().set()
                row++
                ws.value(row, 0, data.notes)
                ws.style(row, 0).fontColor("525252").set()
            }

            workbook.finish()
        }
        return outFile
    }

    private fun fmtLocalDate(d: LocalDate): String =
        InvoiceFormatting.shortDateFmt.format(d)
}
