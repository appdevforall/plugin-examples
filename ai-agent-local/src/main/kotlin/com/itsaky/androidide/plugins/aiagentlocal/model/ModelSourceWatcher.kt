package com.itsaky.androidide.plugins.aiagentlocal.model

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import java.io.Closeable
import java.io.File

/**
 * Watches the file behind a resident model and reports when it goes away, so its gigabytes are
 * given back at deletion time rather than at the user's next message.
 *
 * Best-effort by contract: a provider that does not notify simply never fires, and the
 * before-generation reachability check stays the guarantee. Nothing here may be the only thing
 * standing between a deleted model and a reply.
 */
interface ModelSourceWatcher {

    /**
     * @param modelReference the resident model, as a `content://` URI or a filesystem path
     * @param onGone invoked, off the caller's thread, when the file looks gone; may fire more than
     *   once and may fire spuriously, so the callback must confirm before acting
     * @return a handle that stops the watch, or null when this source cannot be watched
     */
    fun watch(modelReference: String, onGone: () -> Unit): Closeable?
}

/**
 * [ModelSourceWatcher] over the document provider and the filesystem.
 *
 * Callbacks arrive on a private [HandlerThread] — never the main thread, and never a thread the
 * caller owns — started with the first watch and stopped with the last, so an idle plugin holds
 * no thread. See ADFA-5253.
 *
 * @param onError reports a failed registration, so a silently unwatched model can be explained
 */
class PlatformModelSourceWatcher(
    private val context: Context,
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) : ModelSourceWatcher {

    /** Guards [thread] and [handler]; both are touched from watch and from close. */
    private val lock = Any()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    /** Live watches, so the last one out stops the thread. */
    private var watchCount = 0

    override fun watch(modelReference: String, onGone: () -> Unit): Closeable? = try {
        if (modelReference.startsWith(CONTENT_SCHEME)) {
            watchDocument(modelReference, onGone)
        } else {
            watchFile(modelReference, onGone)
        }
    } catch (e: Exception) {
        onError("could not watch $modelReference", e)
        null
    }

    /**
     * Providers notify on their own terms — often for the parent tree rather than the document,
     * and often for edits rather than deletion — so this registers for descendants too and lets
     * the callback decide. `onGone` is a hint, never a verdict.
     */
    private fun watchDocument(uriString: String, onGone: () -> Unit): Closeable {
        val uri = Uri.parse(uriString)
        val observer = object : ContentObserver(acquireHandler()) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = onGone()
        }
        try {
            context.contentResolver.registerContentObserver(uri, true, observer)
        } catch (e: Exception) {
            // The handler is already counted; give it back or the thread outlives every watch.
            releaseHandler()
            throw e
        }
        return Closeable {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } finally {
                releaseHandler()
            }
        }
    }

    /**
     * `DELETE_SELF` covers the delete; `MOVE_SELF` covers a rename or a move to another volume,
     * which breaks a configured path just as thoroughly.
     */
    private fun watchFile(path: String, onGone: () -> Unit): Closeable? {
        val file = File(path)
        if (!file.isFile) return null
        val observer = object : FileObserver(file, DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, path: String?) = onGone()
        }
        // The framework holds FileObserver weakly and stops watching once it is collected, so the
        // returned handle keeps the only strong reference alive for as long as the watch is wanted.
        observer.startWatching()
        return Closeable { observer.stopWatching() }
    }

    /** Starts the delivery thread on the first watch. */
    private fun acquireHandler(): Handler = synchronized(lock) {
        if (thread == null) {
            thread = HandlerThread(THREAD_NAME).also {
                it.start()
                handler = Handler(it.looper)
            }
        }
        watchCount++
        handler!!
    }

    /** Stops the delivery thread with the last watch, so an idle plugin holds no thread. */
    private fun releaseHandler() = synchronized(lock) {
        watchCount--
        if (watchCount <= 0) {
            watchCount = 0
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    private companion object {
        const val CONTENT_SCHEME = "content://"
        const val THREAD_NAME = "LocalLlm-ModelWatch"
    }
}
