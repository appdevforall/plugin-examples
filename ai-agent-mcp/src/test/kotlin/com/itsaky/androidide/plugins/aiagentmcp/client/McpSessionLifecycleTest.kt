package com.itsaky.androidide.plugins.aiagentmcp.client

import com.itsaky.androidide.plugins.aiagentmcp.transport.McpHttpClient
import java.net.HttpURLConnection
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether `notifications/initialized` is sent, which the negotiated revision alone decides.
 *
 * Reading an absent `Mcp-Session-Id` as "stateless" left a conforming 2025-06-18 server without
 * the notification, so its next `tools/list` answered "not initialized" and the user saw no tools.
 */
class McpSessionLifecycleTest {

    private companion object {
        const val ENDPOINT = "https://example.test/mcp"
        const val NOTIFICATION = "notifications/initialized"
    }

    /** Answers `initialize` with [protocolVersion], recording the methods it was asked for. */
    private class FakeHttpClient(
        private val protocolVersion: String,
        private val sessionId: String? = null,
    ) : McpHttpClient() {

        val methods = mutableListOf<String>()

        override fun post(
            url: String,
            token: String,
            body: JSONObject,
            sessionId: String?,
            protocolVersion: String?,
            extraHeaders: Map<String, String>,
            onConnected: (HttpURLConnection) -> Unit,
        ): Response {
            val method = body.optString("method")
            methods += method
            if (method != "initialize") return Response(null, this.sessionId)
            val result = JSONObject().put("protocolVersion", this.protocolVersion)
            val document = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", body.opt("id"))
                .put("result", result)
            return Response(document.toString(), this.sessionId)
        }
    }

    private fun sessionOn(http: McpHttpClient) =
        McpSession(ENDPOINT, { McpCredentials("", emptyMap()) }, http)

    @Test
    fun givenAStatefulRevisionAndNoSessionHeader_whenInitializing_thenTheNotificationIsStillSent() {
        val http = FakeHttpClient(McpSession.PREFERRED_PROTOCOL_VERSION, sessionId = null)

        sessionOn(http).initialize()

        assertEquals(listOf("initialize", NOTIFICATION), http.methods)
    }

    @Test
    fun givenAStatefulRevisionAndASessionHeader_whenInitializing_thenTheNotificationIsSent() {
        val http = FakeHttpClient(McpSession.PREFERRED_PROTOCOL_VERSION, sessionId = "s-1")

        sessionOn(http).initialize()

        assertEquals(listOf("initialize", NOTIFICATION), http.methods)
    }

    @Test
    fun givenAStatelessRevision_whenInitializing_thenNoNotificationIsSent() {
        val http = FakeHttpClient(McpSession.STATELESS_FROM_VERSION, sessionId = null)

        sessionOn(http).initialize()

        assertEquals(listOf("initialize"), http.methods)
    }

    @Test
    fun givenAnUnorderableRevision_whenInitializing_thenItIsTreatedAsStateful() {
        val http = FakeHttpClient("2.1.0", sessionId = null)

        sessionOn(http).initialize()

        assertTrue("an unparseable revision must fail safe", NOTIFICATION in http.methods)
    }
}
