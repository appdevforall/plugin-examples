package org.appdevforall.codeonthego.computervision.domain.margin

import kotlin.math.abs
import org.appdevforall.codeonthego.computervision.domain.WidgetTagParser
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

/**
 * Assigns untagged margin blocks to the nearest unresolved canvas tag group.
 */
internal object ImplicitAnnotationResolver {
    /** Assigns blocks by minimizing vertical distance to the next unresolved tag group. */
    fun resolve(
        implicitBlocks: List<ParsedBlock>,
        canvasTags: List<Pair<String, DetectionResult>>,
        existingAnnotations: Map<String, String>
    ): Map<String, String> {
        val resolvedAnnotations = mutableMapOf<String, String>()
        val canvasTagsByPrefix = canvasTags
            .groupBy { (tag, _) -> tag.substringBefore('-') }
            .mapValues { (_, tags) -> tags.sortedBy { (_, detection) -> detection.centerY() } }
        val unresolvedTagsByPrefix = canvasTagsByPrefix
            .mapValues { (_, tags) ->
                tags.map { it.first }
                    .filter { tag -> tag !in existingAnnotations }
                    .sortedBy { tag -> WidgetTagParser.extractOrdinal(tag) ?: Int.MAX_VALUE }
                    .toMutableList()
            }.toMutableMap()

        for (block in implicitBlocks.sortedBy { it.centerY }) {
            val closestPrefix = unresolvedTagsByPrefix
                .filterValues { it.isNotEmpty() }
                .minByOrNull { (prefix, remainingTags) ->
                    val nearestTagY = canvasTagsByPrefix[prefix]
                        ?.firstOrNull { (tag, _) -> tag == remainingTags.firstOrNull() }
                        ?.second?.centerY() ?: Float.MAX_VALUE
                    abs(nearestTagY - block.centerY)
                }?.key ?: continue

            val assignedTag = unresolvedTagsByPrefix[closestPrefix]?.removeFirstOrNull() ?: continue
            resolvedAnnotations[assignedTag] = block.annotationText
        }

        return resolvedAnnotations
    }
}
