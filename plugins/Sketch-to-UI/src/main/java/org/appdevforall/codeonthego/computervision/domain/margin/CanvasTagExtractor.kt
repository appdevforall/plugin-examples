package org.appdevforall.codeonthego.computervision.domain.margin

import org.appdevforall.codeonthego.computervision.domain.WidgetTagParser
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

internal object CanvasTagExtractor {
    fun extract(canvasDetections: List<DetectionResult>): List<Pair<String, DetectionResult>> {
        return canvasDetections.mapNotNull { detection ->
            if (!WidgetTagParser.isTag(detection.text)) return@mapNotNull null
            WidgetTagParser.extractTag(detection.text)?.let { (tag, _) -> tag to detection }
        }
    }
}
