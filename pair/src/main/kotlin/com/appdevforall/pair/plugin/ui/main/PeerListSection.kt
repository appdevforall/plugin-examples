package com.appdevforall.pair.plugin.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appdevforall.pair.plugin.data.PeerSession
import com.appdevforall.pair.plugin.ui.components.PeerRow
import com.appdevforall.pair.plugin.ui.components.SectionLabel
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles
import com.appdevforall.pair.plugin.ui.theme.peerColorFor

@Composable
fun PeerListSection(
    peers: List<PeerSession>,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalPluginDimens.current
    val styles = LocalPluginTextStyles.current

    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel(text = "PEERS (${peers.size})")
        if (peers.isEmpty()) {
            Text(
                text = "Waiting for someone to join.",
                style = styles.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                modifier = Modifier.padding(horizontal = dimens.spaceLg, vertical = dimens.spaceSm),
            )
        } else {
            peers.forEachIndexed { index, peer ->
                if (index > 0) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        thickness = dimens.borderHairline,
                        modifier = Modifier.padding(horizontal = dimens.spaceLg),
                    )
                }
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
        Spacer(Modifier.height(dimens.spaceSm))
    }
}

@ThemePreviews
@Composable
private fun PeerListSectionPreview() {
    PluginPreview {
        PeerListSection(peers = PreviewSamples.peers)
    }
}

@ThemePreviews
@Composable
private fun PeerListSectionEmptyPreview() {
    PluginPreview {
        PeerListSection(peers = emptyList())
    }
}
