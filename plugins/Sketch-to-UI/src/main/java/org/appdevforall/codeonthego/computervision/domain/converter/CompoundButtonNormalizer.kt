package org.appdevforall.codeonthego.computervision.domain.converter

import org.appdevforall.codeonthego.computervision.domain.WidgetTagParser
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.widgettag.WidgetTagPrefixes
import kotlin.math.abs

class CompoundButtonNormalizer {

    fun normalizeByNearestTag(
        boxes: List<ScaledBox>,
        canvasTags: List<ScaledBox>
    ): List<ScaledBox> {
        val compoundTags = canvasTags.mapNotNull { tagBox ->
            val normalizedTag = WidgetTagParser.normalizeTagText(tagBox.text)

            when {
                normalizedTag.startsWith(WidgetTagPrefixes.CHECKBOX) -> tagBox to CompoundGroupType.CHECKBOX
                normalizedTag.startsWith(WidgetTagPrefixes.RADIO) -> tagBox to CompoundGroupType.RADIO
                else -> null
            }
        }

        if (compoundTags.isEmpty()) {
            return collapseOverlappingCompoundButtons(boxes)
        }

        val normalizedBoxes = boxes.map { box ->
            if (!DetectionLabels.isCompoundButton(box.label)) return@map box

            val nearestTag = compoundTags.minByOrNull { (tagBox, _) ->
                val verticalDistance = abs(tagBox.centerY - box.centerY)
                val horizontalDistance = abs(tagBox.centerX - box.centerX)

                (verticalDistance * VERTICAL_DISTANCE_WEIGHT) + horizontalDistance
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
            if (!DetectionLabels.isCompoundButton(box.label)) {
                result.add(box)
                continue
            }

            val duplicateIndex = result.indexOfFirst { existing ->
                DetectionLabels.isCompoundButton(existing.label) &&
                    existing.label == box.label &&
                    existing.overlapsCompoundDuplicate(box)
            }

            if (duplicateIndex < 0) {
                result.add(box)
                continue
            }

            val existing = result[duplicateIndex]
            if (box.area() > existing.area()) {
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

    private fun ScaledBox.asCheckboxLabel(): String {
        if (DetectionLabels.isChecked(label)) {
            return DetectionLabels.CHECKBOX_CHECKED
        }
        return DetectionLabels.CHECKBOX_UNCHECKED
    }

    private fun ScaledBox.asRadioButtonLabel(): String {
        if (DetectionLabels.isChecked(label)) {
            return DetectionLabels.RADIO_BUTTON_CHECKED
        }
        return DetectionLabels.RADIO_BUTTON_UNCHECKED
    }

    private fun ScaledBox.overlapsCompoundDuplicate(other: ScaledBox): Boolean {
        val intersectionWidth = minOf(x + w, other.x + other.w) - maxOf(x, other.x)
        val intersectionHeight = minOf(y + h, other.y + other.h) - maxOf(y, other.y)

        if (intersectionWidth <= 0 || intersectionHeight <= 0) return false

        val intersectionArea = intersectionWidth * intersectionHeight
        val smallerArea = minOf(area(), other.area()).coerceAtLeast(1)

        return intersectionArea.toFloat() / smallerArea.toFloat() >= COMPOUND_DUPLICATE_OVERLAP_THRESHOLD
    }

    private fun ScaledBox.area(): Int {
        return w * h
    }

    private enum class CompoundGroupType {
        RADIO,
        CHECKBOX
    }

    private companion object {
        const val VERTICAL_DISTANCE_WEIGHT = 2
        const val MAX_COMPOUND_TAG_VERTICAL_DISTANCE = 100
        const val COMPOUND_DUPLICATE_OVERLAP_THRESHOLD = 0.80f
    }
}
