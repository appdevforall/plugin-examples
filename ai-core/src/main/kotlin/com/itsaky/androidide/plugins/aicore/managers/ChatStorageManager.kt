package com.itsaky.androidide.plugins.aicore.managers

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.plugins.aicore.models.ChatSession

/**
 * Keeps the chat history across restarts, as whole sessions written to SharedPreferences as JSON.
 * Every read is total and every write replaces the stored list, so it holds no session state of its
 * own; `ChatViewModel` owns the live list and is the only caller, always from the main thread.
 *
 * @param context any Android context; only its SharedPreferences are used
 */
class ChatStorageManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai_assistant_chats", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_SESSIONS = "chat_sessions"
        private const val KEY_CURRENT_SESSION_ID = "current_session_id"
    }

    /**
     * Replaces the stored history with [sessions].
     *
     * @param sessions every session to keep; an empty list clears the history
     */
    fun saveSessions(sessions: List<ChatSession>) {
        val json = gson.toJson(sessions)
        prefs.edit().putString(KEY_SESSIONS, json).apply()
    }

    /**
     * Reads the stored history back. Unreadable JSON yields an empty list rather than throwing:
     * losing the history must not stop the Agent from opening, and a [ChatSession] field added in a
     * later release arrives here as exactly that case.
     *
     * @return the stored sessions, or an empty list when nothing is stored or the blob is unusable
     */
    fun loadSessions(): List<ChatSession> {
        val json = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        val type = object : TypeToken<List<ChatSession>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Records which session the user was last looking at.
     *
     * @param sessionId the session's id, or null to leave no session selected
     */
    fun saveCurrentSessionId(sessionId: String?) {
        prefs.edit().putString(KEY_CURRENT_SESSION_ID, sessionId).apply()
    }

    /**
     * The session the user was last looking at. Not validated against [loadSessions] — the id may
     * name a session since deleted, so the caller resolves it and falls back on its own.
     *
     * @return the stored session id, or null when none was recorded
     */
    fun loadCurrentSessionId(): String? {
        return prefs.getString(KEY_CURRENT_SESSION_ID, null)
    }
}
