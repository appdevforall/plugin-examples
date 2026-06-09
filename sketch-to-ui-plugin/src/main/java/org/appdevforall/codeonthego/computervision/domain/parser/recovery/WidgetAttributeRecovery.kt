package org.appdevforall.codeonthego.computervision.domain.parser.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.DrawableCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.IdCleaner

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

    /**
     * Restores ImageView-only metadata that is commonly lost in compact OCR.
     *
     * Recovery is limited to square dimensions and ID normalization backed by drawable evidence.
     */
    private fun recoverImageViewAttributes(attributes: Map<String, String>, annotation: String): Map<String, String> {
        val withSquareDimensions = recoverRepeatedSquareDimension(attributes, annotation).toMutableMap()
        val drawableName = resolveDrawableName(withSquareDimensions, annotation)
        normalizeExistingImageId(withSquareDimensions, drawableName)?.let { return it }

        val recoveredId = explicitIdRegex.find(annotation)?.groupValues?.get(1)
            ?: imageViewIdRegex.find(annotation)?.value
            ?: imageViewIdCompactRegex.find(annotation)?.value
            ?: return withSquareDimensions

        val cleanedId = IdCleaner.clean(recoveredId, IMAGE_VIEW_TAG)
        return withSquareDimensions + (AttributeKey.ID.xmlName to normalizeCompactImageId(cleanedId, drawableName))
    }

    private fun normalizeExistingImageId(
        attributes: MutableMap<String, String>,
        drawableName: String?
    ): Map<String, String>? {
        val id = attributes[AttributeKey.ID.xmlName] ?: return null
        attributes[AttributeKey.ID.xmlName] = normalizeCompactImageId(id, drawableName)
        return attributes
    }

    /**
     * Restores the missing side of a square ImageView only when OCR shows the same dimension twice.
     *
     * This keeps icon/logo dimensions stable without inventing a width or height from unrelated text.
     */
    private fun recoverRepeatedSquareDimension(attributes: Map<String, String>, annotation: String): Map<String, String> {
        val width = attributes[AttributeKey.WIDTH.xmlName]
        val height = attributes[AttributeKey.HEIGHT.xmlName]
        val hasWidth = width != null
        val hasHeight = height != null
        val hasExactlyOneDimension = hasWidth != hasHeight

        if (!hasExactlyOneDimension) {
            return attributes
        }

        val knownDimension = width ?: height ?: return attributes
        val repeatedDimension = adjacentRepeatedDimensionRegex.find(annotation)?.groupValues?.get(1)?.let { "${it}dp" }
        if (!knownDimension.equals(repeatedDimension, ignoreCase = true)) {
            return attributes
        }

        val missingKey = if (width == null) AttributeKey.WIDTH.xmlName else AttributeKey.HEIGHT.xmlName
        return attributes + (missingKey to knownDimension)
    }

    private fun normalizeCompactImageId(id: String, drawableName: String?): String {
        val compactMatch = compactImageIdRegex.matchEntire(id) ?: return id
        val suffix = compactMatch.groupValues[1]
        return if (suffix == drawableName) "img_$suffix" else id
    }

    private fun resolveDrawableName(attributes: Map<String, String>, annotation: String): String? {
        val rawDrawable = attributes[AttributeKey.SRC.xmlName]
            ?: srcRegex.find(annotation)?.groupValues?.get(1)
            ?: return null

        return DrawableCleaner.clean(rawDrawable).substringAfterLast('/')
    }

    private val imageViewIdRegex = AttributeRegexPatterns.IMAGE_VIEW_ID_TOKENS
    private val imageViewIdCompactRegex = AttributeRegexPatterns.IMAGE_VIEW_ID_COMPACT
    private val explicitIdRegex = AttributeRegexPatterns.EXPLICIT_LOWER_ID
    private val adjacentRepeatedDimensionRegex = AttributeRegexPatterns.ADJACENT_REPEATED_DIMENSION
    private val compactImageIdRegex = AttributeRegexPatterns.COMPACT_IMAGE_ID
    private val srcRegex = AttributeRegexPatterns.EXPLICIT_SRC
}
