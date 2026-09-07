package org.appdevforall.getaimodels.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the Android-free half of the persistence path: the record encoding and the stat-based
 * revalidation deciding whether a remembered verification still holds. The SharedPreferences wrapper
 * is a thin withContext(IO) shell and needs a device.
 */
class VerifiedModelStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun givenAVerifiedRecord_whenEncodedAndDecoded_thenItIsUnchanged() {
        val record = VerifiedModel("/sdcard/Download/model.gguf", 2_497_280_256L, 1_700_000_000_000L)
        val restored = VerifiedModel.fromJson(record.toJson())
        assertEquals(record, restored)
    }

    @Test
    fun givenAnUnreadableRecord_whenDecoded_thenItIsDroppedRatherThanThrown() {
        assertNull(VerifiedModel.fromJson("not json"))
        assertNull(VerifiedModel.fromJson("{}"))
        // A zero or negative size would make the stat check meaningless.
        assertNull(VerifiedModel.fromJson("""{"path":"/a/b.gguf","sizeBytes":0}"""))
        assertNull(VerifiedModel.fromJson("""{"path":"","sizeBytes":10}"""))
    }

    @Test
    fun givenNoFileAtTheRecordedPath_whenTheDiskIsChecked_thenAbsentIsReported() {
        val missing = folder.root.resolve("gone.gguf")
        assertEquals(DiskStatus.ABSENT, DiskCheck.status(missing.absolutePath, 10))
    }

    @Test
    fun givenADirectoryAtTheRecordedPath_whenTheDiskIsChecked_thenAbsentIsReported() {
        val dir = folder.newFolder("not-a-file.gguf")
        assertEquals(DiskStatus.ABSENT, DiskCheck.status(dir.absolutePath, 10))
    }

    @Test
    fun givenATruncatedFile_whenTheDiskIsChecked_thenASizeMismatchIsReported() {
        val file = folder.newFile("model.gguf").apply { writeBytes(ByteArray(64)) }
        assertEquals(DiskStatus.SIZE_MISMATCH, DiskCheck.status(file.absolutePath, 128))
    }

    @Test
    fun givenAFileOfTheVerifiedLength_whenTheDiskIsChecked_thenAMatchIsReported() {
        val file = folder.newFile("model.gguf").apply { writeBytes(ByteArray(128)) }
        assertEquals(DiskStatus.MATCHES, DiskCheck.status(file.absolutePath, 128))
    }
}
