package com.drivelift.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.TransferStatus
import com.drivelift.app.transfer.Notifications

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        routeIntent(intent)
        setContent { DriveLiftTheme { AppRoot(vm) } }
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
    var showDestination by remember { mutableStateOf(false) }
    val inFolder = tab == AppViewModel.TAB_PHONE && openBucket != NO_BUCKET

    BackHandler(enabled = inFolder) {
        if (selection.isNotEmpty()) vm.clearSelection() else openBucket = NO_BUCKET
    }

    val inFlight = (counts[TransferStatus.QUEUED.name]?.count ?: 0) +
        (counts[TransferStatus.UPLOADING.name]?.count ?: 0)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!inFolder) {
                NavigationBar {
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                AppViewModel.TAB_PHONE -> when {
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
                AppViewModel.TAB_DRIVE -> DriveBrowser(vm = vm, pickMode = false)
                else -> TransfersScreen(vm)
            }
        }
    }

    if (showDestination) {
        DestinationSheet(
            vm = vm,
            onDismiss = {
                showDestination = false
                // A whole-folder move started from the list has no visible selection to keep.
                if (!inFolder) vm.clearSelection()
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
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("Your photos, moved to Drive", style = MaterialTheme.typography.displaySmall)
        Text(
            "DriveLift needs to see the photos and videos on this phone to list your folders. " +
                "Nothing leaves the phone until you choose files and a Drive folder.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp).widthIn(max = 480.dp),
        )
        Button(onClick = onGrant) { Text("Allow access to photos and videos") }
    }
}
