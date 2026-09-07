package org.appdevforall.codeonthego.computervision.domain.parser.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeTokenMapper
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.IdCleaner
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.IdPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.TextPatterns

internal object EditTextAttributeRecovery {
    private const val EDIT_TEXT_TAG = "EditText"
    private const val PIPE_DELIMITER = "|"

    fun recover(attributes: Map<String, String>, annotation: String): Map<String, String> {
        val recovered = attributes.toMutableMap()
        val likelyPassword = isLikelyPasswordInput(annotation)

        if (likelyPassword) {
            recovered[AttributeKey.INPUT_TYPE.xmlName] = "textPassword"
        } else {
            recoverExplicitTextValue(annotation)?.let { recovered.putIfAbsent(AttributeKey.TEXT.xmlName, it) }
            recoverExplicitHintValue(annotation)?.let { recovered.putIfAbsent(AttributeKey.HINT.xmlName, it) }
        }

        if (AttributeKey.ID.xmlName !in recovered) {
            (editTextIdRegex.find(annotation)?.groupValues?.getOrNull(1) ?: recoverBareEditTextId(annotation))
                ?.takeIf { it.isNotBlank() }
                ?.let { recovered[AttributeKey.ID.xmlName] = IdCleaner.clean(it, EDIT_TEXT_TAG) }
        }

        if (likelyPassword || hasMetadataTextLeakage(recovered[AttributeKey.TEXT.xmlName])) {
            recovered.remove(AttributeKey.TEXT.xmlName)
        }

        return recovered
    }

    fun enforceOutputRules(attributes: Map<String, String>, annotation: String): Map<String, String> {
        return if (isLikelyPasswordInput(annotation)) {
            attributes - AttributeKey.TEXT.xmlName
        } else {
            attributes.toMutableMap().apply {
                recoverExplicitTextValue(annotation)?.let { putIfAbsent(AttributeKey.TEXT.xmlName, it) }
                recoverExplicitHintValue(annotation)?.let { putIfAbsent(AttributeKey.HINT.xmlName, it) }
            }
        }
    }

    private fun isLikelyPasswordInput(annotation: String): Boolean {
        val compact = annotation.lowercase().replace(TextPatterns.NON_LETTERS, "")
        return compact.contains("textpassword") ||
            compact.contains("inputtypetextpassword") ||
            compact.contains("password") ||
            annotation.split(PIPE_DELIMITER)
                .map { it.trim().lowercase() }
                .any { it == "password" || it == "textpassword" }
    }

    private fun hasMetadataTextLeakage(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains(TextPatterns.EDIT_TEXT_METADATA_LEAKAGE)
    }

    /** Selects an unkeyed ID candidate after password metadata while keeping single-token noise out. */
    private fun recoverBareEditTextId(annotation: String): String? {
        val parts = annotation.split(PIPE_DELIMITER).map { it.trim() }
        val passwordIndex = parts.indexOfFirst { isLikelyPasswordInput(it) }
        if (passwordIndex < 0) return null

        val candidates = parts.drop(passwordIndex + 1)
            .map { it.trim() }
            .filter { candidate ->
                candidate.matches(IdPatterns.BARE_EDIT_TEXT_ID_CANDIDATE) &&
                    !isLikelyPasswordInput(candidate) &&
                    AttributeTokenMapper.fuzzyMatchKey(candidate) == null
            }

        if (candidates.size < MIN_UNKEYED_ID_CORROBORATION_COUNT) return null
        val longestLength = candidates.maxOfOrNull { it.length } ?: return null
        return candidates.singleOrNull { it.length == longestLength }
            ?: candidates.firstOrNull()
    }

    private fun recoverExplicitTextValue(annotation: String): String? {
        return explicitTextRegex.find(annotation)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun recoverExplicitHintValue(annotation: String): String? {
        return explicitHintRegex.find(annotation)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private val editTextIdRegex = IdPatterns.keyedIdValue(AttributeKey.ID.aliases.first())
    private val explicitTextRegex = TextPatterns.EXPLICIT_TEXT_FRAGMENT
    private val explicitHintRegex = TextPatterns.EXPLICIT_HINT_FRAGMENT

    private const val MIN_UNKEYED_ID_CORROBORATION_COUNT = 2
}
