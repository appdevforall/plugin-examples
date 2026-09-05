package org.appdevforall.codeonthego.computervision.domain.margin

import org.appdevforall.codeonthego.computervision.domain.WidgetTagParser
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult

/**
 * Reads margin OCR vertically and groups it into explicit and implicit annotation blocks.
 */
internal object MarginBlockParser {
    fun parseBySource(detections: List<DetectionResult>): List<SourcedBlocks> {
        return detections.groupBy { it.metadataSource }
            .map { (source, sourceDetections) ->
                SourcedBlocks(source, parse(sourceDetections.sortedBy { it.boundingBox.top }))
            }
    }

    private fun parse(sortedDetections: List<DetectionResult>): GroupedBlocks {
        val blocks = GroupedBlocks()
        var currentTag: String? = null
        var currentText = StringBuilder()
        var blockStartY = 0f

        fun saveCurrentBlock() {
            if (currentTag != null) {
                blocks.addExplicitAnnotation(currentTag!!, currentText.toString().trim())
            } else if (currentText.isNotBlank()) {
                blocks.implicitBlocks.add(ParsedBlock(currentText.toString().trim(), blockStartY))
            }
        }

        for (detection in sortedDetections) {
            val text = detection.text.trim().trimStart('|', ':', ';', '.', ',', '_')
            val extraction = WidgetTagParser.extractTag(text)

            if (extraction != null) {
                saveCurrentBlock()
                currentTag = extraction.first
                currentText = StringBuilder()
                blockStartY = detection.centerY()

                val trailing = extraction.second?.trim()
                if (!trailing.isNullOrBlank() && WidgetTagParser.normalizeTagText(trailing) != currentTag) {
                    currentText.appendAnnotationFragment(trailing)
                }
            } else {
                if (currentText.isEmpty()) blockStartY = detection.centerY()
                currentText.appendAnnotationFragment(text)
            }
        }
        saveCurrentBlock()

        return blocks
    }

    private fun StringBuilder.appendAnnotationFragment(text: String) {
        if (isNotBlank()) append(" | ")
        append(text)
    }
}
