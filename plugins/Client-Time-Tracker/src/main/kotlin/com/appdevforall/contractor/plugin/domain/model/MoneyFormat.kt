package com.appdevforall.contractor.plugin.domain.model

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object MoneyFormat {

    fun format(amount: Double, currencyCode: String): String {
        val nf = NumberFormat.getCurrencyInstance(Locale.getDefault())
        return runCatching {
            nf.currency = Currency.getInstance(currencyCode)
            nf.format(amount)
        }.getOrElse { "$currencyCode ${"%,.2f".format(amount)}" }
    }

    fun formatPlain(amount: Double): String = "%,.2f".format(amount)

    fun symbolFor(currencyCode: String): String =
        runCatching { Currency.getInstance(currencyCode).getSymbol(Locale.getDefault()) }
            .getOrElse { currencyCode }

    fun commonCurrencies(): List<String> = listOf(
        "USD", "EUR", "GBP", "CAD", "AUD", "JPY", "CHF", "CNY", "INR", "NGN", "ZAR", "BRL", "MXN", "SGD", "NZD", "SEK", "NOK", "DKK"
    )
}
