package org.appdevforall.codeonthego.computervision.domain.margin

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.MetadataOcrSource

internal data class DetectionDistribution(
    val canvas: List<DetectionResult>,
    val leftMargin: List<DetectionResult>,
    val rightMargin: List<DetectionResult>
)

internal data class ParsedBlock(
    val annotationText: String,
    val centerY: Float
)

internal data class GroupedBlocks(
    val explicitAnnotations: MutableMap<String, String> = mutableMapOf(),
    val implicitBlocks: MutableList<ParsedBlock> = mutableListOf()
) {
    fun addExplicitAnnotation(tag: String, text: String) {
        if (text.isBlank()) return
        explicitAnnotations.merge(tag, text) { existing, newText -> "$existing | $newText" }
    }
}

internal data class SourcedBlocks(
    val source: MetadataOcrSource?,
    val blocks: GroupedBlocks
)

/** Assigns a lower numeric priority to more reliable OCR sources. */
internal val MetadataOcrSource?.priority: Int
    get() = when (this) {
        MetadataOcrSource.MARGIN_CROP -> 0
        null -> 1
        MetadataOcrSource.FULL_IMAGE -> 2
    }

/** Calculates the horizontal center of a detection box. */
internal fun DetectionResult.centerX(): Float = (boundingBox.left + boundingBox.right) / 2f

/** Calculates the vertical center of a detection box. */
internal fun DetectionResult.centerY(): Float = (boundingBox.top + boundingBox.bottom) / 2f
