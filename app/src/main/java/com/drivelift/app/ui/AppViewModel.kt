package com.drivelift.app.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelift.app.Graph
import com.drivelift.app.data.MediaAccess
import com.drivelift.app.data.MediaFolder
import com.drivelift.app.data.PresetEntity
import com.drivelift.app.data.StatusCount
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.groupFolders
import com.drivelift.app.drive.ACCOUNT_BOUND_STATUSES
import com.drivelift.app.drive.AccountConnector
import com.drivelift.app.drive.ConnectOutcome
import com.drivelift.app.drive.DriveAbout
import com.drivelift.app.drive.DriveAuth
import com.drivelift.app.drive.DriveFolders
import com.drivelift.app.drive.stillConnected
import com.drivelift.app.transfer.Destination
import com.drivelift.app.transfer.LiveProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DriveConn { UNKNOWN, CONNECTING, CONNECTED, DISCONNECTED }

/** Non-PII layout and window inset measurements recorded by the destination folder picker. */
data class PickerWindowMetrics(
    val windowWidthPx: Int = 0,
    val windowHeightPx: Int = 0,
    val topInsetPx: Int = 0,
    val bottomInsetPx: Int = 0,
    val rootBottomPx: Int = 0,
    val footerTopPx: Int = 0,
    val footerBottomPx: Int = 0,
    val moveHereTopPx: Int = 0,
    val moveHereBottomPx: Int = 0,
    val moveHereHeightPx: Int = 0,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = Graph.get(app)
    private val dao = graph.db.transfers()
    val drive get() = graph.drive

    /** What the Drive browser lists and creates folders through. Replaced only in screenshot tests. */
    var driveFolders: DriveFolders = graph.drive
    val prefs get() = graph.prefs

    val tab = MutableStateFlow(0)

    // Phone media
    val media = graph.media.items
    val mediaLoaded = graph.media.loaded
    val folders: StateFlow<List<MediaFolder>> = media.map { groupFolders(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selection = MutableStateFlow<Set<Long>>(emptySet())

    // Drive connection
    val driveConn = MutableStateFlow(DriveConn.UNKNOWN)
    val account = MutableStateFlow<DriveAbout?>(null)
    val consentRequests = MutableSharedFlow<PendingIntent>(extraBufferCapacity = 1)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    // Transfers
    val transfers: StateFlow<List<TransferEntity>> = dao.observeRecent(400)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val counts: StateFlow<Map<String, StatusCount>> = dao.observeCounts()
        .map { list -> list.associateBy { it.status } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val trackedUris: StateFlow<Set<String>> = dao.observeTrackedUris()
        .map { it.toHashSet() as Set<String> }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val presets: StateFlow<List<PresetEntity>> = graph.db.presets().observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val live = LiveProgress.state
    val pickerMetrics = MutableStateFlow(PickerWindowMetrics())

    val online: StateFlow<Boolean> = callbackFlow {
        val manager = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(manager.activeNetwork != null)
            }
        }
        trySend(manager.activeNetwork != null)
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    init {
        refreshMedia()
        connectDrive(interactive = false)
    }

    fun refreshMedia() {
        viewModelScope.launch { graph.media.refresh() }
    }

    /** Account this session has a working authorization for. Null until a connection succeeds. */
    private var provenEmail: String? = null

    private val connector = AccountConnector(
        pin = prefs,
        identify = { token -> drive.about(token) },
        openTransfers = { dao.count(ACCOUNT_BOUND_STATUSES) },
        forgetDestinations = {
            graph.db.presets().clear()
            drive.clearPreviewCache()
        },
    )

    fun connectDrive(interactive: Boolean) {
        if (driveConn.value == DriveConn.CONNECTING) return
        driveConn.value = DriveConn.CONNECTING
        viewModelScope.launch {
            try {
                // A tap on Connect may be a switch of account, so that request is not pinned.
                // The saved pin itself is left alone until another account has been proven.
                val pinned = if (interactive) null else prefs.accountEmail?.takeIf { it.isNotBlank() }
                val result = DriveAuth.authorize(getApplication(), pinned)
                if (result.hasResolution()) {
                    settleUnchanged()
                    if (interactive) result.pendingIntent?.let { consentRequests.emit(it) }
                } else if (pinned != null) {
                    // Google answered for the saved account itself, so the pin already holds.
                    // The profile is only for display and may be missing while offline.
                    provenEmail = pinned
                    driveConn.value = DriveConn.CONNECTED
                    runCatching { drive.about() }
                        .onSuccess { account.value = it }
                        .onFailure { messages.emit("Connected, but Drive did not answer: ${it.message}") }
                } else {
                    finish(connector.establish(result.accessToken))
                }
            } catch (e: Exception) {
                settleUnchanged()
                if (interactive) messages.emit(
                    "Could not reach Google sign-in (${e.message}). See docs/GOOGLE_SETUP.md."
                )
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            try {
                finish(connector.establish(DriveAuth.tokenFromConsent(getApplication(), data)))
            } catch (e: Exception) {
                settleUnchanged()
                messages.emit("Google Drive was not connected (${e.message}). See docs/GOOGLE_SETUP.md.")
            }
        }
    }

    private suspend fun finish(outcome: ConnectOutcome) {
        when (outcome) {
            is ConnectOutcome.Connected -> {
                // The pin is saved by now, so work started from here runs on the proven account.
                account.value = outcome.about
                provenEmail = outcome.about.email
                driveConn.value = DriveConn.CONNECTED
                graph.transfers.recover()
            }
            ConnectOutcome.Cancelled -> settleUnchanged()
            is ConnectOutcome.SwitchRefused -> {
                settleUnchanged()
                messages.emit(
                    "Still using ${outcome.current}. Finish, cancel or clear its transfers " +
                        "before switching to ${outcome.requested}."
                )
            }
        }
    }

    /** Nothing new was proven: connected only if the account connected before is still the saved pin. */
    private fun settleUnchanged() {
        driveConn.value =
            if (stillConnected(provenEmail, prefs.accountEmail)) DriveConn.CONNECTED else DriveConn.DISCONNECTED
    }

    // Selection
    fun toggle(id: Long) {
        selection.value = selection.value.let { if (id in it) it - id else it + id }
    }

    fun setSelected(ids: Collection<Long>, selected: Boolean) {
        selection.value = if (selected) selection.value + ids else selection.value - ids.toSet()
    }

    fun clearSelection() {
        selection.value = emptySet()
    }

    fun selectFolder(bucketId: Long) {
        selection.value = media.value.filter { it.bucketId == bucketId }.mapTo(HashSet()) { it.id }
    }

    // Transfers
    fun startTransfer(dest: Destination) {
        val ids = selection.value
        if (ids.isEmpty()) return
        selection.value = emptySet()
        viewModelScope.launch {
            if (!mediaLoaded.value && MediaAccess.hasPermission(getApplication())) {
                mediaLoaded.first { it }
            }
            val items = media.value.filter { it.id in ids }
            if (items.isEmpty()) return@launch
            val added = graph.transfers.enqueue(items, dest)
            val skipped = items.size - added
            messages.emit(
                if (skipped == 0) "$added items queued for Drive"
                else "$added queued. $skipped were already in a transfer"
            )
            tab.value = TAB_TRANSFERS
        }
    }

    fun pin(preset: PresetEntity) {
        viewModelScope.launch { graph.db.presets().upsert(preset) }
    }

    fun unpin(folderId: String) {
        viewModelScope.launch { graph.db.presets().delete(folderId) }
    }

    fun pause() = graph.transfers.pause()
    fun resume() = graph.transfers.resume()

    fun setWifiOnly(value: Boolean) {
        prefs.setWifiOnly(value)
        graph.transfers.applyNetworkRule()
    }

    fun cancel(id: Long) {
        viewModelScope.launch { graph.transfers.cancel(id) }
    }

    fun cancelAllPending() {
        viewModelScope.launch { graph.transfers.cancelAllPending() }
    }

    fun retryFailed() {
        viewModelScope.launch { graph.transfers.retryFailed() }
    }

    fun clearFinished() {
        viewModelScope.launch { graph.transfers.clearFinished() }
    }

    suspend fun verifiedForRemoval(limit: Int): List<String> = graph.transfers.verifiedForRemoval(limit)

    fun confirmRemoved(uris: List<String>) {
        viewModelScope.launch {
            graph.transfers.markRemoved(uris)
            graph.media.refresh()
            messages.emit("${uris.size} files removed from this phone")
        }
    }

    companion object {
        const val TAB_PHONE = 0
        const val TAB_DRIVE = 1
        const val TAB_TRANSFERS = 2
    }
}
