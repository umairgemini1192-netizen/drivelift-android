package com.drivelift.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.drivelift.app.data.DriveMovePlan

/**
 * Confirmation for moving Drive files to another Drive folder (issue #36): names, count, size and
 * the destination path, and which selected items stay where they are and why. Solid dialog (rule 3).
 */
@Composable
internal fun DriveMoveDialog(plan: DriveMovePlan, working: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val count = plan.eligible.size
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("drive-move"),
        title = {
            Text(
                when {
                    plan.sameFolder -> "Already in ${plan.toName}"
                    count == 0 -> if (plan.folders.size + plan.heldByTransfer.size == 1) "This item can't be moved" else "These items can't be moved"
                    count == 1 -> "Move 1 file to ${plan.toName}?"
                    else -> "Move $count files to ${plan.toName}?"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (plan.sameFolder) {
                    Text(
                        "The selected files are already in this folder. Choose a different folder.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (count > 0) {
                    Text(
                        "Moving to ${plan.toLabel}. Each file keeps its content and history; nothing is copied " +
                            "or deleted, and nothing on this phone is changed. " +
                            "${formatBytes(context, plan.eligible.sumOf { it.size })} in total.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Access can change: moved files take on the sharing of ${plan.toName}, so anyone who " +
                            "can open that folder can open them, and access they had only from this folder ends.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Tokens.Small),
                    )
                    DriveNames(plan.eligible)
                }
                if (plan.heldByTransfer.isNotEmpty() && !plan.sameFolder) {
                    Text(
                        "Left in place because a transfer still depends on them. Finish them in Transfers first:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    DriveNames(plan.heldByTransfer)
                }
                if (plan.folders.isNotEmpty() && !plan.sameFolder) {
                    Text(
                        "Folders are left in place; DriveLift moves files only:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    DriveNames(plan.folders)
                }
                if (working) {
                    Text(
                        "Moving...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                }
            }
        },
        confirmButton = {
            if (count > 0) {
                Button(onClick = onConfirm, enabled = !working) { Text("Move to ${plan.toName}") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (count > 0) {
            { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } }
        } else null,
    )
}
