package org.appdevforall.codeonthego.computervision.domain.margin

import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.model.MetadataOcrSource
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.CoreParserPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.DimensionPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.TextPatterns

/**
 * Merges duplicate explicit annotations while selecting the strongest fragment for each key.
 */
internal object ExplicitAnnotationMerger {
    fun merge(sourceBlocks: List<SourcedBlocks>): MutableMap<String, String> {
        return sourceBlocks
            .sortedBy { it.source.priority }
            .flatMap { sourced ->
                sourced.blocks.explicitAnnotations.map { (tag, annotation) ->
                    tag to SourcedAnnotation(annotation, sourced.source)
                }
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, candidates) -> mergeFragments(candidates) }
            .toMutableMap()
    }

    private fun mergeFragments(candidates: List<SourcedAnnotation>): String {
        val fragments = candidates.toSourcedFragments()
        val keyed = fragments.filter { it.key != null }
            .groupBy { it.key.orEmpty() }
            .map { (key, values) -> values.bestFragmentFor(key) }
        val unkeyed = fragments.filter { it.key == null }.map { it.text }.distinct()
        return (keyed.map { it.text } + unkeyed).joinToString(" | ")
    }

    private fun List<SourcedAnnotation>.toSourcedFragments(): List<SourcedFragment> {
        return sortedBy { it.source.priority }.flatMap { candidate ->
            candidate.text.groupAdjacentDimensionFragments().map { fragment ->
                SourcedFragment(fragment, normalizedAttributeKey(fragment), candidate.source.priority)
            }
        }
    }

    /** Selects the strongest fragment by repeatedly applying source and noise-score comparisons. */
    private fun List<SourcedFragment>.bestFragmentFor(key: String): SelectedFragment {
        return map { SelectedFragment(it.text, it.sourcePriority) }
            .reduce { currentBest, candidate ->
                if (candidate.isBetterThan(currentBest, key)) candidate else currentBest
            }
    }

    private fun String.groupAdjacentDimensionFragments(): List<String> {
        return split('|').map(String::trim).filter(String::isNotEmpty)
            .fold(mutableListOf()) { grouped, fragment ->
                val previous = grouped.lastOrNull()
                if (previous != null && shouldJoinUnlabeledDimension(previous, fragment)) {
                    grouped[grouped.lastIndex] = "$previous $fragment"
                } else {
                    grouped.add(fragment)
                }
                grouped
            }
    }

    private fun shouldJoinUnlabeledDimension(previous: String, fragment: String): Boolean {
        return normalizedAttributeKey(previous) in DIMENSION_KEYS &&
            DimensionPatterns.UNLABELED_DIMENSION.matches(fragment)
    }

    private fun normalizedAttributeKey(fragment: String): String? {
        val rawKey = CoreParserPatterns.ATTRIBUTE_KEY_PREFIX.find(fragment)?.groupValues?.get(1) ?: return null
        val compactKey = rawKey.lowercase().replace(TextPatterns.NON_LETTERS, "")
        return when {
            compactKey.startsWith("layoutwidth") -> AttributeKey.WIDTH.aliases.first()
            compactKey.startsWith("layoutheight") || compactKey.startsWith("layoutheiqht") ->
                AttributeKey.HEIGHT.aliases.first()
            compactKey in AttributeKey.ID.aliases -> AttributeKey.ID.aliases.first()
            compactKey.isNotBlank() -> compactKey
            else -> null
        }
    }

    private data class SourcedAnnotation(val text: String, val source: MetadataOcrSource?)

    private data class SourcedFragment(val text: String, val key: String?, val sourcePriority: Int)

    private data class SelectedFragment(val text: String, val sourcePriority: Int) {
        /** Compares source priority, then dimension-noise scores for equal-priority fragments. */
        fun isBetterThan(existing: SelectedFragment, key: String): Boolean {
            if (sourcePriority != existing.sourcePriority) return sourcePriority < existing.sourcePriority
            if (key !in DIMENSION_KEYS) return false
            return dimensionNoiseScore(text) < dimensionNoiseScore(existing.text)
        }
    }

    private val DIMENSION_KEYS = AttributeKey.WIDTH.aliases.take(2).toSet() + AttributeKey.HEIGHT.aliases

    /** Counts OCR noise characters embedded in a dimension fragment. */
    private fun dimensionNoiseScore(fragment: String): Int {
        return DimensionPatterns.DIMENSION_OCR_NOISE.findAll(fragment).count()
    }
}
