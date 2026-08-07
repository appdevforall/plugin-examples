package com.appdevforall.pair.plugin.ui.main

import androidx.compose.runtime.Immutable
import com.appdevforall.pair.plugin.data.DiscoveredHost
import com.appdevforall.pair.plugin.data.SessionState
import com.appdevforall.pair.plugin.data.StoredSession

@Immutable
data class PairUiState(
    val session: SessionState,
    val recentSessions: List<StoredSession> = emptyList(),
    val joinMode: Boolean = false,
    val addressInput: String = "",
    val passcodeInput: String = "",
    val renamingSessionId: String? = null,
    val discoverable: Boolean = true,
    val discoveredHosts: List<DiscoveredHost> = emptyList(),
) {
    val renamingSession: StoredSession?
        get() = renamingSessionId?.let { id -> recentSessions.firstOrNull { it.id == id } }
}