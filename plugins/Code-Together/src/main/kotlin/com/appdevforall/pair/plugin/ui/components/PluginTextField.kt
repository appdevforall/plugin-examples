package com.appdevforall.pair.plugin.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import com.appdevforall.pair.plugin.ui.preview.PluginPreview
import com.appdevforall.pair.plugin.ui.preview.ThemePreviews
import com.appdevforall.pair.plugin.ui.theme.LocalPluginDimens

@Composable
fun PluginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    error: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val dimens = LocalPluginDimens.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = label?.let { { Text(it) } },
        placeholder = placeholder?.let { { Text(it) } },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        enabled = enabled,
        readOnly = readOnly,
        singleLine = singleLine,
        maxLines = maxLines,
        textStyle = textStyle,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(dimens.radiusMd),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.outline,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
            errorBorderColor = MaterialTheme.colorScheme.error,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            disabledTextColor = MaterialTheme.colorScheme.onSurface,
            focusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@ThemePreviews
@Composable
private fun PluginTextFieldEmptyPreview() {
    PluginPreview {
        PluginTextField(
            value = "",
            onValueChange = {},
            placeholder = "192.168.1.42:7050",
        )
    }
}

@ThemePreviews
@Composable
private fun PluginTextFieldFilledPreview() {
    PluginPreview {
        PluginTextField(
            value = "Studio iMac",
            onValueChange = {},
        )
    }
}

@ThemePreviews
@Composable
private fun PluginTextFieldErrorPreview() {
    PluginPreview {
        PluginTextField(
            value = "",
            onValueChange = {},
            error = "Invalid address",
        )
    }
}

@ThemePreviews
@Composable
private fun PluginTextFieldLabelPreview() {
    PluginPreview {
        PluginTextField(
            value = "",
            onValueChange = {},
            label = "Address",
        )
    }
}
