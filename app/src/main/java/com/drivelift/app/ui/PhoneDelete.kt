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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.drivelift.app.data.MediaItem
import com.drivelift.app.data.PhoneDeletePlan

private const val DELETE_NAMES_SHOWN = 8

/**
 * A user-directed delete of phone files (issue #36), from the folder's selection or the viewer.
 * The files go to Android's trash through MediaStore.createTrashRequest, so they can be restored
 * for a while; DriveLift never deletes them outright.
 *
 * Confirmation follows invariant 3: with Media management granted, DriveLift's own text-only
 * dialog names the files and Android then completes the request without its preview; otherwise
 * Android's own dialog confirms. DriveLift's dialog is also shown whenever some selected files are
 * left out because a transfer still depends on them, so the user sees which ones.
 */
@Composable
fun PhoneDeleteFlow(vm: AppViewModel, ids: List<Long>, onDone: () -> Unit) {
    var plan by remember(ids) { mutableStateOf<PhoneDeletePlan?>(null) }
    // The exact batch handed to Android, kept across rotation and recreation so the result of
    // Android's dialog still reaches this flow (the launcher re-registers under the same key).
    var asked by rememberSaveable(ids) { mutableStateOf<LongArray?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.finishPhoneDelete(asked?.toList().orEmpty(), confirmed = result.resultCode == Activity.RESULT_OK)
        onDone()
    }

    fun askAndroid(items: List<MediaItem>) {
        asked = items.map { it.id }.toLongArray()
        runCatching {
            launcher.launch(IntentSenderRequest.Builder(vm.phoneTrashRequest(items).intentSender).build())
        }.onFailure {
            vm.messages.tryEmit("Android could not open the trash request: ${it.message}")
            onDone()
        }
    }

    // Waiting for Android's answer: nothing else to show or ask.
    if (asked != null) return
    LaunchedEffect(ids) { plan = vm.planPhoneDelete(ids) }

    val p = plan ?: return
    when {
        p.eligible.isEmpty() -> PhoneDeleteBlockedDialog(p.blocked, onDismiss = onDone)
        vm.textOnlyRemoval() || p.blocked.isNotEmpty() -> PhoneDeleteDialog(
            plan = p,
            onConfirm = { askAndroid(p.eligible) },
            onCancel = {
                vm.finishPhoneDelete(p.eligible.map { it.id }, confirmed = false)
                onDone()
            },
        )
        else -> LaunchedEffect(p) { askAndroid(p.eligible) }
    }
}

/** DriveLift's own confirmation: names, count and total size, and what the trash means. */
@Composable
internal fun PhoneDeleteDialog(plan: PhoneDeletePlan, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val count = plan.eligible.size
    AlertDialog(
        onDismissRequest = onCancel,
        modifier = Modifier.testTag("phone-delete"),
        title = { Text(if (count == 1) "Move 1 file to this phone's trash?" else "Move $count files to this phone's trash?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "They leave this phone's folders. Android keeps trashed items for a limited time, usually 30 days, " +
                        "then deletes them for good. Nothing on Google Drive is changed. ${formatBytes(context, plan.totalBytes)} in total.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FileNames(plan.eligible)
                if (plan.blocked.isNotEmpty()) {
                    Text(
                        "Left out because they are part of a transfer. Finish or cancel them in Transfers first:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = Tokens.Medium),
                    )
                    FileNames(plan.blocked)
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
            ) { Text("Move to trash") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Every selected file is part of a transfer: nothing can go to the trash yet. */
@Composable
internal fun PhoneDeleteBlockedDialog(blocked: List<MediaItem>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("phone-delete-blocked"),
        title = { Text(if (blocked.size == 1) "This file can't go to the trash yet" else "These files can't go to the trash yet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "They are part of a transfer to Google Drive. Finish or cancel them in Transfers first.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                FileNames(blocked)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun FileNames(items: List<MediaItem>) {
    val context = LocalContext.current
    items.take(DELETE_NAMES_SHOWN).forEach { item ->
        Text(
            "${item.name}  ·  ${formatBytes(context, item.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Tokens.Tiny),
        )
    }
    if (items.size > DELETE_NAMES_SHOWN) {
        Text(
            "and ${items.size - DELETE_NAMES_SHOWN} more",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Tiny),
        )
    }
}
