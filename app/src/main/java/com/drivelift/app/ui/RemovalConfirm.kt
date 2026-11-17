package com.drivelift.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow

/** One verified file in a removal batch, described by text only. */
data class RemovalItem(val name: String, val size: Long)

private const val NAMES_SHOWN = 8

/**
 * DriveLift's own removal confirmation, used when Media management access is granted: names and
 * sizes only, never a picture or video frame. Android then completes the delete request without
 * its preview dialog.
 */
@Composable
fun TextOnlyRemovalDialog(items: List<RemovalItem>, onRemove: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val total = items.sumOf { it.size }
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("text-only-removal"),
        title = {
            Text(if (items.size == 1) "Remove 1 file from this phone?" else "Remove ${items.size} files from this phone?")
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Each Drive copy matched its phone file in size and checksum. ${formatBytes(context, total)} in total.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                items.take(NAMES_SHOWN).forEach { item ->
                    Text(
                        "${item.name}  ·  ${formatBytes(context, item.size)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = Tokens.Tiny),
                    )
                }
                if (items.size > NAMES_SHOWN) {
                    Text(
                        "and ${items.size - NAMES_SHOWN} more",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Tiny),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onRemove,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Opens Android's Media management screen for DriveLift (Android 12 and later). */
fun openMediaManagementSettings(context: Context) {
    if (Build.VERSION.SDK_INT < 31) return
    val intent = Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:" + context.packageName))
    runCatching { context.startActivity(intent) }
}
