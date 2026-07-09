package com.appdevforall.pair.plugin.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.ui.components.PluginButtonFilled
import com.appdevforall.pair.plugin.ui.components.PluginButtonOutlined
import com.appdevforall.pair.plugin.ui.components.PluginButtonText
import com.appdevforall.pair.plugin.ui.components.PluginButtonTonal
import com.appdevforall.pair.plugin.ui.components.PluginTextField
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ScreenThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles

@Composable
fun HomeScreen(
    state: PairUiState,
    onIntent: (PairIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    var editingName by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dimens.spaceXl),
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(Modifier.height(dimens.spaceXl))

        IdentityRow(
            name = state.session.localDisplayName,
            onEdit = { editingName = true },
        )

        Spacer(Modifier.height(dimens.spaceLg))

        Text(
            text = "Code together on\nthe same WiFi.",
            style = styles.title.copy(color = MaterialTheme.colorScheme.onSurface),
        )

        Spacer(Modifier.height(dimens.spaceXl))

        // Path 1 — host.
        PluginButtonFilled(
            text = "HOST A SESSION",
            onClick = { onIntent(PairIntent.StartHosting) },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(dimens.spaceMd))

        // Path 2 — join (expands to manual address + passcode), plus QR scan.
        AnimatedVisibility(
            visible = state.joinMode,
            enter = fadeIn(tween(200)) + expandVertically(tween(200)),
            exit = fadeOut(tween(150)) + shrinkVertically(tween(150)),
        ) {
            Column {
                PluginTextField(
                    value = state.addressInput,
                    onValueChange = { onIntent(PairIntent.AddressChanged(it)) },
                    placeholder = "192.168.1.42:7050",
                    keyboardType = KeyboardType.Uri,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(dimens.spaceSm))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
                ) {
                    PluginTextField(
                        value = state.passcodeInput,
                        onValueChange = { onIntent(PairIntent.PasscodeChanged(it)) },
                        placeholder = "4-digit code",
                        keyboardType = KeyboardType.NumberPassword,
                        modifier = Modifier.weight(1f),
                    )
                    PluginButtonTonal(
                        text = "CONNECT",
                        onClick = { onIntent(PairIntent.SubmitJoin) },
                        enabled = state.addressInput.isNotBlank() &&
                            (state.addressInput.contains("?t=") || state.passcodeInput.isNotBlank()),
                    )
                }
                Spacer(Modifier.height(dimens.spaceMd))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        ) {
            PluginButtonOutlined(
                text = if (state.joinMode) "CANCEL" else "JOIN A SESSION",
                onClick = { onIntent(PairIntent.ToggleJoinMode) },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(dimens.spaceXl))

        if (state.discoveredHosts.isNotEmpty()) {
            NearbySection(
                hosts = state.discoveredHosts,
                onIntent = onIntent,
            )
            Spacer(Modifier.height(dimens.spaceXl))
        }

        if (state.session.connecting) {
            Text(
                text = "Connecting…",
                style = styles.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.height(dimens.spaceSm))
        }

        if (state.session.lastError != null) {
            Text(
                text = state.session.lastError,
                style = styles.small.copy(color = MaterialTheme.colorScheme.error),
            )
            Spacer(Modifier.height(dimens.spaceSm))
        }

        Text(
            text = "Both devices must be on the same WiFi or hotspot.",
            style = styles.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )

        Spacer(Modifier.height(dimens.spaceXl))

        RecentSection(
            sessions = state.recentSessions,
            renamingSession = state.renamingSession,
            onIntent = onIntent,
        )

        Spacer(Modifier.height(dimens.spaceXl))
    }

    if (editingName) {
        DeviceNameDialog(
            initial = state.session.localDisplayName,
            onConfirm = {
                onIntent(PairIntent.SetDeviceName(it))
                editingName = false
            },
            onDismiss = { editingName = false },
        )
    }
}

@Composable
private fun IdentityRow(
    name: String,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "YOU",
                style = styles.label.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Spacer(Modifier.height(dimens.spaceXs))
            Text(
                text = name,
                style = styles.subtitle.copy(color = MaterialTheme.colorScheme.onSurface),
            )
        }
        PluginButtonText(
            text = "EDIT",
            onClick = onEdit,
            contentColor = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun DeviceNameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Your name",
                style = styles.subtitle.copy(color = MaterialTheme.colorScheme.onSurface),
            )
        },
        text = {
            Column {
                Text(
                    text = "Shown to peers and on your cursor in the editor.",
                    style = styles.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                )
                Spacer(Modifier.height(dimens.spaceMd))
                PluginTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = "e.g. Daniel's phone",
                )
            }
        },
        confirmButton = {
            PluginButtonText(
                text = "SAVE",
                onClick = { onConfirm(value) },
                enabled = value.isNotBlank(),
            )
        },
        dismissButton = {
            PluginButtonText(
                text = "CANCEL",
                onClick = onDismiss,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@ScreenThemePreviews
@Composable
private fun HomeScreenPreview() {
    PluginPreview(contentPadding = 0.dp) {
        HomeScreen(state = PreviewSamples.homeState, onIntent = {})
    }
}

@ScreenThemePreviews
@Composable
private fun HomeScreenJoinPreview() {
    PluginPreview(contentPadding = 0.dp) {
        HomeScreen(state = PreviewSamples.homeJoinState, onIntent = {})
    }
}

@ScreenThemePreviews
@Composable
private fun HomeScreenErrorPreview() {
    PluginPreview(contentPadding = 0.dp) {
        HomeScreen(state = PreviewSamples.homeErrorState, onIntent = {})
    }
}
