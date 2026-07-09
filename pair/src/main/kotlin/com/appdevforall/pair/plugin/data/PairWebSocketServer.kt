package com.appdevforall.pair.plugin.data

import com.appdevforall.pair.plugin.util.NetUtil
import com.appdevforall.pair.plugin.util.PairLog
import org.java_websocket.WebSocket
import org.java_websocket.framing.CloseFrame
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.Collections

class PairWebSocketServer(
    port: Int,
    private val expectedToken: String,
    private val callbacks: ServerCallbacks,
    private val onDecodeError: (Throwable) -> Unit = {},
) : WebSocketServer(InetSocketAddress(port)) {

    interface ServerCallbacks {
        fun onClientConnected(connection: WebSocket)
        fun onClientDisconnected(connection: WebSocket, reason: String?)
        fun onMessageReceived(connection: WebSocket, message: ProtocolMessage)
        fun onServerStarted(port: Int)
        fun onError(error: Throwable)
    }

    private val connections: MutableSet<WebSocket> = Collections.synchronizedSet(mutableSetOf())

    init {
        isReuseAddr = true
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
        val provided = handshake?.getFieldValue(NetUtil.PAIR_TOKEN_HEADER).orEmpty()
        val match = tokenMatches(provided)
        PairLog.d("[SERVER] onOpen from ${conn.remoteSocketAddress} providedTokenLen=${provided.length} match=$match")
        if (!match) {
            PairLog.w("[SERVER] rejecting ${conn.remoteSocketAddress}: token mismatch")
            runCatching { conn.close(CloseFrame.POLICY_VALIDATION, "unauthorized") }
            return
        }
        connections.add(conn)
        callbacks.onClientConnected(conn)
    }

    private fun tokenMatches(provided: String): Boolean {
        if (provided.isEmpty()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            expectedToken.toByteArray(Charsets.UTF_8),
        )
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        PairLog.d("[SERVER] onClose ${conn.remoteSocketAddress} code=$code reason=$reason remote=$remote")
        connections.remove(conn)
        callbacks.onClientDisconnected(conn, reason)
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val parsed = runCatching { MessageCodec.decode(message) }
            .onFailure { onDecodeError(it) }
            .getOrNull() ?: return
        callbacks.onMessageReceived(conn, parsed)
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        PairLog.e("[SERVER] onError conn=${conn?.remoteSocketAddress} ${ex.javaClass.simpleName}: ${ex.message}", ex)
        callbacks.onError(ex)
    }

    override fun onStart() {
        PairLog.d("[SERVER] onStart listening on port $port")
        callbacks.onServerStarted(port)
    }

    fun broadcastExcept(origin: WebSocket?, message: ProtocolMessage) {
        val encoded = MessageCodec.encode(message)
        val snapshot = synchronized(connections) { connections.toList() }
        for (conn in snapshot) {
            if (conn !== origin && conn.isOpen) {
                runCatching { conn.send(encoded) }
            }
        }
    }

    fun sendTo(target: WebSocket, message: ProtocolMessage) {
        if (!target.isOpen) return
        runCatching { target.send(MessageCodec.encode(message)) }
    }

    fun sendBinaryTo(target: WebSocket, data: ByteArray) {
        if (!target.isOpen) return
        runCatching { target.send(data) }
    }

    fun connectionCount(): Int = connections.size
}
