package com.itsaky.androidide.plugins.aiagentgemini.errors

import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aiagentgemini.preferences.GeminiPreferences

/**
 * Why the last request was refused for credential reasons, kept for the settings pane to report.
 *
 * A refused request is otherwise only legible in the transcript, which the user has already left
 * by the time they open the pane to fix the key — so the one screen that can fix it said nothing
 * (ADFA-5491). Holds the sentence the backend already produced for the user, never the credential.
 *
 * @param prefs this plugin's settings store; re-read on every call, and null before `initialize()`
 */
internal class CredentialFailureLog(private val prefs: () -> SharedPreferences?) {

    /** Records [reason] as the current credential problem, replacing any earlier one. */
    fun record(reason: String) {
        prefs()?.edit()?.putString(GeminiPreferences.KEY_CREDENTIAL_FAILURE, reason)?.apply()
    }

    /** The recorded reason, or null when the credential has not been refused since it was set. */
    fun read(): String? = prefs()
        ?.getString(GeminiPreferences.KEY_CREDENTIAL_FAILURE, null)
        ?.takeIf { it.isNotBlank() }

    /** Forgets the reason: the credential changed, or a request has since gone through on it. */
    fun clear() {
        prefs()?.edit()?.remove(GeminiPreferences.KEY_CREDENTIAL_FAILURE)?.apply()
    }
}
