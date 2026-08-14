package com.itsaky.androidide.plugins.aiagentmcp.transport

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

private const val TAG = "$LOG_PREFIX.McpHttpClient"

/**
 * The HTTP transport this plugin speaks: one POST that answers with JSON or with an SSE stream
 * carrying the same JSON, and one DELETE that ends a session.
 *
 * [HttpURLConnection] rather than an SDK: plugins run in the host IDE's classloader, where
 * `okhttp3` resolves to the host's older OkHttp and an SDK bundling its own copy crashes with a
 * `NoSuchMethodError`. Kept apart from [com.itsaky.androidide.plugins.aiagentmcp.client.McpSession]
 * so the session is about the protocol, not about sockets.
 *
 * @param connectTimeoutMs how long to wait for the connection itself.
 * @param readTimeoutMs how long a call may take to answer.
 */
class McpHttpClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        /** A remote tool can take a while to run, so reading outlives connecting by a lot. */
        private const val READ_TIMEOUT_MS = 60_000

        /** Header carrying the server-assigned session, when the server keeps state. */
        const val HEADER_SESSION_ID = "Mcp-Session-Id"

        /** Header naming the negotiated revision, required from the 2025-06-18 revision on. */
        const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"

        private const val CONTENT_TYPE_SSE = "text/event-stream"
    }

    /**
     * One answer to a POST.
     * @property document the JSON-RPC document the server replied with, or null for `202 Accepted`,
     *   which is what a notification gets.
     * @property sessionId the session the server assigned or confirmed, when it sent one.
     */
    data class Response(val document: String?, val sessionId: String?)

    /**
     * POSTs [body] and reads whichever answer shape the server chose.
     *
     * @param url the server's MCP endpoint.
     * @param token bearer token, or blank for a server that needs none.
     * @param body the JSON-RPC envelope to send.
     * @param sessionId the session to continue, or null before one exists.
     * @param protocolVersion the negotiated revision, or null before `initialize` has answered.
     * @param onConnected receives the live connection, so a caller can disconnect it to cancel.
     * @return the reply document and any session id the server sent.
     * @throws McpHttpException on a non-2xx answer, carrying the server's error body.
     */
    fun post(
        url: String,
        token: String,
        body: JSONObject,
        sessionId: String? = null,
        protocolVersion: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        onConnected: (HttpURLConnection) -> Unit = {},
    ): Response {
        val conn = open(url, "POST", token, sessionId, protocolVersion, extraHeaders).apply {
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            // Both are advertised because the server picks, and a client that offers only one
            // gets 406 from servers that stream by default.
            setRequestProperty("Accept", "application/json, $CONTENT_TYPE_SSE")
        }
        onConnected(conn)
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            conn.failIfNotOk()

            val assignedSession = conn.getHeaderField(HEADER_SESSION_ID)?.takeIf { it.isNotBlank() }
            // 202 with no body is the correct answer to a notification; there is nothing to read.
            if (conn.responseCode == HttpURLConnection.HTTP_ACCEPTED) {
                return Response(null, assignedSession)
            }

            val streaming = conn.contentType.orEmpty().contains(CONTENT_TYPE_SSE, ignoreCase = true)
            val document = conn.inputStream.bufferedReader().use { reader ->
                if (streaming) readFirstSseDocument(reader) else reader.readText()
            }
            Response(document, assignedSession)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Ends a server-side session, best effort.
     *
     * A server that keeps no session answers 405 and a stale one answers 404; both mean the same
     * thing here — there is nothing left to close — so neither is worth surfacing.
     *
     * @param url the server's MCP endpoint.
     * @param token bearer token, or blank.
     * @param sessionId the session to end.
     */
    fun deleteSession(
        url: String,
        token: String,
        sessionId: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ) {
        val conn = open(url, "DELETE", token, sessionId, null, extraHeaders)
        try {
            Log.d(TAG, "Closing session, server answered ${conn.responseCode}")
        } catch (e: Exception) {
            Log.d(TAG, "Could not close the session: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Reads an SSE body until the first complete event, whose data is the JSON-RPC reply.
     *
     * Everything after it is dropped: this client has one call in flight and never subscribes to
     * the server-initiated stream, so a server that keeps the connection open must not keep the
     * caller waiting on it.
     *
     * @param reader the response body.
     * @return the reply document, or an empty string when the stream ended without one.
     */
    private fun readFirstSseDocument(reader: BufferedReader): String {
        val payload = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            when (val event = SseChunk.parse(line)) {
                is SseChunk.Event.Data -> payload.append(event.payload)
                is SseChunk.Event.Named -> Log.d(TAG, "SSE event '${event.name}'")
                SseChunk.Event.Dispatch -> if (payload.isNotEmpty()) return payload.toString()
                SseChunk.Event.Ignored -> Unit
            }
        }
        return payload.toString()
    }

    /**
     * Opens a connection carrying the session and bearer headers this call needs.
     *
     * The token travels as a header, never a query string: query strings leak into logs, proxies
     * and crash reports. The read timeout starts at the connect budget; only a POST raises it.
     *
     * The token is checked before the socket is opened for the same reason a user's own header is:
     * a CR or LF in it would forge a second header. The settings screen refuses one on Save, so
     * reaching this is a value that predates that check — refusing beats sending it.
     *
     * @throws IOException when the token cannot be put in a header.
     */
    private fun open(
        url: String,
        method: String,
        token: String,
        sessionId: String?,
        protocolVersion: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        if (token.isNotBlank() && !McpHeaders.isSendableToken(token)) {
            throw IOException("The stored token cannot be sent: it contains a line break or control character.")
        }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMs
            readTimeout = connectTimeoutMs
            instanceFollowRedirects = true
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
            // Before the session and protocol headers, so a user cannot displace either; the
            // sanitiser already refuses those names, and this makes the order irrelevant.
            McpHeaders.sanitize(extraHeaders).forEach { (name, value) ->
                setRequestProperty(name, value)
            }
            sessionId?.let { setRequestProperty(HEADER_SESSION_ID, it) }
            protocolVersion?.let { setRequestProperty(HEADER_PROTOCOL_VERSION, it) }
        }
    }

    /**
     * Fails with the server's error body attached, so the status reaches its readers as a number
     * rather than as text they have to match.
     */
    private fun HttpURLConnection.failIfNotOk() {
        val code = responseCode
        if (code !in 200..299) {
            val body = errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            throw McpHttpException(code, body)
        }
    }
}
