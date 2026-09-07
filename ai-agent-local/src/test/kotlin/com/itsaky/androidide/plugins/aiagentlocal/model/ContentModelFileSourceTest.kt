package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException

/**
 * The read grant is the only thing keeping a picked model reachable — it is read in place, never
 * copied — so persisting it is what makes a selection survive a restart (ADFA-5253).
 */
class ContentModelFileSourceTest {

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var uri: Uri
    private val errors = mutableListOf<String>()
    private val source = ContentModelFileSource { what, _ -> errors += what }

    @Before
    fun setup() {
        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        uri = mockk(relaxed = true)
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns uri
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun givenContentUri_whenPersistAccess_thenTakesPersistableReadPermission() {
        assertTrue(source.persistAccess(context, CONTENT_URI))

        verify { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun givenFilesystemPath_whenPersistAccess_thenNoGrantIsNeeded() {
        assertTrue(source.persistAccess(context, "/sdcard/Download/model.gguf"))

        verify(exactly = 0) { resolver.takePersistableUriPermission(any(), any()) }
    }

    @Test
    fun givenNonPersistableGrant_whenPersistAccess_thenReportsFailureWithoutThrowing() {
        every { resolver.takePersistableUriPermission(any(), any()) } throws
            SecurityException("No persistable permission grants found")

        assertFalse(source.persistAccess(context, CONTENT_URI))
        assertEquals(1, errors.size)
    }

    @Test
    fun givenContentUri_whenReleaseAccess_thenGivesTheReadGrantBack() {
        source.releaseAccess(context, CONTENT_URI)

        verify {
            resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Test
    fun givenDeletedDocument_whenProbed_thenGoneWithoutReportingAnError() {
        every { resolver.openInputStream(uri) } throws
            FileNotFoundException("open failed: ENOENT (No such file or directory)")

        assertEquals(SourceReachability.GONE, source.readability(context, CONTENT_URI))
        // A model that is gone is an answer for the caller, not a lookup failure to log.
        assertTrue(errors.toString(), errors.isEmpty())
    }

    @Test
    fun givenAProviderThatServesOnTheSecondAsk_whenProbed_thenItIsReachableRatherThanGone() {
        // The resolver turns provider death into the FileNotFoundException a deletion gives, so
        // only the re-ask keeps this from telling the user to re-pick a model that is intact.
        every { resolver.openInputStream(uri) } throws FileNotFoundException() andThen
            ByteArrayInputStream(ByteArray(4))

        assertEquals(SourceReachability.REACHABLE, source.readability(context, CONTENT_URI))
    }

    @Test
    fun givenAProviderThatStaysSilentOnTheSecondAsk_whenProbed_thenTheAnswerIsUnknown() {
        // Neither ask established anything, and only GONE may say "select the model again".
        every { resolver.openInputStream(uri) } throws FileNotFoundException() andThen null

        assertEquals(SourceReachability.UNKNOWN, source.readability(context, CONTENT_URI))
    }

    @Test
    fun givenOpenableDocument_whenProbed_thenReachableAndTheStreamIsClosed() {
        val stream = spyk(ByteArrayInputStream(ByteArray(4)))
        every { resolver.openInputStream(uri) } returns stream

        assertEquals(SourceReachability.REACHABLE, source.readability(context, CONTENT_URI))
        verify { stream.close() }
    }

    @Test
    fun givenAProviderThatAnswersWithNothing_whenProbed_thenTheAnswerIsUnknown() {
        // No stream and no failure is not the provider saying the document is gone.
        every { resolver.openInputStream(uri) } returns null

        assertEquals(SourceReachability.UNKNOWN, source.readability(context, CONTENT_URI))
    }

    @Test
    fun givenMissingFilesystemPath_whenProbed_thenGone() {
        assertEquals(SourceReachability.GONE, source.readability(context, "/sdcard/Download/gone.gguf"))
    }

    @Test
    fun givenExistingFile_whenProbed_thenReachable() {
        val file = File.createTempFile("model", ".gguf").apply { deleteOnExit() }

        assertEquals(SourceReachability.REACHABLE, source.readability(context, file.absolutePath))
    }

    private companion object {
        const val CONTENT_URI = "content://com.android.providers.downloads/document/42"
    }
}
