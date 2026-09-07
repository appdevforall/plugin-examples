package org.appdevforall.codeonthego.computervision.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.appdevforall.codeonthego.computervision.domain.parser.FuzzyAttributeParser

class MetadataAnnotationRecoveryTest {

    @Test
    fun `Given_consistent_same_prefix_dimensions_When_recovered_Then_unreliable_sibling_uses_fallbacks`() {
        val recovered = MetadataAnnotationRecovery.resolve(
            mapOf(
                "T-1" to "layout_width: 200dp | layout_height: 52dp",
                "T-2" to "layoutwidth | layoutheight30"
            )
        ).getValue("T-2")

        assertTrue(recovered.contains("layout_width: 200dp"))
        assertTrue(recovered.contains("layout_height: 52dp"))
    }

    @Test
    fun `Given_dimension_evidence_from_another_prefix_When_recovered_Then_dimension_is_not_shared`() {
        val recovered = MetadataAnnotationRecovery.resolve(
            mapOf(
                "B-1" to "layout_width: 200dp",
                "T-1" to "layoutheight30"
            )
        ).getValue("T-1")

        assertFalse(recovered.contains("layout_width: 200dp"))
    }

    @Test
    fun `Given_repeated_same_block_ID_evidence_When_recovered_Then_strongest_clean_ID_is_prepended`() {
        val recovered = MetadataAnnotationRecovery.resolve(
            mapOf("SW-1" to "id:remenber | idi remember | idiremember")
        ).getValue("SW-1")

        assertTrue(recovered.startsWith("id: remember |"))
    }

    @Test
    fun `Given_password_like_EditText_metadata_When_recovered_Then_password_input_type_is_added`() {
        val recovered = MetadataAnnotationRecovery.resolve(
            mapOf("T-1" to "layout_height: 52dp | vextPassword")
        ).getValue("T-1")

        assertEquals("textPassword", FuzzyAttributeParser.parse(recovered, "EditText")["android:inputType"])
    }
}
