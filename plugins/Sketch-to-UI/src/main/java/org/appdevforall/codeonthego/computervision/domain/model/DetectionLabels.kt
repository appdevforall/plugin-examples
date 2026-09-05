package org.appdevforall.codeonthego.computervision.domain.model

object DetectionLabels {
    const val GENERIC_BOX = "generic_box"
    const val DROPDOWN_SYMBOL = "dropdown_symbol"
    const val IMAGE_PLACEHOLDER = "image_placeholder"
    const val BUTTON = "button"

    const val CHECKBOX_CHECKED = "checkbox_checked"
    const val CHECKBOX_UNCHECKED = "checkbox_unchecked"

    const val RADIO_BUTTON_CHECKED = "radio_button_checked"
    const val RADIO_BUTTON_UNCHECKED = "radio_button_unchecked"

    const val SLIDER = "slider"

    const val SWITCH_OFF = "switch_off"
    const val SWITCH_ON = "switch_on"

    const val WIDGET_TAG = "widget_tag"
    const val TEXT = "text"

    const val DROPDOWN = "dropdown"
    const val TEXT_ENTRY_BOX = "text_entry_box"

    const val CHECKED_SUFFIX = "_checked"
    const val ON_SUFFIX = "_on"

    const val CHECKBOX_PREFIX = "checkbox"
    const val RADIO_BUTTON_PREFIX = "radio_button"
    const val SWITCH_PREFIX = "switch"
    const val DROPDOWN_PREFIX = "dropdown"
    const val SLIDER_PREFIX = "slider"
    const val BUTTON_PREFIX = "button"
    const val TEXT_ENTRY_BOX_PREFIX = "text_entry_box"
    const val IMAGE_PLACEHOLDER_PREFIX = "image_placeholder"
    const val ICON_PREFIX = "icon"

    val checkboxLabels = setOf(
        CHECKBOX_CHECKED,
        CHECKBOX_UNCHECKED
    )

    val radioButtonLabels = setOf(
        RADIO_BUTTON_CHECKED,
        RADIO_BUTTON_UNCHECKED
    )

    val switchLabels = setOf(
        SWITCH_ON,
        SWITCH_OFF
    )

    val compoundButtonLabels = checkboxLabels + radioButtonLabels

    fun isCheckbox(label: String): Boolean {
        return label in checkboxLabels
    }

    fun isRadioButton(label: String): Boolean {
        return label in radioButtonLabels
    }

    fun isSwitch(label: String): Boolean {
        return label in switchLabels
    }

    fun isCompoundButton(label: String): Boolean {
        return label in compoundButtonLabels
    }

    fun isChecked(label: String): Boolean {
        return label.endsWith(CHECKED_SUFFIX) || label.endsWith(ON_SUFFIX)
    }

    fun isImagePlaceholder(label: String): Boolean {
        return label.startsWith(IMAGE_PLACEHOLDER_PREFIX)
    }
}
