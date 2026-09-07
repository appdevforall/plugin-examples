package com.appdevforall.contractor.plugin.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Base ViewModel implementing the unidirectional MVI cycle.
 *
 *  View → [dispatch]([UiIntent]) → [handleIntent] → [reduce] → [state] → View
 *                                              ↘ [emit] → [effects] → View (one-shot)
 *
 * Subclasses implement [handleIntent] and use [reduce] to update state, [emit] to send
 * one-shot effects (snackbars, dialogs, dismissals).
 */
abstract class BaseViewModel<S : UiState, I : UiIntent, E : UiEffect>(initialState: S) : ViewModel() {

    private val _state = MutableStateFlow(initialState)
    val state: StateFlow<S> = _state.asStateFlow()

    private val _effects = Channel<E>(Channel.BUFFERED)
    val effects: Flow<E> = _effects.receiveAsFlow()

    protected val currentState: S get() = _state.value

    fun dispatch(intent: I) {
        handleIntent(intent)
    }

    protected abstract fun handleIntent(intent: I)

    protected fun reduce(reducer: S.() -> S) {
        _state.update(reducer)
    }

    protected fun emit(effect: E) {
        viewModelScope.launch { _effects.send(effect) }
    }
}
