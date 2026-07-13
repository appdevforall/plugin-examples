package org.appdevforall.codeonthego.computervision.domain.xml

import org.appdevforall.codeonthego.computervision.domain.model.LayoutItem
import org.appdevforall.codeonthego.computervision.domain.model.ScaledBox
import org.appdevforall.codeonthego.computervision.domain.model.DetectionLabels
import org.appdevforall.codeonthego.computervision.domain.parser.AttributeKey
import org.appdevforall.codeonthego.computervision.domain.parser.FuzzyAttributeParser
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.IdPatterns
import org.appdevforall.codeonthego.computervision.domain.parser.patterns.ResourceNamePatterns

class WidgetFactory(
    private val context: XmlContext,
    private val annotations: Map<ScaledBox, String>,
    private val selectedImageOverrides: Map<ScaledBox, String> = emptyMap()
) {
    private val radioChildGroupIdPatterns = listOf(
        IdPatterns.RADIO_BUTTON_GROUP_CHILD_ID,
        IdPatterns.RADIO_GROUP_CHILD_ID
    )

    fun createWidgets(item: LayoutItem): List<AndroidWidget> = when (item) {
        is LayoutItem.SimpleView -> createWidgetsForBox(item.box)
        is LayoutItem.HorizontalRow -> createHorizontalRow(item)
        is LayoutItem.RadioGroup -> createRadioGroup(item)
        is LayoutItem.CheckboxGroup -> createCheckboxGroup(item)
    }

    private fun createHorizontalRow(item: LayoutItem.HorizontalRow): List<AndroidWidget> {
        val children = item.row.flatMapIndexed { index, box ->
            val extraAttrs = getMarginEndForHorizontalGap(item.row, index)
            createWidgetsForBox(box, extraAttrs = extraAttrs)
        }
        return listOf(HorizontalRowWidget(children))
    }

    private fun createWidgetsForBox(
        box: ScaledBox,
        extraAttrs: Map<String, String> = emptyMap(),
        idOverride: String? = null,
        parsedAttrsOverride: Map<String, String>? = null
    ): List<AndroidWidget> {
        val widgets = mutableListOf<AndroidWidget>()

        val dropdownTitle = box.text
            .takeIf { box.label == DetectionLabels.DROPDOWN }
            ?.let(::extractDropdownTitle)

        if (dropdownTitle != null) {
            val titleBox = box.copy(label = DetectionLabels.TEXT, text = dropdownTitle)

            val titleAttrs = mapOf(
                AttributeKey.WIDTH.xmlName to AndroidConstants.WRAP_CONTENT,
                AttributeKey.HEIGHT.xmlName to AndroidConstants.WRAP_CONTENT,
                AttributeKey.LAYOUT_MARGIN_BOTTOM.xmlName to "4dp",
                AttributeKey.TEXT.xmlName to dropdownTitle,
                AttributeKey.TEXT_STYLE.xmlName to "bold"
            )
            widgets.add(createSimpleWidget(titleBox, parsedAttrsOverride = titleAttrs))

            val baseParsedAttrs = parsedAttrsOverride?.toMutableMap()
                ?: FuzzyAttributeParser.parse(annotations[box], AndroidWidget.getTagFor(box.label)).toMutableMap()
            baseParsedAttrs.remove(AttributeKey.TEXT.xmlName)

            val spinnerBox = box.copy(text = "")
            widgets.add(createSimpleWidget(spinnerBox, extraAttrs, idOverride, baseParsedAttrs))

            return widgets
        }

        widgets.add(createSimpleWidget(box, extraAttrs, idOverride, parsedAttrsOverride))
        return widgets
    }

    private fun createRadioGroup(item: LayoutItem.RadioGroup): List<AndroidWidget> {
        val groupAnnotation = item.boxes.firstNotNullOfOrNull { annotations[it] }
        val fullGroupAttrs = FuzzyAttributeParser.parse(groupAnnotation, AndroidWidgetTags.RADIO_GROUP)

        val groupId = resolveRadioGroupId(fullGroupAttrs[AttributeKey.ID.xmlName]?.substringAfterLast('/'))

        val groupStructuralAttrs = setOf(
            AttributeKey.ID.xmlName,
            AttributeKey.WIDTH.xmlName,
            AttributeKey.HEIGHT.xmlName,
            AttributeKey.ORIENTATION.xmlName
        )
        // Group metadata can contain style attributes; visible option labels must stay with each RadioButton's OCR text.
        val sharedAttrs = fullGroupAttrs.filterKeys {
            it !in groupStructuralAttrs && it != AttributeKey.TEXT.xmlName
        }

        var checkedId: String? = null

        val children = item.boxes.mapIndexed { index, box ->
            val childAnnotation = annotations[box]
            val childAttrs = FuzzyAttributeParser.parse(childAnnotation, AndroidWidgetTags.RADIO_BUTTON)
                .filterKeys { key -> childAnnotation != groupAnnotation || key !in groupStructuralAttrs }
            val parsedAttrs = (sharedAttrs + childAttrs).toMutableMap()
            if (box.text.isNotBlank()) {
                parsedAttrs.remove(AttributeKey.TEXT.xmlName)
            }

            if (parsedAttrs[AttributeKey.ID.xmlName] == fullGroupAttrs[AttributeKey.ID.xmlName]) {
                parsedAttrs.remove(AttributeKey.ID.xmlName)
            }

            val requestedId = parsedAttrs[AttributeKey.ID.xmlName]?.substringAfterLast('/')
            val childId = if (requestedId != null && radioChildGroupIdPatterns.any { it.matches(requestedId) }) {
                context.nextId(AndroidIdLabels.RADIO_BUTTON)
            } else {
                context.resolveId(requestedId, AndroidIdLabels.RADIO_BUTTON)
            }

            val isChecked = box.label == DetectionLabels.RADIO_BUTTON_CHECKED ||
                parsedAttrs[AttributeKey.CHECKED.xmlName]?.equals(AndroidConstants.TRUE, ignoreCase = true) == true
            if (isChecked) {
                checkedId = childId
                parsedAttrs[AttributeKey.CHECKED.xmlName] = AndroidConstants.TRUE
            } else {
                parsedAttrs[AttributeKey.CHECKED.xmlName] = AndroidConstants.FALSE
            }

            val extraAttrs = if (item.orientation == AndroidConstants.ORIENTATION_HORIZONTAL) {
                getMarginEndForHorizontalGap(item.boxes, index)
            } else emptyMap()

            createSimpleWidget(box, parsedAttrsOverride = parsedAttrs, idOverride = childId, extraAttrs = extraAttrs)
        }

        val textStyleAttrs = setOf(
            AttributeKey.TEXT_COLOR.xmlName,
            AttributeKey.TEXT_SIZE.xmlName,
            AttributeKey.TEXT_STYLE.xmlName,
            AttributeKey.FONT_FAMILY.xmlName
        )
        val groupFinalAttrs = fullGroupAttrs.filterKeys { it !in textStyleAttrs }.toMutableMap()
        groupFinalAttrs[AttributeKey.ID.xmlName] = groupId

        return listOf(RadioGroupWidget(groupFinalAttrs, children, item.orientation, checkedId))
    }

    private fun createCheckboxGroup(item: LayoutItem.CheckboxGroup): List<AndroidWidget> {
        val groupAnnotation = item.boxes.firstNotNullOfOrNull { annotations[it] }
        val parsedAttrs = FuzzyAttributeParser.parse(groupAnnotation, AndroidWidgetTags.CHECK_BOX)

        val requestedId = parsedAttrs[AttributeKey.ID.xmlName]?.substringAfterLast('/')
        val baseId = resolveCheckboxGroupId(requestedId)

        return item.boxes.mapIndexed { index, box ->
            val suffix = ('a' + index).toString()
            val childId = "${baseId}_$suffix"

            val safeAttrs = parsedAttrs.toMutableMap()
            safeAttrs.remove(AttributeKey.ID.xmlName)

            val extraAttrs = if (item.orientation == AndroidConstants.ORIENTATION_HORIZONTAL) {
                getMarginEndForHorizontalGap(item.boxes, index)
            } else emptyMap()

            createSimpleWidget(box, parsedAttrsOverride = safeAttrs, idOverride = childId, extraAttrs = extraAttrs)
        }
    }

    private fun createSimpleWidget(
        box: ScaledBox,
        extraAttrs: Map<String, String> = emptyMap(),
        idOverride: String? = null,
        parsedAttrsOverride: Map<String, String>? = null
    ): AndroidWidget {
        val tag = AndroidWidget.getTagFor(box.label)
        val rawAnnotation = annotations[box]
        val parsedAttrs = parsedAttrsOverride?.toMutableMap()
            ?: FuzzyAttributeParser.parse(rawAnnotation, tag).toMutableMap()

        selectedImageOverrides[box]?.let { drawableReference ->
            parsedAttrs[AttributeKey.SRC.xmlName] = drawableReference
        }

        return AndroidWidget.create(box, parsedAttrs).apply {
            this.idOverride = idOverride
            this.extraAttrs = extraAttrs
        }
    }

    private fun extractDropdownTitle(rawText: String): String? {
        val cleaned = rawText.trim()
            .replace(Regex("\\s*[▼▽▾▿⌄˅∨]$|\\s+[vV]$"), "")
            .replace(Regex("^[vV]\\s+"), "")
            .trim()

        return cleaned.takeIf { it.isNotBlank() && !it.equals(DetectionLabels.DROPDOWN, ignoreCase = true) }
    }

    /** Calculates the non-negative horizontal gap to the next box as an end margin. */
    private fun getMarginEndForHorizontalGap(boxes: List<ScaledBox>, currentIndex: Int): Map<String, String> {
        if (currentIndex >= boxes.lastIndex) return emptyMap()
        val currentBox = boxes[currentIndex]
        val nextBox = boxes[currentIndex + 1]
        val gap = maxOf(0, nextBox.x - (currentBox.x + currentBox.w))
        return mapOf(AttributeKey.LAYOUT_MARGIN_END.xmlName to "${gap}dp")
    }

    private fun resolveRadioGroupId(requestedId: String?): String {
        var cleanId = requestedId
        if (requestedId != null) {
            val normalizedId = requestedId.lowercase()
            when {
                normalizedId.startsWith("radio_grou") -> cleanId = AndroidIdLabels.RADIO_GROUP
                normalizedId.startsWith("rb_grou") || normalizedId.startsWith(AndroidIdLabels.RADIO_BUTTON_GROUP) ->
                    cleanId = AndroidIdLabels.RADIO_BUTTON_GROUP
            }
        }
        return context.resolveId(cleanId, AndroidIdLabels.RADIO_GROUP)
    }

    private fun resolveCheckboxGroupId(requestedId: String?): String {
        if (requestedId == null) return context.nextId(AndroidIdLabels.CHECKBOX_GROUP, initialIndex = 1)
        if (IdPatterns.CHECKBOX_GROUP_ID.matches(requestedId)) {
            return context.resolveId(requestedId, AndroidIdLabels.CHECKBOX_GROUP)
        }

        val compactId = requestedId.lowercase().replace(ResourceNamePatterns.NON_ALPHANUMERIC_LOWER, "")
        val recoveredSuffix = IdPatterns.COMPACT_CHECKBOX_GROUP_ID.matchEntire(compactId)?.groupValues?.getOrNull(1)
        val cleanId = recoveredSuffix?.let { "${AndroidIdLabels.CHECKBOX_GROUP}_$it" }

        return if (cleanId != null) {
            context.resolveId(cleanId, AndroidIdLabels.CHECKBOX_GROUP)
        } else {
            context.nextId(AndroidIdLabels.CHECKBOX_GROUP, initialIndex = 1)
        }
    }
}
