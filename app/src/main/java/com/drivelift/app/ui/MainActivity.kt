package com.drivelift.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.FragmentActivity
import com.drivelift.app.Graph
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.PresetEntity
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.transfer.Notifications

class MainActivity : FragmentActivity() {
    private val vm: AppViewModel by viewModels()

    /** Set when Back leaves the root screen; the session ends once the app is off screen. */
    private var leavingFromRoot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so system bar icons are always light.
        val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        routeIntent(intent)
        val graph = Graph.get(this)
        (graph.authenticator as? BiometricAuthenticator)?.bind(this)
        // Registered before any Compose BackHandler, so it only runs when nothing inside the app
        // (a folder, Settings, a viewer) handles Back. Since Android 12 that Back sends a root
        // launcher activity to the background instead of finishing it, so onDestroy alone would
        // miss it. The lock is applied in onStop, after the app has left the screen, so no prompt
        // appears while it animates away. Home and other apps never set the flag.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leavingFromRoot = true
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })
        setContent {
            DriveLiftTheme {
                GlassBackdrop {
                    // Outside the lock gate: a phone trash request keeps its result handler if the
                    // app locks while Android's dialog is open (issue #36). The same holds for
                    // Android's write consent for a rename (issue #42).
                    PhoneTrashHost(vm)
                    PhoneRenameHost(vm)
                    PhoneMoveHost(vm)
                    LockGate(graph.lock, graph.authenticator) { AppRoot(vm) }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        leavingFromRoot = false
        Graph.get(this).desktopDrop.controller.setAppInForeground(true)
    }

    override fun onStop() {
        if (leavingFromRoot) Graph.get(this).lock.lock()
        leavingFromRoot = false
        Graph.get(this).desktopDrop.controller.setAppInForeground(false)
        super.onStop()
    }

    override fun onDestroy() {
        // Back from the root or a swipe in Recents ends the UI session; uploads may keep the
        // process alive, so the lock is reset here rather than relying on a process restart.
        val graph = Graph.get(this)
        if (isFinishing && !isChangingConfigurations) graph.lock.lock()
        (graph.authenticator as? BiometricAuthenticator)?.unbind(this)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeIntent(intent)
    }

    private fun routeIntent(intent: Intent?) {
        when {
            intent?.getBooleanExtra(Notifications.EXTRA_OPEN_TRANSFERS, false) == true ->
                vm.tab.value = AppViewModel.TAB_TRANSFERS
            intent?.getBooleanExtra(
                com.drivelift.app.feature.desktopdrop.service.DropApprovalNotifier.EXTRA_APPROVE_KNOCK,
                false,
            ) == true -> {
                vm.tab.value = TAB_DESKTOP_DROP
                Graph.get(this).desktopDrop.controller.requestKnockPrompt()
            }
            intent?.getBooleanExtra(
                com.drivelift.app.feature.desktopdrop.service.DesktopDropService.EXTRA_OPEN_DESKTOP_DROP,
                false,
            ) == true ->
                vm.tab.value = TAB_DESKTOP_DROP
            intent?.getBooleanExtra(
                com.drivelift.app.feature.applock.AppLockModule.EXTRA_OPEN_APP_LOCK,
                false,
            ) == true ->
                Graph.get(this).appLock.requestOpenManager()
            intent?.getBooleanExtra(
                com.drivelift.app.feature.cockpit.overlay.CockpitOverlayService.EXTRA_OPEN_COCKPIT,
                false,
            ) == true || intent?.action == android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES ->
                Graph.get(this).cockpit.requestOpenHub()
        }
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(MediaAccess.hasPermission(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        hasPermission = MediaAccess.hasPermission(context)
        if (hasPermission) vm.refreshMedia()
    }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onConsentResult(result.data) }

    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.consentRequests.collect {
            consentLauncher.launch(IntentSenderRequest.Builder(it.intentSender).build())
        }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // A browser knocking at the Desktop Drop address opens the phone's biometric prompt on any tab.
    com.drivelift.app.feature.desktopdrop.ui.DropKnockBiometricHost()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasPermission = MediaAccess.hasPermission(context)
        vm.refreshMedia()
        if (vm.driveConn.value != DriveConn.CONNECTED) vm.connectDrive(interactive = false)
    }

    val tab by vm.tab.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    var openBucket by rememberSaveable { mutableLongStateOf(NO_BUCKET) }
    var showDestination by rememberSaveable { mutableStateOf(false) }
    var pickingDestination by rememberSaveable { mutableStateOf(false) }
    var savedSelectionIds by rememberSaveable { mutableStateOf(LongArray(0)) }
    var selectionSynced by remember { mutableStateOf(false) }
    var viewingDriveFile by remember { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showCockpit by rememberSaveable { mutableStateOf(false) }
    var showAppLock by rememberSaveable { mutableStateOf(false) }
    // Favorites opens over the Phone or Drive tab; Back returns there (issues #36 and #42).
    var showFavorites by rememberSaveable { mutableStateOf(false) }
    var showStorage by rememberSaveable { mutableStateOf(false) }
    var showStorageIntelligence by rememberSaveable { mutableStateOf(false) }
    var showCleaner by rememberSaveable { mutableStateOf(false) }
    var showSleepMode by rememberSaveable { mutableStateOf(false) }
    var showWhatsAppQuickSender by rememberSaveable { mutableStateOf(false) }
    var whatsAppPayload by remember { mutableStateOf<com.drivelift.app.feature.whatsappsender.model.WhatsAppSendPayload?>(null) }
    // On-Device Intelligence & Automation opens over the Phone tab; Back returns to the folder list.
    var showIntelligence by rememberSaveable { mutableStateOf(false) }
    val openCockpitPending by remember(context) { Graph.get(context).cockpit.openHubPending }.collectAsStateWithLifecycle()
    LaunchedEffect(openCockpitPending) {
        if (openCockpitPending) {
            showCockpit = true
            Graph.get(context).cockpit.consumeOpenHubRequest()
        }
    }
    val openAppLockPending by remember(context) { Graph.get(context).appLock.openManagerPending }.collectAsStateWithLifecycle()
    LaunchedEffect(openAppLockPending) {
        if (openAppLockPending) {
            showAppLock = true
            Graph.get(context).appLock.consumeOpenManagerRequest()
        }
    }
    val inFolder = tab == AppViewModel.TAB_PHONE && openBucket != NO_BUCKET
    val inCockpit = showCockpit
    val inAppLock = showAppLock && !inCockpit
    val inSettings = tab == AppViewModel.TAB_TRANSFERS && showSettings && !inCockpit && !inAppLock
    val inFavorites = showFavorites && (tab == AppViewModel.TAB_PHONE || tab == AppViewModel.TAB_DRIVE)
    // Storage insights (issue #42) opens over the Phone tab; Back returns to the folder list.
    val inStorage = tab == AppViewModel.TAB_PHONE && showStorage && openBucket == NO_BUCKET && hasPermission
    val inStorageIntelligence = tab == AppViewModel.TAB_PHONE && showStorageIntelligence && openBucket == NO_BUCKET && hasPermission
    val inCleaner = tab == AppViewModel.TAB_PHONE && showCleaner && openBucket == NO_BUCKET && hasPermission
    val inSleepMode = tab == AppViewModel.TAB_PHONE && showSleepMode && openBucket == NO_BUCKET && hasPermission
    val inWhatsAppQuickSender = tab == AppViewModel.TAB_PHONE && showWhatsAppQuickSender && openBucket == NO_BUCKET && hasPermission
    val inIntelligence = tab == AppViewModel.TAB_PHONE && showIntelligence && openBucket == NO_BUCKET && hasPermission
    val dropRunning by remember(context) { Graph.get(context).desktopDrop.controller.isServerRunning }.collectAsStateWithLifecycle()

    LaunchedEffect(selection) {
        if (!selectionSynced) {
            selectionSynced = true
            if (selection.isEmpty() && savedSelectionIds.isNotEmpty()) {
                vm.setSelected(savedSelectionIds.toList(), true)
                return@LaunchedEffect
            }
        }
        savedSelectionIds = selection.toLongArray()
        if (selection.isEmpty()) {
            showDestination = false
            pickingDestination = false
        }
    }

    // Favorites belongs to the Phone and Drive tabs; leaving for Transfers or Drop closes it.
    LaunchedEffect(tab) {
        if (tab == AppViewModel.TAB_TRANSFERS || tab == TAB_DESKTOP_DROP) showFavorites = false
    }

    // A step up one level: Settings back to Transfers, Storage or a folder back to the folder
    // list. The predictive back gesture previews it (BackPreview.kt).
    val upBack = rememberUpBack(
        enabled = !pickingDestination && (inCockpit || inAppLock || inSettings || inStorage || inStorageIntelligence || inCleaner || inSleepMode || inIntelligence || inWhatsAppQuickSender || (inFolder && selection.isEmpty())),
    ) {
        when {
            inCockpit -> showCockpit = false
            inAppLock -> showAppLock = false
            inSettings -> showSettings = false
            inSleepMode -> showSleepMode = false
            inCleaner -> showCleaner = false
            inStorageIntelligence -> showStorageIntelligence = false
            inIntelligence -> showIntelligence = false
            inWhatsAppQuickSender -> showWhatsAppQuickSender = false
            inStorage -> showStorage = false
            inFolder -> openBucket = NO_BUCKET
        }
    }
    // Back while selecting in a folder clears the selection; it is not a step up, so no preview.
    BackHandler(enabled = inFolder && !pickingDestination && selection.isNotEmpty()) { vm.clearSelection() }

    // The level shown now and how it arrived: a step down into a folder or a screen opened over
    // a tab, a step back up, or a move across to another tab (Motion.kt).
    val level = listOf(tab, openBucket, inCockpit, inAppLock, inSettings, inStorage, inStorageIntelligence, inCleaner, inSleepMode, inIntelligence, inWhatsAppQuickSender, inFavorites, pickingDestination, hasPermission)
    val depth = listOf(inFolder, inCockpit, inAppLock, inSettings, inStorage, inStorageIntelligence, inCleaner, inSleepMode, inIntelligence, inWhatsAppQuickSender, inFavorites, pickingDestination).count { it }
    var lastLevel by remember { mutableStateOf(Pair(tab, depth)) }
    val levelStep = when {
        tab != lastLevel.first -> NavStep.ACROSS
        depth >= lastLevel.second -> NavStep.DEEPER
        else -> NavStep.UP
    }
    SideEffect { lastLevel = Pair(tab, depth) }

    val inFlight = (counts[TransferStatus.QUEUED.name]?.count ?: 0) +
        (counts[TransferStatus.UPLOADING.name]?.count ?: 0)

    // Viewers draw through this root slot, outside the Scaffold's system-bar padding, so they fill
    // the whole screen (see FullScreenOverlay).
    val overlay = remember { FullScreenOverlayState() }
    CompositionLocalProvider(LocalFullScreenOverlay provides overlay) {
        Box(Modifier.fillMaxSize()) {
            Scaffold(
                // Transparent, so the backdrop drawn at the root shows through the glass.
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                contentWindowInsets = WindowInsets.safeDrawing,
                // While a viewer fills the screen, messages show above it instead (below).
                snackbarHost = { if (overlay.content == null) SnackbarHost(snackbar) },
                bottomBar = {
                    if (!inFolder && !inCockpit && !inAppLock && !inSettings && !inFavorites && !inStorage && !inStorageIntelligence && !inCleaner && !inSleepMode && !inIntelligence && !inWhatsAppQuickSender && !pickingDestination && !viewingDriveFile) {
                        NavigationBar(modifier = Modifier.glassBar(), containerColor = Color.Transparent) {
                            NavigationBarItem(
                                selected = tab == AppViewModel.TAB_PHONE,
                                onClick = { vm.tab.value = AppViewModel.TAB_PHONE },
                                icon = { Icon(Icons.Outlined.PhoneAndroid, contentDescription = null) },
                                label = { Text("Phone") },
                            )
                            NavigationBarItem(
                                selected = tab == AppViewModel.TAB_DRIVE,
                                onClick = { vm.tab.value = AppViewModel.TAB_DRIVE },
                                icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                                label = { Text("Drive", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                            NavigationBarItem(
                                selected = tab == TAB_DESKTOP_DROP,
                                onClick = { vm.tab.value = TAB_DESKTOP_DROP },
                                icon = {
                                    BadgedBox(badge = { if (dropRunning) Badge() }) {
                                        Icon(Icons.Outlined.Devices, contentDescription = null)
                                    }
                                },
                                label = { Text("Drop", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                            NavigationBarItem(
                                selected = tab == AppViewModel.TAB_TRANSFERS,
                                onClick = { vm.tab.value = AppViewModel.TAB_TRANSFERS },
                                icon = {
                                    BadgedBox(badge = { if (inFlight > 0) Badge { Text("$inFlight") } }) {
                                        Icon(Icons.Outlined.SwapVert, contentDescription = null)
                                    }
                                },
                                label = { Text("Transfers", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            )
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).backPreview(upBack).arriveOnChange(level, levelStep)) {
                    when {
                        // Render the folder picker directly inside the root Activity window instead of a
                        // nested Dialog above the bottom sheet, so edge-to-edge system bars and display
                        // cutouts are measured and respected consistently across Android versions.
                        pickingDestination -> DriveBrowser(
                            vm = vm,
                            pickMode = true,
                            onClose = {
                                pickingDestination = false
                                showDestination = true
                            },
                            onPick = { dest, pin ->
                                if (pin && dest.folderId != null) {
                                    vm.pin(PresetEntity(dest.folderId, dest.label.substringAfterLast(" / "), dest.label))
                                }
                                pickingDestination = false
                                showDestination = false
                                openBucket = NO_BUCKET
                                vm.startTransfer(dest)
                            },
                        )
                        inFavorites -> FavoritesScreen(
                            vm = vm,
                            onBack = { showFavorites = false },
                            onOpenPhoneFolder = { bucketId ->
                                showFavorites = false
                                vm.tab.value = AppViewModel.TAB_PHONE
                                openBucket = bucketId
                            },
                            onOpenDriveFolder = { file ->
                                showFavorites = false
                                vm.openDriveFolder(file)
                            },
                        )
                        tab == AppViewModel.TAB_PHONE -> when {
                            !hasPermission -> PermissionGate(
                                onGrant = { permissionLauncher.launch(MediaAccess.runtimePermissions()) }
                            )
                            inFolder -> FolderScreen(
                                vm = vm,
                                bucketId = openBucket,
                                onBack = {
                                    vm.clearSelection()
                                    openBucket = NO_BUCKET
                                },
                                onMove = { showDestination = true },
                                onSendWhatsApp = { selectedItems, folderTitle ->
                                    whatsAppPayload = com.drivelift.app.feature.whatsappsender.model.WhatsAppSendPayload(
                                        title = folderTitle,
                                        items = com.drivelift.app.feature.whatsappsender.data.LocalMediaAdapter.fromMediaItems(selectedItems),
                                        isFolderSource = false,
                                        sourceBucketId = openBucket,
                                        mediaItemIds = selectedItems.map { it.id },
                                    )
                                },
                            )
                            inWhatsAppQuickSender -> com.drivelift.app.feature.whatsappsender.ui.WhatsAppQuickSenderScreen(
                                vm = vm,
                                onBack = { showWhatsAppQuickSender = false },
                                onOpenPayload = { whatsAppPayload = it },
                            )
                            inStorageIntelligence -> com.drivelift.app.feature.storageintelligence.ui.StorageIntelligenceHubScreen(
                                appViewModel = vm,
                                onBack = { showStorageIntelligence = false },
                                onRevealLocalFolder = { bucketId ->
                                    showStorageIntelligence = false
                                    showStorage = false
                                    openBucket = bucketId
                                },
                                onRevealDriveFolder = { file ->
                                    showStorageIntelligence = false
                                    showStorage = false
                                    vm.openDriveFolder(file)
                                },
                            )
                            inIntelligence -> com.drivelift.app.feature.intelligence.ui.IntelligenceHubScreen(
                                appViewModel = vm,
                                onBack = { showIntelligence = false },
                            )
                            inSleepMode -> com.drivelift.app.feature.hibernation.ui.SleepModeScreen(
                                onBack = { showSleepMode = false },
                            )
                            inCleaner -> com.drivelift.app.feature.cleaner.ui.CleanerDashboardScreen(
                                onBack = { showCleaner = false },
                                onOpenSleepMode = { showSleepMode = true },
                            )
                            inStorage -> StorageScreen(
                                vm = vm,
                                onBack = { showStorage = false },
                                onOpenIntelligence = { showStorageIntelligence = true },
                                onOpenWhatsAppQuickSender = { showWhatsAppQuickSender = true },
                                onOpenCleaner = { showCleaner = true },
                            )
                            else -> PhoneScreen(
                                vm = vm,
                                onOpenFavorites = { showFavorites = true },
                                onOpen = { openBucket = it },
                                onOpenStorage = { showStorage = true },
                                onOpenIntelligence = { showIntelligence = true },
                                onMoveFolder = {
                                    vm.selectFolder(it)
                                    showDestination = true
                                },
                                onOpenWhatsAppQuickSender = { showWhatsAppQuickSender = true },
                                onSendFolderToWhatsApp = { bucketId ->
                                    val folder = vm.folders.value.firstOrNull { it.bucketId == bucketId }
                                    if (folder != null) {
                                        whatsAppPayload = com.drivelift.app.feature.whatsappsender.data.LocalMediaAdapter.fromFolder(
                                            folder,
                                            vm.media.value,
                                        )
                                    }
                                },
                            )
                        }
                        inCockpit -> com.drivelift.app.feature.cockpit.ui.CockpitSetupScreen(
                            onBack = { showCockpit = false },
                        )
                        inAppLock -> com.drivelift.app.feature.applock.ui.AppLockManagerScreen(
                            onBack = { showAppLock = false },
                        )
                        inSettings -> SettingsScreen(
                            vm = vm,
                            onBack = { showSettings = false },
                            onOpenCockpit = { showCockpit = true },
                            onOpenAppLock = { showAppLock = true },
                        )
                        tab == AppViewModel.TAB_DRIVE -> DriveBrowser(
                            vm = vm,
                            pickMode = false,
                            onViewingFileChange = { viewingDriveFile = it },
                            onOpenFavorites = { showFavorites = true },
                        )
                        tab == TAB_DESKTOP_DROP -> com.drivelift.app.feature.desktopdrop.ui.DesktopDropScreen(
                            appViewModel = vm,
                        )
                        else -> TransfersScreen(vm, onOpenSettings = { showSettings = true })
                    }
                }
            }
            FullScreenOverlayHost(overlay)
            // Viewer feedback (for example a saved frame) stays visible above the full-screen
            // viewer, clear of the system bars.
            if (overlay.content != null) {
                SnackbarHost(
                    snackbar,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
        }
    }

    if (showDestination && !pickingDestination && selection.isNotEmpty()) {
        DestinationSheet(
            vm = vm,
            onDismiss = {
                showDestination = false
                // A whole-folder move started from the list has no visible selection to keep.
                if (!inFolder) vm.clearSelection()
            },
            onBrowseFolders = {
                showDestination = false
                pickingDestination = true
            },
            onChosen = { dest ->
                showDestination = false
                openBucket = NO_BUCKET
                vm.startTransfer(dest)
            },
        )
    }

    whatsAppPayload?.let { payload ->
        com.drivelift.app.feature.whatsappsender.ui.WhatsAppSenderSheet(
            payload = payload,
            onDismiss = { whatsAppPayload = null },
            onSent = { message ->
                whatsAppPayload = null
                vm.clearSelection()
                vm.messages.tryEmit(message)
            },
            onHandoffToDrive = { mediaIds ->
                whatsAppPayload = null
                showWhatsAppQuickSender = false
                vm.clearSelection()
                vm.setSelected(mediaIds, true)
                showDestination = true
            },
            onHandoffToDesktopDrop = { items ->
                val dropItems = items.map { item ->
                    com.drivelift.app.feature.desktopdrop.model.SharedDropItem(
                        id = item.id,
                        name = item.name,
                        relativePath = item.relativeFolderPath,
                        sizeBytes = item.sizeBytes,
                        mimeType = item.mimeType,
                        sourceUri = item.uriString,
                        localFilePath = item.filePath,
                        addedAtMs = Graph.get(context).clock(),
                    )
                }
                Graph.get(context).desktopDrop.controller.addSharedMediaFiles(dropItems)
                whatsAppPayload = null
                showWhatsAppQuickSender = false
                openBucket = NO_BUCKET
                vm.clearSelection()
                vm.tab.value = TAB_DESKTOP_DROP
                vm.messages.tryEmit(
                    if (dropItems.size == 1) "Added ${dropItems.first().name} to Desktop Drop"
                    else "Added ${dropItems.size} files to Desktop Drop",
                )
            },
        )
    }
}

private const val NO_BUCKET = Long.MIN_VALUE
private const val TAB_DESKTOP_DROP = 3

@Composable
private fun PermissionGate(onGrant: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = Tokens.Section),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("Your photos, moved to Drive", style = MaterialTheme.typography.displaySmall)
        Text(
            "DriveLift needs to see the photos and videos on this phone to list your folders. " +
                "Nothing leaves the phone until you choose files and a Drive folder.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Tokens.Medium, bottom = Tokens.Section).widthIn(max = Tokens.ReadableWidth),
        )
        Button(onClick = onGrant) { Text("Allow access to photos and videos") }
    }
}
