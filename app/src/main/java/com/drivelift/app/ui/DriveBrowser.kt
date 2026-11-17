package com.drivelift.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.drive.DriveClient
import com.drivelift.app.drive.DriveFile
import com.drivelift.app.transfer.Destination
import com.drivelift.app.transfer.describeError
import kotlin.math.roundToInt
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
    val view = LocalView.current
    val density = LocalDensity.current
    val safeDrawing = WindowInsets.safeDrawing
    val topInsetPx = safeDrawing.getTop(density)
    val bottomInsetPx = safeDrawing.getBottom(density)
    val conn by vm.driveConn.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var stack by remember { mutableStateOf(listOf(Crumb(DriveClient.ROOT, "My Drive"))) }
    var entries by remember { mutableStateOf<List<DriveFile>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var showNewFolder by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf(false) }
    val lastLoggedMetrics = remember { arrayOf<PickerWindowMetrics?>(null) }
    val here = stack.last()
    val path = stack.joinToString(" / ") { it.name }

    fun recordPickerBounds(update: PickerWindowMetrics.() -> PickerWindowMetrics) {
        if (!pickMode) return
        val next = vm.pickerMetrics.value.update().copy(
            windowWidthPx = view.width,
            windowHeightPx = view.height,
            topInsetPx = topInsetPx,
            bottomInsetPx = bottomInsetPx,
        )
        vm.pickerMetrics.value = next
        if (next.moveHereHeightPx > 0 && next != lastLoggedMetrics[0]) {
            lastLoggedMetrics[0] = next
            runCatching {
                Log.d(
                    "DriveLiftPicker",
                    "window=${next.windowWidthPx}x${next.windowHeightPx} " +
                        "insets=(${next.topInsetPx},${next.bottomInsetPx}) " +
                        "rootBottom=${next.rootBottomPx} " +
                        "footer=(${next.footerTopPx}..${next.footerBottomPx}) " +
                        "button=(${next.moveHereTopPx}..${next.moveHereBottomPx},h=${next.moveHereHeightPx})",
                )
            }
        }
    }

    LaunchedEffect(here.id, reload, conn) {
        if (conn != DriveConn.CONNECTED) return@LaunchedEffect
        entries = null
        error = null
        try {
            entries = vm.driveFolders.listChildren(here.id, foldersOnly = pickMode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeError(e)
        }
    }

    BackHandler(enabled = stack.size > 1 || onClose != null) {
        if (stack.size > 1) stack = stack.dropLast(1) else onClose?.invoke()
    }

    Column(
        Modifier.fillMaxSize().then(
            if (pickMode) Modifier.onGloballyPositioned { coords ->
                recordPickerBounds { copy(rootBottomPx = coords.boundsInWindow().bottom.roundToInt()) }
            } else Modifier
        )
    ) {
        TopAppBar(
            // Consumed when hosted inside AppRoot's Scaffold; still clears the status bar if
            // DriveBrowser is hosted directly without a Scaffold.
            windowInsets = TopAppBarDefaults.windowInsets,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
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
                    modifier = Modifier.padding(Tokens.Edge),
                )
                error != null -> Column(Modifier.padding(Tokens.Edge)) {
                    Text("Could not load this folder", style = MaterialTheme.typography.titleMedium)
                    Text(
                        error.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Tokens.Tiny, bottom = Tokens.Medium),
                    )
                    OutlinedButton(onClick = { reload++ }) { Text("Try again") }
                }
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                list.isEmpty() -> Text(
                    if (pickMode) "No folders inside. You can move files right here, or create a folder."
                    else "This folder is empty.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Tokens.Edge),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.id }) { file ->
                        DriveRow(
                            file = file,
                            sizeLabel = if (file.isFolder) null else formatBytes(context, file.size),
                            opensExternally = !file.isFolder && !pickMode,
                            onClick = when {
                                file.isFolder -> { { stack = stack + Crumb(file.id, file.name) } }
                                // The picker chooses folders only; a file there is not a target.
                                pickMode -> null
                                else -> { { openInDrive(context, file.id)?.let { scope.launch { vm.messages.emit(it) } } } }
                            },
                        )
                    }
                }
            }
        }

        if (pickMode && onPick != null && conn == DriveConn.CONNECTED) {
            Box(
                Modifier.fillMaxWidth().glassBar().onGloballyPositioned { coords ->
                    val bounds = coords.boundsInWindow()
                    recordPickerBounds {
                        copy(
                            footerTopPx = bounds.top.roundToInt(),
                            footerBottomPx = bounds.bottom.roundToInt(),
                        )
                    }
                },
            ) {
                // Keep the footer controls above system navigation even if DriveBrowser is hosted
                // outside AppRoot's Scaffold; inside AppRoot, Scaffold already consumes it.
                Column(
                    Modifier.fillMaxWidth().navigationBarsPadding()
                        .padding(horizontal = Tokens.Edge, vertical = Tokens.Medium),
                ) {
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { pin = !pin },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = pin, onCheckedChange = null)
                        Text(
                            "Keep this folder as a preset",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = Tokens.Medium, top = Tokens.Small, bottom = Tokens.Small),
                        )
                    }
                    Button(
                        onClick = { onPick(Destination(here.id, path), pin) },
                        modifier = Modifier.fillMaxWidth().padding(top = Tokens.Tiny).onGloballyPositioned { coords ->
                            val bounds = coords.boundsInWindow()
                            recordPickerBounds {
                                copy(
                                    moveHereTopPx = bounds.top.roundToInt(),
                                    moveHereBottomPx = bounds.bottom.roundToInt(),
                                    moveHereHeightPx = bounds.height.roundToInt(),
                                )
                            }
                        },
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
                                val created = vm.driveFolders.createFolder(name.trim(), here.id)
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
private fun DriveRow(file: DriveFile, sizeLabel: String?, opensExternally: Boolean, onClick: (() -> Unit)?) {
    val icon = when {
        file.isFolder -> Icons.Outlined.Folder
        file.mimeType.startsWith("image/") -> Icons.Outlined.Image
        file.mimeType.startsWith("video/") -> Icons.Outlined.Videocam
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
    GlassPanel(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
        onClick = onClick,
        onClickLabel = if (opensExternally) "Open in Google Drive" else null,
    ) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.RowLoose),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(Tokens.Icon),
            tint = if (file.isFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Tokens.Gutter))
        Column(Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sizeLabel != null) Text(
                sizeLabel, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (opensExternally) Icon(
            Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (file.isFolder) Icon(
            Icons.Outlined.ChevronRight, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    }
}

/**
 * Google Drive's own page for a file. It holds no token or account detail: whoever opens it is
 * asked by Google to sign in if needed.
 */
fun driveViewUrl(fileId: String): String = "https://drive.google.com/file/d/" + Uri.encode(fileId) + "/view"

/**
 * Opens a Drive file outside the app, in the Google Drive app if it is installed, otherwise in a
 * browser. DriveLift has no viewer of its own. Returns a message to show when nothing can open it.
 */
fun openInDrive(context: Context, fileId: String): String? {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(driveViewUrl(fileId))).addCategory(Intent.CATEGORY_BROWSABLE)
    return try {
        context.startActivity(intent)
        null
    } catch (e: ActivityNotFoundException) {
        "Nothing on this phone can open Google Drive links. Install Google Drive or a web browser."
    }
}
