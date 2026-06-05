package org.appdevforall.codeonthego.computervision.domain

import android.graphics.RectF
import com.google.mlkit.vision.text.Text
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector
import org.appdevforall.codeonthego.computervision.utils.OcrTextAssembler.joinElementsWithTolerance

class DetectionMerger(
    private val enrichedComponents: List<DetectionResult>,
    private val remainingYoloDetections: List<DetectionResult>,
    private val fullImageTextBlocks: List<Text.TextBlock>,
    private val leftBoundPx: Float? = null,
    private val rightBoundPx: Float? = null
) {
    private val containerLabels = setOf("card", "toolbar")

    fun merge(): List<DetectionResult> {
        val finalDetections = mutableListOf<DetectionResult>()
        val usedTextBlocks = mutableSetOf<Text.TextBlock>()

        finalDetections.addAll(enrichedComponents)
        finalDetections.addAll(remainingYoloDetections)

        val containers = remainingYoloDetections.filter { it.label in containerLabels }
        for (container in containers) {
            val candidates = fullImageTextBlocks.filter { it !in usedTextBlocks }
            for (textBlock in candidates) {
                val textBox = textBlock.boundingBox?.let { RectF(it) } ?: continue
                val region = classify(textBox)
                val isCanvasText = region == null || region == SketchRegion.CANVAS
                if (isCanvasText && container.boundingBox.contains(textBox)) {
                    finalDetections.add(
                        DetectionResult(
                            boundingBox = textBox,
                            label = "text",
                            score = 0.99f,
                            text = textBlock.text.replace("\n", " "),
                            isYolo = false,
                            region = region
                        )
                    )
                    usedTextBlocks.add(textBlock)
                }
            }
        }

        val orphanDetections = fullImageTextBlocks
            .filter { it !in usedTextBlocks }
            .flatMap { it.lines }
            .mapNotNull { line ->
                line.boundingBox?.let { box ->
                    val bounds = RectF(box)
                    val region = classify(bounds)
                    val text = joinElementsWithTolerance(line)
                    val resolvedRegion = resolveCrossBoundaryTextRegion(region, text)
                        ?: return@mapNotNull null
                    DetectionResult(
                        boundingBox = bounds,
                        label = "text",
                        score = if (resolvedRegion != SketchRegion.CANVAS) 0.98f else 0.99f,
                        text = text,
                        isYolo = false,
                        region = resolvedRegion
                    )
                }
            }

        finalDetections.addAll(orphanDetections)

        return finalDetections
    }

    private fun classify(bounds: RectF): SketchRegion? {
        val left = leftBoundPx ?: return null
        val right = rightBoundPx ?: return null
        return SketchRegionClassifier.classify(bounds, left, right)
    }

    private fun resolveCrossBoundaryTextRegion(region: SketchRegion?, text: String): SketchRegion? {
        if (region != SketchRegion.CROSS_BOUNDARY) return region
        return if (MetadataDetector.isCanvasMetadata(text) || WidgetTagParser.isTagSequence(text)) {
            null
        } else {
            SketchRegion.CANVAS
        }
    }
}
