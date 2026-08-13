package com.appdevforall.pair.plugin.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appdevforall.pair.plugin.data.DiscoveredHost
import com.appdevforall.pair.plugin.ui.components.DiscoveredHostRow
import com.appdevforall.pair.plugin.ui.components.SectionLabel
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens

@Composable
fun NearbySection(
    hosts: List<DiscoveredHost>,
    onIntent: (PairIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hosts.isEmpty()) return

    val dimens = LocalPluginDimens.current

    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel("NEARBY")
        Column(
            verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        ) {
            hosts.forEach { host ->
                DiscoveredHostRow(
                    host = host,
                    onJoin = { onIntent(PairIntent.JoinDiscoveredHost(host)) },
                )
            }
        }
    }
}

@ThemePreviews
@Composable
private fun NearbySectionPreview() {
    PluginPreview {
        NearbySection(
            hosts = PreviewSamples.discoveredHosts,
            onIntent = {},
        )
    }
}
