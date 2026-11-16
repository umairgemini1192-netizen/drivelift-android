package com.drivelift.app.ui

import android.app.Activity
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.transfer.LiveProgress
import kotlinx.coroutines.delay

private const val REMOVE_BATCH = 2000

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransfersScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val transfers by vm.transfers.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val paused by vm.prefs.paused.collectAsStateWithLifecycle()
    val wifiOnly by vm.prefs.wifiOnly.collectAsStateWithLifecycle()
    val autoRemove by vm.prefs.autoRemove.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()

    fun count(s: TransferStatus) = counts[s.name]?.count ?: 0
    fun bytes(s: TransferStatus) = counts[s.name]?.bytes ?: 0L

    val pending = count(TransferStatus.QUEUED) + count(TransferStatus.UPLOADING)
    val uploaded = count(TransferStatus.UPLOADED)
    val verified = count(TransferStatus.VERIFIED)
    val removed = count(TransferStatus.REMOVED)
    val failed = count(TransferStatus.FAILED)
    val tracked = pending + uploaded + verified + removed
    val settledBytes = bytes(TransferStatus.UPLOADED) + bytes(TransferStatus.VERIFIED) + bytes(TransferStatus.REMOVED)
    val totalBytes = settledBytes + bytes(TransferStatus.QUEUED) + bytes(TransferStatus.UPLOADING)
    val uploading = live.phase == LiveProgress.Phase.UPLOADING
    val doneBytes = (settledBytes + if (uploading) live.sent else 0L).coerceAtMost(totalBytes)
    val fraction = if (totalBytes > 0) doneBytes.toFloat() / totalBytes else 0f
    val idle = live.phase == LiveProgress.Phase.IDLE && pending == 0 && uploaded == 0

    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000)
        }
    }

    // Removal from the phone always goes through Android's own confirmation dialog.
    var awaitingConfirm by remember { mutableStateOf<List<String>>(emptyList()) }
    var removeRequest by remember { mutableIntStateOf(0) }
    var lastAutoPrompt by rememberSaveable { mutableIntStateOf(-1) }
    val removeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vm.confirmRemoved(awaitingConfirm)
            lastAutoPrompt = -1
        }
        awaitingConfirm = emptyList()
    }
    LaunchedEffect(removeRequest) {
        if (removeRequest == 0) return@LaunchedEffect
        val uris = vm.verifiedForRemoval(REMOVE_BATCH)
        if (uris.isEmpty()) return@LaunchedEffect
        awaitingConfirm = uris
        runCatching {
            val request = MediaStore.createDeleteRequest(context.contentResolver, uris.map(Uri::parse))
            removeLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure {
            awaitingConfirm = emptyList()
            vm.messages.tryEmit("Android could not open the removal prompt: ${it.message}")
        }
    }
    LaunchedEffect(idle, verified, autoRemove) {
        if (autoRemove && idle && verified > 0 && verified != lastAutoPrompt) {
            lastAutoPrompt = verified
            removeRequest++
        }
    }

    if (tracked + failed + count(TransferStatus.CANCELLED) == 0) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Nothing in transit", style = MaterialTheme.typography.displaySmall)
            Text(
                "Open a folder on the Phone tab, select photos or videos, and choose where they go in Drive. Progress shows up here.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp).widthIn(max = 480.dp),
            )
            SettingRows(wifiOnly, autoRemove, vm, Modifier.padding(top = 24.dp))
        }
        return
    }

    val status: String = when {
        live.phase == LiveProgress.Phase.UPLOADING -> "Uploading ${live.name}"
        live.phase == LiveProgress.Phase.VERIFYING -> "Verifying ${live.name} against its Drive copy"
        live.phase == LiveProgress.Phase.RETRY_WAIT -> {
            val seconds = ((live.retryAtMs - now) / 1000).coerceAtLeast(0)
            "${live.message ?: "Something went wrong"}. Trying again in ${seconds}s."
        }
        paused && pending + uploaded > 0 -> "Paused. Nothing is lost; resume continues mid-file."
        pending + uploaded > 0 && !online -> "Waiting for a connection. This resumes on its own."
        pending + uploaded > 0 && wifiOnly -> "Waiting for Wi-Fi."
        pending > 0 -> "Starting"
        uploaded > 0 -> "Waiting to verify"
        verified > 0 -> "All uploads verified. Safe to remove from this phone."
        else -> "Done. Everything was moved and removed from this phone."
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "lead") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp)) {
                Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.displayLarge)
                Text(
                    status,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (live.phase == LiveProgress.Phase.RETRY_WAIT) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(8.dp),
                )
                val detail = buildString {
                    append("${formatBytes(context, doneBytes)} of ${formatBytes(context, totalBytes)}")
                    if (uploading && live.bytesPerSec > 0) {
                        append("  ·  ${formatBytes(context, live.bytesPerSec)}/s")
                        append("  ·  ${formatEta((totalBytes - doneBytes) / live.bytesPerSec)} left")
                    }
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                FlowRow(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Stat("Waiting", pending)
                    Stat("To verify", uploaded)
                    Stat("Verified", verified)
                    Stat("Removed", removed)
                    if (failed > 0) Stat("Failed", failed)
                }
            }
        }

        if (verified > 0) {
            item(key = "remove") {
                Column(Modifier.padding(horizontal = 20.dp).padding(top = 20.dp)) {
                    Button(
                        onClick = { removeRequest++ },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Remove $verified verified files from phone")
                    }
                    Text(
                        "Only files whose Drive copy matched byte for byte are included. Android asks you to confirm.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        item(key = "controls") {
            Column(Modifier.padding(horizontal = 20.dp).padding(top = 16.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (pending + uploaded > 0) {
                        if (paused) OutlinedButton(onClick = vm::resume) { Text("Resume") }
                        else OutlinedButton(onClick = vm::pause) { Text("Pause") }
                    }
                    if (failed > 0) OutlinedButton(onClick = vm::retryFailed) { Text("Retry failed") }
                    if (pending > 0) TextButton(onClick = vm::cancelAllPending) { Text("Cancel waiting") }
                    if (removed + failed + count(TransferStatus.CANCELLED) > 0) {
                        TextButton(onClick = vm::clearFinished) { Text("Clear finished") }
                    }
                }
                SettingRows(wifiOnly, autoRemove, vm, Modifier.padding(top = 8.dp))
            }
        }

        item(key = "heading") {
            Text(
                if (tracked > transfers.size) "Files (first ${transfers.size})" else "Files",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
            )
        }
        items(transfers, key = { it.id }) { item ->
            TransferRow(
                item = item,
                live = live.takeIf { it.itemId == item.id },
                onCancel = { vm.cancel(item.id) },
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

@Composable
private fun Stat(label: String, value: Int) {
    Column {
        Text("$value", style = MaterialTheme.typography.titleLarge)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingRows(wifiOnly: Boolean, autoRemove: Boolean, vm: AppViewModel, modifier: Modifier = Modifier) {
    Column(modifier) {
        SwitchRow(
            "Upload on Wi-Fi only", "Mobile data is never used for uploads.",
            wifiOnly, vm::setWifiOnly,
        )
        SwitchRow(
            "Offer removal when verified",
            "Opens Android's confirmation as soon as a batch is fully verified.",
            autoRemove, vm.prefs::setAutoRemove,
        )
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun TransferRow(item: TransferEntity, live: LiveProgress.State?, onCancel: () -> Unit) {
    val context = LocalContext.current
    val status = runCatching { TransferStatus.valueOf(item.status) }.getOrDefault(TransferStatus.QUEUED)
    val icon: ImageVector
    val label: String
    var tint = MaterialTheme.colorScheme.onSurfaceVariant
    when (status) {
        TransferStatus.QUEUED -> { icon = Icons.Outlined.Schedule; label = "Waiting" }
        TransferStatus.UPLOADING -> { icon = Icons.Outlined.CloudUpload; label = "Uploading"; tint = MaterialTheme.colorScheme.primary }
        TransferStatus.UPLOADED -> { icon = Icons.Outlined.CloudDone; label = "Uploaded, waiting to verify" }
        TransferStatus.VERIFIED -> { icon = Icons.Outlined.CheckCircle; label = "Verified in Drive"; tint = MaterialTheme.colorScheme.primary }
        TransferStatus.REMOVED -> { icon = Icons.Outlined.CheckCircle; label = "In Drive, removed from phone" }
        TransferStatus.FAILED -> { icon = Icons.Outlined.ErrorOutline; label = "Not moved"; tint = MaterialTheme.colorScheme.error }
        TransferStatus.CANCELLED -> { icon = Icons.Outlined.Close; label = "Cancelled" }
    }
    val active = status == TransferStatus.QUEUED || status == TransferStatus.UPLOADING

    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val progress = live?.takeIf { it.total > 0 && it.phase != LiveProgress.Phase.RETRY_WAIT }
            Text(
                buildString {
                    append(if (live?.phase == LiveProgress.Phase.VERIFYING) "Verifying" else label)
                    append("  ·  ")
                    if (progress != null) append("${formatBytes(context, progress.sent)} of ")
                    append(formatBytes(context, item.size))
                    append("  ·  ${item.destLabel}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.sent.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            val problem = item.lastError
            if (problem != null && (active || status == TransferStatus.FAILED)) {
                Text(
                    if (active) "$problem. Retry ${item.attempts}, still trying." else problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (active) {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = "Cancel ${item.name}")
            }
        }
    }
}
