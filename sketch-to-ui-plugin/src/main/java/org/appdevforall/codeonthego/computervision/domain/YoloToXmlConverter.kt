package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.converter.CanvasTagExtractor
import org.appdevforall.codeonthego.computervision.domain.converter.CompoundButtonNormalizer
import org.appdevforall.codeonthego.computervision.domain.converter.ImagePlaceholderDuplicateFilter
import org.appdevforall.codeonthego.computervision.domain.converter.TextAssociationProcessor
import org.appdevforall.codeonthego.computervision.domain.converter.UiCandidateExtractor
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.xml.AndroidXmlGenerator
import org.appdevforall.codeonthego.computervision.utils.buildPlaceholderOverrides
import kotlin.comparisons.compareBy

class YoloToXmlConverter(
    private val annotationMatcher: WidgetAnnotationMatcher,
    private val xmlGenerator: AndroidXmlGenerator,
    private val uiCandidateExtractor: UiCandidateExtractor = UiCandidateExtractor(),
    private val canvasTagExtractor: CanvasTagExtractor = CanvasTagExtractor(annotationMatcher),
    private val textAssociationProcessor: TextAssociationProcessor = TextAssociationProcessor(annotationMatcher),
    private val compoundButtonNormalizer: CompoundButtonNormalizer = CompoundButtonNormalizer(),
    private val imagePlaceholderDuplicateFilter: ImagePlaceholderDuplicateFilter = ImagePlaceholderDuplicateFilter()
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
        val uiCandidates = uiCandidateExtractor.extract(detections)

        val scaledBoxes = scaleDetections(
            candidates = uiCandidates,
            sourceWidth = sourceImageWidth,
            sourceHeight = sourceImageHeight,
            targetWidth = targetDpWidth,
            targetHeight = targetDpHeight
        )

        val canvasTags = canvasTagExtractor.extract(
            detections = detections,
            sourceWidth = sourceImageWidth,
            sourceHeight = sourceImageHeight,
            targetWidth = targetDpWidth,
            targetHeight = targetDpHeight
        )

        val normalizedBoxes = compoundButtonNormalizer.normalizeByNearestTag(
            boxes = scaledBoxes,
            canvasTags = canvasTags
        )

        val associatedBoxes = textAssociationProcessor.associateText(normalizedBoxes)
        val uiElements = textAssociationProcessor.finalizeUiElements(associatedBoxes)

        val finalAnnotations = annotationMatcher.matchAnnotationsToElements(
            canvasTags = canvasTags,
            uiElements = uiElements,
            annotations = annotations
        )

        val filteredUiElements = imagePlaceholderDuplicateFilter.filter(
            uiElements = uiElements,
            annotations = finalAnnotations
        )

        val sortedBoxes = filteredUiElements.sortedWith(compareBy({ it.y }, { it.x }))

        val selectedImageOverrides = filteredUiElements.buildPlaceholderOverrides(
            selectedImagesByPlaceholderId
        )

        return xmlGenerator.buildXml(
            boxes = sortedBoxes,
            annotations = finalAnnotations,
            selectedImageOverrides = selectedImageOverrides,
            targetDpHeight = targetDpHeight,
            wrapInScroll = wrapInScroll
        )
    }

    private fun scaleDetections(
        candidates: List<DetectionResult>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): List<ScaledBox> {
        return candidates.map { candidate ->
            DetectionScaler.scale(
                candidate,
                sourceWidth,
                sourceHeight,
                targetWidth,
                targetHeight
            )
        }
    }

    companion object {
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
