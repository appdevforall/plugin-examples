package org.appdevforall.codeonthego.computervision.domain.parser.cleaner

import kotlin.math.abs
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.appdevforall.codeonthego.computervision.domain.model.WidgetTypes
import org.appdevforall.codeonthego.computervision.domain.parser.ValueCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.AttributeKeyPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.CoreParserPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.IdPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.ResourceNamePatterns
import org.appdevforall.codeonthego.computervision.domain.xml.AndroidWidgetTags

internal object IdCleaner : ValueCleaner {
    private const val SWITCH_ID_OCR_THRESHOLD = 70
    private const val IMAGE_VIEW_ID_OCR_THRESHOLD = 80
    private val idVocabulary = listOf(
        "cb", "rb", "group", "checkbox", "radio", "btn", "button", "text", IdPatterns.IMAGE_VIEW_ID_VIEW_TOKEN,
        "img", "image", "input", WidgetTypes.SWITCH
    )
    private val switchIdTargets = listOf(WidgetTypes.SWITCH)
    private val imageViewIdPrefixTargets = listOf(IdPatterns.IMAGE_VIEW_ID_PREFIX, "img", "image")
    private val imageViewIdViewTargets = listOf(IdPatterns.IMAGE_VIEW_ID_VIEW_TOKEN)

    override fun clean(rawValue: String): String {
        val cleaned = rawValue.trim().lowercase()
            .replace(AttributeKeyPatterns.OCR_IM_OR_M_CONFUSION) { match ->
                if (match.value == "inm") IdPatterns.IMAGE_VIEW_ID_PREFIX else "m"
            }
            .replace(ResourceNamePatterns.RESOURCE_NAME_UNSAFE_CHARS, "_")
            .replace(CoreParserPatterns.MULTIPLE_UNDERSCORES, "_")
            .trim('_')

        return normalizeKnownIdVocabulary(cleaned)
    }

    fun clean(rawValue: String, tag: String): String {
        val cleaned = clean(rawValue)
        val tokens = cleaned.split('_').filter { it.isNotBlank() }
        return normalizeSwitchIdIfNeeded(tokens, tag)
            ?: normalizeImageViewId(rawValue, tokens, tag)
            ?: cleaned
    }

    private fun normalizeKnownIdVocabulary(identifier: String): String {
        if (identifier.isBlank()) return identifier
        return identifier.split('_').filter { it.isNotBlank() }
            .flatMap(::normalizeIdToken).joinToString("_")
    }

    /** Selects vocabulary corrections using fuzzy score and token-length distance. */
    private fun normalizeIdToken(token: String): List<String> {
        if (token.isBlank()) return emptyList()
        if (token.all(Char::isDigit)) return listOf(token)
        if ((token.startsWith("img") && token != "img") || (token.startsWith("image") && token != "image")) {
            return listOf(token)
        }

        val exactMatch = FuzzySearch.extractOne(token, idVocabulary)
        if (exactMatch.score >= 80 && abs(token.length - exactMatch.string.length) <= 2) {
            return listOf(exactMatch.string)
        }
        return listOf(token)
    }

    private fun normalizeSwitchIdIfNeeded(tokens: List<String>, tag: String): String? {
        if (tag != AndroidWidgetTags.SWITCH || !isSwitchIdOcrCandidate(tokens)) return null
        return buildId(WidgetTypes.SWITCH, extractTrailingNumber(tokens))
    }

    /** Accepts a switch ID candidate when its fuzzy score reaches the switch threshold. */
    private fun isSwitchIdOcrCandidate(tokens: List<String>): Boolean {
        val firstToken = tokens.firstOrNull() ?: return false
        return fuzzyTokenScore(firstToken, switchIdTargets) >= SWITCH_ID_OCR_THRESHOLD
    }

    private fun normalizeImageViewId(rawValue: String, tokens: List<String>, tag: String): String? {
        if (tag != AndroidWidgetTags.IMAGE_VIEW) return null

        return removeLeakedImagePrefixIfNeeded(rawValue, tokens)
            ?: recoverGenericImageViewIdIfNeeded(tokens)
    }

    private fun removeLeakedImagePrefixIfNeeded(
        rawValue: String,
        tokens: List<String>
    ): String? {
        if (tokens.size < 3) return null
        if (!rawValue.trim().contains(CoreParserPatterns.WHITESPACE)) return null
        if (tokens.first() !in imageViewIdPrefixTargets) return null

        val remainingTokens = tokens.drop(1)
        val remainingId = remainingTokens.joinToString("_")

        return remainingId.takeIf {
            remainingTokens.firstOrNull() in imageViewIdPrefixTargets
        }
    }

    private fun recoverGenericImageViewIdIfNeeded(tokens: List<String>): String? {
        if (!isImageViewIdOcrCandidate(tokens)) return null
        return buildId(IdPatterns.IMAGE_VIEW_ID_PREFIX, IdPatterns.IMAGE_VIEW_ID_VIEW_TOKEN, extractTrailingNumber(tokens))
    }

    private fun isImageViewIdOcrCandidate(tokens: List<String>): Boolean {
        if (tokens.isEmpty()) return false
        return tokens.any(::isImageViewPrefixToken) && tokens.any(::isImageViewViewToken)
    }

    /** Accepts image-prefix tokens whose fuzzy scores reach the image threshold. */
    private fun isImageViewPrefixToken(token: String): Boolean {
        return token == "m" ||
            token in imageViewIdPrefixTargets ||
            fuzzyTokenScore(token, imageViewIdPrefixTargets) >= IMAGE_VIEW_ID_OCR_THRESHOLD
    }

    /** Accepts view tokens whose fuzzy scores reach the image threshold. */
    private fun isImageViewViewToken(token: String): Boolean {
        return token in imageViewIdViewTargets ||
            fuzzyTokenScore(token, imageViewIdViewTargets) >= IMAGE_VIEW_ID_OCR_THRESHOLD
    }

    private fun extractTrailingNumber(tokens: List<String>): String? {
        return tokens.lastOrNull()?.takeIf { token -> token.all(Char::isDigit) }
    }

    private fun buildId(vararg parts: String?): String = parts.filterNotNull().joinToString("_")

    /** Calculates the strongest fuzzy-match score for a token against candidate targets. */
    private fun fuzzyTokenScore(token: String, targets: List<String>): Int {
        return FuzzySearch.extractOne(token, targets).score
    }
}
