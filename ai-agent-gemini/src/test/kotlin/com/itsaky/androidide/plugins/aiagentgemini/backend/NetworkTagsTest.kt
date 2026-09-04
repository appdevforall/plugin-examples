package com.itsaky.androidide.plugins.aiagentgemini.backend

import android.net.TrafficStats
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * [TrafficStats] is stubbed rather than letting `isReturnDefaultValues` mock every Android API,
 * which would silently change behaviour for the rest of the suite.
 */
class NetworkTagsTest {

    private var currentTag = UNTAGGED

    @Before
    fun setup() {
        mockkStatic(TrafficStats::class)
        every { TrafficStats.getThreadStatsTag() } answers { currentTag }
        every { TrafficStats.setThreadStatsTag(any()) } answers { currentTag = firstArg() }
        every { TrafficStats.clearThreadStatsTag() } answers { currentTag = UNTAGGED }
    }

    @After
    fun tearDown() {
        unmockkStatic(TrafficStats::class)
    }

    @Test
    fun givenATag_whenRunningABlock_thenTheTagIsSetForTheBlockAndItsValueReturned() {
        var tagDuringBlock = UNTAGGED

        val result = withTrafficTag(NetworkTags.INFERENCE) {
            tagDuringBlock = TrafficStats.getThreadStatsTag()
            "done"
        }

        assertEquals(NetworkTags.INFERENCE, tagDuringBlock)
        assertEquals("done", result)
    }

    @Test
    fun givenABlockThatCompletes_whenItReturns_thenTheThreadStatsTagIsCleared() {
        // A tag left behind would be charged to whatever this shared Dispatchers.IO thread
        // does next, which is the very defect this helper exists to fix.
        withTrafficTag(NetworkTags.CATALOG) {}

        assertEquals(UNTAGGED, currentTag)
    }

    @Test
    fun givenABlockThatThrows_whenRunningIt_thenTheThreadStatsTagIsStillCleared() {
        assertThrows(IllegalStateException::class.java) {
            withTrafficTag(NetworkTags.INFERENCE) { throw IllegalStateException("boom") }
        }

        assertEquals(UNTAGGED, currentTag)
    }

    @Test
    fun givenTheDeclaredTags_whenInspected_thenTheyAreDistinctAndNonZero() {
        // A zero tag means "untagged" to the kernel, so it would defeat the whole point.
        assertNotEquals(0, NetworkTags.INFERENCE)
        assertNotEquals(0, NetworkTags.CATALOG)
        assertNotEquals(NetworkTags.INFERENCE, NetworkTags.CATALOG)
    }

    private companion object {
        /** What `clearThreadStatsTag()` leaves behind — the platform's "no tag" value. */
        const val UNTAGGED = -1
    }
}
