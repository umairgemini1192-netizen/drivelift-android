package com.drivelift.app.feature.desktopdrop.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LaptopMac
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.drivelift.app.feature.desktopdrop.model.BiometricGateMode
import com.drivelift.app.feature.desktopdrop.model.ConnectivityMode
import com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot
import com.drivelift.app.feature.desktopdrop.model.DropTransferProgress
import com.drivelift.app.feature.desktopdrop.model.DropTransferStatus
import com.drivelift.app.feature.desktopdrop.model.HotspotCredentials
import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import com.drivelift.app.feature.desktopdrop.qr.QrBitmapGenerator
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.GlassPalette
import com.drivelift.app.ui.GlassPanel
import com.drivelift.app.ui.Tokens
import com.drivelift.app.ui.formatBytes
import com.drivelift.app.ui.formatEta

/**
 * Primary Compose UI for Zero-Config Peer & Desktop Drop following DriveLift's Dark Tactile
 * Glass design system.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopDropScreen(
    @Suppress("UNUSED_PARAMETER") appViewModel: AppViewModel,
    vm: DesktopDropViewModel = viewModel(),
) {
    val context = LocalContext.current
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var troubleshootingExpanded by rememberSaveable { mutableStateOf(false) }
    var startHotspotAfterGrant by remember { mutableStateOf(false) }

    val hotspotPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val missing = vm.missingHotspotPermissions()
        if (missing.isEmpty() && startHotspotAfterGrant) {
            startHotspotAfterGrant = false
            vm.startServer(ConnectivityMode.DIRECT_HOTSPOT)
        } else if (missing.isNotEmpty()) {
            startHotspotAfterGrant = false
            vm.notifyMessage("Nearby Wi-Fi permission is required for Direct Hotspot")
        }
    }

    val pickFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNotEmpty()) vm.addSharedDocuments(uris)
    }

    val pickShareFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) vm.addSharedFolder(uri)
    }

    val pickDestinationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) vm.setDestinationFolder(uri)
    }

    LaunchedEffect(Unit) {
        vm.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    fun requestModeOrStart(targetMode: ConnectivityMode, startImmediately: Boolean) {
        if (targetMode == ConnectivityMode.DIRECT_HOTSPOT) {
            val missing = vm.missingHotspotPermissions()
            if (missing.isNotEmpty()) {
                startHotspotAfterGrant = startImmediately || snapshot.status == ServerStatus.RUNNING
                hotspotPermissionLauncher.launch(missing.toTypedArray())
                return
            }
        }
        if (startImmediately) {
            vm.startServer(targetMode)
        } else {
            vm.selectMode(targetMode)
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        vm.notifyMessage("Copied $label")
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag("dd-screen"),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = Color.Transparent,
                ),
                title = { Text("Desktop drop", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(
                        onClick = { showSettings = true },
                        modifier = Modifier.testTag("dd-settings-btn"),
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = "Desktop Drop settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("dd-list"),
            contentPadding = PaddingValues(bottom = Tokens.Section),
        ) {
            item(key = "header") {
                ServerControlHeader(
                    snapshot = snapshot,
                    onToggleServer = {
                        if (snapshot.status == ServerStatus.RUNNING || snapshot.status == ServerStatus.STARTING) {
                            vm.stopServer()
                        } else {
                            requestModeOrStart(snapshot.mode, startImmediately = true)
                        }
                    },
                    onSelectMode = { mode ->
                        requestModeOrStart(mode, startImmediately = snapshot.status == ServerStatus.RUNNING)
                    },
                )
            }

            if (snapshot.status == ServerStatus.RUNNING && snapshot.dropUrl != null) {
                item(key = "address") {
                    BookmarkAddressCard(
                        snapshot = snapshot,
                        onCopy = ::copyToClipboard,
                    )
                }
                item(key = "qr-flow") {
                    PairAnotherDevicePill(
                        snapshot = snapshot,
                        onCopy = ::copyToClipboard,
                    )
                }
            }

            item(key = "gate") {
                BiometricGatePanel(
                    mode = snapshot.prefs.gateMode,
                    onSelect = vm::setGateMode,
                )
            }

            item(key = "troubleshoot") {
                TroubleshootingBanner(
                    expanded = troubleshootingExpanded,
                    isHotspotActive = snapshot.mode == ConnectivityMode.DIRECT_HOTSPOT && snapshot.status == ServerStatus.RUNNING,
                    onToggle = { troubleshootingExpanded = !troubleshootingExpanded },
                    onSwitchToHotspot = {
                        requestModeOrStart(ConnectivityMode.DIRECT_HOTSPOT, startImmediately = true)
                    },
                )
            }

            item(key = "peers-header") {
                val approvedPeers = snapshot.peers.filter { it.status == PeerApprovalStatus.APPROVED }
                if (approvedPeers.isNotEmpty()) {
                    Text(
                        "Connected browsers (${approvedPeers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(
                            start = Tokens.Edge,
                            end = Tokens.Edge,
                            top = Tokens.Medium,
                            bottom = Tokens.Small,
                        ),
                    )
                }
            }

            items(
                snapshot.peers.filter { it.status == PeerApprovalStatus.APPROVED },
                key = { "peer-${it.sessionId}" },
            ) { peer ->
                ConnectedPeerRow(
                    peer = peer,
                    onDisconnect = { vm.revokePeer(peer.sessionId) },
                )
            }

            item(key = "destination") {
                DestinationPanel(
                    label = snapshot.prefs.destinationLabel,
                    hasCustomFolder = snapshot.prefs.destinationTreeUri != null,
                    onChangeFolder = { pickDestinationLauncher.launch(null) },
                    onResetDefault = vm::resetDestinationToDefault,
                )
            }

            item(key = "share-queue") {
                ShareQueueHeader(
                    count = snapshot.sharedItems.size,
                    onAddFiles = { pickFilesLauncher.launch(arrayOf("*/*")) },
                    onAddFolder = { pickShareFolderLauncher.launch(null) },
                    onClear = vm::clearSharedItems,
                )
            }

            if (snapshot.sharedItems.isEmpty()) {
                item(key = "share-empty") {
                    GlassPanel(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
                    ) {
                        Text(
                            "No phone files queued to share. Tap Add files or Add folder to download them from your laptop.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
                        )
                    }
                }
            } else {
                items(snapshot.sharedItems, key = { "share-${it.id}" }) { item ->
                    SharedFileRow(
                        item = item,
                        onRemove = { vm.removeSharedItem(item.id) },
                    )
                }
            }

            if (snapshot.transfers.isNotEmpty()) {
                item(key = "transfers-header") {
                    Text(
                        "Recent transfers",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(
                            start = Tokens.Edge,
                            end = Tokens.Edge,
                            top = Tokens.Section,
                            bottom = Tokens.Small,
                        ),
                    )
                }
                items(snapshot.transfers, key = { "xfer-${it.transferId}" }) { transfer ->
                    DropTransferRow(transfer = transfer)
                }
            }
        }
    }

    snapshot.pendingApproval?.let { peer ->
        PeerApprovalDialog(
            peer = peer,
            onApprove = {
                val shown = DropBiometric.prompt(
                    context = context,
                    knock = peer,
                    onApproved = { vm.approveKnockAfterBiometric(peer.sessionId) },
                    onMessage = vm::notifyMessage,
                )
                if (!shown) vm.notifyMessage("The biometric prompt could not be opened here")
            },
            onDeny = { vm.revokePeer(peer.sessionId) },
        )
    }

    if (showSettings) {
        DesktopDropSettingsDialog(
            prefs = snapshot.prefs,
            onSave = { port, idleMins ->
                vm.updateSettings(port, idleMins)
                showSettings = false
            },
            onResetDestination = {
                vm.resetDestinationToDefault()
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ServerControlHeader(
    snapshot: DesktopDropSnapshot,
    onToggleServer: () -> Unit,
    onSelectMode: (ConnectivityMode) -> Unit,
) {
    val context = LocalContext.current
    val compact = LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP ||
        LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    val running = snapshot.status == ServerStatus.RUNNING || snapshot.status == ServerStatus.STARTING
    val leadStyle = if (compact) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayLarge

    val leadText = when {
        snapshot.activeTransferCount > 0 && snapshot.aggregateSpeedBytesPerSec > 0L ->
            "${formatBytes(context, snapshot.aggregateSpeedBytesPerSec)}/s"
        snapshot.status == ServerStatus.RUNNING -> "Online"
        snapshot.status == ServerStatus.STARTING -> "Starting"
        else -> "Ready"
    }

    val subtitleText = when {
        snapshot.errorMessage != null -> snapshot.errorMessage
        snapshot.status == ServerStatus.RUNNING && snapshot.port != null ->
            "Port ${snapshot.port} · ${snapshot.mode.label} · ${snapshot.prefs.gateMode.label}"
        snapshot.statusNote != null -> snapshot.statusNote
        else -> "LAN and Direct Hotspot HTTP server (port ${snapshot.prefs.preferredPort} default)"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Text(leadText, style = leadStyle)
        Text(
            subtitleText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (snapshot.errorMessage != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Tiny),
        )

        Spacer(Modifier.height(Tokens.Medium))

        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                ModeChipGroup(snapshot.mode, enabled = snapshot.status != ServerStatus.STARTING, onSelectMode = onSelectMode)
                ServerToggleButton(running = running, starting = snapshot.status == ServerStatus.STARTING, onClick = onToggleServer)
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Box(Modifier.weight(1f)) {
                    ModeChipGroup(snapshot.mode, enabled = snapshot.status != ServerStatus.STARTING, onSelectMode = onSelectMode)
                }
                ServerToggleButton(running = running, starting = snapshot.status == ServerStatus.STARTING, onClick = onToggleServer)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModeChipGroup(
    selectedMode: ConnectivityMode,
    enabled: Boolean,
    onSelectMode: (ConnectivityMode) -> Unit,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
        verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
    ) {
        for (mode in ConnectivityMode.entries) {
            val icon = if (mode == ConnectivityMode.SAME_WIFI) Icons.Outlined.Wifi else Icons.Outlined.WifiTethering
            FilterChip(
                selected = selectedMode == mode,
                onClick = { onSelectMode(mode) },
                enabled = enabled,
                leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall)) },
                label = { Text(mode.label) },
                modifier = Modifier.heightIn(min = Tokens.TouchTarget),
            )
        }
    }
}

@Composable
private fun ServerToggleButton(
    running: Boolean,
    starting: Boolean,
    onClick: () -> Unit,
) {
    if (running) {
        OutlinedButton(
            onClick = onClick,
            enabled = !starting,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("dd-server-toggle"),
        ) {
            Text(if (starting) "Starting..." else "Stop server")
        }
    } else {
        Button(
            onClick = onClick,
            modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("dd-server-toggle"),
        ) {
            Text("Start server")
        }
    }
}

@Composable
private fun BookmarkAddressCard(
    snapshot: DesktopDropSnapshot,
    onCopy: (label: String, text: String) -> Unit,
) {
    val bookmark = snapshot.bookmarkUrl ?: return
    val lan = snapshot.dropUrl
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small)
            .testTag("dd-address-card"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Text("Bookmark this on your laptop", style = MaterialTheme.typography.titleMedium)
            Text(
                "The same address every time, with no code in it. Your Pixel asks for your fingerprint or face.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AddressChip(
                label = "Bookmark address",
                value = bookmark,
                highlighted = true,
                tag = "dd-bookmark-chip",
                onCopy = { onCopy("address", bookmark) },
            )
            if (lan != null) {
                AddressChip(
                    label = "If that does not open, use",
                    value = lan,
                    highlighted = false,
                    tag = "dd-lan-chip",
                    onCopy = { onCopy("LAN address", lan) },
                )
            }
        }
    }
}

