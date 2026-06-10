package org.appdevforall.codeonthego.computervision.domain.parser

import me.xdrop.fuzzywuzzy.FuzzySearch

internal object TextContentCleaner : ValueCleaner {
    private val trailingWidgetTagRegex = Regex(
        "\\s+(?:[A-Z]{1,2}\\s+)?(?:B|P|D|T|C|R|SW|S)\\s*-\\s*[A-Z0-9_]+\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val trailingRepeatedPrefixRegex = Regex(
        "\\s+(?:B|P|D|T|C|R|SW|S)\\s+(?=(?:B|P|D|T|C|R|SW|S)\\s*-\\s*[A-Z0-9_]+\\s*$)",
        RegexOption.IGNORE_CASE
    )
    override fun clean(rawValue: String): String {
        return rawValue
            .replace(trailingRepeatedPrefixRegex, " ")
            .replace(trailingWidgetTagRegex, "")
            .replace(AttributeRegexPatterns.WHITESPACE, " ")
            .trim()
    }
}


internal object NumberCleaner : ValueCleaner {
    private val ocrCharMap = mapOf(
        'O' to '0', 'A' to '0', '@' to '0', 'Q' to '0',
        'L' to '1', 'I' to '1', '|' to '1', '!' to '1', '/' to '1', '\\' to '1',
        '(' to '1', ')' to '1', '[' to '1', ']' to '1',
        'Z' to '2', 'S' to '5', 'B' to '6'
    )

    override fun clean(rawValue: String): String {
        val translated = rawValue.map { ocrCharMap[it.uppercaseChar()] ?: it }.joinToString("")
        return Regex("-?\\d+").find(translated)?.value ?: rawValue
    }
}

internal object DimensionCleaner : ValueCleaner {
    private val leadingNumberRegex = Regex("^-?\\d+")

    override fun clean(rawValue: String): String {
        val trimmedValue = rawValue.trim().lowercase()
        val normalized = trimmedValue.replace(" ", "_")

        if (DimensionValueSet.matchKeywords.any { it in normalized }) return DimensionValueSet.MATCH_PARENT
        if (DimensionValueSet.wrapKeywords.any { it in normalized }) return DimensionValueSet.WRAP_CONTENT

        val fuzzyResult = FuzzySearch.extractOne(normalized, DimensionValueSet.values)
        if (fuzzyResult.score >= 60) return fuzzyResult.string

        val unitMatch = Regex("(dp|sp|px|in|mm|pt)$").find(trimmedValue)
        val originalUnit = unitMatch?.value ?: "dp"

        val firstToken = trimmedValue.substringBefore(" ")
        val rawNumber = firstToken.removeSuffix(originalUnit).trim()
        val numericPart = NumberCleaner.clean(rawNumber)

        val numMatch = leadingNumberRegex.find(numericPart)?.value
            ?: return trimmedValue
        val correctedNum = removeOcrTrailingZero(numMatch).trimLeadingZeros()

        return "$correctedNum$originalUnit"
    }

    private fun removeOcrTrailingZero(num: String): String {
        val isOcrArtifact = num.endsWith("0") && (num.toLongOrNull() ?: 0L) >= 1000L
        return if (isOcrArtifact) num.dropLast(1) else num
    }

    private fun String.trimLeadingZeros(): String {
        val negative = startsWith("-")
        val digits = if (negative) drop(1) else this
        val trimmed = digits.trimStart('0').ifEmpty { "0" }
        return if (negative) "-$trimmed" else trimmed
    }
}

internal object SpDimensionCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        val normalized = rawValue.lowercase().replace(" ", "").replace(Regex("(sp|5p)$"), "")
        val numericPart = NumberCleaner.clean(normalized.replace("_", ""))
        return if (numericPart != normalized) "${numericPart}sp" else rawValue
    }
}

