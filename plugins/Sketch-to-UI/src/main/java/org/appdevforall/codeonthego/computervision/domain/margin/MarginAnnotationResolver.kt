package org.appdevforall.codeonthego.computervision.domain.margin

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

/**
 * Resolves explicit and implicit blocks across both margins using one shared canvas tag pool.
 */
internal object MarginAnnotationResolver {
    fun resolve(
        leftMargin: List<DetectionResult>,
        rightMargin: List<DetectionResult>,
        canvasTags: List<Pair<String, DetectionResult>>
    ): Map<String, String> {
        val sourceBlocks = MarginBlockParser.parseBySource(leftMargin) +
            MarginBlockParser.parseBySource(rightMargin)
        val explicitAnnotations = ExplicitAnnotationMerger.merge(sourceBlocks)
        val implicitAnnotations = ImplicitAnnotationResolver.resolve(
            implicitBlocks = sourceBlocks.flatMap { it.blocks.implicitBlocks },
            canvasTags = canvasTags,
            existingAnnotations = explicitAnnotations
        )

        return mergeAnnotations(explicitAnnotations, implicitAnnotations)
    }

    private fun mergeAnnotations(vararg maps: Map<String, String>): MutableMap<String, String> {
        return maps.flatMap { it.toList() }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, values) -> values.joinToString(" | ") }
            .toMutableMap()
    }
}
