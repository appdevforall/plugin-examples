package com.appdevforall.pair.plugin.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles
import com.appdevforall.pair.plugin.ui.theme.peerColorFor
import java.io.File

@Composable
fun PeerRow(
    displayName: String,
    isHost: Boolean,
    color: Color,
    filePath: String?,
    line: Int?,
    column: Int?,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    val pulseKey = Triple(filePath, line, column)
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(pulseKey) {
        if (filePath != null) {
            alpha.snapTo(0.55f)
            alpha.animateTo(1f, animationSpec = tween(1200))
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = dimens.rowHeightMin)
            .padding(horizontal = dimens.spaceLg, vertical = dimens.spaceMd),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
    ) {
        Box(
            modifier = Modifier
                .size(dimens.liveDotSize)
                .alpha(alpha.value)
                .clip(CircleShape)
                .background(color),
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = styles.body.copy(color = MaterialTheme.colorScheme.onSurface),
                )
                if (isHost) {
                    Text(
                        text = "  HOST",
                        style = styles.label.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
            }
            Text(
                text = peerSubtitle(filePath, line, column),
                style = styles.mono.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = styles.small.fontSize,
                ),
            )
        }
    }
}

private fun peerSubtitle(filePath: String?, line: Int?, column: Int?): String {
    if (filePath.isNullOrBlank()) return "idle"
    val name = File(filePath).name
    return when {
        line != null && column != null -> "$name:${line + 1}:${column + 1}"
        line != null -> "$name:${line + 1}"
        else -> name
    }
}

@ThemePreviews
@Composable
private fun PeerRowHostPreview() {
    val peer = PreviewSamples.hostPeer
    PluginPreview {
        PeerRow(
            displayName = peer.displayName,
            isHost = peer.isHost,
            color = peerColorFor(peer.colorIndex),
            filePath = peer.currentFile,
            line = peer.cursorLine,
            column = peer.cursorColumn,
        )
    }
}

@ThemePreviews
@Composable
private fun PeerRowIdlePreview() {
    val peer = PreviewSamples.idlePeer
    PluginPreview {
        PeerRow(
            displayName = peer.displayName,
            isHost = peer.isHost,
            color = peerColorFor(peer.colorIndex),
            filePath = peer.currentFile,
            line = peer.cursorLine,
            column = peer.cursorColumn,
        )
    }
}
