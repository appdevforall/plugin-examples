package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.model.SketchRegion
import org.appdevforall.codeonthego.computervision.domain.xml.AndroidXmlGenerator
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector
import org.appdevforall.codeonthego.computervision.utils.buildPlaceholderOverrides
import kotlin.comparisons.compareBy
import kotlin.math.abs

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
        val scaledBoxes = scaleDetections(
            uiCandidates,
            sourceImageWidth,
            sourceImageHeight,
            targetDpWidth,
            targetDpHeight
        )

        // 3. Extract and scale reference tags before text association.
        // This allows checkbox/radio normalization to use C-* and R-* context.
        val canvasTags = extractCanvasTags(
            detections,
            sourceImageWidth,
            sourceImageHeight,
            targetDpWidth,
            targetDpHeight
        )

        // 4. Normalize noisy checkbox/radio detections before text association and layout grouping
        val normalizedBoxes = normalizeCompoundButtonsByNearestTag(scaledBoxes, canvasTags)

        // 5. Associate isolated text detections to their respective UI widgets
        val associatedBoxes = associateTextToWidgets(normalizedBoxes)

        // 6. Clean up and finalize the UI elements list
        val uiElements = finalizeUiElements(associatedBoxes)

        // 7. Match margin annotations with the extracted UI elements
        val finalAnnotations = annotationMatcher.matchAnnotationsToElements(canvasTags, uiElements, annotations)
        val filteredUiElements = filterLikelyDuplicateImagePlaceholders(uiElements, finalAnnotations)

        // 8. Sort boxes top-to-bottom, left-to-right for sequential XML rendering
        val sortedBoxes = filteredUiElements.sortedWith(compareBy({ it.y }, { it.x }))

        // 9. Prepare local drawable resources overrides for image placeholders
        val selectedImageOverrides = filteredUiElements.buildPlaceholderOverrides(selectedImagesByPlaceholderId)

        // 10. Generate final XML output
        return xmlGenerator.buildXml(
            boxes = sortedBoxes,
            annotations = finalAnnotations,
            selectedImageOverrides = selectedImageOverrides,
            targetDpHeight = targetDpHeight,
            wrapInScroll = wrapInScroll
        )
    }

    /** Groups switches by vertical center bands before collapsing duplicate text-input detections. */
    private fun extractUiCandidates(detections: List<DetectionResult>): List<DetectionResult> {
        val candidates = detections
            .filterNot { it.isInvalidWidgetTag() }
            .filter { (it.isYolo || it.label == "text") && it.label != "widget_tag" }
            .filter { it.isCanvasRenderable() }
            .filterNot { MetadataDetector.isMetadataDetection(it.label, it.text) }
            .distinctBy {
                if (it.label.startsWith("switch")) {
                    // Deduplicate switches by grouping them within a {SWITCH_VERTICAL_BAND_SIZE}px vertical band
                    "${((it.boundingBox.top + it.boundingBox.bottom) / 2f).toInt() / SWITCH_VERTICAL_BAND_SIZE}"
                } else {
                    // Exact coordinate deduplication for other widgets
                    "${it.label}:${it.boundingBox.left}:${it.boundingBox.top}:${it.boundingBox.right}:${it.boundingBox.bottom}"
                }
            }

        return TextInputDetectionCollapser.collapse(candidates)
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

    private fun normalizeCompoundButtonsByNearestTag(
        boxes: List<ScaledBox>,
        canvasTags: List<ScaledBox>
    ): List<ScaledBox> {
        val compoundTags = canvasTags.mapNotNull { tagBox ->
            val normalizedTag = WidgetTagParser.normalizeTagText(tagBox.text)

            when {
                normalizedTag.startsWith(CHECKBOX_TAG_PREFIX) -> tagBox to CompoundGroupType.CHECKBOX
                normalizedTag.startsWith(RADIO_TAG_PREFIX) -> tagBox to CompoundGroupType.RADIO
                else -> null
            }
        }

        if (compoundTags.isEmpty()) {
            return collapseOverlappingCompoundButtons(boxes)
        }

        val normalizedBoxes = boxes.map { box ->
            if (!box.isCompoundButton()) return@map box

            val nearestTag = compoundTags.minByOrNull { (tagBox, _) ->
                val verticalDistance = abs(tagBox.centerY - box.centerY)
                val horizontalDistance = abs(tagBox.centerX - box.centerX)

                (verticalDistance * COMPOUND_TAG_VERTICAL_DISTANCE_WEIGHT) + horizontalDistance
            } ?: return@map box

            val tagBox = nearestTag.first
            val groupType = nearestTag.second
            val verticalDistance = abs(tagBox.centerY - box.centerY)

            if (verticalDistance > MAX_COMPOUND_TAG_VERTICAL_DISTANCE) {
                box
            } else {
                box.withCompoundGroupType(groupType)
            }
        }

        return collapseOverlappingCompoundButtons(normalizedBoxes)
    }

    private fun collapseOverlappingCompoundButtons(boxes: List<ScaledBox>): List<ScaledBox> {
        val result = mutableListOf<ScaledBox>()

        for (box in boxes.sortedWith(compareBy({ it.y }, { it.x }))) {
            if (!box.isCompoundButton()) {
                result.add(box)
                continue
            }

            val duplicateIndex = result.indexOfFirst { existing ->
                existing.isCompoundButton() &&
                    existing.label == box.label &&
                    existing.overlapsCompoundDuplicate(box)
            }

            if (duplicateIndex < 0) {
                result.add(box)
                continue
            }

            val existing = result[duplicateIndex]
            if (box.area > existing.area) {
                result[duplicateIndex] = box
            }
        }

        return result
    }

    private fun ScaledBox.withCompoundGroupType(groupType: CompoundGroupType): ScaledBox {
        return when (groupType) {
            CompoundGroupType.CHECKBOX -> copy(label = asCheckboxLabel())
            CompoundGroupType.RADIO -> copy(label = asRadioButtonLabel())
        }
    }

    private fun ScaledBox.isCompoundButton(): Boolean {
        return label.startsWith(RADIO_BUTTON_LABEL_PREFIX) ||
            label.startsWith(CHECKBOX_LABEL_PREFIX)
    }

    private fun ScaledBox.asCheckboxLabel(): String {
        return if (label.endsWith(CHECKED_LABEL_SUFFIX)) {
            CHECKBOX_CHECKED_LABEL
        } else {
            CHECKBOX_UNCHECKED_LABEL
        }
    }

    private fun ScaledBox.asRadioButtonLabel(): String {
        return if (label.endsWith(CHECKED_LABEL_SUFFIX)) {
            RADIO_BUTTON_CHECKED_LABEL
        } else {
            RADIO_BUTTON_UNCHECKED_LABEL
        }
    }

    private fun ScaledBox.overlapsCompoundDuplicate(other: ScaledBox): Boolean {
        val intersectionWidth = minOf(x + w, other.x + other.w) - maxOf(x, other.x)
        val intersectionHeight = minOf(y + h, other.y + other.h) - maxOf(y, other.y)

        if (intersectionWidth <= 0 || intersectionHeight <= 0) return false

        val intersectionArea = intersectionWidth * intersectionHeight
        val smallerArea = minOf(area, other.area).coerceAtLeast(1)

        return intersectionArea.toFloat() / smallerArea.toFloat() >= COMPOUND_DUPLICATE_OVERLAP_THRESHOLD
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

    /** Compares area, horizontal overlap, and vertical gap to identify duplicate image placeholders. */
    private fun ScaledBox.isLikelyDuplicateOf(annotated: ScaledBox): Boolean {
        val maxDuplicateArea = annotated.area * IMAGE_PLACEHOLDER_MAX_DUPLICATE_AREA_RATIO
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

    /** Calculates the scaled box area in target DP units. */
    private val ScaledBox.area: Int
        get() = w * h

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

            SketchRegion.LEFT_METADATA,
            SketchRegion.RIGHT_METADATA -> false
        }
    }

    private enum class CompoundGroupType {
        RADIO,
        CHECKBOX
    }

    companion object {
        private const val SWITCH_VERTICAL_BAND_SIZE = 50

        private const val CHECKBOX_TAG_PREFIX = "C-"
        private const val RADIO_TAG_PREFIX = "R-"

        private const val CHECKBOX_LABEL_PREFIX = "checkbox"
        private const val RADIO_BUTTON_LABEL_PREFIX = "radio_button"
        private const val CHECKED_LABEL_SUFFIX = "_checked"

        private const val CHECKBOX_CHECKED_LABEL = "checkbox_checked"
        private const val CHECKBOX_UNCHECKED_LABEL = "checkbox_unchecked"
        private const val RADIO_BUTTON_CHECKED_LABEL = "radio_button_checked"
        private const val RADIO_BUTTON_UNCHECKED_LABEL = "radio_button_unchecked"

        private const val COMPOUND_TAG_VERTICAL_DISTANCE_WEIGHT = 2
        private const val MAX_COMPOUND_TAG_VERTICAL_DISTANCE = 100
        private const val COMPOUND_DUPLICATE_OVERLAP_THRESHOLD = 0.80f

        private const val IMAGE_PLACEHOLDER_MAX_DUPLICATE_AREA_RATIO = 0.35

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
