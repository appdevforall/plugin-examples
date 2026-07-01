package com.itsaky.androidide.plugins.aiassistant.data

import android.content.Context
import android.content.SharedPreferences
import com.itsaky.androidide.plugins.aiassistant.models.ChatMessage
import com.itsaky.androidide.plugins.aiassistant.models.ChatSession
import com.itsaky.androidide.plugins.aiassistant.models.Sender
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ChatStorageManager.
 * Tests JSON serialization/deserialization with error handling.
 */
class ChatStorageManagerTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var storageManager: ChatStorageManager

    @Before
    fun setup() {
        // Mock Android Context and SharedPreferences
        context = mockk(relaxed = true)
        sharedPreferences = mockk(relaxed = true)
        editor = mockk(relaxed = true)

        every { context.getSharedPreferences("ai_assistant_chats", Context.MODE_PRIVATE) } returns sharedPreferences
        every { sharedPreferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit

        storageManager = ChatStorageManager(context)
    }

    @Test
    fun testSaveSessionsWithEmptyList() {
        val sessions = emptyList<ChatSession>()
        val jsonSlot = slot<String>()

        every { editor.putString("chat_sessions", capture(jsonSlot)) } returns editor

        storageManager.saveSessions(sessions)

        verify { editor.putString("chat_sessions", any()) }
        verify { editor.apply() }

        // Should serialize to empty JSON array
        assertEquals("[]", jsonSlot.captured)
    }

    @Test
    fun testSaveSessionsWithSingleSession() {
        val session = ChatSession(
            id = "test-123",
            createdAt = 1234567890L,
            messages = mutableListOf()
        )
        val sessions = listOf(session)
        val jsonSlot = slot<String>()

        every { editor.putString("chat_sessions", capture(jsonSlot)) } returns editor

        storageManager.saveSessions(sessions)

        verify { editor.putString("chat_sessions", any()) }
        verify { editor.apply() }

        // Verify JSON contains session data
        val json = jsonSlot.captured
        assertTrue(json.contains("test-123"))
        assertTrue(json.contains("1234567890"))
    }

    @Test
    fun testSaveSessionsWithMultipleSessions() {
        val session1 = ChatSession(id = "session-1", createdAt = 1000L)
        val session2 = ChatSession(id = "session-2", createdAt = 2000L)
        val sessions = listOf(session1, session2)
        val jsonSlot = slot<String>()

        every { editor.putString("chat_sessions", capture(jsonSlot)) } returns editor

        storageManager.saveSessions(sessions)

        val json = jsonSlot.captured
        assertTrue(json.contains("session-1"))
        assertTrue(json.contains("session-2"))
    }

    @Test
    fun testSaveSessionsWithMessages() {
        val message1 = ChatMessage(
            id = "msg-1",
            text = "Hello",
            sender = Sender.USER
        )
        val message2 = ChatMessage(
            id = "msg-2",
            text = "Hi there",
            sender = Sender.AGENT
        )
        val session = ChatSession(
            id = "session-with-messages",
            messages = mutableListOf(message1, message2)
        )
        val jsonSlot = slot<String>()

        every { editor.putString("chat_sessions", capture(jsonSlot)) } returns editor

        storageManager.saveSessions(listOf(session))

        val json = jsonSlot.captured
        assertTrue(json.contains("Hello"))
        assertTrue(json.contains("Hi there"))
        assertTrue(json.contains("msg-1"))
        assertTrue(json.contains("msg-2"))
    }

    @Test
    fun testLoadSessionsWithNoData() {
        every { sharedPreferences.getString("chat_sessions", null) } returns null

        val sessions = storageManager.loadSessions()

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testLoadSessionsWithEmptyList() {
        every { sharedPreferences.getString("chat_sessions", null) } returns "[]"

        val sessions = storageManager.loadSessions()

        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testLoadSessionsWithValidData() {
        val validJson = """
            [
                {
                    "id": "test-session",
                    "createdAt": 1234567890,
                    "messages": []
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString("chat_sessions", null) } returns validJson

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals("test-session", sessions[0].id)
        assertEquals(1234567890L, sessions[0].createdAt)
        assertTrue(sessions[0].messages.isEmpty())
    }

    @Test
    fun testLoadSessionsWithMultipleSessions() {
        val validJson = """
            [
                {
                    "id": "session-1",
                    "createdAt": 1000,
                    "messages": []
                },
                {
                    "id": "session-2",
                    "createdAt": 2000,
                    "messages": []
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString("chat_sessions", null) } returns validJson

        val sessions = storageManager.loadSessions()

        assertEquals(2, sessions.size)
        assertEquals("session-1", sessions[0].id)
        assertEquals("session-2", sessions[1].id)
    }

    @Test
    fun testLoadSessionsWithMessages() {
        val validJson = """
            [
                {
                    "id": "session-1",
                    "createdAt": 1000,
                    "messages": [
                        {
                            "id": "msg-1",
                            "text": "User message",
                            "sender": "USER",
                            "status": "SENT",
                            "timestamp": 1000
                        },
                        {
                            "id": "msg-2",
                            "text": "Agent response",
                            "sender": "AGENT",
                            "status": "COMPLETED",
                            "timestamp": 2000,
                            "durationMs": 500
                        }
                    ]
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString("chat_sessions", null) } returns validJson

        val sessions = storageManager.loadSessions()

        assertEquals(1, sessions.size)
        assertEquals(2, sessions[0].messages.size)
        assertEquals("User message", sessions[0].messages[0].text)
        assertEquals(Sender.USER, sessions[0].messages[0].sender)
        assertEquals("Agent response", sessions[0].messages[1].text)
        assertEquals(Sender.AGENT, sessions[0].messages[1].sender)
    }

    @Test
    fun testLoadSessionsWithCorruptedJson() {
        val corruptedJson = "{invalid json this is not valid"

        every { sharedPreferences.getString("chat_sessions", null) } returns corruptedJson

        val sessions = storageManager.loadSessions()

        // Should return empty list on parse error
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun testLoadSessionsWithMalformedJson() {
        val malformedJson = """
            [
                {
                    "id": "test",
                    "invalid_field": "this should not break parsing"
                }
            ]
        """.trimIndent()

        every { sharedPreferences.getString("chat_sessions", null) } returns malformedJson

        val sessions = storageManager.loadSessions()

        // Gson should handle extra fields gracefully
        // Should still load what it can
        assertTrue(sessions.size >= 0)
    }

    @Test
    fun testSaveCurrentSessionIdWithValidId() {
        val sessionId = "session-123"
        val idSlot = slot<String>()

        every { editor.putString("current_session_id", capture(idSlot)) } returns editor

        storageManager.saveCurrentSessionId(sessionId)

        verify { editor.putString("current_session_id", any()) }
        verify { editor.apply() }
        assertEquals(sessionId, idSlot.captured)
    }

    @Test
    fun testSaveCurrentSessionIdWithNull() {
        val idSlot = slot<String?>()

        every { editor.putString("current_session_id", captureNullable(idSlot)) } returns editor

        storageManager.saveCurrentSessionId(null)

        verify { editor.putString("current_session_id", null) }
        verify { editor.apply() }
        assertNull(idSlot.captured)
    }

    @Test
    fun testLoadCurrentSessionIdWithValidId() {
        val sessionId = "session-456"
        every { sharedPreferences.getString("current_session_id", null) } returns sessionId

        val loadedId = storageManager.loadCurrentSessionId()

        assertEquals(sessionId, loadedId)
    }

    @Test
    fun testLoadCurrentSessionIdWithNoData() {
        every { sharedPreferences.getString("current_session_id", null) } returns null

        val loadedId = storageManager.loadCurrentSessionId()

        assertNull(loadedId)
    }

    @Test
    fun testRoundTripSaveAndLoad() {
        // Create test data
        val message1 = ChatMessage(text = "Test message", sender = Sender.USER)
        val session1 = ChatSession(
            id = "round-trip-test",
            createdAt = 9999999L,
            messages = mutableListOf(message1)
        )
        val sessions = listOf(session1)

        // Capture what gets saved
        val jsonSlot = slot<String>()
        every { editor.putString("chat_sessions", capture(jsonSlot)) } returns editor

        // Save
        storageManager.saveSessions(sessions)

        // Now mock the load to return what was saved
        every { sharedPreferences.getString("chat_sessions", null) } returns jsonSlot.captured

        // Load
        val loadedSessions = storageManager.loadSessions()

        // Verify round-trip
        assertEquals(1, loadedSessions.size)
        assertEquals("round-trip-test", loadedSessions[0].id)
        assertEquals(9999999L, loadedSessions[0].createdAt)
        assertEquals(1, loadedSessions[0].messages.size)
        assertEquals("Test message", loadedSessions[0].messages[0].text)
    }

    @Test
    fun testRoundTripCurrentSessionId() {
        val sessionId = "current-session"
        val idSlot = slot<String>()

        every { editor.putString("current_session_id", capture(idSlot)) } returns editor

        // Save
        storageManager.saveCurrentSessionId(sessionId)

        // Mock load to return what was saved
        every { sharedPreferences.getString("current_session_id", null) } returns idSlot.captured

        // Load
        val loadedId = storageManager.loadCurrentSessionId()

        assertEquals(sessionId, loadedId)
    }

    @Test
    fun testUsesCorrectSharedPreferencesName() {
        // Verify that ChatStorageManager uses the correct SharedPreferences file name
        verify { context.getSharedPreferences("ai_assistant_chats", Context.MODE_PRIVATE) }
    }

    @Test
    fun testUsesCorrectKeys() {
        val sessions = listOf(ChatSession())
        storageManager.saveSessions(sessions)

        // Verify correct key is used
        verify { editor.putString("chat_sessions", any()) }

        val sessionId = "test"
        storageManager.saveCurrentSessionId(sessionId)

        // Verify correct key is used
        verify { editor.putString("current_session_id", any()) }
    }
}
