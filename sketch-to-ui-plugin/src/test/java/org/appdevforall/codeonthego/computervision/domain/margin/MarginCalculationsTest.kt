package org.appdevforall.codeonthego.computervision.domain.margin

import android.graphics.RectF
import org.appdevforall.codeonthego.computervision.domain.model.DetectionResult
import org.appdevforall.codeonthego.computervision.domain.model.MetadataOcrSource
import org.junit.Assert.assertEquals
import org.junit.Test

class MarginCalculationsTest {

    @Test
    fun `Given_detection_box_When_centers_are_calculated_Then_midpoints_are_returned`() {
        val detection = detection("text", 10f, 20f, 30f, 60f)

        assertEquals(20f, detection.centerX(), 0.0001f)
        assertEquals(40f, detection.centerY(), 0.0001f)
    }

    @Test
    fun `Given_unclassified_detections_When_distributed_Then_percentage_guides_define_regions`() {
        val left = detection("text", 50f, 0f, 100f, 20f)
        val canvas = detection("button", 400f, 0f, 500f, 20f)
        val right = detection("text", 900f, 0f, 950f, 20f)

        val result = MarginDetectionDistributor.distribute(
            detections = listOf(left, canvas, right),
            imageWidth = 1000,
            leftGuidePct = 0.20f,
            rightGuidePct = 0.80f
        )

        assertEquals(listOf(left), result.leftMargin)
        assertEquals(listOf(canvas), result.canvas)
        assertEquals(listOf(right), result.rightMargin)
    }

    @Test
    fun `Given_implicit_blocks_and_canvas_tags_When_resolved_Then_nearest_unresolved_prefix_is_selected`() {
        val tags = listOf(
            "B-1" to detection("text", 400f, 90f, 440f, 110f),
            "T-1" to detection("text", 400f, 290f, 440f, 310f)
        )
        val blocks = listOf(
            ParsedBlock("button annotation", centerY = 105f),
            ParsedBlock("input annotation", centerY = 295f)
        )

        val result = ImplicitAnnotationResolver.resolve(blocks, tags, existingAnnotations = emptyMap())

        assertEquals("button annotation", result["B-1"])
        assertEquals("input annotation", result["T-1"])
    }

    @Test
    fun `Given_existing_annotation_When_implicit_block_is_resolved_Then_existing_tag_is_skipped`() {
        val tags = listOf(
            "B-1" to detection("text", 400f, 90f, 440f, 110f),
            "T-1" to detection("text", 400f, 290f, 440f, 310f)
        )

        val result = ImplicitAnnotationResolver.resolve(
            implicitBlocks = listOf(ParsedBlock("remaining annotation", centerY = 105f)),
            canvasTags = tags,
            existingAnnotations = mapOf("B-1" to "explicit")
        )

        assertEquals(mapOf("T-1" to "remaining annotation"), result)
    }

    @Test
    fun `Given_equal_priority_dimension_fragments_When_merged_Then_fragment_with_less_OCR_noise_is_selected`() {
        val noisy = SourcedBlocks(
            source = MetadataOcrSource.MARGIN_CROP,
            blocks = GroupedBlocks(explicitAnnotations = mutableMapOf("B-1" to "layoutwidth: 52Odp"))
        )
        val clean = SourcedBlocks(
            source = MetadataOcrSource.MARGIN_CROP,
            blocks = GroupedBlocks(explicitAnnotations = mutableMapOf("B-1" to "layoutwidth: 520dp"))
        )

        val result = ExplicitAnnotationMerger.merge(listOf(noisy, clean))

        assertEquals("layoutwidth: 520dp", result["B-1"])
    }

    private fun detection(
        label: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): DetectionResult {
        return DetectionResult(
            boundingBox = rect(left, top, right, bottom),
            label = label,
            score = 0.99f
        )
    }

    private fun rect(left: Float, top: Float, right: Float, bottom: Float): RectF {
        return RectF().apply {
            this.left = left
            this.top = top
            this.right = right
            this.bottom = bottom
        }
    }
}
