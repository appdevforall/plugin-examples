package com.appdevforall.pair.plugin.domain

import com.appdevforall.pair.plugin.data.PeerSession
import com.appdevforall.pair.plugin.data.ProtocolMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

class PeerRegistry {

    private val peersById: ConcurrentHashMap<String, PeerSession> = ConcurrentHashMap()
    private val _peers: MutableStateFlow<List<PeerSession>> = MutableStateFlow(emptyList())
    val peers: StateFlow<List<PeerSession>> = _peers

    fun upsertFromHello(hello: ProtocolMessage.Hello, isHost: Boolean) {
        val now = System.currentTimeMillis()
        val session = peersById.compute(hello.peerId) { _, existing ->
            existing?.copy(
                displayName = hello.displayName,
                colorIndex = hello.colorIndex,
                isHost = isHost,
            ) ?: PeerSession(
                peerId = hello.peerId,
                displayName = hello.displayName,
                colorIndex = hello.colorIndex,
                isHost = isHost,
                joinedAtMillis = now,
            )
        }
        if (session != null) {
            publish()
        }
    }

    fun remove(peerId: String) {
        if (peersById.remove(peerId) != null) {
            publish()
        }
    }

    fun updateCursor(peerId: String, file: String, line: Int, column: Int) {
        val updated = peersById.compute(peerId) { _, existing ->
            existing?.copy(currentFile = file, cursorLine = line, cursorColumn = column)
        }
        if (updated != null) {
            publish()
        }
    }

    fun updateFocus(peerId: String, file: String?) {
        val updated = peersById.compute(peerId) { _, existing ->
            existing?.copy(currentFile = file)
        }
        if (updated != null) {
            publish()
        }
    }

    fun snapshot(): List<PeerSession> = peersById.values.toList()

    fun peer(peerId: String): PeerSession? = peersById[peerId]

    fun clear() {
        peersById.clear()
        _peers.update { emptyList() }
    }

    private fun publish() {
        _peers.update { peersById.values.sortedBy { it.joinedAtMillis } }
    }
}
