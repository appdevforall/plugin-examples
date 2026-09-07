package org.appdevforall.codeonthego.computervision.domain.metadata.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey

internal object RecoveredAnnotationFormatter {
    private const val ANNOTATION_SEPARATOR = "|"

    fun format(metadata: ParsedMetadata, recoveredAttributes: Map<String, String>): String {
        return recoveredAttributes.entries.fold(metadata.rawText) { annotation, (key, value) ->
            if (metadata.attributes[key] == value) annotation else annotation.withResolvedFragment(key, value)
        }
    }

    private fun String.withResolvedFragment(xmlKey: String, value: String): String {
        val fragment = "${xmlKey.removePrefix("android:")}: $value"
        return if (xmlKey == AttributeKey.ID.xmlName) prependFragment(fragment) else appendFragment(fragment)
    }

    private fun String.appendFragment(fragment: String): String {
        return if (isBlank()) fragment else "$this $ANNOTATION_SEPARATOR $fragment"
    }

    private fun String.prependFragment(fragment: String): String {
        return if (isBlank()) fragment else "$fragment $ANNOTATION_SEPARATOR $this"
    }
}
