package com.appdevforall.pair.plugin.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.appdevforall.pair.plugin.PairServiceLocator
import com.appdevforall.pair.plugin.data.SessionRole
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ScreenThemePreviews
import com.appdevforall.pair.plugin.ui.viewModelFactory

@Composable
fun PairRoot() {
    val container = remember { PairServiceLocator.get() }
    val viewModel: PairViewModel = viewModel(
        factory = remember(container) {
            viewModelFactory {
                PairViewModel(container.broker, container.history, container.discovery, container.deviceSettings)
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    PairContent(state = state, onIntent = viewModel::onIntent)
}

@Composable
fun PairContent(
    state: PairUiState,
    onIntent: (PairIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        AnimatedContent(
            targetState = state.session.role,
            transitionSpec = {
                (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 }) togetherWith
                    (fadeOut(tween(200)) + slideOutVertically(tween(200)) { -it / 4 })
            },
            label = "pair-root-role-transition",
        ) { role ->
            when (role) {
                SessionRole.IDLE -> HomeScreen(state = state, onIntent = onIntent)
                SessionRole.HOST -> HostSessionScreen(
                    session = state.session,
                    discoverable = state.discoverable,
                    onIntent = onIntent,
                )
                SessionRole.GUEST -> GuestSessionScreen(session = state.session, onIntent = onIntent)
            }
        }

        state.session.pendingProjectPath?.let { path ->
            val projectName = path.substringAfterLast('/').ifBlank { path }
            AlertDialog(
                onDismissRequest = { onIntent(PairIntent.DismissOpenPulledProject) },
                title = { Text("Open shared project?") },
                text = {
                    Text("This closes your current project and opens “$projectName” synced from the host.")
                },
                confirmButton = {
                    TextButton(onClick = { onIntent(PairIntent.ConfirmOpenPulledProject) }) {
                        Text("Open")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { onIntent(PairIntent.DismissOpenPulledProject) }) {
                        Text("Not now")
                    }
                },
            )
        }
    }
}

@ScreenThemePreviews
@Composable
private fun PairContentHomePreview() {
    PluginPreview(contentPadding = 0.dp) {
        PairContent(state = PreviewSamples.homeState, onIntent = {})
    }
}

@ScreenThemePreviews
@Composable
private fun PairContentHostPreview() {
    PluginPreview(contentPadding = 0.dp) {
        PairContent(state = PreviewSamples.hostUiState, onIntent = {})
    }
}

@ScreenThemePreviews
@Composable
private fun PairContentGuestPreview() {
    PluginPreview(contentPadding = 0.dp) {
        PairContent(state = PreviewSamples.guestUiState, onIntent = {})
    }
}