package com.drivelift.app.feature.whatsappsender.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.drivelift.app.data.MediaFolder
import com.drivelift.app.feature.whatsappsender.data.LocalMediaAdapter
import com.drivelift.app.feature.whatsappsender.model.LocalSendItem
import com.drivelift.app.feature.whatsappsender.model.WhatsAppSendPayload
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.formatBytes
import kotlinx.coroutines.launch

/**
 * Dedicated Quick Sender hub accessible from Phone and Storage tools.
 * Lets users browse any Pixel folder via SAF (Downloads, Documents, SD card) or pick local media folders
 * and launch [WhatsAppSenderSheet].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WhatsAppQuickSenderScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenPayload: ((WhatsAppSendPayload) -> Unit)? = null,
    onHandoffToDrive: ((List<Long>) -> Unit)? = null,
    onHandoffToDesktopDrop: ((List<LocalSendItem>) -> Unit)? = null,
    senderVm: WhatsAppSenderViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val media by vm.media.collectAsStateWithLifecycle()
    val mediaLoaded by vm.mediaLoaded.collectAsStateWithLifecycle()
    val senderState by senderVm.state.collectAsStateWithLifecycle()

    var internalPayload by remember { mutableStateOf<WhatsAppSendPayload?>(null) }
    var loadingExternal by remember { mutableStateOf(false) }
    var externalError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        senderVm.refreshAvailability()
    }

    val deliverPayload: (WhatsAppSendPayload) -> Unit = { payload ->
        if (onOpenPayload != null) {
            onOpenPayload(payload)
        } else {
            internalPayload = payload
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            loadingExternal = true
            externalError = null
            val result = runCatching { LocalMediaAdapter.readSafTree(context, uri) }
            loadingExternal = false
            result.onSuccess { payload ->
                if (payload.items.isEmpty()) {
                    externalError = "No readable files found in ${payload.title}."
                } else {
                    deliverPayload(payload)
                }
            }.onFailure { err ->
                externalError = err.message ?: "Could not read selected folder."
            }
        }
    }

    val filesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            loadingExternal = true
            externalError = null
            val result = runCatching { LocalMediaAdapter.readSafDocuments(context, uris) }
            loadingExternal = false
            result.onSuccess { payload ->
                if (payload.items.isEmpty()) {
                    externalError = "Selected files could not be read."
                } else {
                    deliverPayload(payload)
                }
            }.onFailure { err ->
                externalError = err.message ?: "Could not read selected files."
            }
        }
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("wa-quick-sender-screen"),
    ) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                scrolledContainerColor = Color.Transparent,
            ),
            title = { Text("WhatsApp Quick Sender") },
            navigationIcon = {
                IconButton(onClick = onBack, modifier = Modifier.size(Tokens.TouchTarget)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            },
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("wa-quick-sender-list"),
        ) {
            item(key = "status") {
                GlassPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
                ) {
                    Column(
                        modifier = Modifier.padding(Tokens.Gutter),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        Text(
                            senderState.availability.statusSummary(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            "Default: ${senderState.selectedTarget.label}, ${senderState.qualityMode.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (senderState.recentPresets.isNotEmpty()) {
                            Text(
                                "Recent presets",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = Tokens.Tiny),
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                            ) {
                                senderState.recentPresets.forEach { preset ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { senderVm.applyPreset(preset) },
                                        label = { Text("${preset.folderLabel}: ${preset.label}") },
                                        modifier = Modifier.defaultMinSize(minHeight = Tokens.TouchTarget),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item(key = "saf-browse") {
                GlassPanel(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
                ) {
                    Column(
                        modifier = Modifier.padding(Tokens.Gutter),
                        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                    ) {
                        Text("Any Pixel folder or file", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Browse Downloads, Documents, or any storage directory without granting broad storage access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { treeLauncher.launch(null) },
                            enabled = !loadingExternal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = Tokens.TouchTarget)
                                .testTag("wa-pick-saf-folder"),
                        ) {
                            Icon(
                                Icons.Outlined.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(Tokens.IconSmall),
                            )
                            Spacer(Modifier.width(Tokens.Small))
                            Text("Browse any folder")
                        }
                        OutlinedButton(
                            onClick = { filesLauncher.launch(arrayOf("*/*")) },
                            enabled = !loadingExternal,
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = Tokens.TouchTarget)
                                .testTag("wa-pick-saf-files"),
                        ) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(Tokens.IconSmall),
                            )
                            Spacer(Modifier.width(Tokens.Small))
                            Text("Pick individual files")
                        }
                    }
                }
            }

            if (loadingExternal) {
                item(key = "external-loading") {
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
                    ) {
                        Row(
                            modifier = Modifier.padding(Tokens.Gutter),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Tokens.Medium),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(Tokens.Icon))
                            Text("Reading selected files...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            externalError?.let { error ->
                item(key = "external-error") {
                    GlassPanel(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
                    ) {
                        Text(
                            error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(Tokens.Gutter),
                        )
                    }
                }
            }

            item(key = "folders-heading") {
                Text(
                    "Phone media folders",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(
                        start = Tokens.Edge,
                        end = Tokens.Edge,
                        top = Tokens.Gutter,
                        bottom = Tokens.Small,
                    ),
                )
            }

            if (!mediaLoaded) {
                item(key = "folders-loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Tokens.EmptyState),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            } else if (folders.isEmpty()) {
                item(key = "folders-empty") {
                    Text(
                        "No local media folders listed yet. Use Browse any folder or Pick individual files above.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
                    )
                }
            } else {
                items(folders, key = { "wa-folder-${it.bucketId}" }) { folder ->
                    QuickFolderRow(
                        folder = folder,
                        onSelect = {
                            val payload = LocalMediaAdapter.fromFolder(folder, media)
                            if (payload.items.isEmpty()) {
                                externalError = "No files found in ${folder.name}."
                            } else {
                                externalError = null
                                deliverPayload(payload)
                            }
                        },
                    )
                }
            }
        }
    }

    internalPayload?.let { payload ->
        WhatsAppSenderSheet(
            payload = payload,
            onDismiss = { internalPayload = null },
            onSent = { msg ->
                internalPayload = null
                vm.messages.tryEmit(msg)
            },
            onHandoffToDrive = onHandoffToDrive,
            onHandoffToDesktopDrop = onHandoffToDesktopDrop,
            senderVm = senderVm,
        )
    }
}

@Composable
private fun QuickFolderRow(
    folder: MediaFolder,
    onSelect: () -> Unit,
) {
    val context = LocalContext.current
    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny)
            .testTag("wa-folder-row-${folder.bucketId}"),
        onClick = onSelect,
        onClickLabel = "Send ${folder.name} to WhatsApp",
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Tokens.Medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(folder.cover).size(192).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(Tokens.FolderThumb)
                    .clip(Tokens.ThumbShape)
                    .background(GlassPalette.Thumb),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Tokens.Gutter),
            ) {
                Text(
                    folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${folder.count} items  \u00B7  ${formatBytes(context, folder.bytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (folder.path.isNotEmpty()) {
                    Text(
                        folder.path,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier.size(Tokens.TouchTarget),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.Send,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
