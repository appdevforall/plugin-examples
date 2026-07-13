package org.appdevforall.codeonthego.computervision.domain.converter

import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox

class ImagePlaceholderDuplicateFilter {

    fun filter(
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

    private fun ScaledBox.isImagePlaceholder(): Boolean = DetectionLabels.isImagePlaceholder(label)

    private fun ScaledBox.isLikelyDuplicateOf(annotated: ScaledBox): Boolean {
        val maxDuplicateArea = annotated.area() * MAX_DUPLICATE_AREA_RATIO
        val verticalGap = verticalGapFrom(annotated)
        val hasHorizontalOverlap = x < annotated.x + annotated.w && x + w > annotated.x

        return area() < maxDuplicateArea &&
            hasHorizontalOverlap &&
            verticalGap <= annotated.h / 2
    }

    private fun ScaledBox.verticalGapFrom(other: ScaledBox): Int {
        val thisBoxBottom = y + h
        val otherBoxBottom = other.y + other.h

        val distanceFromOtherBottomToThisTop = y - otherBoxBottom
        val distanceFromThisBottomToOtherTop = other.y - thisBoxBottom

        return when {
            distanceFromOtherBottomToThisTop >= 0 -> distanceFromOtherBottomToThisTop
            distanceFromThisBottomToOtherTop >= 0 -> distanceFromThisBottomToOtherTop
            else -> 0
        }
    }

    private fun ScaledBox.area(): Int {
        return w * h
    }

    private companion object {
        const val MAX_DUPLICATE_AREA_RATIO = 0.35
    }
}
