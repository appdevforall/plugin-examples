package com.appdevforall.contractor.plugin.ui.common

/**
 * MVI marker types. State is what the View renders. Intent is what the View sends to the
 * ViewModel. Effect is a one-shot side effect (dismiss, snackbar, open dialog) that should not
 * be part of the rendered state because it shouldn't replay on configuration change.
 */
interface UiState

interface UiIntent

interface UiEffect
