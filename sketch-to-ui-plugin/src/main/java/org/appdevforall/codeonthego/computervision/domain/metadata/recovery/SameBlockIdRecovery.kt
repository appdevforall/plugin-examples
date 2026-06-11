package org.appdevforall.codeonthego.computervision.domain.metadata.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeRegexPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.cleaner.IdCleaner

internal object SameBlockIdRecovery {
    private const val ANNOTATION_SEPARATOR = "|"

    fun resolve(metadata: ParsedMetadata): Pair<String, String>? {
        val resolvedId = metadata.rawText.bestSameBlockId(metadata.androidTag)
            ?: metadata.attributes[AttributeKey.ID.xmlName]
            ?: return null
        return AttributeKey.ID.xmlName to resolvedId
    }

    /**
     * Selects repeated clean candidates first, then explicit ID keys, then earlier OCR fragments.
     */
    private fun String.bestSameBlockId(androidTag: String): String? {
        val candidates = idCandidates(androidTag)
        if (candidates.isEmpty()) return null

        val counts = candidates.groupingBy(IdCandidate::cleanedValue).eachCount()
        return candidates.minWithOrNull(
            compareBy<IdCandidate>(
                { -counts.getValue(it.cleanedValue) },
                IdCandidate::quality,
                IdCandidate::index
            )
        )?.cleanedValue
    }

    private fun String.idCandidates(androidTag: String): List<IdCandidate> {
        return split(ANNOTATION_SEPARATOR).map(String::trim).mapIndexedNotNull { index, fragment ->
            val keyed = keyedIdRegex.matchEntire(fragment)
            val compact = compactIdRegex.matchEntire(fragment)
            val rawValue = keyed?.groupValues?.get(2) ?: compact?.groupValues?.get(2)
                ?: return@mapIndexedNotNull null
            val rawKey = keyed?.groupValues?.get(1) ?: compact?.groupValues?.get(1)
                ?: return@mapIndexedNotNull null
            val cleanedValue = IdCleaner.clean(rawValue, androidTag).takeIf(String::isNotBlank)
                ?: return@mapIndexedNotNull null
            IdCandidate(cleanedValue, idKeyQuality(rawKey, keyed != null), index)
        }
    }

    /** Assigns a lower quality score to stronger explicit ID-key evidence. */
    private fun idKeyQuality(rawKey: String, isDelimited: Boolean): Int {
        val normalizedKey = rawKey.lowercase()
        return when {
            normalizedKey == AttributeKey.ID.aliases.first() && isDelimited -> 0
            normalizedKey == AttributeKey.ID.aliases.first() -> 1
            isDelimited -> 2
            else -> 3
        }
    }

    private data class IdCandidate(
        val cleanedValue: String,
        val quality: Int,
        val index: Int
    )

    private val idKeyPattern = AttributeKey.ID.aliases
        .sortedByDescending(String::length)
        .joinToString("|")
    private val keyedIdRegex = AttributeRegexPatterns.keyedIdValue(idKeyPattern)
    private val compactIdRegex = AttributeRegexPatterns.compactIdValue(idKeyPattern)
}