internal object ColorCleaner : ValueCleaner {
    val colorMap = mapOf(
        "red" to "#FF0000", "rel" to "#FF0000", "rad" to "#FF0000", "reo" to "#FF0000",
        "green" to "#00FF00",
        "blue" to "#0000FF", "ine" to "#0000FF", "hne" to "#0000FF", "hlue" to "#0000FF", "ane" to "#0000FF", "lne" to "#0000FF",
        "black" to "#000000", "white" to "#FFFFFF", "gray" to "#808080",
        "grey" to "#808080", "dark_gray" to "#A9A9A9", "yellow" to "#FFFF00",
        "cyan" to "#00FFFF", "magenta" to "#FF00FF", "purple" to "#800080",
        "orange" to "#FFA500", "brown" to "#A52A2A", "pink" to "#FFC0CB",
        "light_gray" to "#D3D3D3", "dark_blue" to "#00008B", "dark_green" to "#006400",
        "dark_red" to "#8B0000", "teal" to "#008080", "navy" to "#000080",
        "transparent" to "@android:color/transparent"
    )

    override fun clean(rawValue: String): String {
        if (rawValue.startsWith("#") || rawValue.startsWith("@")) return rawValue

        val normalizedValue = rawValue.lowercase().replace(Regex("[^a-z_]"), "").replace(" ", "_")

        val exactColor = colorMap[normalizedValue]
        if (exactColor != null) return exactColor

        val result = FuzzySearch.extractOne(normalizedValue, colorMap.keys.toList())
        return if (result.score >= 70) colorMap[result.string] ?: rawValue else rawValue
    }
}

internal object IdCleaner : ValueCleaner {
    private const val SWITCH_TAG = "Switch"
    private const val IMAGE_VIEW_TAG = "ImageView"
    private const val SWITCH_ID_TARGET = "switch"
    private const val IMAGE_VIEW_ID_PREFIX = "im"
    private const val IMAGE_VIEW_ID_VIEW_TOKEN = "view"
    private const val SWITCH_ID_OCR_THRESHOLD = 70
    private const val IMAGE_VIEW_ID_OCR_THRESHOLD = 80
    private val ID_VOCABULARY = listOf("cb", "rb", "group", "checkbox", "radio", "btn", "button", "text", "view", "img", "image", "input", "switch")
    private val switchIdTargets = listOf(SWITCH_ID_TARGET)
    private val imageViewIdPrefixTargets = listOf(IMAGE_VIEW_ID_PREFIX, "img", "image")
    private val imageViewIdViewTargets = listOf(IMAGE_VIEW_ID_VIEW_TOKEN)

    override fun clean(rawValue: String): String {
        val cleaned = rawValue.trim().lowercase()
            .replace(AttributeRegexPatterns.OCR_IM_OR_M_CONFUSION) { m -> if (m.value == "inm") "im" else "m" }
            .replace(AttributeRegexPatterns.RESOURCE_NAME_UNSAFE_CHARS, "_")
            .replace(AttributeRegexPatterns.MULTIPLE_UNDERSCORES, "_")
            .trim('_')

        return normalizeKnownIdVocabulary(cleaned)
    }

    fun clean(rawValue: String, tag: String): String {
        val cleaned = clean(rawValue)
        val tokens = cleaned.split('_').filter { it.isNotBlank() }

        return normalizeSwitchIdIfNeeded(tokens, tag)
            ?: normalizeImageViewIdIfNeeded(tokens, tag)
            ?: cleaned
    }

    private fun normalizeKnownIdVocabulary(identifier: String): String {
        if (identifier.isBlank()) return identifier
        return identifier.split('_').filter { it.isNotBlank() }
            .flatMap(::normalizeIdToken).joinToString("_")
    }

    private fun normalizeIdToken(token: String): List<String> {
        if (token.isBlank()) return emptyList()
        if (token.all(Char::isDigit)) return listOf(token)

        val exactMatch = FuzzySearch.extractOne(token, ID_VOCABULARY)
        if (exactMatch.score >= 80 && kotlin.math.abs(token.length - exactMatch.string.length) <= 2) {
            return listOf(exactMatch.string)
        }
        return listOf(token)
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
}

internal object DrawableCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        if (rawValue.startsWith("@drawable/")) return rawValue

