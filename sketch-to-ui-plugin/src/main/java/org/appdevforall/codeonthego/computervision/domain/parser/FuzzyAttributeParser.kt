package org.appdevforall.codeonthego.computervision.domain.parser

import me.xdrop.fuzzywuzzy.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.grammar.UiGrammarValidator
import org.appdevforall.codeonthego.computervision.domain.parser.sanitizer.OcrSanitizerFactory
import java.lang.StringBuilder

object FuzzyAttributeParser {
    private val grammarValidator = UiGrammarValidator()
    private const val PIPE_DELIMITER = "|"
    private val multipleUnderscoresRegex = Regex("_+")
    private val textColorKeyPhraseRegex = Regex(
        "\\btext\\s*[-_ ]\\s*(?:colou?r|calar|colar)\\b",
        RegexOption.IGNORE_CASE
    )
    private val inputTypeValues = InputTypeValueSet.values.map { it.lowercase() }.toSet()
    private val sanitizer = OcrSanitizerFactory.createDefaultSanitizer()
    private const val SWITCH_TAG = "Switch"
    private const val IMAGE_VIEW_TAG = "ImageView"
    private const val SWITCH_ID_TARGET = "switch"
    private const val IMAGE_VIEW_ID_PREFIX = "im"
    private const val IMAGE_VIEW_ID_VIEW_TOKEN = "view"
    private const val SWITCH_ID_OCR_THRESHOLD = 70
    private const val IMAGE_VIEW_ID_OCR_THRESHOLD = 80
    private val switchIdTargets = listOf(SWITCH_ID_TARGET)
    private val imageViewIdPrefixTargets = listOf(IMAGE_VIEW_ID_PREFIX, "img", "image")
    private val imageViewIdViewTargets = listOf(IMAGE_VIEW_ID_VIEW_TOKEN)

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

    private val numericTypes = setOf(
        ValueType.DIMENSION,
        ValueType.SP_DIMENSION,
        ValueType.INTEGER,
        ValueType.FLOAT
    )

    fun parse(annotation: String?, tag: String): Map<String, String> {
        if (annotation.isNullOrBlank()) return emptyMap()

        val normalizedInput = normalizeOcrKeyPhrases(annotation.replace(Regex("\\s+:"), ":"))
        val tokens = tokenizeAnnotation(normalizedInput)

        val rawAttributes = recoverMissingAttributes(
            mapTokensToAttributes(tokens, tag),
            normalizedInput,
            tag
        )
        val finalAttributes = grammarValidator.enforceGrammar(rawAttributes, tag)

        return finalAttributes
    }

