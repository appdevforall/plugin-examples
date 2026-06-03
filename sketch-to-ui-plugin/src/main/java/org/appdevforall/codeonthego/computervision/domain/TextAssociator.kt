package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.utils.TextCleaner.cleanTextPreservingLeadingO
import org.appdevforall.codeonthego.computervision.utils.TextCleaner.cleanTextStrippingLeadingO
import kotlin.math.abs
import kotlin.math.max

/**
 * Applies spatial proximity and intersection heuristics to associate
 * loose text blocks (OCR) with their corresponding visual widget (YOLO).
 */
object TextAssociator {
    private const val OVERLAP_THRESHOLD = 0.6

    fun assignTextToParents(parents: List<ScaledBox>, texts: List<ScaledBox>, allBoxes: List<ScaledBox>): List<ScaledBox> {
        val consumedTexts = mutableListOf<ScaledBox>()
        val updatedParents = mutableListOf<Pair<ScaledBox, ScaledBox>>()

        for (parent in parents) {
            texts.firstOrNull { text ->
                !consumedTexts.any { it.sameGeometryAs(text) } &&
                    textOverlapRatio(parent, text) > OVERLAP_THRESHOLD
            }?.let {
                updatedParents.add(parent to parent.copy(text = it.text))
                consumedTexts.add(it)
            }
        }

        return allBoxes.mapNotNull { box ->
            when {
                consumedTexts.any { it.sameGeometryAs(box) } -> null
                else -> updatedParents.firstOrNull { it.first.sameGeometryAs(box) }?.second ?: box
            }
        }
    }

    fun assignNearbyTextToWidgets(boxes: List<ScaledBox>, availableTexts: List<ScaledBox>): List<ScaledBox> {
        val consumedTexts = mutableListOf<ScaledBox>()
        val updatedWidgets = mutableListOf<Pair<ScaledBox, ScaledBox>>()

        val labelableWidgets = boxes.filter { isLabelableWidget(it) }.sortedWith(compareBy({ it.y }, { it.x }))

        for (widget in labelableWidgets) {
            val nearbyText = availableTexts
                .asSequence()
                .filter { candidate -> consumedTexts.none { it.sameGeometryAs(candidate) } }
                .filter { text -> widget.isVerticallyAlignedWith(text, tolerance = max(widget.h * 2.5, 40.0)) }
                .minByOrNull { text -> widget.calculateProximityScoreTo(text) }

            if (nearbyText != null) {
                val labelFragments = collectLabelFragments(widget, nearbyText, availableTexts, consumedTexts)
                val finalText = cleanWidgetText(widget, labelFragments.joinToString(" ") { it.text })
                updatedWidgets.add(widget to widget.copy(text = finalText))
                consumedTexts.addAll(labelFragments)
            }
        }

        return boxes.mapNotNull { box ->
            when {
                consumedTexts.any { it.sameGeometryAs(box) } -> null
                else -> updatedWidgets.firstOrNull { it.first.sameGeometryAs(box) }?.second ?: box
            }
        }
    }

    private fun isLabelableWidget(box: ScaledBox): Boolean {
        return box.label in setOf(
            "radio_button_unchecked", "radio_button_checked",
            "checkbox_unchecked", "checkbox_checked",
            "switch_on", "switch_off"
        )
    }

    private fun cleanWidgetText(widget: ScaledBox, rawText: String): String {
        return if (widget.label.contains("radio", ignoreCase = true)) {
            cleanTextStrippingLeadingO(rawText)
        } else {
            cleanTextPreservingLeadingO(rawText)
        }
    }

    private fun collectLabelFragments(
        widget: ScaledBox,
        anchor: ScaledBox,
        availableTexts: List<ScaledBox>,
        consumedTexts: List<ScaledBox>
    ): List<ScaledBox> {
        val lineTolerance = max(anchor.h, widget.h).coerceAtLeast(12)
        val maxGap = max(widget.h * 2, 28)

        val fragments = mutableListOf(anchor)
        var previous = anchor

        while (true) {
            val next = availableTexts
                .asSequence()
                .filter { candidate -> consumedTexts.none { it.sameGeometryAs(candidate) } }
                .filter { candidate -> fragments.none { it.sameGeometryAs(candidate) } }
                .filter { abs(it.centerY - anchor.centerY) <= lineTolerance }
                .filter { it.x >= previous.x + previous.w }
                .filter { it.x - (previous.x + previous.w) <= maxGap }
                .minByOrNull { it.x }
                ?: break

            fragments.add(next)
            previous = next
        }

        return fragments.sortedBy { it.x }
    }

    private fun ScaledBox.isVerticallyAlignedWith(other: ScaledBox, tolerance: Double): Boolean {
        return abs(this.centerY - other.centerY) < tolerance
    }

    private fun ScaledBox.calculateProximityScoreTo(other: ScaledBox): Double {
        val dx = this.horizontalDistanceTo(other).toDouble()
        val dy = abs(this.centerY - other.centerY).toDouble()
        return (dx * dx) + (dy * dy * 5)
    }

    private fun textOverlapRatio(parent: ScaledBox, text: ScaledBox): Float {
        val intersectionWidth = minOf(parent.x + parent.w, text.x + text.w) - maxOf(parent.x, text.x)
        val intersectionHeight = minOf(parent.y + parent.h, text.y + text.h) - maxOf(parent.y, text.y)
        if (intersectionWidth <= 0 || intersectionHeight <= 0) return 0f

        val textArea = text.w * text.h
        if (textArea <= 0) return 0f

        return (intersectionWidth * intersectionHeight).toFloat() / textArea.toFloat()
    }

    private fun ScaledBox.horizontalDistanceTo(other: ScaledBox): Int = when {
        this.x + this.w < other.x -> other.x - (this.x + this.w)
        this.x > other.x + other.w -> this.x - (other.x + other.w)
        else -> 0
    }

    private fun ScaledBox.sameGeometryAs(other: ScaledBox): Boolean {
        return label == other.label &&
            text == other.text &&
            x == other.x &&
            y == other.y &&
            w == other.w &&
            h == other.h
    }
}
