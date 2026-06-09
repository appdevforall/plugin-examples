package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.FuzzyAttributeParser
import org.appdevforall.codeonthego.computervision.domain.parser.IdCleaner

/**
 * Recovers metadata attributes using only evidence from the same widget or same sketch-prefix group.
 *
 * This stage runs after margin annotations are grouped by widget tag. It keeps recovery scoped so
 * noisy OCR from one control cannot rewrite unrelated controls.
 */
internal object MetadataAnnotationRecovery {
    private const val ANNOTATION_SEPARATOR = "|"

    fun resolve(annotations: Map<String, String>): Map<String, String> {
        if (annotations.isEmpty()) return annotations

        val parsedByTag = annotations.mapValues { (tag, rawText) ->
            val androidTag = WidgetTagParser.androidTagFor(tag)
            ParsedMetadata(
                tag = tag,
                androidTag = androidTag,
                rawText = rawText,
                attributes = FuzzyAttributeParser.parse(rawText, androidTag)
            )
        }
        val dimensionsByPrefix = parsedByTag.entries
            .groupBy { WidgetTagParser.normalizeTagText(it.key).substringBefore('-') }
            .mapValues { (_, entries) -> entries.map { it.value }.consistentDimensions() }

        return parsedByTag.mapValues { (_, metadata) ->
            val prefix = WidgetTagParser.normalizeTagText(metadata.tag).substringBefore('-')
            metadata.resolve(dimensionsByPrefix[prefix].orEmpty())
        }
    }

    private fun ParsedMetadata.resolve(fallbackDimensions: Map<String, String>): String {
        val resolved = attributes.toMutableMap()
        resolveDimension(AttributeKey.WIDTH, fallbackDimensions)?.let { resolved[AttributeKey.WIDTH.xmlName] = it }
        resolveDimension(AttributeKey.HEIGHT, fallbackDimensions)?.let { resolved[AttributeKey.HEIGHT.xmlName] = it }
        resolveId()?.let { resolved[AttributeKey.ID.xmlName] = it }
        resolvePasswordInputType()?.let { resolved[AttributeKey.INPUT_TYPE.xmlName] = it }
        normalizeImageViewId(resolved)
        return resolved.toRecoveredAnnotation(rawText, attributes)
    }

    private fun Map<String, String>.toRecoveredAnnotation(
        rawText: String,
        originalAttributes: Map<String, String>
    ): String {
        return entries.fold(rawText) { annotation, (key, value) ->
            if (originalAttributes[key] == value) {
                annotation
            } else {
                annotation.withResolvedFragment(key, value)
            }
        }
    }

    private fun String.withResolvedFragment(xmlKey: String, value: String): String {
        val fragment = "${xmlKey.removePrefix("android:")}: $value"
        return if (xmlKey == AttributeKey.ID.xmlName) {
            prependFragment(fragment)
        } else {
            appendFragment(fragment)
        }
    }

    private fun String.appendFragment(fragment: String): String {
        return if (isBlank()) fragment else "$this $ANNOTATION_SEPARATOR $fragment"
    }

    private fun String.prependFragment(fragment: String): String {
        return if (isBlank()) fragment else "$fragment $ANNOTATION_SEPARATOR $this"
    }

    private fun ParsedMetadata.resolveDimension(
        key: AttributeKey,
        fallbackDimensions: Map<String, String>
    ): String? {
        val parsedValue = attributes[key.xmlName]
        if (parsedValue != null && hasReliableDimension(key)) return parsedValue
        return fallbackDimensions[key.xmlName]
    }

    private fun ParsedMetadata.resolveId(): String? {
        val bestId = rawText.bestSameBlockId(androidTag)
        val parsedId = attributes[AttributeKey.ID.xmlName]
        return bestId ?: parsedId
    }

    private fun ParsedMetadata.resolvePasswordInputType(): String? {
        if (androidTag != EDIT_TEXT_TAG) return null
        if (attributes.containsKey(AttributeKey.INPUT_TYPE.xmlName)) return attributes[AttributeKey.INPUT_TYPE.xmlName]
        return "textPassword".takeIf { rawText.containsPasswordLikeFragment() }
    }

    private fun ParsedMetadata.normalizeImageViewId(attributes: MutableMap<String, String>) {
        if (androidTag != IMAGE_VIEW_TAG) return
        val id = attributes[AttributeKey.ID.xmlName] ?: return
        val suffix = compactImageIdRegex.matchEntire(id)?.groupValues?.get(1) ?: return
        val drawableName = attributes[AttributeKey.SRC.xmlName]
            ?.substringAfterLast('/')
            ?.lowercase()
            ?: return
        if (suffix == drawableName) {
            attributes[AttributeKey.ID.xmlName] = "img_$suffix"
        }
    }