    private fun tokenizeAnnotation(annotation: String): List<String> {
        val sanitized = sanitizer.sanitize(annotation)

        return if (sanitized.contains(PIPE_DELIMITER)) {
            sanitized.split(PIPE_DELIMITER)
                .flatMap(::tokenizeDelimitedChunk)
                .filter { it.isNotEmpty() }
        } else {
            sanitized.split(Regex("[:;]|\\s+")).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun normalizeOcrKeyPhrases(annotation: String): String {
        return textColorKeyPhraseRegex.replace(annotation, "textcolor")
            .replace(Regex("\\blay(?:out|aut)[_\\- ]?gr(?:av|a)ity\\b", RegexOption.IGNORE_CASE), "layout_gravity")
            .replace(Regex("\\blayoutgravity\\b", RegexOption.IGNORE_CASE), "layout_gravity")
    }

    private fun tokenizeDelimitedChunk(chunk: String): List<String> {
        val trimmed = chunk.trim()
        if (trimmed.isEmpty()) return emptyList()

        val delimiterIndex = trimmed.indexOfFirst { it == ':' || it == ';' }
        if (delimiterIndex >= 0) {
            val key = trimmed.substring(0, delimiterIndex).trim()
            val value = trimmed.substring(delimiterIndex + 1).trim()
            return listOf(key, value).filter { it.isNotEmpty() }
        }

        val firstSpace = trimmed.indexOfFirst { it.isWhitespace() }
        if (firstSpace < 0) return listOf(trimmed)

        val key = trimmed.substring(0, firstSpace).trim()
        val value = trimmed.substring(firstSpace + 1).trim()
        return listOf(key, value).filter { it.isNotEmpty() }
    }

    private fun mapTokensToAttributes(tokens: List<String>, tag: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var currentKey: AttributeKey? = null
        val currentValue = StringBuilder()

        for (token in tokens) {
            val matchedKey = if (shouldTreatTokenAsValue(token, currentKey, currentValue)) {
                null
            } else {
                fuzzyMatchKey(token)
            }

            if (matchedKey != null) {
                flushAttribute(currentKey, currentValue.toString(), tag, result)
                currentKey = matchedKey
                currentValue.clear()
            } else {
                currentValue.append(token).append(" ")
            }
        }

        flushAttribute(currentKey, currentValue.toString(), tag, result)
        return result
    }

    private fun shouldTreatTokenAsValue(
        token: String,
        currentKey: AttributeKey?,
        currentValue: StringBuilder
    ): Boolean {
        val lowerToken = token.trim().lowercase()

        return when {
            currentKey == AttributeKey.ID &&
                currentValue.isBlank() &&
                AttributeKey.findByAlias(normalizeKeyToken(token)) == null -> true
            currentKey == AttributeKey.INPUT_TYPE && lowerToken in inputTypeValues -> true
            currentKey in setOf(AttributeKey.LAYOUT_GRAVITY, AttributeKey.GRAVITY) && lowerToken in GravityValueSet.values -> true
            currentKey?.valueType == ValueType.COLOR && isColorToken(lowerToken) -> true
            currentKey?.valueType == ValueType.DIMENSION && DimensionValueSet.allKeywords.any { it in lowerToken } -> true
            currentKey?.valueType in numericTypes -> lowerToken.any { it.isDigit() }
            else -> false
        }
    }

    private fun isColorToken(token: String): Boolean {
        return token.startsWith("#") || token.startsWith("@") || token in ColorCleaner.colorMap
    }

    private fun flushAttribute(key: AttributeKey?, rawValue: String, tag: String, destination: MutableMap<String, String>) {
        if (key == null || rawValue.isBlank()) return

        val trimmedRawValue = rawValue.trim()
        val cleaner = cleaners[key.valueType] ?: ValueCleaner { it }
        val cleanedValue = when (key) {
            AttributeKey.ID -> normalizeIdValue(trimmedRawValue, tag)
            AttributeKey.LAYOUT_GRAVITY, AttributeKey.GRAVITY -> cleanGravityValue(trimmedRawValue)
            else -> cleaner.clean(trimmedRawValue)
        }

        if (cleanedValue.isNotEmpty()) {
            val recoveredValue = recoverValue(key, rawValue, cleanedValue, tag)
            val (xmlAttr, finalValue) = resolveXmlAttribute(key, recoveredValue, tag)
            val existingValue = destination[xmlAttr]
            if (existingValue == null || shouldReplaceAttribute(xmlAttr, existingValue, finalValue, tag)) {
                destination[xmlAttr] = finalValue
            }
        }
    }

    private fun fuzzyMatchKey(rawKey: String): AttributeKey? {
        val normalizedKey = normalizeKeyToken(rawKey)

        val exactMatch = AttributeKey.findByAlias(normalizedKey)
        if (exactMatch != null) return exactMatch

        if (normalizedKey.length <= 2) return null

        val threshold = when {
            normalizedKey.length <= 3 -> 65
            normalizedKey.length <= 6 -> 75
            else -> 80
        }

        val result = FuzzySearch.extractOne(normalizedKey, AttributeKey.allAliases)

        return if (result.score >= threshold) AttributeKey.findByAlias(result.string) else null
    }

    private fun normalizeKeyToken(rawKey: String): String {
        return rawKey.lowercase()
            .replace("-", "_")
            .replace(Regex("\\s+"), "_")
            .replace(".", "_")
            .replace(multipleUnderscoresRegex, "_")
            .replace(Regex("lay[ao0]ut"), "layout")
            .replace(Regex("(?<=^|_)[lt]d(?=$|_)"), "id")
    }

    private fun resolveXmlAttribute(key: AttributeKey, value: String, tag: String): Pair<String, String> {
        if (key == AttributeKey.BACKGROUND && tag == "Button") return "app:backgroundTint" to value
        if (key == AttributeKey.ID) return key.xmlName to value.replace(" ", "_")
        return key.xmlName to value
    }

    private fun shouldReplaceAttribute(xmlAttr: String, existingValue: String, candidateValue: String, tag: String): Boolean {
        val existingValid = grammarValidator.enforceGrammar(mapOf(xmlAttr to existingValue), tag).containsKey(xmlAttr)
        val candidateValid = grammarValidator.enforceGrammar(mapOf(xmlAttr to candidateValue), tag).containsKey(xmlAttr)
        return !existingValid && candidateValid
    }

    private fun recoverValue(key: AttributeKey, rawValue: String, cleanedValue: String, tag: String): String {
        val switchWidthLostLeadingOne = tag == SWITCH_TAG &&
            key == AttributeKey.WIDTH &&
            cleanedValue == "0dp" &&
            Regex("^0{2}\\s*dp$", RegexOption.IGNORE_CASE).matches(rawValue.trim())

        return if (switchWidthLostLeadingOne) "100dp" else cleanedValue
    }

    private fun recoverMissingAttributes(attributes: Map<String, String>, annotation: String, tag: String): Map<String, String> {
        if (tag != IMAGE_VIEW_TAG || attributes.containsKey(AttributeKey.ID.xmlName)) return attributes

        val recoveredId = imageViewIdRegex.find(annotation)?.value
            ?: imageViewIdCompactRegex.find(annotation)?.value
            ?: return attributes

        return attributes + (AttributeKey.ID.xmlName to normalizeIdValue(recoveredId, tag))
    }

    private fun normalizeIdValue(rawValue: String, tag: String): String {
        val cleaned = IdCleaner.clean(rawValue)
        val tokens = cleaned.split('_').filter { it.isNotBlank() }

        return normalizeSwitchIdIfNeeded(tokens, tag)
            ?: normalizeImageViewIdIfNeeded(tokens, tag)
            ?: cleaned
    }

    private fun normalizeSwitchIdIfNeeded(tokens: List<String>, tag: String): String? {
        if (tag != SWITCH_TAG || !isSwitchIdOcrCandidate(tokens)) return null
        return buildId(SWITCH_ID_TARGET, extractTrailingNumber(tokens))
    }

    private fun isSwitchIdOcrCandidate(tokens: List<String>): Boolean {
        val firstToken = tokens.firstOrNull() ?: return false
        return fuzzyTokenScore(firstToken, switchIdTargets) >= SWITCH_ID_OCR_THRESHOLD
    }

    private fun normalizeImageViewIdIfNeeded(tokens: List<String>, tag: String): String? {
        if (tag != IMAGE_VIEW_TAG || !isImageViewIdOcrCandidate(tokens)) return null
        return buildId(IMAGE_VIEW_ID_PREFIX, IMAGE_VIEW_ID_VIEW_TOKEN, extractTrailingNumber(tokens))
    }

    private fun isImageViewIdOcrCandidate(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        val hasImagePrefix = tokens.any(::isImageViewPrefixToken)
        val hasViewToken = tokens.any(::isImageViewViewToken)
        return hasImagePrefix && hasViewToken
    }

    private fun isImageViewPrefixToken(token: String): Boolean {
        return token == "m" ||
            token in imageViewIdPrefixTargets ||
            fuzzyTokenScore(token, imageViewIdPrefixTargets) >= IMAGE_VIEW_ID_OCR_THRESHOLD
    }

    private fun isImageViewViewToken(token: String): Boolean {
        return token in imageViewIdViewTargets ||
            fuzzyTokenScore(token, imageViewIdViewTargets) >= IMAGE_VIEW_ID_OCR_THRESHOLD
    }

    private fun extractTrailingNumber(tokens: List<String>): String? {
        return tokens.lastOrNull()?.takeIf { token -> token.all(Char::isDigit) }
    }

    private fun buildId(vararg parts: String?): String {
        return parts.filterNotNull().joinToString("_")
    }

    private fun fuzzyTokenScore(token: String, targets: List<String>): Int {
        return FuzzySearch.extractOne(token, targets).score
    }

    private fun cleanGravityValue(rawValue: String): String {
        val normalized = rawValue.lowercase().replace(Regex("[^a-z_]+"), " ")
        return GravityValueSet.values.firstOrNull { value ->
            Regex("(^|\\s)${Regex.escape(value)}(\\s|$)").containsMatchIn(normalized)
        } ?: rawValue.trim()
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
