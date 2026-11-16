package com.drivelift.app.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivelift.app.Graph
import com.drivelift.app.data.MediaFolder
import com.drivelift.app.data.PresetEntity
import com.drivelift.app.data.StatusCount
import com.drivelift.app.data.TransferEntity
import com.drivelift.app.data.groupFolders
import com.drivelift.app.drive.DriveAbout
import com.drivelift.app.drive.DriveAuth
import com.drivelift.app.transfer.Destination
import com.drivelift.app.transfer.LiveProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class DriveConn { UNKNOWN, CONNECTING, CONNECTED, DISCONNECTED }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = Graph.get(app)
    private val dao = graph.db.transfers()
    val drive get() = graph.drive
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

    fun connectDrive(interactive: Boolean) {
        if (driveConn.value == DriveConn.CONNECTING) return
        driveConn.value = DriveConn.CONNECTING
        viewModelScope.launch {
            try {
                // A tap on Connect may be a switch of account, so it must not be pinned to the old one.
                if (interactive) prefs.accountEmail = null
                val result = DriveAuth.authorize(getApplication(), prefs.accountEmail)
                if (result.hasResolution()) {
                    driveConn.value = DriveConn.DISCONNECTED
                    if (interactive) result.pendingIntent?.let { consentRequests.emit(it) }
                } else {
                    loadAccount()
                }
            } catch (e: Exception) {
                driveConn.value = DriveConn.DISCONNECTED
                if (interactive) messages.emit(
                    "Could not reach Google sign-in (${e.message}). See docs/GOOGLE_SETUP.md."
                )
            }
        }
    }

    fun onConsentResult(data: Intent?) {
        viewModelScope.launch {
            try {
                if (DriveAuth.tokenFromConsent(getApplication(), data) != null) {
                    prefs.accountEmail = null
                    loadAccount()
                    graph.transfers.recover()
                } else {
                    driveConn.value = DriveConn.DISCONNECTED
                }
            } catch (e: Exception) {
                driveConn.value = DriveConn.DISCONNECTED
                messages.emit("Google Drive was not connected (${e.message}). See docs/GOOGLE_SETUP.md.")
            }
        }
    }

    private suspend fun loadAccount() {
        // Having a token is what makes the connection usable; the profile is a nicety.
        driveConn.value = DriveConn.CONNECTED
        runCatching { drive.about() }
            .onSuccess {
                account.value = it
                prefs.accountEmail = it.email
            }
            .onFailure { messages.emit("Connected, but Drive did not answer: ${it.message}") }
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
        val items = media.value.filter { it.id in ids }
        if (items.isEmpty()) return
        selection.value = emptySet()
        viewModelScope.launch {
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
