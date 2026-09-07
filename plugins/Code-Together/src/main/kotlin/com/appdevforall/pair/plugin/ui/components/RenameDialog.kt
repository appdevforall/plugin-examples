package com.appdevforall.pair.plugin.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginTextStyles

@Composable
fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val styles = LocalPluginTextStyles.current
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = "Rename session",
                style = styles.subtitle.copy(color = MaterialTheme.colorScheme.onSurface),
            )
        },
        text = {
            PluginTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = "Session name",
            )
        },
        confirmButton = {
            PluginButtonText(
                text = "RENAME",
                onClick = { onConfirm(value) },
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

@ThemePreviews
@Composable
private fun RenameDialogPreview() {
    PluginPreview {
        RenameDialog(
            initial = "Studio iMac",
            onConfirm = {},
            onDismiss = {},
        )
    }
}
