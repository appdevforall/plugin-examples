package com.itsaky.androidide.plugins.aicore

import com.itsaky.androidide.plugins.aicore.ModelLoadDiagnostics.Diagnosis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ModelLoadDiagnosticsTest {

    private fun tempFile(bytes: Int, magic: Boolean = true): File =
        File.createTempFile("model", ".gguf").apply {
            deleteOnExit()
            outputStream().use { out ->
                if (magic && bytes >= 4) {
                    out.write(byteArrayOf(0x47, 0x47, 0x55, 0x46)) // "GGUF"
                    out.write(ByteArray(bytes - 4))
                } else {
                    out.write(ByteArray(bytes))
                }
            }
        }

    @Test
    fun givenMissingFile_whenDiagnosed_thenFileMissing() {
        val d = ModelLoadDiagnostics.diagnose("/does/not/exist.gguf", availableMemoryBytes = 8L shl 30)
        assertEquals(Diagnosis.FileMissing, d)
    }

    @Test
    fun givenEmptyFile_whenDiagnosed_thenFileEmpty() {
        val d = ModelLoadDiagnostics.diagnose(tempFile(0).absolutePath, availableMemoryBytes = 8L shl 30)
        assertEquals(Diagnosis.FileEmpty, d)
    }

    @Test
    fun givenNonGgufContent_whenDiagnosed_thenNotGguf() {
        val d = ModelLoadDiagnostics.diagnose(tempFile(2048, magic = false).absolutePath, availableMemoryBytes = 8L shl 30)
        assertEquals(Diagnosis.NotGguf, d)
    }

    @Test
    fun givenValidGgufAndLowMemory_whenDiagnosed_thenLowMemory() {
        // 1 MB "model", only 512 KB free -> below the headroom floor.
        val d = ModelLoadDiagnostics.diagnose(tempFile(1 shl 20).absolutePath, availableMemoryBytes = 512L shl 10)
        assertTrue(d is Diagnosis.LowMemory)
        // neededBytes reports the headroom that tripped the check; here the 256 MB floor dominates.
        assertEquals(256L shl 20, (d as Diagnosis.LowMemory).neededBytes)
    }

    @Test
    fun givenLargeModelAndHeadroomBelowFileSize_whenDiagnosed_thenNotLowMemory() {
        // Guards the mmap property: free RAM under the file size is not itself a shortage.
        val d = ModelLoadDiagnostics.diagnose(
            tempFile(1 shl 20).absolutePath,
            availableMemoryBytes = 512L shl 20, // 512 MB free, above the floor
        )
        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenValidGgufAndAmpleMemory_whenDiagnosed_thenUnsupportedOrCorrupt() {
        val d = ModelLoadDiagnostics.diagnose(tempFile(1 shl 20).absolutePath, availableMemoryBytes = 8L shl 30)
        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenUnknownMemory_whenDiagnosed_thenNotLowMemory() {
        // availMem < 0 (unreadable) must not be treated as "no memory".
        val d = ModelLoadDiagnostics.diagnose(tempFile(1 shl 20).absolutePath, availableMemoryBytes = -1L)
        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenZeroFreeMemory_whenDiagnosed_thenLowMemory() {
        // 0 free bytes is a genuine out-of-memory reading (only a negative value means "unknown"),
        // so it must classify as low memory rather than falling through to unsupported/corrupt.
        val d = ModelLoadDiagnostics.diagnose(tempFile(1 shl 20).absolutePath, availableMemoryBytes = 0L)
        assertTrue(d is Diagnosis.LowMemory)
        assertEquals(0L, (d as Diagnosis.LowMemory).availableBytes)
    }

    @Test
    fun givenAlreadyLoadedError_whenDiagnosed_thenModelBusy() {
        // A valid file with ample RAM but the run loop is busy must not be blamed on the file.
        val d = ModelLoadDiagnostics.diagnose(
            tempFile(1 shl 20).absolutePath,
            availableMemoryBytes = 8L shl 30,
            nativeError = "Model already loaded",
        )
        assertEquals(Diagnosis.ModelBusy, d)
    }

    @Test
    fun givenAlreadyLoadedErrorAndLowMemory_whenDiagnosed_thenModelBusyWinsOverMemory() {
        // "Already loaded" is a state issue, so it outranks the low-memory heuristic.
        val d = ModelLoadDiagnostics.diagnose(
            tempFile(1 shl 20).absolutePath,
            availableMemoryBytes = 512L shl 10,
            nativeError = "Model already loaded",
        )
        assertEquals(Diagnosis.ModelBusy, d)
    }

    @Test
    fun givenContextAllocError_whenDiagnosed_thenInitializationFailed() {
        // A valid file with ample RAM that still fails to allocate its context is memory pressure,
        // not a corrupt file.
        val d = ModelLoadDiagnostics.diagnose(
            tempFile(1 shl 20).absolutePath,
            availableMemoryBytes = 8L shl 30,
            nativeError = "new_context() failed",
        )
        assertEquals(Diagnosis.InitializationFailed, d)
    }

    @Test
    fun givenUnrecognizedError_whenDiagnosed_thenUnsupportedOrCorrupt() {
        // An unknown native message on a valid-looking file falls back to the safe default.
        val d = ModelLoadDiagnostics.diagnose(
            tempFile(1 shl 20).absolutePath,
            availableMemoryBytes = 8L shl 30,
            nativeError = "something unexpected",
        )
        assertEquals(Diagnosis.UnsupportedOrCorrupt, d)
    }

    @Test
    fun givenGgufMagic_whenIsGguf_thenTrue() {
        assertTrue(GgufModelInspector.isGguf(tempFile(64).absolutePath))
    }

    @Test
    fun givenNonGgufContent_whenIsGguf_thenFalse() {
        assertFalse(GgufModelInspector.isGguf(tempFile(64, magic = false).absolutePath))
        assertFalse(GgufModelInspector.isGguf("/does/not/exist.gguf"))
    }
}
