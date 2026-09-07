package org.appdevforall.codeonthego.computervision.domain.converter

import org.appdevforall.codeonthego.computervision.domain.TextAssociator
import org.appdevforall.codeonthego.computervision.domain.WidgetAnnotationMatcher
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.utils.MetadataDetector

class TextAssociationProcessor(
    private val annotationMatcher: WidgetAnnotationMatcher
) {

    fun associateText(boxes: List<ScaledBox>): List<ScaledBox> {
        val parents = boxes.filterNot { it.isText() }
        val initialTexts = boxes.filter { it.isAssociableText() }

        val textAssignedBoxes = TextAssociator.assignTextToParents(
            parents = parents,
            texts = initialTexts,
            allBoxes = boxes
        )

        val remainingTexts = textAssignedBoxes.filter { it.isAssociableText() }

        return TextAssociator.assignNearbyTextToWidgets(textAssignedBoxes, remainingTexts)
    }

    fun finalizeUiElements(boxes: List<ScaledBox>): List<ScaledBox> {
        return boxes.filter { box ->
            val isRenderableText = box.isText() && !annotationMatcher.isTagLikeText(box.text)
            val isWidget = !box.isText()

            (isWidget || isRenderableText) &&
                !MetadataDetector.isMetadataDetection(box.label, box.text)
        }
    }

    private fun ScaledBox.isAssociableText(): Boolean {
        return isText() && !annotationMatcher.isTagLikeText(text)
    }

    private fun ScaledBox.isText(): Boolean {
        return label == TEXT_LABEL
    }

    private companion object {
        const val TEXT_LABEL = "text"
    }
}
