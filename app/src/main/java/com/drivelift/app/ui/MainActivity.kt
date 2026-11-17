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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.runtime.LaunchedEffect
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
                GlassBackdrop { LockGate(graph.lock, graph.authenticator) { AppRoot(vm) } }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        leavingFromRoot = false
    }

    override fun onStop() {
        if (leavingFromRoot) Graph.get(this).lock.lock()
        leavingFromRoot = false
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
        if (intent?.getBooleanExtra(Notifications.EXTRA_OPEN_TRANSFERS, false) == true) {
            vm.tab.value = AppViewModel.TAB_TRANSFERS
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
    val inFolder = tab == AppViewModel.TAB_PHONE && openBucket != NO_BUCKET
    val inSettings = tab == AppViewModel.TAB_TRANSFERS && showSettings

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

    // Settings opens over Transfers; Back returns there.
    BackHandler(enabled = inSettings && !pickingDestination) { showSettings = false }

    BackHandler(enabled = inFolder && !pickingDestination) {
        if (selection.isNotEmpty()) vm.clearSelection() else openBucket = NO_BUCKET
    }

    val inFlight = (counts[TransferStatus.QUEUED.name]?.count ?: 0) +
        (counts[TransferStatus.UPLOADING.name]?.count ?: 0)

    Scaffold(
        // Transparent, so the backdrop drawn at the root shows through the glass.
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!inFolder && !inSettings && !pickingDestination && !viewingDriveFile) {
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
                        label = { Text("Drive") },
                    )
                    NavigationBarItem(
                        selected = tab == AppViewModel.TAB_TRANSFERS,
                        onClick = { vm.tab.value = AppViewModel.TAB_TRANSFERS },
                        icon = {
                            BadgedBox(badge = { if (inFlight > 0) Badge { Text("$inFlight") } }) {
                                Icon(Icons.Outlined.SwapVert, contentDescription = null)
                            }
                        },
                        label = { Text("Transfers") },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
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
                    )
                    else -> PhoneScreen(
                        vm = vm,
                        onOpen = { openBucket = it },
                        onMoveFolder = {
                            vm.selectFolder(it)
                            showDestination = true
                        },
                    )
                }
                inSettings -> SettingsScreen(vm = vm, onBack = { showSettings = false })
                tab == AppViewModel.TAB_DRIVE -> DriveBrowser(
                    vm = vm,
                    pickMode = false,
                    onViewingFileChange = { viewingDriveFile = it },
                )
                else -> TransfersScreen(vm, onOpenSettings = { showSettings = true })
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
}

private const val NO_BUCKET = Long.MIN_VALUE

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
