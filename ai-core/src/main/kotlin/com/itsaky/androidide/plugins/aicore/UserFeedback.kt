package com.itsaky.androidide.plugins.aicore

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Surfaces actionable LLM errors to the user as a Toast, from anywhere in the process.
 *
 * ai-core is the shared LLM provider: chat, code-suggestions and code-review all funnel their
 * generation through it. When something is misconfigured (e.g. no model selected), each consumer
 * would otherwise fail silently in its own way — code-suggestions in particular retries on every
 * keystroke, so the same error can arrive many times per second. This helper is the single place
 * that turns those into one visible, throttled message for the user.
 *
 * Throttling is keyed by message text: an identical message shown within [COOLDOWN_MS] is
 * suppressed, so typing doesn't stack a wall of toasts while still re-notifying later if the
 * problem persists after the user has had a chance to act.
 */
object UserFeedback {

    private const val COOLDOWN_MS = 10_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var lastMessage: String? = null
    @Volatile private var lastShownAt = 0L

    /**
     * Shows [message] as a long Toast, unless the identical message was already shown within the
     * cooldown window. Safe to call from any thread.
     */
    fun notify(context: Context, message: String) {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (message == lastMessage && now - lastShownAt < COOLDOWN_MS) return
            lastMessage = message
            lastShownAt = now
        }
        val appContext = context.applicationContext
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Thrown when generation can't proceed because the local LLM isn't set up (no model selected, or
 * the configured model path can't be resolved). Distinct from a runtime generation failure so the
 * backend can surface it to the user instead of failing silently. The [message] is written to be
 * shown directly to the user.
 */
class ModelNotConfiguredException(message: String) : IllegalStateException(message)

/**
 * Thrown when the selected local model is the wrong *kind* for the requested operation — most
 * commonly an embedding (encoder-only) model selected for chat, which would abort native
 * inference. Like [ModelNotConfiguredException] the [message] is written for direct display, and
 * the backend surfaces it to the user instead of attempting generation. See ADFA-4388.
 */
class IncompatibleModelException(message: String) : IllegalStateException(message)
