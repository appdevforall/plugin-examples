package org.appdevforall.codeonthego.computervision.domain.converter

import org.appdevforall.codeonthego.computervision.domain.TextInputDetectionCollapser
import org.appdevforall.codeonthego.computervision.domain.WidgetTagParser
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector

class UiCandidateExtractor {

    fun extract(detections: List<DetectionResult>): List<DetectionResult> {
        val candidates = detections
            .filterNot { it.isInvalidWidgetTag() }
            .filter { (it.isYolo || it.label == DetectionLabels.TEXT) && it.label != DetectionLabels.WIDGET_TAG }
            .filter { it.isCanvasRenderable() }
            .filterNot { MetadataDetector.isMetadataDetection(it.label, it.text) }
            .distinctBy { it.deduplicationKey() }

        return TextInputDetectionCollapser.collapse(candidates)
    }

    private fun DetectionResult.deduplicationKey(): String {
        if (label.startsWith(DetectionLabels.SWITCH_PREFIX)) {
            val centerY = ((boundingBox.top + boundingBox.bottom) / 2f).toInt()
            return "${DetectionLabels.SWITCH_PREFIX}_${centerY / SWITCH_VERTICAL_BAND_SIZE}"
        }

        return "${label}:${boundingBox.left}:${boundingBox.top}:${boundingBox.right}:${boundingBox.bottom}"
    }

    private fun DetectionResult.isInvalidWidgetTag(): Boolean {
        return label == DetectionLabels.WIDGET_TAG && WidgetTagParser.extractTag(text) == null
    }

    private fun DetectionResult.isCanvasRenderable(): Boolean {
        return when (region) {
            null, SketchRegion.CANVAS -> true

            SketchRegion.CROSS_BOUNDARY -> label == DetectionLabels.TEXT &&
                text.isNotBlank() &&
                !MetadataDetector.isCanvasMetadata(text) &&
                !WidgetTagParser.isTagSequence(text)

            SketchRegion.LEFT_METADATA,
            SketchRegion.RIGHT_METADATA -> false
        }
    }

    private companion object {
        const val SWITCH_VERTICAL_BAND_SIZE = 50
    }
}
