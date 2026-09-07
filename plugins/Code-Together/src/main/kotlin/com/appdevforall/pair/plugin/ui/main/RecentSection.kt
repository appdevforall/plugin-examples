package com.appdevforall.pair.plugin.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.appdevforall.pair.plugin.data.StoredSession
import com.appdevforall.pair.plugin.ui.components.HistoryRow
import com.appdevforall.pair.plugin.ui.components.RenameDialog
import com.appdevforall.pair.plugin.ui.components.SectionLabel
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.PreviewSamples
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens

@Composable
fun RecentSection(
    sessions: List<StoredSession>,
    renamingSession: StoredSession?,
    onIntent: (PairIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sessions.isEmpty()) return

    val dimens = LocalPluginDimens.current

    Column(modifier = modifier.fillMaxWidth()) {
        SectionLabel("RECENT")
        Column(
            modifier = Modifier,
            verticalArrangement = Arrangement.spacedBy(dimens.spaceSm),
        ) {
            sessions.forEach { session ->
                HistoryRow(
                    session = session,
                    onReconnect = { onIntent(PairIntent.Reconnect(session)) },
                    onRename = { onIntent(PairIntent.RequestRename(session.id)) },
                    onDelete = { onIntent(PairIntent.DeleteSession(session)) },
                )
            }
        }
    }

    if (renamingSession != null) {
        RenameDialog(
            initial = renamingSession.customName ?: renamingSession.address,
            onConfirm = { name -> onIntent(PairIntent.ConfirmRename(name)) },
            onDismiss = { onIntent(PairIntent.DismissRename) },
        )
    }
}

@ThemePreviews
@Composable
private fun RecentSectionPreview() {
    PluginPreview {
        RecentSection(
            sessions = PreviewSamples.storedSessions,
            renamingSession = null,
            onIntent = {},
        )
    }
}

@ThemePreviews
@Composable
private fun RecentSectionRenamingPreview() {
    PluginPreview {
        RecentSection(
            sessions = PreviewSamples.storedSessions,
            renamingSession = PreviewSamples.storedSessions[0],
            onIntent = {},
        )
    }
}
