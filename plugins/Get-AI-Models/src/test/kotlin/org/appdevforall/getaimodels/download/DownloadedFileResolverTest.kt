package org.appdevforall.getaimodels.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A content:// URI used to be handed to File(), whose path (`/all_downloads/42`) is not a filesystem
 * path - so a finished download was reported as missing. Only a file:// URI or COLUMN_LOCAL_FILENAME
 * yields a path; a bare content:// URI resolves to the provider instead of failing the download.
 */
class DownloadedFileResolverTest {

    @Test
    fun givenAFileUri_whenResolved_thenThePathIsReturned() {
        assertEquals(
            DownloadedLocation.OnDisk("/storage/emulated/0/Download/model.gguf"),
            DownloadedFileResolver.resolve(
                localUri = "file:///storage/emulated/0/Download/model.gguf",
                localFileName = null
            )
        )
    }

    @Test
    fun givenAContentUriAndALocalFileName_whenResolved_thenTheFileNameGivesThePath() {
        assertEquals(
            DownloadedLocation.OnDisk("/storage/emulated/0/Download/model.gguf"),
            DownloadedFileResolver.resolve(
                localUri = "content://downloads/all_downloads/42",
                localFileName = "/storage/emulated/0/Download/model.gguf"
            )
        )
    }

    @Test
    fun givenOnlyAContentUri_whenResolved_thenTheProviderIsUsed() {
        assertEquals(
            DownloadedLocation.ViaProvider("content://downloads/all_downloads/42"),
            DownloadedFileResolver.resolve(
                localUri = "content://downloads/all_downloads/42",
                localFileName = null
            )
        )
    }

    /** The original defect: a provider URI must never come back as something File() will be given. */
    @Test
    fun givenOnlyAContentUri_whenResolved_thenItIsNeverTreatedAsAPath() {
        val resolved = DownloadedFileResolver.resolve("content://downloads/all_downloads/42", null)
        assertFalse("a content:// URI resolved to a filesystem path", resolved is DownloadedLocation.OnDisk)
    }

    @Test
    fun givenNothingUsable_whenResolved_thenNothingIsReturned() {
        assertNull(DownloadedFileResolver.resolve(null, null))
        assertNull(DownloadedFileResolver.resolve("file://", " "))
        assertNull(DownloadedFileResolver.resolve("", ""))
    }

    @Test
    fun givenAFileUriWithAnEncodedName_whenResolved_thenTheNameIsDecoded() {
        assertEquals(
            DownloadedLocation.OnDisk("/sdcard/Download/my model.gguf"),
            DownloadedFileResolver.resolve("file:///sdcard/Download/my%20model.gguf", null)
        )
    }
}
