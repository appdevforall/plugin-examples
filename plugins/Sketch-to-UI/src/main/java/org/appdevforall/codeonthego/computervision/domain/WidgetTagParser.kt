package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.widgettag.WidgetTagAndroidMapper
import org.appdevforall.codeonthego.computervision.domain.widgettag.WidgetTagSyntax

/**
 * Parses and normalizes raw OCR text into standardized Android widget tags.
 * Delegates syntax normalization and Android widget mapping to focused components.
 */
internal object WidgetTagParser {
    fun isTag(text: String): Boolean = WidgetTagSyntax.isTag(text)

    fun isTagSequence(text: String): Boolean = WidgetTagSyntax.isTagSequence(text)

    fun normalizeTagText(text: String): String = WidgetTagSyntax.normalize(text)

    fun extractTag(text: String): Pair<String, String?>? {
        return WidgetTagSyntax.extract(text)?.let { it.tag to it.trailingText }
    }

    /** Extracts the numeric ordinal after the tag prefix. */
    fun extractOrdinal(tag: String): Int? = tag.substringAfter('-', "").toIntOrNull()

    fun androidTagFor(tag: String): String = WidgetTagAndroidMapper.androidTagFor(normalizeTagText(tag))
}
