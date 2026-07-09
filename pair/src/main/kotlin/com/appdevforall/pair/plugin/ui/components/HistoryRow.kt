package com.appdevforall.pair.plugin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.appdevforall.pair.plugin.R
import com.appdevforall.pair.plugin.data.SessionRole
import com.appdevforall.pair.plugin.data.StoredSession
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginExtraColors
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles
import com.appdevforall.pair.plugin.ui.theme.peerColorFor

@Composable
fun HistoryRow(
    session: StoredSession,
    onReconnect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    val extras = LocalPluginExtraColors.current
    var menuExpanded by remember { mutableStateOf(false) }

    val dotColor = if (session.role == SessionRole.HOST) {
        peerColorFor(0)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    PluginCard(onClick = onReconnect, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowHeightMin)
                .padding(horizontal = dimens.spaceLg, vertical = dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            StatusDot(color = dotColor)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.customName ?: session.address,
                    style = styles.body.copy(color = MaterialTheme.colorScheme.onSurface),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${session.address}:${session.port}",
                        style = styles.small.copy(
                            color = extras.textMuted,
                            fontFamily = styles.mono.fontFamily,
                        ),
                    )
                    Text(
                        text = relativeTime(session.lastConnectedMillis),
                        style = styles.small.copy(color = extras.textSubtle),
                    )
                }

            }

            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_more_vert),
                        contentDescription = "Session options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename", style = styles.body) },
                        onClick = {
                            menuExpanded = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Delete",
                                style = styles.body.copy(color = MaterialTheme.colorScheme.error),
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@ThemePreviews
@Composable
private fun HistoryRowHostPreview() {
    PluginPreview {
        HistoryRow(
            session = PreviewSamples.storedSessions[0],
            onReconnect = {},
            onRename = {},
            onDelete = {},
        )
    }
}

@ThemePreviews
@Composable
private fun HistoryRowGuestPreview() {
    PluginPreview {
        HistoryRow(
            session = PreviewSamples.storedSessions[1],
            onReconnect = {},
            onRename = {},
            onDelete = {},
        )
    }
}

private fun relativeTime(millis: Long): String {
    val delta = System.currentTimeMillis() - millis
    if (delta < 0L) return "just now"
    val minutes = delta / 60_000L
    val hours = delta / 3_600_000L
    val days = delta / 86_400_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "${minutes}m ago"
        hours < 24L -> "${hours}h ago"
        else -> "${days}d ago"
    }
}
