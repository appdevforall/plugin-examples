package org.appdevforall.codeonthego.computervision.domain.metadata.recovery

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.DimensionPatterns

internal object SamePrefixDimensionRecovery {
    private val dimensionKeys = listOf(AttributeKey.WIDTH, AttributeKey.HEIGHT)

    fun findFallbacks(metadata: Collection<ParsedMetadata>): Map<String, Map<String, String>> {
        return metadata.groupBy(ParsedMetadata::prefix)
            .mapValues { (_, group) -> group.consistentDimensions() }
    }

    fun recover(
        metadata: ParsedMetadata,
        fallbackDimensions: Map<String, String>,
        destination: MutableMap<String, String>
    ) {
        dimensionKeys.forEach { key ->
            metadata.resolveDimension(key, fallbackDimensions)?.let { destination[key.xmlName] = it }
        }
    }

    private fun List<ParsedMetadata>.consistentDimensions(): Map<String, String> {
        return dimensionKeys.mapNotNull { key ->
            val values = mapNotNull { metadata ->
                metadata.attributes[key.xmlName]?.takeIf { metadata.hasReliableDimension(key) }
            }.distinct()
            key.xmlName.takeIf { values.size == 1 }?.let { it to values.single() }
        }.toMap()
    }

    private fun ParsedMetadata.resolveDimension(
        key: AttributeKey,
        fallbackDimensions: Map<String, String>
    ): String? {
        val parsedValue = attributes[key.xmlName]
        return parsedValue?.takeIf { hasReliableDimension(key) } ?: fallbackDimensions[key.xmlName]
    }

    private fun ParsedMetadata.hasReliableDimension(key: AttributeKey): Boolean {
        val aliases = when (key) {
            AttributeKey.WIDTH -> "(?:layout[_-]?width|layoutwidth|width)"
            AttributeKey.HEIGHT -> "(?:layout[_-]?height|layoutheight|layoutheiqht|height)"
            else -> return false
        }
        return DimensionPatterns.explicitDimensionWithUnit(aliases).containsMatchIn(rawText) ||
            DimensionPatterns.compactDimensionWithUnit(aliases).containsMatchIn(rawText)
    }
}
