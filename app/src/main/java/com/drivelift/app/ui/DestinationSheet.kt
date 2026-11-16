package com.drivelift.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.data.PresetEntity
import com.drivelift.app.transfer.Destination

/** Asks where the selected files should go: auto-organize, a preset, or any Drive folder. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DestinationSheet(vm: AppViewModel, onDismiss: () -> Unit, onChosen: (Destination) -> Unit) {
    val context = LocalContext.current
    val selection by vm.selection.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val presets by vm.presets.collectAsStateWithLifecycle()
    val conn by vm.driveConn.collectAsStateWithLifecycle()
    var browsing by remember { mutableStateOf(false) }
    val bytes = remember(selection, media) { media.sumOf { if (it.id in selection) it.size else 0L } }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = Tokens.Edge).padding(bottom = Tokens.Edge).navigationBarsPadding(),
        ) {
            Text("Move ${selection.size} items to Drive", style = MaterialTheme.typography.titleLarge)
            Text(
                "${formatBytes(context, bytes)}. Each file is checked against its Drive copy before " +
                    "anything is removed from this phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Tokens.Tiny, bottom = Tokens.Edge),
            )

            if (conn != DriveConn.CONNECTED) {
                DriveStatusRow(conn = conn, label = null, onConnect = { vm.connectDrive(interactive = true) })
                return@Column
            }

            Button(onClick = { onChosen(Destination.Auto) }, modifier = Modifier.fillMaxWidth()) {
                Text("Auto-organize by phone folder")
            }
            Text(
                "Creates \"${Destination.AUTO_ROOT}\" in My Drive with one folder per phone folder, such as Camera or Screenshots.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Tokens.Snug, bottom = Tokens.Edge),
            )

            if (presets.isNotEmpty()) {
                Text("Presets", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = Tokens.Small))
                presets.forEach { preset ->
                    PresetRow(
                        preset = preset,
                        onUse = { onChosen(Destination(preset.folderId, preset.path)) },
                        onRemove = { vm.unpin(preset.folderId) },
                    )
                }
            }

            OutlinedButton(
                onClick = { browsing = true },
                modifier = Modifier.fillMaxWidth().padding(top = Tokens.Medium),
            ) { Text("Choose a Drive folder") }
        }
    }

    if (browsing) {
        Dialog(
            onDismissRequest = { browsing = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            // A dialog is its own window, so it needs its own backdrop.
            GlassBackdrop {
                DriveBrowser(
                    vm = vm,
                    pickMode = true,
                    onClose = { browsing = false },
                    onPick = { dest, pin ->
                        if (pin && dest.folderId != null) {
                            vm.pin(PresetEntity(dest.folderId, dest.label.substringAfterLast(" / "), dest.label))
                        }
                        browsing = false
                        onChosen(dest)
                    },
                )
            }
        }
    }
}

@Composable
private fun PresetRow(preset: PresetEntity, onUse: () -> Unit, onRemove: () -> Unit) {
    Surface(
        shape = Tokens.PanelShape,
        // Solid, not glass: this row sits on the sheet, where text must stay readable.
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(Tokens.Hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = Tokens.Small),
    ) {
        Row(
            Modifier.clickable(onClick = onUse).padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Snug, bottom = Tokens.Snug),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = Tokens.Medium)) {
                Text(preset.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    preset.path, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove preset ${preset.name}")
            }
        }
    }
}
