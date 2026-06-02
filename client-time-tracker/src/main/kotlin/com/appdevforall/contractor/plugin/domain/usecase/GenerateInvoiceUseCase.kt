package com.appdevforall.contractor.plugin.domain.usecase

import com.appdevforall.contractor.plugin.data.db.entities.TrackedProjectEntity
import com.appdevforall.contractor.plugin.data.db.entities.WorkSessionEntity
import com.appdevforall.contractor.plugin.data.prefs.SettingsStore
import com.appdevforall.contractor.plugin.data.repository.SessionRepository
import com.appdevforall.contractor.plugin.domain.model.BusinessInfo
import com.appdevforall.contractor.plugin.domain.model.ClientInfo
import com.appdevforall.contractor.plugin.domain.model.DateRanges
import com.appdevforall.contractor.plugin.domain.model.InvoiceData
import com.appdevforall.contractor.plugin.domain.model.InvoiceLineItem
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.round

class GenerateInvoiceUseCase(
    private val sessionRepository: SessionRepository,
    private val settingsStore: SettingsStore
) {

    data class Input(
        val project: TrackedProjectEntity,
        val invoiceNumber: String,
        val periodStartMillis: Long,
        val periodEndMillisExclusive: Long,
        val notes: String?
    )

    sealed class Result {
        data class Success(val data: InvoiceData, val sessions: List<WorkSessionEntity>) : Result()
        object NoBillableSessions : Result()
    }

    suspend fun build(input: Input): Result {
        val sessions = sessionRepository.getInRange(
            input.project.id,
            input.periodStartMillis,
            input.periodEndMillisExclusive
        ).filter { SessionMerger.durationOf(it) > 0L }

        if (sessions.isEmpty()) return Result.NoBillableSessions

        val mergeGapMs = settingsStore.sessionMergeGapMinutes.toLong() * 60_000L
        val merged = SessionMerger.mergeAdjacent(sessions, mergeGapMs)

        val zone = ZoneId.systemDefault()
        val byDay = merged.groupBy {
            java.time.Instant.ofEpochMilli(it.startTime).atZone(zone).toLocalDate()
        }.toSortedMap()

        val rate = input.project.hourlyRate
        val lineItems = byDay.map { (date, group) ->
            val totalMs = group.sumOf { it.durationMillis }
            val hours = totalMs / 3_600_000.0
            val amount = round2(hours * rate)
            InvoiceLineItem(
                date = date,
                durationMillis = totalMs,
                hours = round2(hours),
                rate = rate,
                amount = amount,
                description = null
            )
        }

        val totalDuration = lineItems.sumOf { it.durationMillis }
        val subtotal = round2(lineItems.sumOf { it.amount })
        val taxAmount = round2(subtotal * (input.project.taxRatePercent / 100.0))
        val total = round2(subtotal + taxAmount)

        val business = BusinessInfo(
            name = settingsStore.businessName,
            email = settingsStore.businessEmail,
            address = settingsStore.businessAddress
        )

        val client = ClientInfo(
            name = input.project.clientName,
            email = input.project.clientEmail,
            address = input.project.clientAddress
        )

        val periodStart = DateRanges.toLocalDate(input.periodStartMillis)
        val periodEndInclusive = DateRanges.toLocalDate(input.periodEndMillisExclusive - 1)

        val data = InvoiceData(
            invoiceNumber = input.invoiceNumber,
            issueDate = LocalDate.now(zone),
            periodStart = periodStart,
            periodEnd = periodEndInclusive,
            business = business,
            client = client,
            projectDisplayName = input.project.displayName,
            lineItems = lineItems,
            subtotal = subtotal,
            taxRatePercent = input.project.taxRatePercent,
            taxAmount = taxAmount,
            total = total,
            currency = input.project.currency,
            totalDurationMillis = totalDuration,
            notes = input.notes
        )
        return Result.Success(data, sessions)
    }

    private fun round2(v: Double): Double = round(v * 100.0) / 100.0
}
