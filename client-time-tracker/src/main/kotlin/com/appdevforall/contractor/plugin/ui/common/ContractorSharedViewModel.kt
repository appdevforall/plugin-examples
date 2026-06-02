package com.appdevforall.contractor.plugin.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Cross-tab navigation events. Intentionally NOT MVI — it's a tiny event bus, not a state
 * machine. Channel-based so events fire once and don't replay on reconfiguration.
 */
class ContractorSharedViewModel : ViewModel() {

    private val _focusOnProject = Channel<String>(Channel.BUFFERED)
    val focusOnProject: Flow<String> = _focusOnProject.receiveAsFlow()

    private val _switchToTab = Channel<Int>(Channel.BUFFERED)
    val switchToTab: Flow<Int> = _switchToTab.receiveAsFlow()

    fun requestFocus(projectId: String) {
        viewModelScope.launch { _focusOnProject.send(projectId) }
    }

    fun requestTab(index: Int) {
        viewModelScope.launch { _switchToTab.send(index) }
    }
}
