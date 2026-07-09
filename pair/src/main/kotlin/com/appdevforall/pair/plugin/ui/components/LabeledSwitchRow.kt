package com.appdevforall.pair.plugin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles

@Composable
fun LabeledSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
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
                text = title,
                style = styles.body.copy(color = MaterialTheme.colorScheme.onSurface),
            )
            Text(
                text = subtitle,
                style = styles.small.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { contentDescription = title },
        )
    }
}

@ThemePreviews
@Composable
private fun LabeledSwitchRowPreview() {
    PluginPreview {
        LabeledSwitchRow(
            title = "Show others' cursors",
            subtitle = "Display each peer's caret in your editor.",
            checked = true,
            onCheckedChange = {},
        )
    }
}
