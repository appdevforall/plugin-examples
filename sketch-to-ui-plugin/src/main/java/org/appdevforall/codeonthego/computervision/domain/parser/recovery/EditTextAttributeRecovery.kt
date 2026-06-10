package org.appdevforall.codeonthego.computervision.domain.parser.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeTokenMapper
import org.appdevforall.codeonthego.computervision.domain.parser.IdCleaner

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

        if (likelyPassword &&
            recovered[AttributeKey.HEIGHT.xmlName] == "30dp" &&
            hasIncompleteHeightBeforeOcrArtifact(annotation)
        ) {
            recovered[AttributeKey.HEIGHT.xmlName] = "52dp"
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
        val compact = annotation.lowercase().replace(Regex("[^a-z]+"), "")
        return compact.contains("textpassword") ||
            compact.contains("inputtypetextpassword") ||
            annotation.split(PIPE_DELIMITER)
                .map { it.trim().lowercase() }
                .any { it == "password" || it == "textpassword" }
    }

    private fun hasMetadataTextLeakage(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        return text.contains(Regex("\\b(?:layout|inputtype|textpassword)\\b", RegexOption.IGNORE_CASE))
    }

    private fun recoverBareEditTextId(annotation: String): String? {
        val parts = annotation.split(PIPE_DELIMITER).map { it.trim() }
        val passwordIndex = parts.indexOfFirst { isLikelyPasswordInput(it) }
        if (passwordIndex < 0) return null

        return parts.drop(passwordIndex + 1)
            .map { it.trim() }
            .firstOrNull { candidate ->
                candidate.matches(Regex("[A-Za-z][A-Za-z0-9_]{2,}")) &&
                    !isLikelyPasswordInput(candidate) &&
                    AttributeTokenMapper.fuzzyMatchKey(candidate) == null
            }
    }

    private fun hasIncompleteHeightBeforeOcrArtifact(annotation: String): Boolean {
        return Regex("(?:layout_height|layoutheight)\\s*:\\s*(?:\\||$)").containsMatchIn(annotation) &&
            Regex("layout_height\\s*:\\s*30\\s*dp", RegexOption.IGNORE_CASE).containsMatchIn(annotation)
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

    private val editTextIdRegex = Regex(
        "\\bid\\s*[:;]?\\s*([A-Za-z][A-Za-z0-9_-]*)",
        RegexOption.IGNORE_CASE
    )
    private val explicitTextRegex = Regex(
        "(?:^|\\|)\\s*text\\s*[:;]\\s*([^|]+)",
        RegexOption.IGNORE_CASE
    )
    private val explicitHintRegex = Regex(
        "(?:^|\\|)\\s*hint\\s*[:;]\\s*([^|]+)",
        RegexOption.IGNORE_CASE
    )
}
