package com.appdevforall.pair.plugin.ui.main

import com.appdevforall.pair.plugin.data.DiscoveredHost
import com.appdevforall.pair.plugin.data.StoredSession

sealed interface PairIntent {
    data object StartHosting : PairIntent
    data class SetDeviceName(val name: String) : PairIntent
    data class SetShowPeerCursors(val enabled: Boolean) : PairIntent
    data object ToggleJoinMode : PairIntent
    data class AddressChanged(val value: String) : PairIntent
    data class PasscodeChanged(val value: String) : PairIntent
    data object SubmitJoin : PairIntent
    data class Reconnect(val session: StoredSession) : PairIntent
    data class RequestRename(val sessionId: String) : PairIntent
    data class ConfirmRename(val newName: String) : PairIntent
    data object DismissRename : PairIntent
    data class DeleteSession(val session: StoredSession) : PairIntent
    data object StopSession : PairIntent
    data object ForceResync : PairIntent
    data object PullProject : PairIntent
    data object ConfirmOpenPulledProject : PairIntent
    data object DismissOpenPulledProject : PairIntent
    data object Disconnect : PairIntent
    data class JoinDiscoveredHost(val host: DiscoveredHost) : PairIntent
    data object ToggleDiscoverable : PairIntent
}