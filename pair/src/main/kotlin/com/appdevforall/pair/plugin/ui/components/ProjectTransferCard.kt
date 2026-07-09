package com.appdevforall.pair.plugin.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles

private enum class TransferPhase { Idle, Active, Done }

@Composable
fun ProjectTransferCard(
    received: Int,
    total: Int,
    onPull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val phase = when {
        total <= 0 -> TransferPhase.Idle
        received >= total -> TransferPhase.Done
        else -> TransferPhase.Active
    }

    PluginCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimens.spaceLg),
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
                label = "project-transfer",
            ) { state ->
                when (state) {
                    TransferPhase.Idle -> IdleContent(onPull)
                    TransferPhase.Active -> ActiveContent(received, total)
                    TransferPhase.Done -> DoneContent(total, onPull)
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onPull: () -> Unit) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "PROJECT",
            style = styles.label.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
        )
        Spacer(Modifier.height(dimens.spaceSm))
        Text(
            text = "Pull the host's files you don't have yet.",
            style = styles.bodyMuted,
        )
        Spacer(Modifier.height(dimens.spaceMd))
        PluginButtonFilled(
            text = "PULL FROM HOST",
            onClick = onPull,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ActiveContent(received: Int, total: Int) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    val fraction = if (total > 0) (received.toFloat() / total).coerceIn(0f, 1f) else 0f
    val animated by animateFloatAsState(targetValue = fraction, animationSpec = tween(200), label = "transfer-progress")

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "RECEIVING",
                style = styles.label.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
            Text(
                text = "$received / $total files",
                style = styles.mono.copy(color = MaterialTheme.colorScheme.onSurface),
            )
        }
        Spacer(Modifier.height(dimens.spaceMd))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(dimens.radiusSm))
                .background(MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun DoneContent(total: Int, onPull: () -> Unit) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        ) {
            StatusDot(color = MaterialTheme.colorScheme.primary)
            Text(
                text = "PROJECT SYNCED",
                style = styles.label.copy(color = MaterialTheme.colorScheme.primary),
            )
            Text(
                text = "· $total files",
                style = styles.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        PluginButtonText(
            text = "SYNC AGAIN",
            onClick = onPull,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@ThemePreviews
@Composable
private fun ProjectTransferIdlePreview() {
    PluginPreview {
        ProjectTransferCard(received = 0, total = 0, onPull = {})
    }
}

@ThemePreviews
@Composable
private fun ProjectTransferActivePreview() {
    PluginPreview {
        ProjectTransferCard(received = 23, total = 64, onPull = {})
    }
}

@ThemePreviews
@Composable
private fun ProjectTransferDonePreview() {
    PluginPreview {
        ProjectTransferCard(received = 64, total = 64, onPull = {})
    }
}
