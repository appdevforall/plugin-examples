package com.appdevforall.pair.plugin.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appdevforall.pair.plugin.data.DeviceSettingsStore
import com.appdevforall.pair.plugin.data.DiscoveredHost
import com.appdevforall.pair.plugin.data.PairDiscoveryService
import com.appdevforall.pair.plugin.data.SessionHistoryStore
import com.appdevforall.pair.plugin.data.SessionRole
import com.appdevforall.pair.plugin.data.SessionState
import com.appdevforall.pair.plugin.data.StoredSession
import com.appdevforall.pair.plugin.domain.EditBroker
import com.appdevforall.pair.plugin.util.NetUtil
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class PairViewModel(
    private val broker: EditBroker,
    private val history: SessionHistoryStore,
    private val discovery: PairDiscoveryService,
    private val deviceSettings: DeviceSettingsStore,
) : ViewModel() {

    private data class TransientState(
        val joinMode: Boolean = false,
        val addressInput: String = "",
        val passcodeInput: String = "",
        val renamingSessionId: String? = null,
        val discoverable: Boolean = true,
    )

    private val transient = MutableStateFlow(TransientState())

    val state: StateFlow<PairUiState> = combine(
        broker.state,
        history.sessions,
        transient,
        discovery.hosts,
    ) { session, sessions, ui, discoveredHosts ->
        PairUiState(
            session = session,
            recentSessions = sessions,
            joinMode = ui.joinMode,
            addressInput = ui.addressInput,
            passcodeInput = ui.passcodeInput,
            renamingSessionId = ui.renamingSessionId,
            discoverable = ui.discoverable,
            discoveredHosts = discoveredHosts,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MILLIS),
        initialValue = PairUiState(
            session = broker.state.value,
            recentSessions = history.sessions.value,
            discoveredHosts = discovery.hosts.value,
        ),
    )

    init {
        when (broker.state.value.role) {
            SessionRole.IDLE -> startBrowsing()
            SessionRole.HOST -> advertiseIfDiscoverable(broker.state.value)
            SessionRole.GUEST -> {}
        }
        var previousRole = broker.state.value.role
        broker.state
            .distinctUntilChangedBy { it.role }
            .onEach { session ->
                val priorRole = previousRole
                previousRole = session.role
                if (session.role != SessionRole.IDLE) {
                    transient.update { it.copy(joinMode = false) }
                }
                when {
                    priorRole != SessionRole.HOST && session.role == SessionRole.HOST -> {
                        recordHost(session)
                        discovery.stopDiscovery()
                        advertiseIfDiscoverable(session)
                    }
                    priorRole != SessionRole.GUEST && session.role == SessionRole.GUEST -> {
                        recordGuest(session)
                        discovery.stopDiscovery()
                    }
                    priorRole != SessionRole.IDLE && session.role == SessionRole.IDLE -> {
                        discovery.unregister()
                        startBrowsing()
                    }
                }
            }
            .launchIn(viewModelScope)
    }

    fun onIntent(intent: PairIntent) {
        when (intent) {
            PairIntent.StartHosting -> broker.startHosting()
            is PairIntent.SetDeviceName -> {
                val name = intent.name.trim().take(MAX_DEVICE_NAME)
                if (name.isNotEmpty()) {
                    deviceSettings.setDeviceName(name)
                    broker.setDisplayName(name)
                }
            }
            is PairIntent.SetShowPeerCursors -> {
                deviceSettings.setShowPeerCursors(intent.enabled)
                broker.setShowPeerCursors(intent.enabled)
            }
            PairIntent.ToggleJoinMode -> transient.update { current ->
                val enabled = !current.joinMode
                current.copy(
                    joinMode = enabled,
                    addressInput = if (enabled) current.addressInput else "",
                    passcodeInput = if (enabled) current.passcodeInput else "",
                )
            }
            is PairIntent.AddressChanged -> transient.update { it.copy(addressInput = intent.value) }
            is PairIntent.PasscodeChanged -> transient.update {
                it.copy(passcodeInput = intent.value.filter(Char::isDigit).take(PIN_DIGITS))
            }
            PairIntent.SubmitJoin -> submitJoin()
            is PairIntent.Reconnect -> reconnect(intent.session)
            is PairIntent.RequestRename -> transient.update { it.copy(renamingSessionId = intent.sessionId) }
            is PairIntent.ConfirmRename -> confirmRename(intent.newName)
            PairIntent.DismissRename -> transient.update { it.copy(renamingSessionId = null) }
            is PairIntent.DeleteSession -> history.delete(intent.session.id)
            PairIntent.StopSession -> broker.stopSession()
            PairIntent.ForceResync -> broker.forceResyncFromMe()
            PairIntent.PullProject -> broker.requestProjectFromHost()
            PairIntent.ConfirmOpenPulledProject -> broker.confirmOpenPulledProject()
            PairIntent.DismissOpenPulledProject -> broker.dismissPulledProject()
            PairIntent.Disconnect -> broker.stopSession()
            is PairIntent.JoinDiscoveredHost -> broker.joinSession(
                intent.host.host,
                intent.host.port,
                intent.host.token,
            )
            PairIntent.ToggleDiscoverable -> toggleDiscoverable()
        }
    }

    private fun toggleDiscoverable() {
        val enabled = !transient.value.discoverable
        transient.update { it.copy(discoverable = enabled) }
        val session = broker.state.value
        if (session.role != SessionRole.HOST) return
        if (enabled) advertiseIfDiscoverable(session) else discovery.unregister()
    }

    private fun startBrowsing() {
        discovery.startDiscovery(broker.state.value.localPeerId)
    }

    private fun advertiseIfDiscoverable(session: SessionState) {
        if (!transient.value.discoverable) return
        val port = session.localPort ?: return
        val token = session.localToken ?: return
        discovery.register(session.localDisplayName, port, token, session.localPeerId)
    }

    override fun onCleared() {
        discovery.stopDiscovery()
        super.onCleared()
    }

    private fun recordHost(session: SessionState) {
        val address = session.localAddress ?: return
        val port = session.localPort ?: return
        history.record(address, port, SessionRole.HOST)
    }

    private fun recordGuest(session: SessionState) {
        val parsed = NetUtil.parseAddress(session.remoteAddress ?: return) ?: return
        history.record(parsed.first, parsed.second, SessionRole.GUEST)
    }

    private fun submitJoin() {
        val invite = NetUtil.parseInvite(transient.value.addressInput) ?: return
        val token = invite.token.ifEmpty { transient.value.passcodeInput.trim() }
        broker.joinSession(invite.host, invite.port, token)
    }

    private fun reconnect(session: StoredSession) {
        if (session.role == SessionRole.HOST) {
            broker.startHosting()
        } else {
            transient.update {
                it.copy(joinMode = true, addressInput = "${session.address}:${session.port}")
            }
        }
    }

    private fun confirmRename(newName: String) {
        val id = transient.value.renamingSessionId ?: return
        history.rename(id, newName)
        transient.update { it.copy(renamingSessionId = null) }
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MILLIS = 5_000L
        const val PIN_DIGITS = 4
        const val MAX_DEVICE_NAME = 40
    }
}