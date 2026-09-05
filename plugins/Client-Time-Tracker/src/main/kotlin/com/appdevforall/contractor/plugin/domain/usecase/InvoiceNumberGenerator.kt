package com.appdevforall.contractor.plugin.domain.usecase

import com.appdevforall.contractor.plugin.data.prefs.SettingsStore
import com.appdevforall.contractor.plugin.data.repository.InvoiceRepository
import java.time.LocalDate
import java.time.ZoneId

class InvoiceNumberGenerator(
    private val invoiceRepository: InvoiceRepository,
    private val settingsStore: SettingsStore
) {
    suspend fun next(): String {
        val year = LocalDate.now(ZoneId.systemDefault()).year.toString()
        val countSoFar = invoiceRepository.countForYear(year)
        val seq = (countSoFar + 1).toString().padStart(4, '0')
        return "${settingsStore.invoicePrefix}-$year-$seq"
    }
}