@Composable
private fun AddressChip(
    label: String,
    value: String,
    highlighted: Boolean,
    tag: String,
    onCopy: () -> Unit,
) {
    val edge = if (highlighted) MaterialTheme.colorScheme.primary else GlassPalette.WellEdge
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Tokens.ThumbShape)
            .background(GlassPalette.Well, Tokens.ThumbShape)
            .border(if (highlighted) Tokens.Stroke else Tokens.Hairline, edge, Tokens.ThumbShape)
            .padding(start = Tokens.Medium, end = Tokens.Tiny, top = Tokens.Tiny, bottom = Tokens.Tiny)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                value,
                style = if (highlighted) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label")
        }
    }
}

@Composable
private fun PairAnotherDevicePill(
    snapshot: DesktopDropSnapshot,
    onCopy: (label: String, text: String) -> Unit,
) {
    val dropUrl = snapshot.dropUrl ?: return
    val hotspot = snapshot.hotspotCredentials
    // A direct hotspot has to be joined first, so its steps start open; otherwise the QR stays folded.
    var expanded by rememberSaveable(snapshot.mode) { mutableStateOf(hotspot != null) }
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)
                    .testTag("dd-pair-pill"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.QrCode2,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Spacer(Modifier.width(Tokens.Medium))
                Text(
                    "Pair another device (QR)",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Hide pairing QR code" else "Show pairing QR code",
                )
            }
            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Tokens.Gutter, end = Tokens.Gutter, bottom = Tokens.Medium),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Medium),
                ) {
                    if (hotspot != null) {
                        HotspotStepQrCard(credentials = hotspot, onCopy = onCopy)
                    }
                    PairUrlQr(
                        title = if (hotspot != null) "Step 2: Open in laptop browser" else "Scan to open on another device",
                        dropUrl = dropUrl,
                        onCopy = onCopy,
                    )
                }
            }
        }
    }
}

