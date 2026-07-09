package com.appdevforall.pair.plugin.data

import com.appdevforall.pair.plugin.util.NetUtil
import com.appdevforall.pair.plugin.util.PairLog
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import java.nio.ByteBuffer

class PairWebSocketClient(
    host: String,
    port: Int,
    token: String,
    private val callbacks: ClientCallbacks,
    private val onDecodeError: (Throwable) -> Unit = {},
) : WebSocketClient(URI("ws://$host:$port"), mapOf(NetUtil.PAIR_TOKEN_HEADER to token)) {

    private val target: String = "$host:$port"

    interface ClientCallbacks {
        fun onConnected()
        fun onDisconnected(reason: String?)
        fun onMessageReceived(message: ProtocolMessage)
        fun onBinaryReceived(data: ByteBuffer)
        fun onError(error: Throwable)
    }

    override fun onOpen(handshakedata: ServerHandshake?) {
        PairLog.d("[CLIENT] onOpen connected to $target (httpStatus=${handshakedata?.httpStatus})")
        callbacks.onConnected()
    }

    override fun onMessage(message: String) {
        val parsed = runCatching { MessageCodec.decode(message) }
            .onFailure { onDecodeError(it) }
            .getOrNull() ?: return
        callbacks.onMessageReceived(parsed)
    }

    override fun onMessage(bytes: ByteBuffer) {
        callbacks.onBinaryReceived(bytes)
    }

    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        PairLog.d("[CLIENT] onClose target=$target code=$code reason=$reason remote=$remote")
        callbacks.onDisconnected(reason)
    }

    override fun onError(ex: Exception) {
        PairLog.e("[CLIENT] onError target=$target ${ex.javaClass.simpleName}: ${ex.message}", ex)
        callbacks.onError(ex)
    }

    fun sendMessage(message: ProtocolMessage) {
        if (!isOpen) return
        runCatching { send(MessageCodec.encode(message)) }
    }
}
