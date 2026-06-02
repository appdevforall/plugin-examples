package com.appdevforall.contractor.plugin.data.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("contractor_settings", Context.MODE_PRIVATE)

    var idleThresholdMinutes: Int
        get() = prefs.getInt(KEY_IDLE_THRESHOLD, DEFAULT_IDLE_MINUTES)
        set(value) = prefs.edit().putInt(KEY_IDLE_THRESHOLD, value.coerceIn(1, 60)).apply()

    var sessionMergeGapMinutes: Int
        get() = prefs.getInt(KEY_MERGE_GAP, DEFAULT_MERGE_GAP_MINUTES)
        set(value) = prefs.edit().putInt(KEY_MERGE_GAP, value.coerceIn(0, 30)).apply()

    var defaultTaxPercent: Double
        get() = prefs.getString(KEY_DEFAULT_TAX, DEFAULT_TAX.toString())?.toDoubleOrNull() ?: DEFAULT_TAX
        set(value) = prefs.edit().putString(KEY_DEFAULT_TAX, value.toString()).apply()

    var defaultCurrency: String
        get() = prefs.getString(KEY_DEFAULT_CURRENCY, DEFAULT_CURRENCY) ?: DEFAULT_CURRENCY
        set(value) = prefs.edit().putString(KEY_DEFAULT_CURRENCY, value).apply()

    var invoicePrefix: String
        get() = prefs.getString(KEY_INVOICE_PREFIX, DEFAULT_INVOICE_PREFIX) ?: DEFAULT_INVOICE_PREFIX
        set(value) = prefs.edit().putString(KEY_INVOICE_PREFIX, value).apply()

    var businessName: String
        get() = prefs.getString(KEY_BUSINESS_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BUSINESS_NAME, value).apply()

    var businessEmail: String
        get() = prefs.getString(KEY_BUSINESS_EMAIL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BUSINESS_EMAIL, value).apply()

    var businessAddress: String
        get() = prefs.getString(KEY_BUSINESS_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BUSINESS_ADDRESS, value).apply()

    fun observeAny(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun observeIdleThresholdMinutes(): Flow<Int> = observeAny().map { idleThresholdMinutes }

    companion object {
        private const val KEY_IDLE_THRESHOLD = "idle_threshold_minutes"
        private const val KEY_MERGE_GAP = "merge_gap_minutes"
        private const val KEY_DEFAULT_TAX = "default_tax_percent"
        private const val KEY_DEFAULT_CURRENCY = "default_currency"
        private const val KEY_INVOICE_PREFIX = "invoice_prefix"
        private const val KEY_BUSINESS_NAME = "business_name"
        private const val KEY_BUSINESS_EMAIL = "business_email"
        private const val KEY_BUSINESS_ADDRESS = "business_address"

        const val DEFAULT_IDLE_MINUTES = 5
        const val DEFAULT_MERGE_GAP_MINUTES = 2
        const val DEFAULT_TAX = 0.0
        const val DEFAULT_CURRENCY = "USD"
        const val DEFAULT_INVOICE_PREFIX = "INV"
    }
}
