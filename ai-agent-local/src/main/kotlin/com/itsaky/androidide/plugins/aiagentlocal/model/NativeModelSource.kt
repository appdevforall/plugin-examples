package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * A model file held open for the native loader.
 *
 * [nativePath] is a path llama.cpp can `fopen` and `mmap`. For a document picked through SAF that is
 * `/proc/self/fd/N` for the descriptor this handle owns: opening that procfs entry re-opens the
 * underlying inode with an independent file offset, so the native loader behaves exactly as it does
 * for a real path — without copying multiple gigabytes into private storage first. See ADFA-5253.
 *
 * IMPORTANT: the descriptor must stay open for as long as the model is resident. Closing it
 * invalidates the procfs entry, and the pages the loader has mapped are the only thing keeping the
 * model alive after that. [close] is therefore the unload path's job, not the load path's.
 *
 * @property nativePath the path to hand the native loader
 * @property sizeBytes the model's size, or -1 when the descriptor names no regular file
 */
class OpenModelFile(
    val nativePath: String,
    val sizeBytes: Long,
    private val descriptor: Closeable?,
) : Closeable {

    /**
     * Whether [nativePath] can be `mmap`ed and re-opened, which everything above assumes. A
     * streaming provider (Drive, OneDrive) hands back a pipe instead, for which `statSize` is -1
     * and each [openStream] eats bytes the loader never sees, so the caller must refuse it.
     */
    val isSeekable: Boolean get() = sizeBytes >= 0

    /**
     * Opens an independent read stream over the same bytes the native loader sees — header
     * inspection must never disturb the loader's own file offset.
     *
     * @return the stream, or null when the source became unreadable
     */
    fun openStream(): InputStream? = try {
        FileInputStream(nativePath)
    } catch (_: Exception) {
        null
    }

    override fun close() {
        try {
            descriptor?.close()
        } catch (_: Exception) {
            // Already closed, or the provider died with it — there is nothing left to release.
        }
    }
}

/**
 * Opens the user's selected model for the native loader, in place and without copying it.
 * An interface so the backend's load path can be exercised without a device.
 */
interface NativeModelSource {

    /**
     * @param modelReference the configured model, as a `content://` URI or a filesystem path
     * @return an open handle the caller owns and must [OpenModelFile.close], or null when the
     *   model cannot be reached at all (deleted, unmounted, or the read grant was revoked)
     */
    fun open(modelReference: String): OpenModelFile?

    /**
     * Whether [modelReference] still resolves to something readable, reading none of it.
     *
     * A resident model cannot answer this itself: the descriptor the loader holds keeps the
     * deleted inode alive, so the mapped pages outlive the file and the model keeps replying from
     * a document the user has thrown away. Only a fresh open off the reference can tell.
     *
     * @return true when the model is still there; false for deleted, unmounted, or revoked
     */
    fun isReachable(modelReference: String): Boolean
}

/**
 * [NativeModelSource] over the document provider and the filesystem.
 *
 * @param context supplies the resolver holding the picker's persisted read grant
 * @param onError reports a failed open, so a bare "model unavailable" can still be explained
 */
class ContentNativeModelSource(
    private val context: Context,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : NativeModelSource {

    override fun open(modelReference: String): OpenModelFile? =
        if (modelReference.startsWith(CONTENT_SCHEME)) openDocument(modelReference)
        else openFile(modelReference)

    /**
     * Takes the document's descriptor and hands the native loader its procfs path. `"r"` is the
     * only mode asked for, which is all the persisted grant covers. The descriptor need not be a
     * file — a pipe is reported through [OpenModelFile.isSeekable] for the caller to refuse.
     */
    private fun openDocument(uriString: String): OpenModelFile? = try {
        context.contentResolver.openFileDescriptor(Uri.parse(uriString), "r")
            ?.let { OpenModelFile("$FD_DIR${it.fd}", it.statSize, it) }
    } catch (e: Exception) {
        onError("could not open the selected model $uriString", e)
        null
    }

    /**
     * One binder round trip for a document, one stat for a path — nothing is read, so this is
     * cheap enough to ask before every generation. A failure here is the routine answer "it is
     * gone", not an error worth reporting through [onError].
     */
    override fun isReachable(modelReference: String): Boolean = try {
        if (modelReference.startsWith(CONTENT_SCHEME)) {
            context.contentResolver
                .openFileDescriptor(Uri.parse(modelReference), "r")
                ?.use { true } ?: false
        } else {
            File(modelReference).isFile
        }
    } catch (_: Exception) {
        false
    }

    private fun openFile(path: String): OpenModelFile? = try {
        File(path).takeIf { it.isFile }?.let { OpenModelFile(it.absolutePath, it.length(), null) }
    } catch (e: Exception) {
        onError("could not open the model file $path", e)
        null
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"
        const val FD_DIR = "/proc/self/fd/"
    }
}
