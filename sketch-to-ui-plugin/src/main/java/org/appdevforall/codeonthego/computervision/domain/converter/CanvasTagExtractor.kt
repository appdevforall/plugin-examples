package org.appdevforall.codeonthego.computervision.domain.converter

import org.appdevforall.codeonthego.computervision.domain.DetectionScaler
import org.appdevforall.codeonthego.computervision.domain.WidgetAnnotationMatcher
import org.appdevforall.codeonthego.computervision.domain.WidgetTagParser
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion

class CanvasTagExtractor(
    private val annotationMatcher: WidgetAnnotationMatcher
) {

    fun extract(
        detections: List<DetectionResult>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<ScaledBox> {
        return detections
            .filter { it.isCanvasTag() }
            .map { detection ->
                DetectionScaler.scale(
                    detection,
                    sourceWidth,
                    sourceHeight,
                    targetWidth,
                    targetHeight
                )
            }
    }

    private fun DetectionResult.isCanvasTag(): Boolean {
        val isCanvasRegion = region == null || region == SketchRegion.CANVAS
        if (!isCanvasRegion) return false

        val isYoloWidgetTag = label == DetectionLabels.WIDGET_TAG && WidgetTagParser.extractTag(text) != null
        val isOcrTag = !isYolo && annotationMatcher.isTag(text)

        return isYoloWidgetTag || isOcrTag
    }
}
