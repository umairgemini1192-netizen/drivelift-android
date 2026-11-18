package com.drivelift.app.ui

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
import com.drivelift.app.data.DriveTrashPlan
import com.drivelift.app.drive.DriveFile

private const val DRIVE_NAMES_SHOWN = 8

/**
 * Confirmation for a user-directed Drive "Move to trash" (issue #36): names, count and size, what
 * Drive's trash means, and which selected items are left out and why. Solid dialog (rule 3).
 */
@Composable
internal fun DriveTrashDialog(plan: DriveTrashPlan, working: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val count = plan.eligible.size
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("drive-trash"),
        title = {
            Text(
                when (count) {
                    0 -> if (plan.folders.size + plan.heldByTransfer.size == 1) "This item can't go to the trash" else "These items can't go to the trash"
                    1 -> "Move 1 file to Google Drive's trash?"
                    else -> "Move $count files to Google Drive's trash?"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (count > 0) {
                    Text(
                        "They leave this Drive folder. Google keeps trashed files for 30 days, where you can restore " +
                            "them from Drive's trash, then deletes them for good. Nothing on this phone is changed. " +
                            "${formatBytes(context, plan.eligible.sumOf { it.size })} in total.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DriveNames(plan.eligible)
                }
                if (plan.onlyCopy.isNotEmpty()) {
                    Text(
                        "DriveLift already removed the phone originals of these after checking this copy, so the Drive copy is the only one:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    DriveNames(plan.onlyCopy)
                }
                if (plan.heldByTransfer.isNotEmpty()) {
                    Text(
                        "Left out because a transfer still depends on them. Finish them in Transfers first:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    DriveNames(plan.heldByTransfer)
                }
                if (plan.folders.isNotEmpty()) {
                    Text(
                        "Folders are left out; DriveLift moves files only:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    DriveNames(plan.folders)
                }
                if (working) {
                    Text(
                        "Moving to the trash...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                }
            }
        },
        confirmButton = {
            if (count > 0) {
                Button(
                    onClick = onConfirm,
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Move to trash") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (count > 0) {
            { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } }
        } else null,
    )
}

@Composable
internal fun DriveNames(files: List<DriveFile>) {
    val context = LocalContext.current
    files.take(DRIVE_NAMES_SHOWN).forEach { file ->
        Text(
            if (file.isFolder) file.name else "${file.name}  ·  ${formatBytes(context, file.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Tokens.Tiny),
        )
    }
    if (files.size > DRIVE_NAMES_SHOWN) {
        Text(
            "and ${files.size - DRIVE_NAMES_SHOWN} more",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Tiny),
        )
    }
}
