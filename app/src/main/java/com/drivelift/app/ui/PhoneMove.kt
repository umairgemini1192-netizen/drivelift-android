package com.drivelift.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.data.PhoneMovePlan

/**
 * Confirmation for moving phone files to another phone folder: names, count, size and the
 * destination, and which held files stay where they are and why. Solid dialog (rule 3).
 */
@Composable
internal fun PhoneMoveDialog(plan: PhoneMovePlan, working: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val count = plan.eligible.size
    val leftOut = plan.heldByTransfer.size + plan.nameTaken.size + plan.alreadyThere.size + plan.otherVolume.size
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("phone-move"),
        title = {
            Text(
                when {
                    count == 0 -> if (leftOut == 1) "This file can't be moved here" else "These files can't be moved here"
                    count == 1 -> "Move 1 file to ${plan.toName}?"
                    else -> "Move $count files to ${plan.toName}?"
                },
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (count > 0) {
                    Text(
                        "Moving to ${plan.toRelativePath.trimEnd('/')} on this phone. Each file keeps its name, " +
                            "content and date; nothing is copied or deleted, and nothing in Google Drive is changed. " +
                            "${formatBytes(context, plan.totalBytes)} in total.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Apps that look for these files in their old folder will no longer find them there.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Small),
                    )
                    FileNames(plan.eligible)
                }
                if (plan.heldByTransfer.isNotEmpty()) {
                    Text(
                        "Left in place because a transfer still depends on them. Finish them in Transfers first:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    FileNames(plan.heldByTransfer)
                }
                if (plan.nameTaken.isNotEmpty()) {
                    Text(
                        "Left in place because ${plan.toName} already has a file with the same name. Rename them first:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    FileNames(plan.nameTaken)
                }
                if (plan.otherVolume.isNotEmpty()) {
                    Text(
                        "Left in place because they are on other storage, such as an SD card, and ${plan.toName} " +
                            "is not. Android moves files only between folders on the same storage:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    FileNames(plan.otherVolume)
                }
                if (plan.alreadyThere.isNotEmpty()) {
                    Text(
                        "Already in ${plan.toName}:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    FileNames(plan.alreadyThere)
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

/**
 * Asks Android for write consent when a confirmed phone move includes files DriveLift does not
 * own. Lives outside the lock gate, like PhoneRenameHost, so locking keeps the result handler.
 */
@Composable
fun PhoneMoveHost(vm: AppViewModel) {
    val pending by vm.pendingPhoneMove.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.pendingPhoneMoveFinished(confirmed = result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(pending) {
        if (pending?.launched != false) return@LaunchedEffect
        runCatching {
            val request = vm.startPendingPhoneMove() ?: return@LaunchedEffect
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure {
            vm.pendingPhoneMoveFinished(confirmed = false, failure = it.message ?: it.javaClass.simpleName)
        }
    }
}
