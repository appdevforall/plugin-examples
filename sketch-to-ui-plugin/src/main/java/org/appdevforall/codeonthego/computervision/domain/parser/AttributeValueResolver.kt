package org.appdevforall.codeonthego.computervision.domain.parser

import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.ColorCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.DimensionCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.DrawableCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.FloatCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.GravityCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.IdCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.InputTypeCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.NumberCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.SpDimensionCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.TextContentCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.TextStyleCleaner

internal data class ResolvedAttribute(val xmlAttr: String, val value: String)

internal object AttributeValueResolver {
    private const val BUTTON_TAG = "Button"
    private const val SWITCH_TAG = "Switch"

    private val cleaners = mapOf(
        ValueType.TEXT_CONTENT to TextContentCleaner,
        ValueType.DIMENSION to DimensionCleaner,
        ValueType.SP_DIMENSION to SpDimensionCleaner,
        ValueType.COLOR to ColorCleaner,
        ValueType.ID to IdCleaner,
        ValueType.DRAWABLE to DrawableCleaner,
        ValueType.INTEGER to NumberCleaner,
        ValueType.FLOAT to FloatCleaner,
        ValueType.TEXT_STYLE to TextStyleCleaner,
        ValueType.RAW to ValueCleaner { it }
    )

    fun resolve(key: AttributeKey, rawValue: String, tag: String): ResolvedAttribute? {
        val trimmedRawValue = rawValue.trim()
        if (key.valueType == ValueType.DIMENSION && trimmedRawValue.isLowConfidenceDimensionFragment()) return null
        val cleaner = cleaners[key.valueType] ?: ValueCleaner { it }
        val cleanedValue = when (key) {
            AttributeKey.ID -> IdCleaner.clean(trimmedRawValue, tag)
            AttributeKey.INPUT_TYPE -> InputTypeCleaner.clean(trimmedRawValue)
            AttributeKey.LAYOUT_GRAVITY, AttributeKey.GRAVITY -> GravityCleaner.clean(trimmedRawValue)
            else -> cleaner.clean(trimmedRawValue)
        }

        if (cleanedValue.isEmpty()) return null

        val recoveredValue = recoverSwitchWidth(key, rawValue, cleanedValue, tag)
        return resolveXmlAttribute(key, recoveredValue, tag)
    }

    private fun String.isLowConfidenceDimensionFragment(): Boolean {
        val compact = lowercase().replace(AttributeRegexPatterns.NON_ALPHANUMERIC_LOWER, "")
        val hasDimensionKeyText = compact.contains("layoutheight") ||
            compact.contains("layoutheiqht") ||
            compact.contains("height")
        val hasUnit = AttributeRegexPatterns.COMPACT_DIMENSION_UNIT.containsMatchIn(this)
        return hasDimensionKeyText && !hasUnit
    }

    private fun recoverSwitchWidth(key: AttributeKey, rawValue: String, cleanedValue: String, tag: String): String {
        val widthLostLeadingOne = tag == SWITCH_TAG &&
            key == AttributeKey.WIDTH &&
            cleanedValue == "0dp" &&
            AttributeRegexPatterns.ZERO_ZERO_DP.matches(rawValue.trim())

        return if (widthLostLeadingOne) "100dp" else cleanedValue
    }

    private fun resolveXmlAttribute(key: AttributeKey, value: String, tag: String): ResolvedAttribute {
        if (key == AttributeKey.BACKGROUND && tag == BUTTON_TAG) {
            return ResolvedAttribute("app:backgroundTint", value)
        }
        if (key == AttributeKey.ID) {
            return ResolvedAttribute(key.xmlName, value.replace(" ", "_"))
        }
        return ResolvedAttribute(key.xmlName, value)
    }
}
