package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * A resident model cannot notice its own file being deleted — the descriptor the loader holds
 * keeps the inode alive — so the reachability probe is the only thing that can (ADFA-5253).
 */
class ContentNativeModelSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var resolver: ContentResolver
    private lateinit var context: Context
    private lateinit var source: ContentNativeModelSource

    @Before
    fun setup() {
        resolver = mockk(relaxed = true)
        context = mockk(relaxed = true)
        every { context.contentResolver } returns resolver
        mockkStatic(Uri::class)
        every { Uri.parse(any()) } returns mockk(relaxed = true)
        source = ContentNativeModelSource(context)
    }

    @After
    fun tearDown() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun givenAPathThatStillExists_whenProbed_thenItIsReachable() {
        val model = temporaryFolder.newFile("model.gguf")

        assertTrue(source.isReachable(model.absolutePath))
    }

    @Test
    fun givenADeletedPath_whenProbed_thenItIsUnreachable() {
        val model = temporaryFolder.newFile("model.gguf")
        assertTrue(model.delete())

        assertFalse(source.isReachable(model.absolutePath))
    }

    @Test
    fun givenADirectory_whenProbed_thenItIsUnreachable() {
        // A path that resolves but holds no model must not read as a usable one.
        val directory = temporaryFolder.newFolder("models")

        assertFalse(source.isReachable(directory.absolutePath))
    }

    @Test
    fun givenADocumentTheProviderStillServes_whenProbed_thenItIsReachable() {
        every { resolver.openFileDescriptor(any(), "r") } returns mockk<ParcelFileDescriptor>(relaxed = true)

        assertTrue(source.isReachable(CONTENT_URI))
    }

    @Test
    fun givenADeletedDocument_whenProbed_thenItIsUnreachable() {
        // What a deleted document actually does: the provider throws rather than returning null.
        every { resolver.openFileDescriptor(any(), "r") } throws java.io.FileNotFoundException()

        assertFalse(source.isReachable(CONTENT_URI))
    }

    @Test
    fun givenAProviderThatAnswersWithNothing_whenProbed_thenItIsUnreachable() {
        every { resolver.openFileDescriptor(any(), "r") } returns null

        assertFalse(source.isReachable(CONTENT_URI))
    }

    @Test
    fun givenAProbedDocument_whenTheProbeEnds_thenItsDescriptorIsClosed() {
        // The probe must not leak the fd it opens: one per generation would exhaust the table.
        val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
        every { resolver.openFileDescriptor(any(), "r") } returns descriptor

        source.isReachable(CONTENT_URI)

        io.mockk.verify { descriptor.close() }
    }

    @Test
    fun givenADeletedPath_whenOpened_thenNoHandleIsReturned() {
        val model = temporaryFolder.newFile("model.gguf")
        assertTrue(model.delete())

        assertNull(source.open(model.absolutePath))
    }

    private companion object {
        const val CONTENT_URI = "content://com.android.externalstorage.documents/document/model.gguf"
    }
}
