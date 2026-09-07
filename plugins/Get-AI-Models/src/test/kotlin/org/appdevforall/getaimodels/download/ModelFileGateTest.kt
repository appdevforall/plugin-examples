package org.appdevforall.getaimodels.download

import com.itsaky.androidide.plugins.PluginLogger
import kotlinx.coroutines.runBlocking
import org.appdevforall.getaimodels.catalog.CatalogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * The SHA-256 gate on its own: what it returns, and what it leaves in the record store afterwards.
 * Runs off-device because [ModelFileGate] takes a [ModelRecordStore] rather than SharedPreferences.
 */
class ModelFileGateTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** sha256 of "hello" - the fixture every on-disk case below is checked against. */
    private val helloSha = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"

    private val store = FakeRecordStore()
    private val gate = ModelFileGate(store, SilentLogger)

    private fun entry(sha: String = helloSha) = CatalogEntry(
        id = "test-entry",
        name = "Test Model",
        quantization = "Q4_K_M",
        parameters = "0.5B",
        fileName = "model.gguf",
        sizeBytes = 5L,
        sha256 = sha,
        url = "https://example.invalid/model.gguf",
        minRamBytes = 1L,
        publisher = "test",
        contextTokens = 32768,
        license = "apache-2.0",
        baseModel = "test/model",
        description = "fixture",
        behaviouralGatesVerified = false
    )

    private fun fileOf(contents: String): File =
        folder.newFile("model.gguf").apply { writeText(contents) }

    @Test
    fun givenAMatchingFile_whenVerified_thenItIsRecordedAtItsPath() = runBlocking {
        val file = fileOf("hello")

        val result = gate.verify(entry(), VerifyTarget.ofFile(file))

        assertEquals(VerifyResult.Matched(file.absolutePath, recorded = true), result)
        val record = store.records["test-entry"]
        assertEquals(file.absolutePath, record?.path)
        assertEquals(5L, record?.sizeBytes)
    }

    @Test
    fun givenAMismatchingFile_whenVerified_thenNothingIsRecordedAndTheFileIsUntouched() = runBlocking {
        val file = fileOf("hello")
        store.records["test-entry"] = VerifiedModel(file.absolutePath, 5L, 1L)

        val result = gate.verify(entry(sha = "0".repeat(64)), VerifyTarget.ofFile(file))

        assertEquals(VerifyResult.Mismatched, result)
        // The stale record must go, but deleting a failed *download* is DownloadManager's job.
        assertNull(store.records["test-entry"])
        assertTrue("the gate must not delete the file itself", file.exists())
    }

    @Test
    fun givenAPathlessTarget_whenVerified_thenItPassesButIsNotRecorded() = runBlocking {
        val target = VerifyTarget("model.gguf", path = null) {
            ByteArrayInputStream("hello".toByteArray())
        }

        val result = gate.verify(entry(), target)

        assertEquals(VerifyResult.Matched("model.gguf", recorded = false), result)
        assertTrue("a pathless pass has nothing to record", store.records.isEmpty())
    }

    @Test
    fun givenARecordedPathThatIsGone_whenVerified_thenItIsMissingAndTheRecordIsDropped() = runBlocking {
        val file = fileOf("hello")
        store.records["test-entry"] = VerifiedModel(file.absolutePath, 5L, 1L)
        assertTrue(file.delete())

        val result = gate.verify(entry(), VerifyTarget.ofFile(file))

        assertEquals(VerifyResult.Missing, result)
        assertNull(store.records["test-entry"])
    }

    @Test
    fun givenBytesThatCannotBeRead_whenVerified_thenItReportsUnreadableRatherThanThrowing() =
        runBlocking {
            val target = VerifyTarget("model.gguf", path = null) { throw IOException("no stream") }

            val result = gate.verify(entry(), target)

            assertTrue("expected Unreadable, got $result", result is VerifyResult.Unreadable)
        }

    @Test
    fun givenARecordedFile_whenDeleted_thenBothTheFileAndTheRecordAreGone() = runBlocking {
        val file = fileOf("hello")
        store.records["test-entry"] = VerifiedModel(file.absolutePath, 5L, 1L)

        assertTrue(gate.deleteRecorded("test-entry", file.absolutePath))

        assertFalse(file.exists())
        assertNull(store.records["test-entry"])
    }

    @Test
    fun givenAnAlreadyDeletedFile_whenDeleted_thenTheStaleRecordStillClears() = runBlocking {
        val path = folder.root.resolve("never-existed.gguf").absolutePath
        store.records["test-entry"] = VerifiedModel(path, 5L, 1L)

        assertTrue("already gone satisfies the intent", gate.deleteRecorded("test-entry", path))

        assertNull(store.records["test-entry"])
    }

    private class FakeRecordStore : ModelRecordStore {
        val records = mutableMapOf<String, VerifiedModel>()
        override suspend fun all(): Map<String, VerifiedModel> = records.toMap()
        override suspend fun put(entryId: String, record: VerifiedModel) {
            records[entryId] = record
        }

        override suspend fun remove(entryId: String) {
            records.remove(entryId)
        }
    }

    private object SilentLogger : PluginLogger {
        override val pluginId: String = "test"
        override fun debug(message: String) = Unit
        override fun debug(message: String, throwable: Throwable) = Unit
        override fun info(message: String) = Unit
        override fun info(message: String, throwable: Throwable) = Unit
        override fun warn(message: String) = Unit
        override fun warn(message: String, throwable: Throwable) = Unit
        override fun error(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
    }
}
