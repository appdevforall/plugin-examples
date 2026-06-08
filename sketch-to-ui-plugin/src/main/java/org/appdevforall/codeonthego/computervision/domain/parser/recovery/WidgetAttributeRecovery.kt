package org.appdevforall.codeonthego.computervision.domain.parser.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.IdCleaner

internal object WidgetAttributeRecovery {
    private const val EDIT_TEXT_TAG = "EditText"
    private const val IMAGE_VIEW_TAG = "ImageView"

    fun recoverMissingAttributes(attributes: Map<String, String>, annotation: String, tag: String): Map<String, String> {
        return when (tag) {
            EDIT_TEXT_TAG -> EditTextAttributeRecovery.recover(attributes, annotation)
            IMAGE_VIEW_TAG -> recoverImageViewAttributes(attributes, annotation)
            else -> attributes
        }
    }

    fun enforceOutputRules(attributes: Map<String, String>, annotation: String, tag: String): Map<String, String> {
        return when (tag) {
            EDIT_TEXT_TAG -> EditTextAttributeRecovery.enforceOutputRules(attributes, annotation)
            else -> attributes
        }
    }

    private fun recoverImageViewAttributes(attributes: Map<String, String>, annotation: String): Map<String, String> {
        if (attributes.containsKey(AttributeKey.ID.xmlName)) return attributes

        val recoveredId = imageViewIdRegex.find(annotation)?.value
            ?: imageViewIdCompactRegex.find(annotation)?.value
            ?: return attributes

        return attributes + (AttributeKey.ID.xmlName to IdCleaner.clean(recoveredId, IMAGE_VIEW_TAG))
    }

    private val imageViewIdRegex = Regex(
        "\\b(?:i?m)[_\\s-]+view[_\\s-]+\\d+\\b",
        RegexOption.IGNORE_CASE
    )
    private val imageViewIdCompactRegex = Regex(
        "\\b(?:i?m)view\\d+\\b",
        RegexOption.IGNORE_CASE
    )
}
