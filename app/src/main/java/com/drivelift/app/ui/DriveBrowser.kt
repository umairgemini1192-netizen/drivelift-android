package com.drivelift.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.transfer.Destination
import com.drivelift.app.transfer.describeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class Crumb(val id: String, val name: String)

/**
 * Browses Google Drive. As a tab it shows folders and files. In pick mode it
 * shows folders only and ends with "Move here".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveBrowser(
    vm: AppViewModel,
    pickMode: Boolean,
    onClose: (() -> Unit)? = null,
    onPick: ((dest: Destination, pin: Boolean) -> Unit)? = null,
) {
    val context = LocalContext.current
    val conn by vm.driveConn.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var stack by remember { mutableStateOf(listOf(Crumb(DriveClient.ROOT, "My Drive"))) }
    var entries by remember { mutableStateOf<List<DriveFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showNewFolder by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(false) }
    val here = stack.last()
    val path = stack.joinToString(" / ") { it.name }

    LaunchedEffect(here.id, reload, conn) {
        if (conn != DriveConn.CONNECTED) return@LaunchedEffect
        entries = null
        error = null
        try {
            entries = vm.drive.listChildren(here.id, foldersOnly = pickMode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeError(e)
        }
    }

    BackHandler(enabled = stack.size > 1) { stack = stack.dropLast(1) }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(here.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (stack.size > 1) path else account?.email ?: "Google Drive",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            },
            navigationIcon = {
                when {
                    stack.size > 1 -> IconButton(onClick = { stack = stack.dropLast(1) }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Up one folder")
                    }
                    onClose != null -> IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
            },
            actions = {
                if (conn == DriveConn.CONNECTED) {
                    IconButton(onClick = { showNewFolder = true }) {
                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = "New folder here")
                    }
                    IconButton(onClick = { reload++ }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Reload")
                    }
                }
            },
        )

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = entries
            when {
                conn != DriveConn.CONNECTED -> DriveStatusRow(
                    conn = conn, label = null,
                    onConnect = { vm.connectDrive(interactive = true) },
                    modifier = Modifier.padding(20.dp),
                )
                error != null -> Column(Modifier.padding(20.dp)) {
                    Text("Could not load this folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    )
                    OutlinedButton(onClick = { reload++ }) { Text("Try again") }
                }
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    if (pickMode) "No folders inside. You can move files right here, or create a folder."
                    else "This folder is empty.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { file ->
                        DriveRow(
                            file = file,
                            sizeLabel = if (file.isFolder) null else formatBytes(context, file.size),
                            onClick = if (file.isFolder) {
                                { stack = stack + Crumb(file.id, file.name) }
                            } else null,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 60.dp),
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                }
            }
        }

        if (pickMode && onPick != null && conn == DriveConn.CONNECTED) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { pin = !pin },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = pin, onCheckedChange = null)
                        Text(
                            "Keep this folder as a preset",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                        )
                    }
                    Button(
                        onClick = { onPick(Destination(here.id, path), pin) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    ) {
                        Text("Move here: ${here.name}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    if (showNewFolder) {
        var name by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) showNewFolder = false },
            title = { Text("New folder in ${here.name}") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Folder name") },
                    singleLine = true,
                    enabled = !busy,
                    isError = failure != null,
                    supportingText = failure?.let { msg -> { Text(msg) } },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank() && !busy,
                    onClick = {
                        busy = true
                        failure = null
                        scope.launch {
                            try {
                                val created = vm.drive.createFolder(name.trim(), here.id)
                                showNewFolder = false
                                stack = stack + Crumb(created.id, created.name)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                failure = describeError(e)
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) { Text(if (busy) "Creating" else "Create") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { showNewFolder = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DriveRow(file: DriveFile, sizeLabel: String?, onClick: (() -> Unit)?) {
    val icon = when {
        file.isFolder -> Icons.Outlined.Folder
        file.mimeType.startsWith("image/") -> Icons.Outlined.Image
        file.mimeType.startsWith("video/") -> Icons.Outlined.Videocam
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(24.dp),
            tint = if (file.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sizeLabel != null) Text(
                sizeLabel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (file.isFolder) Icon(
            Icons.Outlined.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