@Composable
private fun HotspotStepQrCard(
    credentials: HotspotCredentials,
    onCopy: (label: String, text: String) -> Unit,
) {
    val qrBitmap = remember(credentials.wifiQrPayload) {
        QrBitmapGenerator.generate(credentials.wifiQrPayload, sizePx = 512)
    }
    Column(
        Modifier.fillMaxWidth().testTag("dd-hotspot-qr"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        Text("Step 1: Join direct Wi-Fi hotspot", style = MaterialTheme.typography.titleMedium)
        Text(
            "Connect your laptop directly to this temporary Wi-Fi network.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        QrPreviewBox(bitmap = qrBitmap, description = "Hotspot Wi-Fi QR code")
        CopyableFieldRow(
            label = "Network (SSID)",
            value = credentials.ssid,
            onCopy = { onCopy("SSID", credentials.ssid) },
        )
        CopyableFieldRow(
            label = "Passphrase",
            value = credentials.passphrase,
            onCopy = { onCopy("passphrase", credentials.passphrase) },
        )
    }
}

@Composable
private fun PairUrlQr(
    title: String,
    dropUrl: String,
    onCopy: (label: String, text: String) -> Unit,
) {
    val qrBitmap = remember(dropUrl) {
        QrBitmapGenerator.generate(dropUrl, sizePx = 512)
    }
    Column(
        Modifier.fillMaxWidth().testTag("dd-url-qr"),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        QrPreviewBox(bitmap = qrBitmap, description = "Desktop Drop address QR code")
        CopyableFieldRow(
            label = "Address (HTTP only)",
            value = dropUrl,
            onCopy = { onCopy("Drop URL", dropUrl) },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BiometricGatePanel(
    mode: BiometricGateMode,
    onSelect: (BiometricGateMode) -> Unit,
) {
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small)
            .testTag("dd-gate-panel"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text("Biometric gate", style = MaterialTheme.typography.titleSmall)
            }
            Text(
                when (mode) {
                    BiometricGateMode.APPROVE_EVERY_START ->
                        "Each browser needs your fingerprint or face every time you press Start."
                    BiometricGateMode.TRUST_7_DAYS ->
                        "An approved browser stays signed in for 7 days, even after Stop and Start. Disconnect removes it at once."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
                verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
            ) {
                for (option in BiometricGateMode.entries) {
                    FilterChip(
                        selected = mode == option,
                        onClick = { onSelect(option) },
                        label = { Text(option.label) },
                        modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("dd-gate-${option.name}"),
                    )
                }
            }
        }
    }
}

private fun peerDetail(peer: PeerSession): String =
    if (peer.trusted) "${peer.peerIp} · Trusted for 7 days" else peer.peerIp

@Composable
private fun QrPreviewBox(
    bitmap: Bitmap?,
    description: String,
) {
    if (bitmap == null) return
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = Tokens.Tiny),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = Tokens.ViewerProgressWidth)
                .aspectRatio(1f)
                .clip(Tokens.ThumbShape),
        )
    }
}

