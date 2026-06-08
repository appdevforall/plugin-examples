package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.appdevforall.codeonthego.computervision.domain.xml.AndroidXmlGenerator
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector
import org.appdevforall.codeonthego.computervision.utils.buildPlaceholderOverrides
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.comparisons.compareBy

class YoloToXmlConverter(
    private val annotationMatcher: WidgetAnnotationMatcher,
    private val xmlGenerator: AndroidXmlGenerator
) {
    fun generateXmlLayout(
        detections: List<DetectionResult>,
        annotations: Map<String, String>,
        selectedImagesByPlaceholderId: Map<String, String>,
        sourceImageWidth: Int,
        sourceImageHeight: Int,
        targetDpWidth: Int,
        targetDpHeight: Int,
        wrapInScroll: Boolean = true
    ): Pair<String, String> {
        // 1. Filter and prepare base UI candidates
        val uiCandidates = extractUiCandidates(detections)

        // 2. Scale detections to target DP dimensions
        val scaledBoxes = scaleDetections(uiCandidates, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight)

        // 3. Associate isolated text detections to their respective UI widgets
        val associatedBoxes = associateTextToWidgets(scaledBoxes)

        // 4. Clean up and finalize the UI elements list
        val uiElements = finalizeUiElements(associatedBoxes)

        // 5. Extract and scale reference tags (e.g., T-1, B-1) from the canvas
        val canvasTags = extractCanvasTags(detections, sourceImageWidth, sourceImageHeight, targetDpWidth, targetDpHeight)

        // 6. Match margin annotations with the extracted UI elements
        val finalAnnotations = annotationMatcher.matchAnnotationsToElements(canvasTags, uiElements, annotations)
        val filteredUiElements = filterLikelyDuplicateImagePlaceholders(uiElements, finalAnnotations)

        // 7. Sort boxes top-to-bottom, left-to-right for sequential XML rendering
        val sortedBoxes = filteredUiElements.sortedWith(compareBy({ it.y }, { it.x }))

        // 8. Prepare local drawable resources overrides for image placeholders
        val selectedImageOverrides = filteredUiElements.buildPlaceholderOverrides(selectedImagesByPlaceholderId)

        // 9. Generate final XML output
        return xmlGenerator.buildXml(
            boxes = sortedBoxes,
            annotations = finalAnnotations,
            selectedImageOverrides = selectedImageOverrides,
            targetDpHeight = targetDpHeight,
            wrapInScroll = wrapInScroll
        )
    }

    private fun extractUiCandidates(detections: List<DetectionResult>): List<DetectionResult> {
        val candidates = detections
            .filterNot { it.isInvalidWidgetTag() }
            .filter { (it.isYolo || it.label == "text") && it.label != "widget_tag" }
            .filter { it.isCanvasRenderable() }
            .filterNot { MetadataDetector.isMetadataDetection(it.label, it.text) }
            .distinctBy {
                if (it.label.startsWith("switch")) {
                    // Deduplicate switches by grouping them within a 50px vertical band
                    "${((it.boundingBox.top + it.boundingBox.bottom) / 2f).toInt() / 50}"
                } else {
                    // Exact coordinate deduplication for other widgets
                    "${it.label}:${it.boundingBox.left}:${it.boundingBox.top}:${it.boundingBox.right}:${it.boundingBox.bottom}"
                }
            }

        return collapseOverlappingTextInputs(candidates)
    }

    private fun collapseOverlappingTextInputs(candidates: List<DetectionResult>): List<DetectionResult> {
        val textInputs = candidates.filter { it.label == "text_entry_box" }
        if (textInputs.size <= 1) return candidates

        val collapsedInputs = mutableListOf<DetectionResult>()
        val visited = Collections.newSetFromMap(IdentityHashMap<DetectionResult, Boolean>())

        for (input in textInputs.sortedByDescending { it.area }) {
            if (input in visited) continue

            val cluster = textInputs.filter { other ->
                other !in visited && input.overlapsTextInputDuplicate(other)
            }
            visited.addAll(cluster)
            collapsedInputs.add(cluster.maxWith(compareBy<DetectionResult> { it.area }.thenBy { it.score }))
        }

        return candidates.filter { candidate ->
            candidate.label != "text_entry_box" || collapsedInputs.any { it === candidate }
        }
    }

    private fun DetectionResult.overlapsTextInputDuplicate(other: DetectionResult): Boolean {
        if (this === other) return true
        val intersectionWidth = minOf(boundingBox.right, other.boundingBox.right) -
            maxOf(boundingBox.left, other.boundingBox.left)
        val intersectionHeight = minOf(boundingBox.bottom, other.boundingBox.bottom) -
            maxOf(boundingBox.top, other.boundingBox.top)
        if (intersectionWidth <= 0f || intersectionHeight <= 0f) return false

        val intersectionArea = intersectionWidth * intersectionHeight
        val smallerArea = minOf(area, other.area)
        val unionArea = area + other.area - intersectionArea
        val containedOverlap = intersectionArea / smallerArea
        val intersectionOverUnion = intersectionArea / unionArea

        return containedOverlap >= TEXT_ENTRY_CONTAINMENT_THRESHOLD ||
            intersectionOverUnion >= TEXT_ENTRY_IOU_THRESHOLD
    }

    private fun scaleDetections(
        candidates: List<DetectionResult>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<ScaledBox> {
        return candidates.map { 
            DetectionScaler.scale(it, sourceWidth, sourceHeight, targetWidth, targetHeight)
        }
    }

    private fun associateTextToWidgets(scaledBoxes: List<ScaledBox>): List<ScaledBox> {
        val parents = scaledBoxes.filter { it.label != "text" }
        val initialTexts = scaledBoxes.filter { it.label == "text" && !annotationMatcher.isTagLikeText(it.text) }

        val textAssignedBoxes = TextAssociator.assignTextToParents(parents, initialTexts, scaledBoxes)
        val remainingTexts = textAssignedBoxes.filter { it.label == "text" && !annotationMatcher.isTagLikeText(it.text) }

        return TextAssociator.assignNearbyTextToWidgets(textAssignedBoxes, remainingTexts)
    }

    private fun finalizeUiElements(boxes: List<ScaledBox>): List<ScaledBox> {
        return boxes.filter { box ->
            // Keep the widget if it's not pure text, or if it is text but not recognized as a tag.
            val keep = (box.label != "text" || !annotationMatcher.isTagLikeText(box.text)) &&
                !MetadataDetector.isMetadataDetection(box.label, box.text)
            keep
        }
    }

    private fun extractCanvasTags(
        detections: List<DetectionResult>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<ScaledBox> {
        val widgetTags = detections.filter { 
            (it.region == null || it.region == SketchRegion.CANVAS) &&
                ((it.label == "widget_tag" && WidgetTagParser.extractTag(it.text) != null) ||
                    (!it.isYolo && annotationMatcher.isTag(it.text)))
        }
        return widgetTags.map { detection ->
            DetectionScaler.scale(detection, sourceWidth, sourceHeight, targetWidth, targetHeight)
        }
    }

    private fun filterLikelyDuplicateImagePlaceholders(
        uiElements: List<ScaledBox>,
        annotations: Map<ScaledBox, String>
    ): List<ScaledBox> {
        val annotatedImages = uiElements
            .filter { it.isImagePlaceholder() && annotations.containsKey(it) }
        if (annotatedImages.isEmpty()) return uiElements

        return uiElements.filterNot { box ->
            box.isImagePlaceholder() &&
                !annotations.containsKey(box) &&
                annotatedImages.any { annotated -> box.isLikelyDuplicateOf(annotated) }
        }
    }

    private fun ScaledBox.isImagePlaceholder(): Boolean {
        return label.startsWith("image_placeholder")
    }

    private fun ScaledBox.isLikelyDuplicateOf(annotated: ScaledBox): Boolean {
        val maxDuplicateArea = annotated.area * 0.35
        val verticalGap = when {
            y >= annotated.y + annotated.h -> y - (annotated.y + annotated.h)
            annotated.y >= y + h -> annotated.y - (y + h)
            else -> 0
        }
        val hasHorizontalOverlap = x < annotated.x + annotated.w && x + w > annotated.x
        return area < maxDuplicateArea &&
            hasHorizontalOverlap &&
            verticalGap <= annotated.h / 2
    }

    private val ScaledBox.area: Int
        get() = w * h

    private val DetectionResult.area: Float
        get() = (boundingBox.right - boundingBox.left) * (boundingBox.bottom - boundingBox.top)

    private fun DetectionResult.isInvalidWidgetTag(): Boolean {
        return label == "widget_tag" && WidgetTagParser.extractTag(text) == null
    }

    private fun DetectionResult.isCanvasRenderable(): Boolean {
        return when (region) {
            null, SketchRegion.CANVAS -> true
            SketchRegion.CROSS_BOUNDARY -> label == "text" &&
                text.isNotBlank() &&
                !MetadataDetector.isCanvasMetadata(text) &&
                !WidgetTagParser.isTagSequence(text)
            SketchRegion.LEFT_METADATA, SketchRegion.RIGHT_METADATA -> false
        }
    }

    companion object {
        private const val TEXT_ENTRY_CONTAINMENT_THRESHOLD = 0.85f
        private const val TEXT_ENTRY_IOU_THRESHOLD = 0.50f

        fun generateXmlLayout(
            detections: List<DetectionResult>,
            annotations: Map<String, String>,
            selectedImagesByPlaceholderId: Map<String, String> = emptyMap(),
            sourceImageWidth: Int,
            sourceImageHeight: Int,
            targetDpWidth: Int,
            targetDpHeight: Int,
            wrapInScroll: Boolean = true
        ): Pair<String, String> {
            val matcher = WidgetAnnotationMatcher()
            val generator = AndroidXmlGenerator()

            val converter = YoloToXmlConverter(matcher, generator)

            return converter.generateXmlLayout(
                detections = detections,
                annotations = annotations,
                selectedImagesByPlaceholderId = selectedImagesByPlaceholderId,
                sourceImageWidth = sourceImageWidth,
                sourceImageHeight = sourceImageHeight,
                targetDpWidth = targetDpWidth,
                targetDpHeight = targetDpHeight,
                wrapInScroll = wrapInScroll
            )
        }
    }
}
