package org.appdevforall.dependencyanalysis.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCAFFOLD tests for the domain contract. The implement (domain) agent expands
 * [AdviceParser.parse] coverage to the >=90% line+branch gate using the fixture
 * in the ticket research (unused okhttp / undeclared okio / wrong-config guava).
 *
 * These exercise the stable bits: model derived properties and the diff logic.
 */
class AdviceParserTest {

    @Test
    fun emptyResultIsClean() {
        assertTrue(AnalysisResult.EMPTY.isClean)
        assertEquals(0, AnalysisResult.EMPTY.totalAdviceCount)
    }

    @Test
    fun coordinateDisplayIncludesVersionWhenPresent() {
        val withVersion = DependencyCoordinate("com.squareup.okhttp3:okhttp", "4.12.0")
        val withoutVersion = DependencyCoordinate(":app")
        assertEquals("com.squareup.okhttp3:okhttp:4.12.0", withVersion.display)
        assertEquals(":app", withoutVersion.display)
    }

    @Test
    fun resultWithAdviceIsNotClean() {
        val result = AnalysisResult(
            unused = listOf(
                UnusedDependency(DependencyCoordinate("a:b", "1.0"), "implementation")
            )
        )
        assertFalse(result.isClean)
        assertEquals(1, result.totalAdviceCount)
    }

    @Test
    fun diffCountsAppliedAdviceAcrossCategories() {
        val okhttp = UnusedDependency(DependencyCoordinate("com.squareup.okhttp3:okhttp", "4.12.0"), "implementation")
        val okio = UndeclaredTransitive(DependencyCoordinate("com.squareup.okio:okio", "3.6.0"), "implementation")
        val guava = WrongConfiguration(DependencyCoordinate("com.google.guava:guava", "33.0.0-jre"), "api", "implementation")

        val before = AnalysisResult(
            unused = listOf(okhttp),
            undeclaredTransitive = listOf(okio),
            wrongConfiguration = listOf(guava),
        )
        // After fixing everything, the re-analysis is clean.
        val summary = AdviceParser.diff(before, AnalysisResult.EMPTY)

        assertEquals(1, summary.removed)
        assertEquals(1, summary.added)
        assertEquals(1, summary.reconfigured)
        assertEquals(3, summary.totalChanged)
        assertTrue(summary.reanalysis.isClean)
    }

    @Test
    fun diffCountsZeroWhenNothingChanged() {
        val okhttp = UnusedDependency(DependencyCoordinate("a:b", "1.0"), "implementation")
        val before = AnalysisResult(unused = listOf(okhttp))
        val summary = AdviceParser.diff(before, before)
        assertEquals(0, summary.totalChanged)
    }
}
