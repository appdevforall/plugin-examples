package com.itsaky.androidide.plugins.aiassistant.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.itsaky.androidide.plugins.aiassistant.models.ChatSession

class ChatStorageManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("ai_assistant_chats", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_SESSIONS = "chat_sessions"
        private const val KEY_CURRENT_SESSION_ID = "current_session_id"
    }

    fun saveSessions(sessions: List<ChatSession>) {
        val json = gson.toJson(sessions)
        prefs.edit().putString(KEY_SESSIONS, json).apply()
    }

    fun loadSessions(): List<ChatSession> {
        val json = prefs.getString(KEY_SESSIONS, null) ?: return emptyList()
        val type = object : TypeToken<List<ChatSession>>() {}.type
        return try {
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveCurrentSessionId(sessionId: String?) {
        prefs.edit().putString(KEY_CURRENT_SESSION_ID, sessionId).apply()
    }

    fun loadCurrentSessionId(): String? {
        return prefs.getString(KEY_CURRENT_SESSION_ID, null)
    }
}
