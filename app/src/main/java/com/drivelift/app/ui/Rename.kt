package com.drivelift.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.data.NameCheck
import com.drivelift.app.data.PhoneRenamePlan
import com.drivelift.app.data.baseNameLength
import com.drivelift.app.data.checkPhoneRename
import com.drivelift.app.data.extensionOf

/**
 * Renames one item (issues #36 and #42). A solid dialog (rule 3) with the current name filled in
 * and the part before the ending selected. [validate] checks every keystroke; Rename stays off
 * while the name is empty, unchanged, not allowed, or already in the folder, and the reason is
 * shown under the field. Nothing is renamed until Rename is pressed.
 */
@Composable
internal fun RenameDialog(
    original: String,
    /** What renaming changes and what it does not, in one or two sentences. */
    explanation: String,
    validate: (String) -> NameCheck,
    working: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Shown under the field while the name is valid (for example a changed ending). */
    note: (NameCheck) -> String? = { null },
) {
    var field by remember(original) {
        mutableStateOf(TextFieldValue(original, selection = TextRange(0, baseNameLength(original))))
    }
    val check = validate(field.text)
    // An unchanged name is not an error to show, only a reason Rename is off.
    val edited = field.text.trim() != original
    val message = when {
        working -> "Renaming..."
        check.error != null && edited -> check.error
        else -> note(check)
    }
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        modifier = Modifier.testTag("rename"),
        title = { Text("Rename file") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    original,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    explanation,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = Tokens.Small, bottom = Tokens.Medium),
                )
                OutlinedTextField(
                    value = field,
                    onValueChange = { field = it },
                    label = { Text("New name") },
                    singleLine = true,
                    enabled = !working,
                    isError = check.error != null && edited,
                    supportingText = message?.let { text -> { Text(text) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(check.name) },
                enabled = check.error == null && !working,
                modifier = Modifier.testTag("rename-confirm"),
            ) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel") } },
    )
}

/**
 * The selection bar's summary with Rename beside it when [showRename] (exactly one file selected).
 * With large text or a narrow screen (rule 8) Rename moves under the summary, so the summary keeps
 * the full width.
 */
@Composable
internal fun SummaryWithRename(
    showRename: Boolean,
    onRename: () -> Unit,
    enabled: Boolean = true,
    summary: @Composable () -> Unit,
) {
    val stacked = LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE ||
        LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP
    val rename: @Composable () -> Unit = {
        TextButton(onClick = onRename, enabled = enabled, modifier = Modifier.testTag("rename-selected")) { Text("Rename") }
    }
    if (stacked) {
        Column(Modifier.fillMaxWidth()) {
            summary()
            if (showRename) rename()
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { summary() }
            if (showRename) rename()
        }
    }
}

/** The item cannot be renamed now: says why and what to do. Nothing was changed. */
@Composable
internal fun RenameBlockedDialog(name: String, reason: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("rename-blocked"),
        title = { Text("This file can't be renamed yet") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(reason, style = MaterialTheme.typography.bodyMedium)
                Text(
                    name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Tokens.Small),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/**
 * Renames the phone file [id] from a folder's selection. A file a transfer depends on is refused
 * with a reason. The name is checked against the other files in the folder, and the ending stays
 * the same. Android's write consent, when needed, is asked by [PhoneRenameHost].
 */
@Composable
fun PhoneRenameFlow(vm: AppViewModel, id: Long, onDone: () -> Unit) {
    var plan by remember(id) { mutableStateOf<PhoneRenamePlan?>(null) }
    var loaded by remember(id) { mutableStateOf(false) }
    LaunchedEffect(id) {
        plan = vm.planPhoneRename(id)
        loaded = true
    }
    val working by vm.phoneRenameWorking.collectAsStateWithLifecycle()
    // Closes once a confirmed rename has been reported, including after the screen was recreated.
    var seenWorking by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(working, seenWorking) {
        if (working) seenWorking = true else if (seenWorking) onDone()
    }
    if (!loaded) return
    val p = plan
    when {
        p == null -> LaunchedEffect(Unit) {
            vm.messages.tryEmit("This file is no longer in the folder. Nothing was renamed.")
            onDone()
        }
        p.heldByTransfer -> RenameBlockedDialog(
            name = p.item.name,
            reason = "It is part of a transfer to Google Drive. Finish or cancel it in Transfers first.",
            onDismiss = onDone,
        )
        else -> {
            val ending = extensionOf(p.item.name)
            RenameDialog(
                original = p.item.name,
                explanation = "Only the name changes. The file stays in this folder and its content is not changed." +
                    if (ending.isNotEmpty()) " The ending $ending stays." else "",
                validate = { checkPhoneRename(p, it) },
                working = working,
                onConfirm = { name ->
                    vm.startPhoneRename(p, name)
                    // A rename can finish before the next frame; this still closes the dialog then.
                    seenWorking = true
                },
                onDismiss = {
                    vm.cancelRename()
                    onDone()
                },
            )
        }
    }
}

/**
 * Shows Android's write-consent dialog for a pending phone rename and passes back its answer.
 * Composed above the lock gate in MainActivity, like [PhoneTrashHost], so the app locking while
 * the dialog is open does not drop the result.
 */
@Composable
fun PhoneRenameHost(vm: AppViewModel) {
    val pending by vm.pendingRename.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        vm.pendingRenameFinished(confirmed = result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(pending) {
        if (pending?.launched != false) return@LaunchedEffect
        runCatching {
            val request = vm.startPendingRename() ?: return@LaunchedEffect
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure {
            vm.pendingRenameFinished(confirmed = false, failure = it.message ?: it.javaClass.simpleName)
        }
    }
}
