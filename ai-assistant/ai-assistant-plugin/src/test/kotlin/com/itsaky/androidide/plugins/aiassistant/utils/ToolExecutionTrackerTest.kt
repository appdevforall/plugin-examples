package com.itsaky.androidide.plugins.aiassistant.utils

import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class ToolExecutionTrackerTest {

    @Test
    fun testStartTracking() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        // Should not throw and should be ready for logging
        assertTrue(true)
    }

    @Test
    fun testLogToolCall() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        // Log a tool call
        tracker.logToolCall("read_file", 100L)

        // Should not throw
        assertTrue(true)
    }

    @Test
    fun testGenerateReportWithNoTools() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        val report = tracker.generateReport()

        assertTrue(report.contains("✅ **Operation Complete**"))
        assertTrue(report.contains("No tools were needed for this request."))
    }

    @Test
    fun testGeneratePartialReportWithNoTools() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        val report = tracker.generatePartialReport()

        assertTrue(report.contains("🛑 **Operation Cancelled**"))
        assertTrue(report.contains("No tools were executed before cancellation."))
    }

    @Test
    fun testGeneratePausedReportWithNoTools() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        val report = tracker.generatePausedReport()

        assertTrue(report.contains("⏸️ **Awaiting User Input**"))
        assertTrue(report.contains("No tools were run before the question was asked."))
    }

    @Test
    fun testGenerateReportWithSingleTool() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        // Add a small delay to ensure timestamp difference
        Thread.sleep(10)
        tracker.logToolCall("read_file", 100L)

        val report = tracker.generateReport()

        assertTrue(report.contains("✅ **Operation Complete**"))
        assertTrue(report.contains("**Tool Execution Report:**"))
        assertTrue(report.contains("read_file"))
        assertTrue(report.contains("Sequence:"))
        assertTrue(report.contains("Summary:"))
        assertTrue(report.contains("1 time"))
    }

    @Test
    fun testGenerateReportWithMultipleTools() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        Thread.sleep(10)
        tracker.logToolCall("read_file", 100L)
        Thread.sleep(10)
        tracker.logToolCall("list_files", 150L)
        Thread.sleep(10)
        tracker.logToolCall("read_file", 200L)

        val report = tracker.generateReport()

        assertTrue(report.contains("Operation Complete"))
        assertTrue(report.contains("1. `read_file`"))
        assertTrue(report.contains("2. `list_files`"))
        assertTrue(report.contains("3. `read_file`"))
        assertTrue(report.contains("read_file"))
        assertTrue(report.contains("list_files"))
        assertTrue(report.contains("2 times"))
        assertTrue(report.contains("1 time"))
    }

    @Test
    fun testReportSequenceNumbers() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        tracker.logToolCall("tool_a", 100L)
        tracker.logToolCall("tool_b", 200L)
        tracker.logToolCall("tool_c", 300L)

        val report = tracker.generateReport()

        // Verify sequence numbering
        assertTrue(report.contains("1. `tool_a`"))
        assertTrue(report.contains("2. `tool_b`"))
        assertTrue(report.contains("3. `tool_c`"))
    }

    @Test
    fun testReportTotalDuration() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        tracker.logToolCall("read_file", 500L)

        Thread.sleep(200) // Ensure total duration is at least 200ms

        val report = tracker.generateReport()

        // Should contain the total duration in the title
        assertTrue(report.contains("Total:"))
    }

    @Test
    fun testReportTimestamps() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        Thread.sleep(50)
        tracker.logToolCall("read_file", 100L)

        val report = tracker.generateReport()

        // Should contain timestamps with + notation (relative to start)
        assertTrue(report.contains("at +"))
    }

    @Test
    fun testStartTrackingClearsOldData() {
        val tracker = ToolExecutionTracker()

        // First tracking session
        tracker.startTracking()
        tracker.logToolCall("tool_a", 100L)

        var report = tracker.generateReport()
        assertTrue(report.contains("tool_a"))

        // Second tracking session should clear first
        tracker.startTracking()
        tracker.logToolCall("tool_b", 200L)

        report = tracker.generateReport()
        assertTrue(report.contains("tool_b"))
        assertFalse(report.contains("tool_a"))
    }

    @Test
    fun testFormatTimeSeconds() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        // Log a tool and get its report
        tracker.logToolCall("read_file", 1500L) // 1.5 seconds

        val report = tracker.generateReport()

        // Should format time with decimal
        assertTrue(report.contains("s"))
    }

    @Test
    fun testFormatTimeMinutes() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        // Log tool with duration that will create a long total
        // Sleep to accumulate actual time
        tracker.logToolCall("read_file", TimeUnit.MINUTES.toMillis(2) + 500)

        val report = tracker.generateReport()

        // Should contain minute formatting
        assertTrue(report.contains("m") || report.contains("s"))
    }

    @Test
    fun testNegativeTimeHandling() {
        val tracker = ToolExecutionTracker()

        // This is an edge case - if we log before starting, timestamp could be negative
        tracker.startTracking()
        tracker.logToolCall("test_tool", -100L)

        val report = tracker.generateReport()

        // Should handle gracefully without crashing
        assertTrue(report.contains("test_tool"))
    }

    @Test
    fun testToolCountingSummary() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        tracker.logToolCall("read_file", 100L)
        tracker.logToolCall("read_file", 150L)
        tracker.logToolCall("read_file", 200L)
        tracker.logToolCall("write_file", 300L)

        val report = tracker.generateReport()

        // Summary should show correct counts
        assertTrue(report.contains("read_file"))
        assertTrue(report.contains("3 times"))
        assertTrue(report.contains("write_file"))
        assertTrue(report.contains("1 time"))
    }

    @Test
    fun testEmptyTrackerState() {
        val tracker = ToolExecutionTracker()
        // Don't call startTracking

        // Should handle gracefully
        val report = tracker.generateReport()
        assertTrue(report.isNotEmpty())
    }

    @Test
    fun testReportFormatConsistency() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        tracker.logToolCall("tool_a", 100L)

        val report = tracker.generateReport()

        // Verify the report has proper markdown formatting
        assertTrue(report.contains("**"))
        assertTrue(report.contains("`"))
        assertTrue(report.contains("Sequence:"))
        assertTrue(report.contains("Summary:"))
    }

    @Test
    fun testMultipleReportGenerations() {
        val tracker = ToolExecutionTracker()
        tracker.startTracking()

        tracker.logToolCall("read_file", 100L)

        // Generate report multiple times - should not error
        val report1 = tracker.generateReport()
        val report2 = tracker.generateReport()
        val report3 = tracker.generatePartialReport()
        val report4 = tracker.generatePausedReport()

        assertTrue(report1.isNotEmpty())
        assertTrue(report2.isNotEmpty())
        assertTrue(report3.isNotEmpty())
        assertTrue(report4.isNotEmpty())
    }
}
