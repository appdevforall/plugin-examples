package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.margin.CanvasTagExtractor
import org.appdevforall.codeonthego.computervision.domain.margin.MarginAnnotationResolver
import org.appdevforall.codeonthego.computervision.domain.margin.MarginDetectionDistributor
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector

/**
 * Coordinates margin annotation parsing while delegating each parsing stage to a focused component.
 */
object MarginAnnotationParser {
    fun parse(
        detections: List<DetectionResult>,
        imageWidth: Int,
        leftGuidePct: Float,
        rightGuidePct: Float
    ): Pair<List<DetectionResult>, Map<String, String>> {
        val sanitizedDetections = detections.filterNot { MetadataDetector.isMetadataLabel(it.label) }
        val distribution = MarginDetectionDistributor.distribute(
            detections = sanitizedDetections,
            imageWidth = imageWidth,
            leftGuidePct = leftGuidePct,
            rightGuidePct = rightGuidePct
        )
        val annotations = MarginAnnotationResolver.resolve(
            leftMargin = distribution.leftMargin,
            rightMargin = distribution.rightMargin,
            canvasTags = CanvasTagExtractor.extract(distribution.canvas)
        )

        return distribution.canvas to MetadataAnnotationRecovery.resolve(annotations)
    }
}