@Composable
private fun CopyableFieldRow(
    label: String,
    value: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Tokens.ThumbShape)
            .background(GlassPalette.Well, Tokens.ThumbShape)
            .border(Tokens.Hairline, GlassPalette.WellEdge, Tokens.ThumbShape)
            .padding(start = Tokens.Medium, end = Tokens.Tiny, top = Tokens.Tiny, bottom = Tokens.Tiny),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label")
        }
    }
}

@Composable
private fun TroubleshootingBanner(
    expanded: Boolean,
    isHotspotActive: Boolean,
    onToggle: () -> Unit,
    onSwitchToHotspot: () -> Unit,
) {
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Tokens.TouchTarget)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)
                    .testTag("dd-troubleshoot-toggle"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.LaptopMac,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(Tokens.Icon),
                )
                Spacer(Modifier.width(Tokens.Medium))
                Text(
                    "Laptop can't connect?",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Collapse connection help" else "Expand connection help",
                )
            }
            if (expanded) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = Tokens.Gutter, end = Tokens.Gutter, bottom = Tokens.Medium)
                        .testTag("dd-troubleshoot-body"),
                    verticalArrangement = Arrangement.spacedBy(Tokens.Small),
                ) {
                    Text(
                        "1. Confirm both Pixel and laptop are on the exact same Wi-Fi network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "2. A work VPN on your laptop can block local 192.168.x.x addresses.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!isHotspotActive) {
                        OutlinedButton(
                            onClick = onSwitchToHotspot,
                            modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("dd-switch-hotspot"),
                        ) {
                            Icon(Icons.Outlined.WifiTethering, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall))
                            Spacer(Modifier.width(Tokens.Small))
                            Text("Switch to Direct Hotspot")
                        }
                    }
                    Text(
                        "3. If typing the URL manually, start with http:// (not https://).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedPeerRow(
    peer: PeerSession,
    onDisconnect: () -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP ||
        LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
    ) {
        if (compact) {
            Column(
                Modifier.padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
                verticalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Text(peer.deviceLabel, style = MaterialTheme.typography.titleSmall)
                Text(peerDetail(peer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                ) {
                    Text("Disconnect peer")
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Small),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            ) {
                Icon(Icons.Outlined.Devices, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                Column(Modifier.weight(1f)) {
                    Text(peer.deviceLabel, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(peerDetail(peer), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(
                    onClick = onDisconnect,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun DestinationPanel(
    label: String,
    hasCustomFolder: Boolean,
    onChangeFolder: () -> Unit,
    onResetDefault: () -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < Tokens.COMPACT_WIDTH_DP ||
        LocalDensity.current.fontScale >= Tokens.STACK_AT_FONT_SCALE
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium),
            verticalArrangement = Arrangement.spacedBy(Tokens.Small),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f)) {
                    Text("Incoming drops destination", style = MaterialTheme.typography.titleSmall)
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Tiny)) {
                    OutlinedButton(onClick = onChangeFolder, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                        Text("Change folder")
                    }
                    if (hasCustomFolder) {
                        TextButton(onClick = onResetDefault, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                            Text("Use Downloads default")
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Small)) {
                    OutlinedButton(onClick = onChangeFolder, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                        Text("Change folder")
                    }
                    if (hasCustomFolder) {
                        TextButton(onClick = onResetDefault, modifier = Modifier.heightIn(min = Tokens.TouchTarget)) {
                            Text("Use Downloads default")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareQueueHeader(
    count: Int,
    onAddFiles: () -> Unit,
    onAddFolder: () -> Unit,
    onClear: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Small),
        verticalArrangement = Arrangement.spacedBy(Tokens.Small),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (count > 0) "Share Queue ($count)" else "Share Queue",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (count > 0) {
                TextButton(
                    onClick = onClear,
                    modifier = Modifier.heightIn(min = Tokens.TouchTarget),
                ) {
                    Text("Clear")
                }
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Tokens.Small),
            verticalArrangement = Arrangement.spacedBy(Tokens.Tiny),
        ) {
            OutlinedButton(
                onClick = onAddFiles,
                modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("dd-add-files"),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall))
                Spacer(Modifier.width(Tokens.Tiny))
                Text("Add files")
            }
            OutlinedButton(
                onClick = onAddFolder,
                modifier = Modifier.heightIn(min = Tokens.TouchTarget).testTag("dd-add-folder"),
            ) {
                Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(Tokens.IconSmall))
                Spacer(Modifier.width(Tokens.Tiny))
                Text("Add folder")
            }
        }
    }
}

@Composable
private fun SharedFileRow(
    item: SharedDropItem,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = Tokens.Gutter, end = Tokens.Tiny, top = Tokens.Small, bottom = Tokens.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildString {
                    append(formatBytes(context, item.sizeBytes))
                    if (item.relativePath.isNotBlank()) append(" · ${item.relativePath}")
                }
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove ${item.name} from share queue")
            }
        }
    }
}

@Composable
private fun DropTransferRow(transfer: DropTransferProgress) {
    val context = LocalContext.current
    val detail = when (transfer.status) {
        DropTransferStatus.ACTIVE -> buildString {
            append("${transfer.direction.label} · ${formatBytes(context, transfer.transferredBytes)} / ${formatBytes(context, transfer.totalBytes)}")
            if (transfer.speedBytesPerSec > 0L) {
                append(" · ${formatBytes(context, transfer.speedBytesPerSec)}/s")
            }
            if (transfer.etaSeconds > 0L) {
                append(" · ${formatEta(transfer.etaSeconds)}")
            }
        }
        DropTransferStatus.COMPLETED -> "${transfer.direction.label} · Saved (${formatBytes(context, transfer.totalBytes)})"
        DropTransferStatus.FAILED -> "${transfer.direction.label} · ${transfer.errorMessage ?: "Failed"}"
        DropTransferStatus.CANCELLED -> "${transfer.direction.label} · Cancelled"
        DropTransferStatus.PAUSED -> "${transfer.direction.label} · Paused"
    }
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Tokens.Edge, vertical = Tokens.Tiny),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = Tokens.Gutter, vertical = Tokens.Medium)) {
            Text(transfer.filename, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (transfer.status == DropTransferStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Tokens.Tiny),
            )
            if (transfer.status == DropTransferStatus.ACTIVE || transfer.status == DropTransferStatus.PAUSED) {
                LinearProgressIndicator(
                    progress = { transfer.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Tokens.Small)
                        .height(Tokens.ProgressHeight),
                    color = MaterialTheme.colorScheme.tertiary,
                    trackColor = GlassPalette.Dim,
                )
            }
        }
    }
}
