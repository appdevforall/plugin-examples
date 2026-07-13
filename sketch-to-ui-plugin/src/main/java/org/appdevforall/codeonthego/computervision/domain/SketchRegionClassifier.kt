package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion

object SketchRegionClassifier {
    fun classify(bounds: RectF, leftBoundPx: Float, rightBoundPx: Float): SketchRegion {
        return when {
            bounds.right < leftBoundPx -> SketchRegion.LEFT_METADATA
            bounds.left > rightBoundPx -> SketchRegion.RIGHT_METADATA
            bounds.left >= leftBoundPx && bounds.right <= rightBoundPx -> SketchRegion.CANVAS
            else -> SketchRegion.CROSS_BOUNDARY
        }
    }

    fun classifyDetections(
        detections: List<DetectionResult>,
        leftBoundPx: Float,
        rightBoundPx: Float
    ): List<DetectionResult> {
        return detections.map { detection ->
            detection.copy(region = classify(detection.boundingBox, leftBoundPx, rightBoundPx))
        }
    }
}
