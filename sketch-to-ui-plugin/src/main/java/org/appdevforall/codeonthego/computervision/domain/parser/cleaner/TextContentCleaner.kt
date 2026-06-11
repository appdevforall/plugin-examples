package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner

internal object TextContentCleaner : ValueCleaner {
    private val idKeyPattern = AttributeKey.ID.aliases
        .sortedByDescending { it.length }
        .joinToString("|")
    private val trailingFailedIdFragmentRegex = AttributeRegexPatterns.trailingFailedIdFragment(idKeyPattern)

    override fun clean(rawValue: String): String {
        return rawValue
            .replace(AttributeRegexPatterns.TRAILING_REPEATED_WIDGET_PREFIX, " ")
            .replace(AttributeRegexPatterns.TRAILING_WIDGET_TAG, "")
            .replace(trailingFailedIdFragmentRegex, "")
            .replace(AttributeRegexPatterns.WHITESPACE, " ")
            .trim()
    }
}
