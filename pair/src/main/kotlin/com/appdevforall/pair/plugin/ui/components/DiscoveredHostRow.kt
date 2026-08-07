package com.appdevforall.pair.plugin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.appdevforall.pair.plugin.data.DiscoveredHost
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginExtraColors
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles
import com.appdevforall.pair.plugin.ui.theme.peerColorFor

@Composable
fun DiscoveredHostRow(
    host: DiscoveredHost,
    onJoin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current
    val extras = LocalPluginExtraColors.current

    PluginCard(onClick = onJoin, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = dimens.rowHeightMin)
                .padding(horizontal = dimens.spaceLg, vertical = dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(dimens.spaceMd),
        ) {
            StatusDot(color = peerColorFor(0))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = host.displayName,
                    style = styles.body.copy(color = MaterialTheme.colorScheme.onSurface),
                )
                Text(
                    text = "${host.host}:${host.port}",
                    style = styles.small.copy(
                        color = extras.textMuted,
                        fontFamily = styles.mono.fontFamily,
                    ),
                )
            }
            Text(
                text = "JOIN",
                style = styles.label.copy(color = MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@ThemePreviews
@Composable
private fun DiscoveredHostRowPreview() {
    PluginPreview {
        DiscoveredHostRow(
            host = PreviewSamples.discoveredHosts[0],
            onJoin = {},
        )
    }
}
