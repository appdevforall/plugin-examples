package org.appdevforall.codeonthego.computervision.domain

import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.model.WidgetTypes
import org.appdevforall.codeonthego.computervision.domain.widgettag.WidgetTagPrefixes
import java.util.Collections
import java.util.IdentityHashMap

class WidgetAnnotationMatcher {
    internal fun matchAnnotationsToElements(
        canvasTags: List<ScaledBox>,
        uiElements: List<ScaledBox>,
        annotations: Map<String, String>
    ): Map<ScaledBox, String> {
        val finalAnnotations = IdentityHashMap<ScaledBox, String>()
        val claimedWidgets = Collections.newSetFromMap(IdentityHashMap<ScaledBox, Boolean>())

        val deduplicatedTags = canvasTags
            .distinctBy { WidgetTagParser.normalizeTagText(it.text) }

        val tagsByWidgetType = annotations
            .mapNotNull { (tagText, annotationText) ->
                val normalizedTag = WidgetTagParser.normalizeTagText(tagText)
                val widgetType = getTagType(normalizedTag) ?: return@mapNotNull null

                val matchingTagBox = deduplicatedTags.find { WidgetTagParser.normalizeTagText(it.text) == normalizedTag }

                TaggedAnnotation(
                    normalizedTag = normalizedTag,
                    widgetType = widgetType,
                    annotation = annotationText,
                    tagBox = matchingTagBox
                )
            }
            .groupBy { it.widgetType }

        val widgetsByType = uiElements.groupBy { normalizeWidgetType(it.label) }

        for ((widgetType, taggedAnnotations) in tagsByWidgetType) {
            val candidateWidgets = widgetsByType[widgetType]
                ?.sortedWith(compareBy({ it.y }, { it.x }))
                ?: continue

            val sortedTags = taggedAnnotations.sortedWith(
                compareBy(
                    { WidgetTagParser.extractOrdinal(it.normalizedTag) ?: Int.MAX_VALUE },
                    { it.tagBox?.y ?: Int.MAX_VALUE },
                    { it.tagBox?.x ?: Int.MAX_VALUE }
                )
            )

            for (taggedAnnotation in sortedTags) {
                val ordinal = WidgetTagParser.extractOrdinal(taggedAnnotation.normalizedTag)
                val matchedWidget = findWidgetByOrdinalOrFallback(
                    ordinal = ordinal,
                    tagBox = taggedAnnotation.tagBox,
                    candidates = candidateWidgets,
                    claimedWidgets = claimedWidgets
                ) ?: continue

                finalAnnotations[matchedWidget] = taggedAnnotation.annotation
                claimedWidgets.add(matchedWidget)
            }
        }

        return finalAnnotations
    }

    internal fun isTag(text: String): Boolean = WidgetTagParser.isTag(text)

    internal fun isTagLikeText(text: String): Boolean {
        return WidgetTagParser.isTag(text) || WidgetTagParser.isTagSequence(text)
    }

    private fun getTagType(tag: String): String? {
        return when {
            tag.startsWith(WidgetTagPrefixes.BUTTON) -> WidgetTypes.BUTTON
            tag.startsWith(WidgetTagPrefixes.IMAGE_PLACEHOLDER) -> WidgetTypes.IMAGE_PLACEHOLDER
            tag.startsWith(WidgetTagPrefixes.DROPDOWN) -> WidgetTypes.DROPDOWN
            tag.startsWith(WidgetTagPrefixes.TEXT_ENTRY) -> WidgetTypes.TEXT_ENTRY_BOX
            tag.startsWith(WidgetTagPrefixes.CHECKBOX) -> WidgetTypes.CHECKBOX
            tag.startsWith(WidgetTagPrefixes.RADIO) -> WidgetTypes.RADIO
            tag.startsWith(WidgetTagPrefixes.SWITCH) -> WidgetTypes.SWITCH
            tag.startsWith(WidgetTagPrefixes.SLIDER) -> WidgetTypes.SLIDER
            else -> null
        }
    }

    private fun normalizeWidgetType(label: String): String {
        return when {
            label.startsWith(DetectionLabels.TEXT_ENTRY_BOX_PREFIX) -> WidgetTypes.TEXT_ENTRY_BOX
            label.startsWith(DetectionLabels.BUTTON_PREFIX) -> WidgetTypes.BUTTON
            label.startsWith(DetectionLabels.SWITCH_PREFIX) -> WidgetTypes.SWITCH
            label.startsWith(DetectionLabels.CHECKBOX_PREFIX) -> WidgetTypes.CHECKBOX
            label.startsWith(DetectionLabels.RADIO_BUTTON_PREFIX) -> WidgetTypes.RADIO
            label.startsWith(DetectionLabels.DROPDOWN_PREFIX) -> WidgetTypes.DROPDOWN
            label.startsWith(DetectionLabels.SLIDER_PREFIX) -> WidgetTypes.SLIDER
            label.startsWith(DetectionLabels.IMAGE_PLACEHOLDER_PREFIX) ||
                label.startsWith(DetectionLabels.ICON_PREFIX) -> WidgetTypes.IMAGE_PLACEHOLDER
            else -> label
        }
    }

    private fun findWidgetByOrdinalOrFallback(
        ordinal: Int?,
        tagBox: ScaledBox?,
        candidates: List<ScaledBox>,
        claimedWidgets: Set<ScaledBox>
    ): ScaledBox? {
        val available = candidates.filter { it !in claimedWidgets }
        if (available.isEmpty()) return null

        if (ordinal != null) {
            val oneBasedMatch = candidates.getOrNull(ordinal - 1)
            if (oneBasedMatch != null && oneBasedMatch !in claimedWidgets) {
                return oneBasedMatch
            }

            val zeroBasedMatch = candidates.getOrNull(ordinal)
            if (zeroBasedMatch != null && zeroBasedMatch !in claimedWidgets) {
                return zeroBasedMatch
            }
        }

        if (tagBox != null) {
            return available.minByOrNull { candidate ->
                val verticalDistance = kotlin.math.abs(tagBox.centerY - candidate.centerY)
                val horizontalDistance = kotlin.math.abs(tagBox.centerX - candidate.centerX)
                (verticalDistance * 2) + horizontalDistance
            }
        }

        return available.minByOrNull { it.y }
    }

    private data class TaggedAnnotation(
        val normalizedTag: String,
        val widgetType: String,
        val annotation: String,
        val tagBox: ScaledBox?
    )
}