    /**
     * Finds dimensions that are reliable and identical across a same-prefix widget group.
     *
     * The result is used only as a fallback for widgets whose own dimension is missing or unreliable.
     */
    private fun List<ParsedMetadata>.consistentDimensions(): Map<String, String> {
        return listOf(AttributeKey.WIDTH, AttributeKey.HEIGHT).mapNotNull { key ->
            val values = mapNotNull { parsed ->
                parsed.attributes[key.xmlName]?.takeIf { parsed.hasReliableDimension(key) }
            }.distinct()
            key.xmlName.takeIf { values.size == 1 }?.let { it to values.single() }
        }.toMap()
    }

    private fun ParsedMetadata.hasReliableDimension(key: AttributeKey): Boolean {
        val aliases = when (key) {
            AttributeKey.WIDTH -> "(?:layout[_-]?width|layoutwidth|width)"
            AttributeKey.HEIGHT -> "(?:layout[_-]?height|layoutheight|layoutheiqht|height)"
            else -> return false
        }
        val explicitWithUnit = Regex("\\b$aliases\\s*[:;]\\s*[0-9oOIlLSBZz]+\\s*(?:dp|de|do|clp)\\b", RegexOption.IGNORE_CASE)
        val compactWithUnit = Regex("\\b$aliases[iIl1|]?[0-9oOIlLSBZz]+\\s*(?:dp|de|do|clp)\\b", RegexOption.IGNORE_CASE)
        return explicitWithUnit.containsMatchIn(rawText) || compactWithUnit.containsMatchIn(rawText)
    }

    /**
     * Selects the strongest ID evidence inside one metadata block.
     *
     * Repeated clean candidates win first, then explicitly delimited `id` keys, then earlier OCR
     * fragments. This avoids global semantic correction while still handling noisy same-block IDs.
     */
    private fun String.bestSameBlockId(androidTag: String): String? {
        val candidates = idCandidates(androidTag)
        if (candidates.isEmpty()) return null

        val counts = candidates.groupingBy { it.cleanedValue }.eachCount()
        return candidates.minWithOrNull(
            compareBy<IdCandidate>(
                { -counts.getValue(it.cleanedValue) },
                { it.quality },
                { it.index }
            )
        )?.cleanedValue
    }

    private fun String.idCandidates(androidTag: String): List<IdCandidate> {
        return split(ANNOTATION_SEPARATOR)
            .map(String::trim)
            .mapIndexedNotNull { index, fragment ->
                val keyed = keyedIdRegex.matchEntire(fragment)
                val compact = compactIdRegex.matchEntire(fragment)
                val rawValue = keyed?.groupValues?.get(2) ?: compact?.groupValues?.get(2) ?: return@mapIndexedNotNull null
                val rawKey = keyed?.groupValues?.get(1) ?: compact?.groupValues?.get(1) ?: return@mapIndexedNotNull null
                val cleanedValue = IdCleaner.clean(rawValue, androidTag).takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                IdCandidate(cleanedValue, idKeyQuality(rawKey, keyed != null), index)
            }
    }

    private fun idKeyQuality(rawKey: String, isDelimited: Boolean): Int {
        val normalizedKey = rawKey.lowercase()
        return when {
            normalizedKey == AttributeKey.ID.aliases.first() && isDelimited -> 0
            normalizedKey == AttributeKey.ID.aliases.first() -> 1
            isDelimited -> 2
            else -> 3
        }
    }

    private fun String.containsPasswordLikeFragment(): Boolean {
        val compact = lowercase().replace(Regex("[^a-z]+"), "")
        return compact.contains("password") || compact.contains("textpassword")
    }

    private data class ParsedMetadata(
        val tag: String,
        val androidTag: String,
        val rawText: String,
        val attributes: Map<String, String>
    )

    private data class IdCandidate(
        val cleanedValue: String,
        val quality: Int,
        val index: Int
    )

    private const val EDIT_TEXT_TAG = "EditText"
    private const val IMAGE_VIEW_TAG = "ImageView"
    private val compactImageIdRegex = Regex("^img([a-z0-9]+)$")
    private val idKeyPattern = AttributeKey.ID.aliases
        .sortedByDescending { it.length }
        .joinToString("|")
    private val keyedIdRegex = Regex(
        "\\s*($idKeyPattern)(?:\\s*[:;]\\s*|\\s+)([A-Za-z][A-Za-z0-9_-]*)\\s*",
        RegexOption.IGNORE_CASE
    )
    private val compactIdRegex = Regex(
        "\\s*($idKeyPattern)([A-Za-z][A-Za-z0-9_-]{2,})\\s*",
        RegexOption.IGNORE_CASE
    )
}
