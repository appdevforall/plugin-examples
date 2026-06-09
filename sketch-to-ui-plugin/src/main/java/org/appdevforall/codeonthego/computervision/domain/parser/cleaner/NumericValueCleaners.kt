package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner

internal object NumberCleaner : ValueCleaner {
    private val ocrCharMap = mapOf(
        'O' to '0', 'A' to '0', '@' to '0', 'Q' to '0',
        'L' to '1', 'I' to '1', '|' to '1', '!' to '1', '/' to '1', '\\' to '1',
        '(' to '1', ')' to '1', '[' to '1', ']' to '1',
        'Z' to '2', 'S' to '5', 'B' to '6'
    )

    override fun clean(rawValue: String): String {
        val translated = rawValue.map { ocrCharMap[it.uppercaseChar()] ?: it }.joinToString("")
        return AttributeRegexPatterns.SIGNED_INTEGER.find(translated)?.value ?: rawValue
    }
}

internal object FloatCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        return AttributeRegexPatterns.SIGNED_DECIMAL.find(rawValue)?.value ?: rawValue
    }
}
