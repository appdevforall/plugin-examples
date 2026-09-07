package org.appdevforall.codeonthego.computervision.domain.metadata.recovery

import org.appdevforall.codeonthego.computervision.domain.WidgetTagParser

internal data class ParsedMetadata(
    val tag: String,
    val androidTag: String,
    val rawText: String,
    val attributes: Map<String, String>
) {
    val prefix: String
        get() = WidgetTagParser.normalizeTagText(tag).substringBefore('-')
}
