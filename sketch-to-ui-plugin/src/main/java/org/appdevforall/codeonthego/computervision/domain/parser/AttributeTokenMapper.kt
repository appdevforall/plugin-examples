package org.appdevforall.codeonthego.computervision.domain.parser

import me.xdrop.fuzzywuzzy.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.grammar.UiGrammarValidator
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.ColorCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.AttributeKeyPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.ColorPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.CoreParserPatterns
import java.lang.StringBuilder

internal object AttributeTokenMapper {
    private const val PIPE_DELIMITER = "|"
    private const val MIN_FUZZY_KEY_LENGTH = 3
    private const val SHORT_KEY_MAX_LENGTH = 3
    private const val MEDIUM_KEY_MAX_LENGTH = 6
    private const val SHORT_KEY_FUZZY_THRESHOLD = 65
    private const val MEDIUM_KEY_FUZZY_THRESHOLD = 75
    private const val LONG_KEY_FUZZY_THRESHOLD = 80
    private val inputTypeValues = InputTypeValueSet.values.map { it.lowercase() }.toSet()
    private val grammarValidator = UiGrammarValidator()
    private val numericTypes = setOf(
        ValueType.DIMENSION,
        ValueType.SP_DIMENSION,
        ValueType.INTEGER,
        ValueType.FLOAT
    )

    fun tokenize(annotation: String): List<String> {
        return if (annotation.contains(PIPE_DELIMITER)) {
            annotation.split(PIPE_DELIMITER)
                .flatMap(::tokenizeDelimitedChunk)
                .filter { it.isNotEmpty() }
        } else {
            annotation.split(CoreParserPatterns.TOKEN_SPLIT).map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    fun mapTokensToAttributes(tokens: List<String>, tag: String): Map<String, String> {
        val state = MappingState()

        for (token in tokens) {
            consumeToken(token, resolveMatchedKey(token, state), tag, state)
        }

        flushCurrentAttribute(tag, state)
        return state.attributes
    }

    /** Selects a fuzzy-match threshold from key length and returns a sufficiently similar key. */
    fun fuzzyMatchKey(rawKey: String): AttributeKey? {
        val normalizedKey = normalizeKeyToken(rawKey)

        val exactMatch = AttributeKey.findByAlias(normalizedKey)
        if (exactMatch != null) return exactMatch

        if (normalizedKey.length < MIN_FUZZY_KEY_LENGTH) return null

        val threshold = when {
            normalizedKey.length <= SHORT_KEY_MAX_LENGTH -> SHORT_KEY_FUZZY_THRESHOLD
            normalizedKey.length <= MEDIUM_KEY_MAX_LENGTH -> MEDIUM_KEY_FUZZY_THRESHOLD
            else -> LONG_KEY_FUZZY_THRESHOLD
        }

        val result = FuzzySearch.extractOne(normalizedKey, AttributeKey.allAliases)

        return if (result.score >= threshold) AttributeKey.findByAlias(result.string) else null
    }

    fun normalizeKeyToken(rawKey: String): String {
        return rawKey.lowercase()
            .replace("-", "_")
            .replace(CoreParserPatterns.WHITESPACE, "_")
            .replace(".", "_")
            .replace(CoreParserPatterns.MULTIPLE_UNDERSCORES, "_")
            .replace(AttributeKeyPatterns.LAYOUT_OCR_KEY, "layout")
            .replace(AttributeKeyPatterns.OCR_ID_KEY, "id")
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
            currentKey?.valueType == ValueType.TEXT_CONTENT &&
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
        return hasColorLiteralPrefix(token) ||
            hasResourceReferencePrefix(token) ||
            token in ColorCleaner.colorMap
    }

    private fun hasColorLiteralPrefix(token: String): Boolean {
        return token.startsWith(ColorPatterns.COLOR_HEX_PREFIX)
    }

    private fun hasResourceReferencePrefix(token: String): Boolean {
        return token.startsWith(ColorPatterns.RESOURCE_REFERENCE_PREFIX)
    }

    private fun resolveMatchedKey(token: String, state: MappingState): AttributeKey? {
        if (shouldTreatTokenAsValue(token, state.currentKey, state.currentValue)) return null
        return fuzzyMatchKey(token)
    }

    private fun consumeToken(token: String, matchedKey: AttributeKey?, tag: String, state: MappingState) {
        if (matchedKey == null) {
            appendTokenValue(token, state)
            return
        }

        startNewAttribute(matchedKey, tag, state)
    }

    private fun startNewAttribute(matchedKey: AttributeKey, tag: String, state: MappingState) {
        flushCurrentAttribute(tag, state)
        state.currentKey = matchedKey
        state.currentValue.clear()
    }

    private fun appendTokenValue(token: String, state: MappingState) {
        state.currentValue.append(token).append(" ")
    }

    private fun flushCurrentAttribute(tag: String, state: MappingState) {
        flushAttribute(state.currentKey, state.currentValue.toString(), tag, state.attributes)
    }

    private fun flushAttribute(key: AttributeKey?, rawValue: String, tag: String, destination: MutableMap<String, String>) {
        if (key == null || rawValue.isBlank()) return

        val resolved = AttributeValueResolver.resolve(key, rawValue, tag) ?: return
        val existingValue = destination[resolved.xmlAttr]
        if (existingValue == null || shouldReplaceAttribute(resolved.xmlAttr, existingValue, resolved.value, tag)) {
            destination[resolved.xmlAttr] = resolved.value
        }
    }

    private fun shouldReplaceAttribute(
        xmlAttr: String,
        existingValue: String,
        candidateValue: String,
        tag: String
    ): Boolean {
        if (xmlAttr == AttributeKey.LAYOUT_GRAVITY.xmlName &&
            existingValue == "center" &&
            candidateValue == "center_horizontal"
        ) {
            return true
        }

        val existingValid = grammarValidator.enforceGrammar(mapOf(xmlAttr to existingValue), tag).containsKey(xmlAttr)
        val candidateValid = grammarValidator.enforceGrammar(mapOf(xmlAttr to candidateValue), tag).containsKey(xmlAttr)
        return !existingValid && candidateValid
    }

    private data class MappingState(
        var currentKey: AttributeKey? = null,
        val currentValue: StringBuilder = StringBuilder(),
        val attributes: MutableMap<String, String> = mutableMapOf()
    )
}
