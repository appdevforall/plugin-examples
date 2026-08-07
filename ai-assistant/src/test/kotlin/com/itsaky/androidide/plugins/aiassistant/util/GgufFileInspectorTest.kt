package com.itsaky.androidide.plugins.aiassistant.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class GgufFileInspectorTest {

    /** Captures whatever [GgufFileInspector.looksLikeGguf] reports, standing in for Log.w. */
    private var reportedError: Exception? = null

    private fun looksLikeGguf(openStream: () -> InputStream?): Boolean =
        GgufFileInspector.looksLikeGguf(openStream) { reportedError = it }

    @Test
    fun givenGgufMagic_whenChecked_thenAccepted() {
        val header = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"
        assertTrue(GgufFileInspector.bytesAreGgufMagic(header))
    }

    @Test
    fun givenMagicWithTrailingBytes_whenChecked_thenAccepted() {
        val header = byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x03, 0x00)
        assertTrue(GgufFileInspector.bytesAreGgufMagic(header))
    }

    @Test
    fun givenZipMagic_whenChecked_thenRejected() {
        val header = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // "PK.." — a .zip renamed to .gguf
        assertFalse(GgufFileInspector.bytesAreGgufMagic(header))
    }

    @Test
    fun givenFewerThanFourBytes_whenChecked_thenRejected() {
        assertFalse(GgufFileInspector.bytesAreGgufMagic(byteArrayOf(0x47, 0x47, 0x55)))
    }

    @Test
    fun givenGgufStream_whenInspected_thenAccepted() {
        assertTrue(looksLikeGguf { ByteArrayInputStream(byteArrayOf(0x47, 0x47, 0x55, 0x46, 0x03)) })
        assertNull(reportedError)
    }

    @Test
    fun givenNonGgufStream_whenInspected_thenRejected() {
        assertFalse(looksLikeGguf { ByteArrayInputStream(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) })
        assertNull(reportedError)
    }

    @Test
    fun givenStreamThatThrowsMidRead_whenInspected_thenFailsOpenAndReports() {
        // SAF can drop a content:// stream mid-transfer; a real model must not be rejected for it.
        val truncating = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw IOException("stream died")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 0) throw IOException("stream died")
                served = 2
                b[off] = 0x47
                b[off + 1] = 0x47
                return 2
            }
        }

        assertTrue(looksLikeGguf { truncating })
        assertEquals("stream died", reportedError?.message)
    }

    @Test
    fun givenUnopenableDocument_whenInspected_thenFailsOpenAndReports() {
        assertTrue(looksLikeGguf { throw SecurityException("permission revoked") })
        assertEquals("permission revoked", reportedError?.message)
    }

    @Test
    fun givenNullStream_whenInspected_thenFailsOpenWithoutError() {
        // openInputStream() returns null for a provider that can't produce the document.
        assertTrue(looksLikeGguf { null })
        assertNull(reportedError)
    }

    @Test
    fun givenTruncatedStream_whenInspected_thenFailsOpen() {
        // Fewer than four bytes available: not enough to judge, so don't reject.
        assertTrue(looksLikeGguf { ByteArrayInputStream(byteArrayOf(0x47, 0x47)) })
        assertNull(reportedError)
    }
}
