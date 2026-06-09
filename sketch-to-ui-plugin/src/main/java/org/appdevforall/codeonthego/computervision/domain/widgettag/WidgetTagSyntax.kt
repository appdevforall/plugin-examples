package org.appdevforall.codeonthego.computervision.domain.widgettag

internal object WidgetTagSyntax {
    private val validPrefixes = setOf("B", "P", "D", "T", "C", "R", "SW", "S")
    private val tagRegex = Regex("^(?i)(B|P|D|T|C|R|SW|S)-[A-Z0-9_]+$")
    private val tagExtractRegex = Regex("^(?i)(B|P|D|T|C|R|SW|S|8|8W|S8)([\\s\\-_.,|/]*)([A-Z0-9_\\-]+)")
    private val whitespaceRegex = Regex("\\s+")
    private val invalidTokenCharacterRegex = Regex("[^A-Z0-9_]")
    private val numericLikeOcrCharacters = setOf('O', 'I', 'L', 'Z', 'S', 'B', '!')

    fun isTag(text: String): Boolean {
        val cleaned = clean(text)
        val match = tagExtractRegex.find(cleaned)?.takeIf(::isValidMatch) ?: return false
        val trailingText = cleaned.substring(match.range.last + 1).trim()
        return trailingText.none(Char::isLetterOrDigit) && normalize(cleaned).matches(tagRegex)
    }

    fun isTagSequence(text: String): Boolean {
        val tokens = text.trim().split(whitespaceRegex).filter(String::isNotBlank)
        return tokens.isNotEmpty() && tokens.all(::isTag)
    }

    fun normalize(text: String): String {
        val cleaned = clean(text)
        val match = tagExtractRegex.find(cleaned)?.takeIf(::isValidMatch) ?: return cleaned.uppercase()
        val (prefix, token) = parseParts(match) ?: return cleaned.uppercase()
        return "$prefix-$token"
    }

    fun extract(text: String): ParsedWidgetTag? {
        val cleaned = clean(text)
        val match = tagExtractRegex.find(cleaned)?.takeIf(::isValidMatch) ?: return null
        val (prefix, token) = parseParts(match) ?: return null
        val finalTag = "$prefix-$token".takeIf(tagRegex::matches) ?: return null
        val trailingText = cleaned.substring(match.range.last + 1).trim().takeIf(String::isNotBlank)
        return ParsedWidgetTag(finalTag, trailingText)
    }

    private fun clean(text: String): String = text.trim().trimEnd('.', ',', ';', ':', '_', '|')

    private fun parseParts(match: MatchResult): Pair<String, String>? {
        val prefix = normalizePrefix(match.groupValues[1]).takeIf(validPrefixes::contains) ?: return null
        var rawToken = match.groupValues[3].trim('-')
        val uppercaseToken = rawToken.uppercase()
        val remainder = uppercaseToken.removePrefix(prefix)

        when {
            uppercaseToken.startsWith("$prefix-") || uppercaseToken.startsWith("${prefix}_") -> {
                rawToken = rawToken.substring(prefix.length + 1).trim('-')
            }
            uppercaseToken.startsWith(prefix) &&
                remainder.isNotEmpty() &&
                remainder.all(::isNumericLikeOcrCharacter) -> rawToken = remainder
        }

        return prefix to normalizeToken(rawToken)
    }

    private fun isValidMatch(match: MatchResult): Boolean {
        return match.groupValues[2].isNotEmpty() || match.groupValues[3].all(::isNumericLikeOcrCharacter)
    }

    private fun normalizePrefix(rawPrefix: String): String {
        return when (rawPrefix.uppercase().replace(whitespaceRegex, "")) {
            "8" -> "B"
            "8W", "S8" -> "SW"
            else -> rawPrefix.uppercase().replace(whitespaceRegex, "")
        }
    }

    private fun normalizeToken(rawToken: String): String {
        if (rawToken.isBlank()) return rawToken
        val uppercaseToken = rawToken.uppercase().replace('-', '_')
        return if (uppercaseToken.all(::isNumericLikeOcrCharacter)) {
            uppercaseToken.normalizeOcrDigits()
        } else {
            uppercaseToken.replace(invalidTokenCharacterRegex, "_")
        }
    }

    private fun String.normalizeOcrDigits(): String {
        return replace('I', '1')
            .replace('L', '1')
            .replace('!', '1')
            .replace('O', '0')
            .replace('Z', '2')
            .replace('S', '5')
            .replace('B', '6')
    }

    private fun isNumericLikeOcrCharacter(character: Char): Boolean {
        return character.isDigit() || character.uppercaseChar() in numericLikeOcrCharacters
    }
}
