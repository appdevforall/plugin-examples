package org.appdevforall.getaimodels.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateTest {

    private fun downloading(soFar: Long, total: Long, phase: Phase = Phase.RUNNING) =
        DownloadState.Downloading(soFar, total, phase)

    @Test
    fun givenPartialProgress_whenTheFractionIsComputed_thenItScalesFromEmptyToFull() {
        assertEquals(0f, downloading(0, 1_000).fraction, 0.0001f)
        assertEquals(0.25f, downloading(250, 1_000).fraction, 0.0001f)
        assertEquals(1f, downloading(1_000, 1_000).fraction, 0.0001f)
    }

    @Test
    fun givenOverlongOrUnknownTotals_whenTheFractionIsComputed_thenItIsClampedWithoutDividingByZero() {
        // DownloadManager can report more bytes than the total it first advertised.
        assertEquals(1f, downloading(2_000, 1_000).fraction, 0.0001f)
        // An unknown total must not produce NaN or Infinity, which would break setLevel().
        assertEquals(0f, downloading(500, 0).fraction, 0.0001f)
        assertEquals(0f, downloading(500, -1).fraction, 0.0001f)
    }

    @Test
    fun givenEachPhase_whenIsPausedIsRead_thenOnlyThePausedPhasesReportTrue() {
        assertFalse(downloading(0, 10, Phase.PENDING).isPaused)
        assertFalse(downloading(5, 10, Phase.RUNNING).isPaused)
        assertTrue(downloading(5, 10, Phase.PAUSED_WAITING_FOR_WIFI).isPaused)
        assertTrue(downloading(5, 10, Phase.PAUSED_WAITING_FOR_NETWORK).isPaused)
        assertTrue(downloading(5, 10, Phase.PAUSED_WAITING_TO_RETRY).isPaused)
        assertTrue(downloading(5, 10, Phase.PAUSED_UNKNOWN).isPaused)
    }

    @Test
    fun givenEachState_whenIsBusyIsRead_thenOnlyInFlightAndVerifyingReportTrue() {
        assertTrue(downloading(1, 10).isBusy)
        assertTrue(DownloadState.Verifying.isBusy)
        assertFalse(DownloadState.Idle.isBusy)
        assertFalse(DownloadState.Verified("/sdcard/Download/m.gguf").isBusy)
        assertFalse(DownloadState.Changed("/sdcard/Download/m.gguf").isBusy)
        assertFalse(DownloadState.Failed("nope", false).isBusy)
    }

    @Test
    fun givenTwoProgressValues_whenComparedForEquality_thenDifferingByteCountsAreUnequal() {
        // submitStates() repaints only on inequality, so equal byte counts would freeze the fill.
        assertTrue(downloading(100, 1_000) != downloading(200, 1_000))
        assertTrue(
            downloading(100, 1_000, Phase.RUNNING) !=
                downloading(100, 1_000, Phase.PAUSED_WAITING_FOR_WIFI)
        )
        assertEquals(downloading(100, 1_000), downloading(100, 1_000))
    }
}
