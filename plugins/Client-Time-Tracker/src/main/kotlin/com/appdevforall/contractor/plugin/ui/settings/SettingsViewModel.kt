package com.appdevforall.contractor.plugin.ui.settings

import com.appdevforall.contractor.plugin.ContractorServiceLocator
import com.appdevforall.contractor.plugin.data.prefs.SettingsStore
import com.appdevforall.contractor.plugin.ui.common.BaseViewModel
import com.appdevforall.contractor.plugin.ui.common.viewModelFactory

class SettingsViewModel(
    private val store: SettingsStore
) : BaseViewModel<SettingsState, SettingsIntent, SettingsEffect>(SettingsState()) {

    override fun handleIntent(intent: SettingsIntent) {
        when (intent) {
            SettingsIntent.Init -> {
                if (currentState.initial == null) {
                    reduce {
                        copy(
                            initial = SettingsValues(
                                idleMinutes = store.idleThresholdMinutes,
                                mergeMinutes = store.sessionMergeGapMinutes,
                                defaultTaxPercent = store.defaultTaxPercent,
                                defaultCurrency = store.defaultCurrency,
                                invoicePrefix = store.invoicePrefix,
                                businessName = store.businessName,
                                businessEmail = store.businessEmail,
                                businessAddress = store.businessAddress
                            )
                        )
                    }
                }
            }
            is SettingsIntent.Save -> {
                with(intent.values) {
                    store.idleThresholdMinutes = idleMinutes
                    store.sessionMergeGapMinutes = mergeMinutes
                    store.defaultTaxPercent = defaultTaxPercent
                    store.defaultCurrency = defaultCurrency.takeIf { it.isNotBlank() } ?: store.defaultCurrency
                    store.invoicePrefix = invoicePrefix.takeIf { it.isNotBlank() } ?: store.invoicePrefix
                    store.businessName = businessName
                    store.businessEmail = businessEmail
                    store.businessAddress = businessAddress
                }
                emit(SettingsEffect.Dismiss)
            }
            SettingsIntent.Cancel -> emit(SettingsEffect.Dismiss)
        }
    }

    companion object {
        fun factory() = viewModelFactory {
            val sl = ContractorServiceLocator.get()
            SettingsViewModel(sl.settings)
        }
    }
}