        val cleaned = rawValue.lowercase()
            .replace(Regex("\\.(png|jpg|jpeg|webp|xml|svg)$"), "")
            .replace(AttributeRegexPatterns.OCR_IM_OR_M_CONFUSION) { m -> if (m.value == "inm") "im" else "m" }
            .replace(AttributeRegexPatterns.RESOURCE_NAME_UNSAFE_CHARS, "_")
            .replace(AttributeRegexPatterns.MULTIPLE_UNDERSCORES, "_")
            .trim('_')

        val finalCleaned = cleaned
            .replace("im_age", "image")
            .replace(Regex("(^|_)im($|_)"), "$1image$2")
            .replace(AttributeRegexPatterns.MULTIPLE_UNDERSCORES, "_")
            .trim('_')
        return if (finalCleaned.isEmpty()) rawValue else "@drawable/$finalCleaned"
    }
}

internal object TextStyleCleaner : ValueCleaner {
    private val TEXT_STYLE_VALUES = listOf("normal", "bold", "italic", "bold|italic")

    override fun clean(rawValue: String): String {
        val normalizedValue = rawValue.lowercase().replace(" ", "_")
        if (normalizedValue in TEXT_STYLE_VALUES) return normalizedValue

        val result = FuzzySearch.extractOne(normalizedValue, TEXT_STYLE_VALUES)
        return if (result.score >= 60) result.string else rawValue
    }
}

internal object GravityCleaner : ValueCleaner {
    private const val CENTER_HORIZONTAL = "center_horizontal"
    private const val CENTER_VERTICAL = "center_vertical"
    private const val GRAVITY_TOKEN_SEPARATOR = " "
    private val centerHorizontalCompactRegex = Regex("centerhorizontal")
    private val centerVerticalCompactRegex = Regex("centervertical")
    private val centerHorizontalSpacedRegex = Regex("center\\s+horizontal")
    private val centerVerticalSpacedRegex = Regex("center\\s+vertical")
    private val gravityUnsafeCharsRegex = Regex("[^a-z_]+")

    override fun clean(rawValue: String): String {
        val normalized = rawValue.lowercase()
            .replace(centerHorizontalCompactRegex, CENTER_HORIZONTAL)
            .replace(centerVerticalCompactRegex, CENTER_VERTICAL)
            .replace(centerHorizontalSpacedRegex, CENTER_HORIZONTAL)
            .replace(centerVerticalSpacedRegex, CENTER_VERTICAL)
            .replace(gravityUnsafeCharsRegex, GRAVITY_TOKEN_SEPARATOR)
        return GravityValueSet.values.sortedByDescending { it.length }.firstOrNull { value ->
            wholeGravityValueRegex(value).containsMatchIn(normalized)
        } ?: rawValue.trim()
    }

    private fun wholeGravityValueRegex(value: String): Regex {
        return Regex("(^|\\s)${Regex.escape(value)}(\\s|$)")
    }
}

internal object InputTypeCleaner : ValueCleaner {
    private val inputTypeValues = InputTypeValueSet.values.map { it.lowercase() }.toSet()

    override fun clean(rawValue: String): String {
        val normalized = rawValue.trim()
            .replace(AttributeRegexPatterns.WHITESPACE, "")
            .replace(Regex("[^A-Za-z|]+"), "")

        if (normalized.isBlank()) return rawValue.trim()

        return normalized.split('|')
            .mapNotNull { part ->
                val result = FuzzySearch.extractOne(part.lowercase(), inputTypeValues)
                if (result.score >= 70) {
                    InputTypeValueSet.values.firstOrNull { it.equals(result.string, ignoreCase = true) }
                } else {
                    null
                }
            }
            .distinct()
            .joinToString("|")
            .ifBlank { rawValue.trim() }
    }
}

internal object FloatCleaner : ValueCleaner {
    override fun clean(rawValue: String): String {
        return Regex("-?\\d+\\.?\\d*").find(rawValue)?.value ?: rawValue
    }
}
