package org.appdevforall.codeonthego.computervision.domain.margin

import org.appdevforall.codeonthego.computervision.domain.WidgetTagParser
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector

/**
 * Distributes detections into the canvas and its metadata margins.
 */
internal object MarginDetectionDistributor {
    /** Converts percentage guides to pixels and distributes detections around those boundaries. */
    fun distribute(
        detections: List<DetectionResult>,
        imageWidth: Int,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): DetectionDistribution {
        val leftMarginPx = imageWidth * leftGuidePct
        val rightMarginPx = imageWidth * rightGuidePct
        val canvas = mutableListOf<DetectionResult>()
        val leftMargin = mutableListOf<DetectionResult>()
        val rightMargin = mutableListOf<DetectionResult>()

        for (detection in detections) {
            if (detection.isInvalidWidgetTag()) continue

            when (detection.region) {
                SketchRegion.LEFT_METADATA -> leftMargin.add(detection)
                SketchRegion.RIGHT_METADATA -> rightMargin.add(detection)
                SketchRegion.CANVAS -> canvas.add(detection)
                SketchRegion.CROSS_BOUNDARY -> {
                    if (detection.isRenderableCrossBoundaryText()) canvas.add(detection)
                }
                null -> assignByPosition(
                    detection,
                    imageWidth,
                    leftMarginPx,
                    rightMarginPx,
                    canvas,
                    leftMargin,
                    rightMargin
                )
            }
        }

        return DetectionDistribution(canvas, leftMargin, rightMargin)
    }

    /** Assigns an unclassified detection using its horizontal center and guide positions. */
    private fun assignByPosition(
        detection: DetectionResult,
        imageWidth: Int,
        leftMarginPx: Float,
        rightMarginPx: Float,
        canvas: MutableList<DetectionResult>,
        leftMargin: MutableList<DetectionResult>,
        rightMargin: MutableList<DetectionResult>
    ) {
        val centerX = detection.centerX()
        val region = when {
            MetadataDetector.isCanvasMetadata(detection.text) && centerX < imageWidth / 2f ->
                SketchRegion.LEFT_METADATA
            MetadataDetector.isCanvasMetadata(detection.text) -> SketchRegion.RIGHT_METADATA
            centerX > leftMarginPx && centerX < rightMarginPx -> SketchRegion.CANVAS
            centerX <= leftMarginPx -> SketchRegion.LEFT_METADATA
            else -> SketchRegion.RIGHT_METADATA
        }

        when (region) {
            SketchRegion.LEFT_METADATA -> leftMargin.add(detection)
            SketchRegion.RIGHT_METADATA -> rightMargin.add(detection)
            SketchRegion.CANVAS -> canvas.add(detection)
            SketchRegion.CROSS_BOUNDARY -> Unit
        }
    }

    private fun DetectionResult.isInvalidWidgetTag(): Boolean {
        return label == "widget_tag" && WidgetTagParser.extractTag(text) == null
    }

    private fun DetectionResult.isRenderableCrossBoundaryText(): Boolean {
        return label == "text" &&
            text.isNotBlank() &&
            !MetadataDetector.isCanvasMetadata(text) &&
            !WidgetTagParser.isTagSequence(text)
    }
}
