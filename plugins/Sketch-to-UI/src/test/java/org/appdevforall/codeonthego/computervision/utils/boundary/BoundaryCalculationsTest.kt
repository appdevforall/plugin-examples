package org.appdevforall.codeonthego.computervision.utils.boundary

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BoundaryCalculationsTest {

    @Test
    fun `Given_projection_and_radius_When_smoothed_Then_edge_windows_use_available_columns`() {
        val projection = floatArrayOf(0f, 3f, 6f)

        val result = ProjectionSmoother.smooth(projection, radius = 1)

        assertArrayEquals(floatArrayOf(1.5f, 3f, 4.5f), result, 0.0001f)
    }

    @Test
    fun `Given_radius_larger_than_projection_When_smoothed_Then_each_value_uses_full_projection_average`() {
        val projection = floatArrayOf(0f, 3f, 6f)

        val result = ProjectionSmoother.smooth(projection, radius = 10)

        assertArrayEquals(floatArrayOf(3f, 3f, 3f), result, 0.0001f)
    }

    @Test
    fun `Given_zero_radius_When_smoothed_Then_original_projection_is_returned`() {
        val projection = floatArrayOf(1f, 2f, 3f)

        val result = ProjectionSmoother.smooth(projection, radius = 0)

        assertSame(projection, result)
    }

    @Test
    fun `Given_inclusive_gap_When_width_and_midpoint_are_read_Then_values_include_both_edges`() {
        val gap = GapCandidate(start = 10, end = 20)

        assertEquals(11, gap.width)
        assertEquals(15, gap.midpoint)
    }

    @Test
    fun `Given_image_width_When_fallback_bounds_are_calculated_Then_configured_percentages_are_applied`() {
        val result = BoundaryDetectionConfig.fallbackBounds(width = 1000)

        assertEquals(150 to 850, result)
    }

    @Test
    fun `Given_low_activity_gap_in_each_half_When_gap_bounds_are_detected_Then_exact_midpoints_are_returned`() {
        val projection = FloatArray(100) { 10f }.apply {
            for (index in 20..29) this[index] = 0f
            for (index in 70..79) this[index] = 0f
        }

        val result = GapBasedBoundaryDetector.detect(
            projection = projection,
            width = 100,
            ignoredEdgePixels = 5,
            leftZoneEnd = 50,
            rightZoneStart = 50,
            rightZoneEnd = 95
        )

        assertEquals(25 to 75, result)
    }
}
