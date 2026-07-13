package org.appdevforall.codeonthego.computervision.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionLabelsTest {

    @Test
    fun `Given_checkbox_or_radio_label_When_checking_compound_button_Then_returns_true`() {
        assertTrue(DetectionLabels.isCompoundButton(DetectionLabels.CHECKBOX_CHECKED))
        assertTrue(DetectionLabels.isCompoundButton(DetectionLabels.CHECKBOX_UNCHECKED))
        assertTrue(DetectionLabels.isCompoundButton(DetectionLabels.RADIO_BUTTON_CHECKED))
        assertTrue(DetectionLabels.isCompoundButton(DetectionLabels.RADIO_BUTTON_UNCHECKED))
    }

    @Test
    fun `Given_switch_label_When_checking_compound_button_Then_returns_false`() {
        assertFalse(DetectionLabels.isCompoundButton(DetectionLabels.SWITCH_ON))
        assertFalse(DetectionLabels.isCompoundButton(DetectionLabels.SWITCH_OFF))
    }

    @Test
    fun `Given_checkable_labels_When_checking_state_Then_checked_state_is_detected`() {
        assertTrue(DetectionLabels.isChecked(DetectionLabels.CHECKBOX_CHECKED))
        assertTrue(DetectionLabels.isChecked(DetectionLabels.RADIO_BUTTON_CHECKED))
        assertTrue(DetectionLabels.isChecked(DetectionLabels.SWITCH_ON))

        assertFalse(DetectionLabels.isChecked(DetectionLabels.CHECKBOX_UNCHECKED))
        assertFalse(DetectionLabels.isChecked(DetectionLabels.RADIO_BUTTON_UNCHECKED))
        assertFalse(DetectionLabels.isChecked(DetectionLabels.SWITCH_OFF))
    }

    @Test
    fun `Given_image_placeholder_label_When_checked_Then_returns_true`() {
        assertTrue(DetectionLabels.isImagePlaceholder(DetectionLabels.IMAGE_PLACEHOLDER))
        assertTrue(DetectionLabels.isImagePlaceholder("image_placeholder_extra"))
        assertFalse(DetectionLabels.isImagePlaceholder(DetectionLabels.BUTTON))
    }
}
