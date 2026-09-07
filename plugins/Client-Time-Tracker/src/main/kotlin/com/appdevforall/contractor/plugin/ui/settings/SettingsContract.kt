package com.appdevforall.contractor.plugin.ui.settings

import com.appdevforall.contractor.plugin.ui.common.UiEffect
import com.appdevforall.contractor.plugin.ui.common.UiIntent
import com.appdevforall.contractor.plugin.ui.common.UiState

data class SettingsValues(
    val idleMinutes: Int,
    val mergeMinutes: Int,
    val defaultTaxPercent: Double,
    val defaultCurrency: String,
    val invoicePrefix: String,
    val businessName: String,
    val businessEmail: String,
    val businessAddress: String
)

data class SettingsState(
    val initial: SettingsValues? = null
) : UiState

sealed interface SettingsIntent : UiIntent {
    data object Init : SettingsIntent
    data class Save(val values: SettingsValues) : SettingsIntent
    data object Cancel : SettingsIntent
}

sealed interface SettingsEffect : UiEffect {
    data object Dismiss : SettingsEffect
}
