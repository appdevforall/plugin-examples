package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.CoreParserPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.IdPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.TextPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.WidgetTagPatterns

internal object TextContentCleaner : ValueCleaner {
    private val idKeyPattern = AttributeKey.ID.aliases
        .sortedByDescending { it.length }
        .joinToString("|")
    private val trailingFailedIdFragmentRegex = IdPatterns.trailingFailedIdFragment(idKeyPattern)

    override fun clean(rawValue: String): String {
        return rawValue
            .replace(WidgetTagPatterns.TRAILING_REPEATED_WIDGET_PREFIX, " ")
            .replace(WidgetTagPatterns.TRAILING_WIDGET_TAG, "")
            .replace(trailingFailedIdFragmentRegex, "")
            .replace(CoreParserPatterns.WHITESPACE, " ")
            .trim()
    }
}
