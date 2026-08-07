package com.appdevforall.pair.plugin.data

data class PeerSession(
    val peerId: String,
    val displayName: String,
    val colorIndex: Int,
    val isHost: Boolean,
    val joinedAtMillis: Long,
    val currentFile: String? = null,
    val cursorLine: Int? = null,
    val cursorColumn: Int? = null,
)

enum class SessionRole {
    IDLE,
    HOST,
    GUEST,
}

data class SessionState(
    val role: SessionRole,
    val localPeerId: String,
    val localDisplayName: String,
    val localAddress: String? = null,
    val localPort: Int? = null,
    val localToken: String? = null,
    val remoteAddress: String? = null,
    val connecting: Boolean = false,
    val showPeerCursors: Boolean = true,
    val peers: List<PeerSession> = emptyList(),
    val outOfSync: Boolean = false,
    val transferReceived: Int = 0,
    val transferTotal: Int = 0,
    val pendingProjectPath: String? = null,
    val lastError: String? = null,
)
