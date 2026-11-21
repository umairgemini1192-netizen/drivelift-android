package com.drivelift.app.feature.storageintelligence.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import com.drivelift.app.feature.storageintelligence.model.BatchCleanupPlan
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.formatBytes

/**
 * Explicit confirmation modal detailing per-source item counts, reclaimable bytes,
 * soft-delete Trash recoverability, and skipped protected items before any cleanup executes.
 */
@Composable
fun BatchCleanupConfirmDialog(
    plan: BatchCleanupPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("si-cleanup-confirm"),
        title = { Text(plan.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${plan.totalEligibleCount} items selected, ${formatBytes(context, plan.totalReclaimableBytes)} reclaimable.",
                    style = MaterialTheme.typography.titleSmall,
                )
                for (source in plan.perSourceBreakdown) {
                    val countWord = if (source.itemCount == 1) "1 item" else "${source.itemCount} items"
                    Text(
                        "${source.sourceLabel}: $countWord (${formatBytes(context, source.reclaimableBytes)})",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    Text(
                        source.destinationNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plan.skippedHeldCount > 0) {
                    Text(
                        "${plan.skippedHeldCount} items left out because an active transfer depends on them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                }
                if (plan.skippedLastCopyCount > 0) {
                    Text(
                        "${plan.skippedLastCopyCount} items kept so every duplicate group preserves at least 1 copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Small),
                    )
                }
                if (plan.skippedLockedOrphanCount > 0) {
                    Text(
                        "${plan.skippedLockedOrphanCount} cache or temp items skipped because they are inside the safety grace period or locked by transfers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Small),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Text("Confirm cleanup")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Dialog allowing a custom size threshold in megabytes for Large-File Radar.
 */
@Composable
fun CustomThresholdDialog(
    currentMb: Long,
    onConfirmMb: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(currentMb) { mutableStateOf(currentMb.toString()) }
    val parsed = text.trim().toLongOrNull()
    val valid = parsed != null && parsed in 1L..100_000L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom size threshold") },
        text = {
            Column {
                Text(
                    "Show files at least this size in megabytes.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = Tokens.Medium),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(6) },
                    label = { Text("Minimum MB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (parsed != null) onConfirmMb(parsed) },
                enabled = valid,
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
