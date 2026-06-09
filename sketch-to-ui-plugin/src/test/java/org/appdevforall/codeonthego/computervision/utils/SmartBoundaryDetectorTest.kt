package org.appdevforall.codeonthego.computervision.utils

import org.junit.Assert.assertTrue
import org.junit.Test

class SmartBoundaryDetectorTest {

    @Test
    fun `detects boundaries between left metadata canvas and right metadata clusters`() {
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
    fun `adapts to wider sketch with different metadata column widths`() {
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
    fun `adapts when metadata columns are narrow and canvas is wide`() {
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
    fun `does not choose large internal canvas gap before right metadata`() {
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
    fun `falls back to gap based bounds when three clusters cannot be found`() {
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
    fun `regression keeps right boundary out of central canvas for observed proportions`() {
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
