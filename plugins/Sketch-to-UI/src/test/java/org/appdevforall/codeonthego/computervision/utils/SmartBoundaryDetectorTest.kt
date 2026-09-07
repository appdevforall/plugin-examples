package org.appdevforall.codeonthego.computervision.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class SmartBoundaryDetectorTest {

    @Test
    fun `Given_three_projection_clusters_When_boundaries_are_detected_Then_metadata_and_canvas_are_separated`() {
        val projection = projection(
            width = 600,
            30..120 to 10f,
            180..360 to 12f,
            430..570 to 10f
        )

        val (left, right) = SmartBoundaryDetector.detectSmartBoundariesFromProjection(projection, imageWidth = 600)

        assertInRange(left, 145, 160)
        assertInRange(right, 390, 405)
    }

    @Test
    fun `Given_wider_sketch_with_different_metadata_widths_When_boundaries_are_detected_Then_bounds_adapt`() {
        val projection = projection(
            width = 1200,
            40..245 to 9f,
            360..815 to 13f,
            930..1135 to 9f
        )

        val (left, right) = SmartBoundaryDetector.detectSmartBoundariesFromProjection(projection, imageWidth = 1200)

        assertInRange(left, 295, 305)
        assertInRange(right, 870, 880)
    }

    @Test
    fun `Given_narrow_metadata_and_wide_canvas_When_boundaries_are_detected_Then_bounds_adapt`() {
        val projection = projection(
            width = 900,
            35..95 to 8f,
            165..720 to 12f,
            790..850 to 8f
        )

        val (left, right) = SmartBoundaryDetector.detectSmartBoundariesFromProjection(projection, imageWidth = 900)

        assertInRange(left, 125, 135)
        assertInRange(right, 755, 765)
    }

    @Test
    fun `Given_large_internal_canvas_gap_When_boundaries_are_detected_Then_right_metadata_separator_is_selected`() {
        val projection = projection(
            width = 700,
            35..130 to 10f,
            190..320 to 12f,
            390..440 to 12f,
            520..665 to 10f
        )

        val (left, right) = SmartBoundaryDetector.detectSmartBoundariesFromProjection(projection, imageWidth = 700)

        assertInRange(left, 155, 170)
        assertInRange(right, 475, 490)
        assertTrue("right boundary must not be placed in the internal canvas gap", right > 460)
    }

    @Test
    fun `Given_projection_without_three_clusters_When_boundaries_are_detected_Then_gap_based_bounds_are_used`() {
        val projection = projection(
            width = 600,
            40..150 to 10f,
            230..430 to 12f
        )

        val (left, right) = SmartBoundaryDetector.detectSmartBoundariesFromProjection(projection, imageWidth = 600)

        assertInRange(left, 151, 230)
        assertInRange(right, 430, 570)
    }

    @Test
    fun `Given_observed_projection_proportions_When_boundaries_are_detected_Then_right_boundary_stays_outside_canvas`() {
        val projection = projection(
            width = 785,
            29..155 to 10f,
            212..380 to 12f,
            430..483 to 12f,
            542..774 to 10f
        )

        val (left, right) = SmartBoundaryDetector.detectSmartBoundariesFromProjection(projection, imageWidth = 785)

        assertInRange(left, 180, 205)
        assertInRange(right, 500, 540)
        assertTrue("right boundary must not be near the old incorrect 444px result", right > 480)
    }

    private fun projection(width: Int, vararg spans: Pair<IntRange, Float>): FloatArray {
        return FloatArray(width).also { values ->
            spans.forEach { (range, value) ->
                range.forEach { x ->
                    if (x in values.indices) values[x] = value
                }
            }
        }
    }

    private fun assertInRange(actual: Int, start: Int, end: Int) {
        assertTrue("Expected $actual to be in $start..$end", actual in start..end)
    }
}
