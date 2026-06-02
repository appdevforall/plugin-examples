package com.appdevforall.contractor.plugin.data.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.appdevforall.contractor.plugin.domain.model.InvoiceData
import com.appdevforall.contractor.plugin.domain.model.InvoiceFormat
import java.io.File
import java.io.FileOutputStream

class PdfInvoiceExporter : InvoiceExporter {
    override val format = InvoiceFormat.PDF

    private val pageWidth = 595
    private val pageHeight = 842
    private val marginX = 48
    private val marginY = 56

    override fun export(data: InvoiceData, outFile: File): File {
        outFile.parentFile?.mkdirs()
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            drawInvoice(canvas, data)
            doc.finishPage(page)

            FileOutputStream(outFile).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return outFile
    }

    private fun drawInvoice(canvas: Canvas, data: InvoiceData) {
        val labelPaint = paint(Color.parseColor("#737373"), 9f, Typeface.SANS_SERIF, letterSpacing = 0.08f)
        val titlePaint = paint(Color.parseColor("#0A0A0A"), 28f, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD), letterSpacing = -0.02f)
        val bodyPaint = paint(Color.parseColor("#0A0A0A"), 11f, Typeface.SANS_SERIF)
        val mutedPaint = paint(Color.parseColor("#525252"), 11f, Typeface.SANS_SERIF)
        val monoPaint = paint(Color.parseColor("#0A0A0A"), 11f, Typeface.MONOSPACE)
        val monoMutedPaint = paint(Color.parseColor("#525252"), 11f, Typeface.MONOSPACE)
        val totalLabelPaint = paint(Color.parseColor("#0A0A0A"), 13f, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
        val totalAmountPaint = paint(Color.parseColor("#0A0A0A"), 18f, Typeface.MONOSPACE)
        val accentPaint = paint(Color.parseColor("#C2410C"), 11f, Typeface.MONOSPACE)
        val rulePaint = Paint().apply {
            color = Color.parseColor("#E5E5E5")
            strokeWidth = 1f
            isAntiAlias = false
        }

        var y = marginY.toFloat()

        canvas.drawText("INVOICE", marginX.toFloat(), y, labelPaint)
        y += 26f
        canvas.drawText(data.invoiceNumber, marginX.toFloat(), y, titlePaint)
        y += 8f

        val rightX = (pageWidth - marginX).toFloat()
        var headerRightY = (marginY + 10).toFloat()
        canvas.drawText("ISSUED", rightX - widthOf("ISSUED", labelPaint), headerRightY, labelPaint)
        headerRightY += 16f
        val issuedStr = InvoiceFormatting.dateFmt.format(data.issueDate)
        canvas.drawText(issuedStr, rightX - widthOf(issuedStr, bodyPaint), headerRightY, bodyPaint)
        headerRightY += 22f
        canvas.drawText("PERIOD", rightX - widthOf("PERIOD", labelPaint), headerRightY, labelPaint)
        headerRightY += 16f
        val periodLine = "${InvoiceFormatting.shortDateFmt.format(data.periodStart)} — ${InvoiceFormatting.shortDateFmt.format(data.periodEnd)}"
        canvas.drawText(periodLine, rightX - widthOf(periodLine, bodyPaint), headerRightY, bodyPaint)

        y += 32f
        canvas.drawLine(marginX.toFloat(), y, rightX, y, rulePaint)
        y += 24f

        val colWidth = (pageWidth - 2 * marginX) / 2
        val rightColX = (marginX + colWidth + 16).toFloat()
        val startY = y

        canvas.drawText("FROM", marginX.toFloat(), y, labelPaint)
        var leftY = y + 16f
        if (!data.business.isEmpty) {
            data.business.name.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(it, marginX.toFloat(), leftY, paint(Color.parseColor("#0A0A0A"), 12f, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)))
                leftY += 18f
            }
            data.business.email.takeIf { it.isNotBlank() }?.let {
                canvas.drawText(it, marginX.toFloat(), leftY, mutedPaint)
                leftY += 16f
            }
            data.business.address.takeIf { it.isNotBlank() }?.let { addr ->
                drawWrapped(canvas, addr, marginX.toFloat(), leftY, colWidth.toFloat(), mutedPaint)
                leftY += 16f * (linesNeeded(addr, colWidth.toFloat(), mutedPaint))
            }
        } else {
            canvas.drawText("(set in Settings)", marginX.toFloat(), leftY, mutedPaint)
            leftY += 16f
        }

        canvas.drawText("BILL TO", rightColX, startY, labelPaint)
        var rightY = startY + 16f
        canvas.drawText(data.client.name, rightColX, rightY, paint(Color.parseColor("#0A0A0A"), 12f, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)))
        rightY += 18f
        data.client.email?.takeIf { it.isNotBlank() }?.let {
            canvas.drawText(it, rightColX, rightY, mutedPaint)
            rightY += 16f
        }
        data.client.address?.takeIf { it.isNotBlank() }?.let { addr ->
            drawWrapped(canvas, addr, rightColX, rightY, colWidth.toFloat(), mutedPaint)
            rightY += 16f * (linesNeeded(addr, colWidth.toFloat(), mutedPaint))
        }

        y = maxOf(leftY, rightY) + 12f
        canvas.drawLine(marginX.toFloat(), y, rightX, y, rulePaint)
        y += 20f

        canvas.drawText("PROJECT", marginX.toFloat(), y, labelPaint)
        y += 16f
        canvas.drawText(data.projectDisplayName, marginX.toFloat(), y, bodyPaint)
        y += 28f

        val colDate = marginX.toFloat()
        val colHours = (pageWidth - marginX - 280).toFloat()
        val colRate = (pageWidth - marginX - 170).toFloat()
        val colAmount = rightX

        canvas.drawText("DATE", colDate, y, labelPaint)
        canvas.drawText("HOURS", colHours - widthOf("HOURS", labelPaint), y, labelPaint)
        canvas.drawText("RATE", colRate - widthOf("RATE", labelPaint), y, labelPaint)
        canvas.drawText("AMOUNT", colAmount - widthOf("AMOUNT", labelPaint), y, labelPaint)
        y += 8f
        canvas.drawLine(marginX.toFloat(), y, rightX, y, rulePaint)
        y += 18f

        data.lineItems.forEach { item ->
            canvas.drawText(InvoiceFormatting.shortDateFmt.format(item.date), colDate, y, bodyPaint)
            val hoursStr = InvoiceFormatting.hours(item.hours)
            canvas.drawText(hoursStr, colHours - widthOf(hoursStr, monoPaint), y, monoPaint)
            val rateStr = InvoiceFormatting.moneyPlain(item.rate)
            canvas.drawText(rateStr, colRate - widthOf(rateStr, monoMutedPaint), y, monoMutedPaint)
            val amountStr = InvoiceFormatting.moneyPlain(item.amount)
            canvas.drawText(amountStr, colAmount - widthOf(amountStr, monoPaint), y, monoPaint)
            y += 18f
        }

        y += 6f
        canvas.drawLine(marginX.toFloat(), y, rightX, y, rulePaint)
        y += 22f

        val labelXSummary = (pageWidth - marginX - 220).toFloat()
        canvas.drawText("Subtotal", labelXSummary, y, mutedPaint)
        canvas.drawText(InvoiceFormatting.moneyPlain(data.subtotal), colAmount - widthOf(InvoiceFormatting.moneyPlain(data.subtotal), monoPaint), y, monoPaint)
        y += 18f
        if (data.taxRatePercent > 0.0) {
            canvas.drawText("Tax (${"%.2f".format(data.taxRatePercent)}%)", labelXSummary, y, mutedPaint)
            canvas.drawText(InvoiceFormatting.moneyPlain(data.taxAmount), colAmount - widthOf(InvoiceFormatting.moneyPlain(data.taxAmount), monoPaint), y, monoPaint)
            y += 18f
        }
        y += 8f
        canvas.drawLine(labelXSummary, y, rightX, y, rulePaint)
        y += 24f
        canvas.drawText("TOTAL", labelXSummary, y, totalLabelPaint)
        val totalStr = InvoiceFormatting.money(data.total, data.currency)
        canvas.drawText(totalStr, colAmount - widthOf(totalStr, totalAmountPaint), y, totalAmountPaint)

        if (!data.notes.isNullOrBlank()) {
            y += 40f
            canvas.drawText("NOTES", marginX.toFloat(), y, labelPaint)
            y += 16f
            drawWrapped(canvas, data.notes, marginX.toFloat(), y, (pageWidth - 2 * marginX).toFloat(), mutedPaint)
        }

        val footerY = (pageHeight - marginY).toFloat()
        canvas.drawText("Generated with Contractor", marginX.toFloat(), footerY, mutedPaint)
        canvas.drawText("•", marginX + widthOf("Generated with Contractor", mutedPaint) + 6f, footerY, accentPaint)
    }

    private fun paint(
        color: Int,
        size: Float,
        typeface: Typeface,
        letterSpacing: Float = 0f
    ): Paint = Paint().apply {
        this.color = color
        this.textSize = size
        this.typeface = typeface
        this.isAntiAlias = true
        this.letterSpacing = letterSpacing
    }

    private fun widthOf(text: String, paint: Paint): Float = paint.measureText(text)

    private fun linesNeeded(text: String, maxWidth: Float, paint: Paint): Int {
        val words = text.split(' ')
        var line = StringBuilder()
        var lines = 0
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth) {
                lines++
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) lines++
        return maxOf(lines, 1)
    }

    private fun drawWrapped(canvas: Canvas, text: String, x: Float, startY: Float, maxWidth: Float, paint: Paint) {
        val words = text.split(' ')
        var y = startY
        var line = StringBuilder()
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) > maxWidth) {
                canvas.drawText(line.toString(), x, y, paint)
                y += 16f
                line = StringBuilder(word)
            } else {
                line = StringBuilder(candidate)
            }
        }
        if (line.isNotEmpty()) canvas.drawText(line.toString(), x, y, paint)
    }
}
