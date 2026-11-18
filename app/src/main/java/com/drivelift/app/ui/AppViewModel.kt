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

    /** When [account], and so the Drive quota, was last read from Drive (Storage, issue #42). */
    val accountReadAt = MutableStateFlow<Long?>(null)
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
    val gridDensity: StateFlow<GridDensity> = graph.prefs.gridDensity

    fun setGridDensity(density: GridDensity) {
        graph.prefs.setGridDensity(density)
    }

    fun cycleGridDensity() {
        setGridDensity(gridDensity.value.nextAccessible())
    }

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
        // Once the phone listing has loaded, a selection never names files that no longer exist,
        // for example files a trash request removed while the app was away, even when a saved
        // selection is restored after the process was recreated.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(selection, media, mediaLoaded) { selected, items, loaded ->
                if (!loaded || selected.isEmpty()) null
                else items.mapTo(HashSet()) { it.id }.let { present -> selected.filterTo(HashSet()) { it in present } }
                    .takeIf { it.size != selected.size }
            }.collect { pruned -> if (pruned != null) selection.value = pruned }
        }
    }

    fun refreshMedia() {
        viewModelScope.launch { graph.media.refresh() }
    }

    /**
     * Work that belongs to the connected account and blocks switching to another one: transfers
     * that can still call Drive, and a confirmed Drive trash or move batch that is still running.
     */
    suspend fun accountSwitchBlockers(): Int =
        dao.count(ACCOUNT_BOUND_STATUSES) +
            (if (driveTrashRun.value?.working == true) 1 else 0) +
            (if (driveMoveRun.value?.working == true) 1 else 0)

    /** Account this session has a working authorization for. Null until a connection succeeds. */
    private var provenEmail: String? = null

    private val connector = AccountConnector(
        pin = prefs,
        identify = { token -> drive.about(token) },
        // A running Drive trash batch belongs to the account it was confirmed for, so it blocks a
        // switch the same way transfers do (issue #36).
        openTransfers = { accountSwitchBlockers() },
        forgetDestinations = {
            graph.db.presets().clear()
            drive.clearPreviewCache()
        },
    )

    /** A stream for the in-app video player, authorised for the connected account. */
    suspend fun openVideoStream(file: com.drivelift.app.drive.DriveFile): com.drivelift.app.drive.DriveVideoStream =
        driveFolders.openVideoStream(file)

    /** Saves a frame captured from a video into Pictures/DriveLift and says how it went. */
    suspend fun saveVideoFrame(bitmap: android.graphics.Bitmap, videoName: String, positionMs: Long) {
        val name = com.drivelift.app.data.frameFileName(videoName, positionMs)
        val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.drivelift.app.data.saveFrameToPictures(getApplication(), bitmap, name)
        }
        messages.emit(
            if (saved != null) "Saved $name to Pictures/${com.drivelift.app.data.FRAME_FOLDER}"
            else "Couldn't save the frame. Check that the phone has free space.",
        )
    }

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
                        .onSuccess {
                            account.value = it
                            accountReadAt.value = graph.clock()
                        }
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
                accountReadAt.value = graph.clock()
                provenEmail = outcome.about.email
                driveConn.value = DriveConn.CONNECTED
                graph.transfers.recover()
            }
            ConnectOutcome.Cancelled -> settleUnchanged()
            is ConnectOutcome.SwitchRefused -> {
                settleUnchanged()
                messages.emit(
                    "Still using ${outcome.current}. Finish, cancel or clear its transfers, and let a " +
                        "Drive trash finish, before switching to ${outcome.requested}."
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

    /** The removal request built from [verifiedForRemoval] was answered, cancelled or not shown. */
    fun removalFinished() {
        viewModelScope.launch { graph.transfers.removalFinished() }
    }

    /**
     * The exact files in [uris] that are still VERIFIED, with name and size, looked up by their
     * own addresses. The text-only confirmation shows these and removes only these, so what the
     * user confirms is always exactly what is deleted.
     */
    suspend fun describeForRemoval(uris: List<String>): List<RemovalItem> {
        val rows = uris.chunked(400).flatMap { graph.db.transfers().byMediaUris(it) }
            .filter { it.status == com.drivelift.app.data.TransferStatus.VERIFIED.name }
            .associateBy { it.mediaUri }
        return uris.mapNotNull { uri -> rows[uri]?.let { RemovalItem(it.name, it.size, uri) } }
    }

    /** Whether removal can use DriveLift's text-only confirmation instead of Android's preview. */
    fun textOnlyRemoval(): Boolean = graph.canManageMedia(getApplication())

    fun confirmRemoved(uris: List<String>) {
        viewModelScope.launch {
            graph.transfers.markRemoved(uris)
            graph.media.refresh()
            messages.emit("${uris.size} files removed from this phone")
        }
    }

    /**
     * Works out a user-directed phone delete for the selected items (issue #36): which go to the
     * trash and which a transfer still depends on. Nothing is changed.
     */
    suspend fun planPhoneDelete(ids: List<Long>): com.drivelift.app.data.PhoneDeletePlan {
        // A request restored after the process was recreated can arrive before the listing has
        // loaded; resolve the ids only against a loaded listing.
        // (Without media permission the listing never loads, so there is nothing to wait for.)
        if (!mediaLoaded.value && MediaAccess.hasPermission(getApplication())) mediaLoaded.first { it }
        val byId = media.value.associateBy { it.id }
        val selected = ids.mapNotNull(byId::get)
        val rows = selected.map { it.uri.toString() }.chunked(400).flatMap { graph.db.transfers().byMediaUris(it) }
        return com.drivelift.app.data.planPhoneDelete(selected, rows)
    }

    /**
     * A phone trash request (issue #36). [ids] is what the user confirmed; [submitted] is the
     * exact list of URIs handed to Android once its dialog is started (files that left the folder
     * in between are not sent and are counted in [notSent]). Kept here, outside the lock-gated
     * screen, so a rotation or the app locking cannot lose it; PhoneTrashHost also keeps a saved
     * copy of [submitted] for process recreation.
     */
    data class PendingTrash(
        val ids: List<Long>,
        val launched: Boolean = false,
        val submitted: List<String> = emptyList(),
        val notSent: Int = 0,
    )

    val pendingTrash = MutableStateFlow<PendingTrash?>(null)

    /** Asks for exactly [ids] to be moved to the phone's trash; PhoneTrashHost shows Android's dialog. */
    fun requestPhoneTrash(ids: List<Long>) {
        if (ids.isNotEmpty()) pendingTrash.value = PendingTrash(ids)
    }

    /**
     * Records the exact URIs sent to Android for the pending batch, marks it started (so it is
     * never shown twice) and builds Android's request. Returns null when nothing is left to send.
     */
    fun startPendingTrash(): android.app.PendingIntent? {
        val pending = pendingTrash.value?.takeIf { !it.launched } ?: return null
        val byId = media.value.associateBy { it.id }
        val uris = pending.ids.mapNotNull { byId[it]?.uri }
        if (uris.isEmpty()) {
            pendingTrash.value = null
            messages.tryEmit("These files are no longer in the folder. Nothing was sent to the trash.")
            return null
        }
        pendingTrash.value = pending.copy(
            launched = true,
            submitted = uris.map { it.toString() },
            notSent = pending.ids.size - uris.size,
        )
        return graph.trashRequest(getApplication<android.app.Application>().contentResolver, uris)
    }

    /**
     * Android answered (or could not show its dialog). The result applies only to the submitted
     * URIs. [savedSubmitted] is PhoneTrashHost's saved copy, used when the process was recreated
     * while Android's dialog was open and this view model starts without the batch.
     */
    fun pendingTrashFinished(confirmed: Boolean, failure: String? = null, savedSubmitted: List<String>? = null) {
        val pending = pendingTrash.value
        val submitted = pending?.submitted?.takeIf { it.isNotEmpty() } ?: savedSubmitted
        pendingTrash.value = null
        if (failure != null) {
            messages.tryEmit("Android could not open the trash request: $failure")
            return
        }
        if (submitted.isNullOrEmpty()) return
        finishPhoneTrash(submitted, pending?.notSent ?: 0, confirmed)
    }

    /** The user cancelled DriveLift's own confirmation: nothing was sent. */
    fun cancelPhoneDelete() {
        messages.tryEmit("Nothing was moved to the trash.")
    }

    /**
     * After Android finished the trash request: reads each submitted file's trash state back from
     * MediaStore and reports confirmed, still present and unchecked files separately. Nothing is
     * inferred from the file having left the listing.
     */
    private fun finishPhoneTrash(submitted: List<String>, notSent: Int, confirmed: Boolean) {
        viewModelScope.launch {
            if (!confirmed) {
                messages.emit("Nothing was moved to the trash.")
                return@launch
            }
            val resolver = getApplication<android.app.Application>().contentResolver
            val states = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                submitted.map { graph.trashState(resolver, android.net.Uri.parse(it)) }
            }
            lastTrashOutcome = com.drivelift.app.data.trashOutcome(states, notSent)
            graph.media.refresh()
            clearSelection()
            messages.emit(lastTrashOutcome!!.message())
        }
    }

    /** The last reported trash result; read by tests. */
    @Volatile
    var lastTrashOutcome: com.drivelift.app.data.TrashOutcome? = null
        private set

    // ------------------------------------------------------------------ Storage insights (issue #42)

    /** When the phone listing the Storage screen measures was last loaded. Null until it loads. */
    val mediaListedAt: StateFlow<Long?> = kotlinx.coroutines.flow.combine(media, mediaLoaded) { _, loaded ->
        if (loaded) graph.clock() else null
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val quotaRefreshing = MutableStateFlow(false)

    /** Reads the connected account's quota. Replaced in screenshot tests, which have no Drive. */
    var quotaSource: suspend () -> DriveAbout = { drive.about() }

    /** Reads the Drive quota again for the connected account; keeps the last figure on failure. */
    fun refreshQuota() {
        if (driveConn.value != DriveConn.CONNECTED || quotaRefreshing.value) return
        quotaRefreshing.value = true
        viewModelScope.launch {
            try {
                val about = quotaSource()
                // Shown only if it is still the account the app is pinned to.
                if (about.email.equals(prefs.accountEmail, ignoreCase = true)) {
                    account.value = about
                    accountReadAt.value = graph.clock()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                messages.emit("Couldn't read the Drive quota: ${com.drivelift.app.transfer.describeError(e)}")
            } finally {
                quotaRefreshing.value = false
            }
        }
    }

    /** The duplicate check: not started, reading files, or finished with its result. */
    sealed interface DuplicateScan {
        data object Idle : DuplicateScan
        data class Running(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val bytesTotal: Long) : DuplicateScan
        data class Done(val result: com.drivelift.app.data.DuplicateResult, val at: Long, val candidates: Int) : DuplicateScan
    }

    val duplicateScan = MutableStateFlow<DuplicateScan>(DuplicateScan.Idle)
    private var duplicateJob: kotlinx.coroutines.Job? = null

    /** The checked groups, minus files that have since left the listing or changed size. */
    val duplicateGroups: StateFlow<List<com.drivelift.app.data.DuplicateGroup>> =
        kotlinx.coroutines.flow.combine(duplicateScan, media) { scan, items ->
            if (scan is DuplicateScan.Done) com.drivelift.app.data.pruneGroups(scan.result.groups, items) else emptyList()
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Files the user picked for cleanup inside the duplicate groups. Nothing is picked by default. */
    val duplicateSelection = MutableStateFlow<Set<Long>>(emptySet())

    init {
        // A duplicate selection only ever names files still in a group.
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(duplicateSelection, duplicateGroups) { selected, groups ->
                val present = groups.flatMap { g -> g.items.map { it.id } }.toHashSet()
                selected.filterTo(HashSet()) { it in present }.takeIf { it.size != selected.size }
            }.collect { pruned -> if (pruned != null) duplicateSelection.value = pruned }
        }
    }

    /**
     * Reads the SHA-256 of a file's original bytes, or null when it cannot be read. Cancellation
     * is passed on, never turned into "unreadable".
     */
    private suspend fun digestOf(item: com.drivelift.app.data.MediaItem, onRead: (Int) -> Unit = {}): String? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            try {
                com.drivelift.app.data.sha256Of(graph.openOriginalStream(getApplication(), item.uri), onRead)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

    /**
     * Groups the listed files by size, then reads every file that shares its size with another and
     * compares SHA-256 checksums. Runs in the view model, so leaving the screen or rotating does not
     * stop it; [cancelDuplicateScan] does. Nothing on the phone is changed.
     */
    fun startDuplicateScan() {
        if (duplicateScan.value is DuplicateScan.Running) return
        duplicateSelection.value = emptySet()
        val candidates = com.drivelift.app.data.sizeCandidates(media.value)
        val files = candidates.sumOf { it.size }
        val bytes = candidates.sumOf { group -> group.sumOf { it.size } }
        duplicateScan.value = DuplicateScan.Running(0, files, 0, bytes)
        duplicateJob = viewModelScope.launch {
            var filesDone = 0
            var bytesDone = 0L
            val result = com.drivelift.app.data.findDuplicates(candidates) { item ->
                digestOf(item) { read ->
                    bytesDone += read
                    duplicateScan.value = DuplicateScan.Running(filesDone, files, bytesDone, bytes)
                }.also {
                    filesDone++
                    duplicateScan.value = DuplicateScan.Running(filesDone, files, bytesDone, bytes)
                }
            }
            duplicateScan.value = DuplicateScan.Done(result, graph.clock(), files)
        }
    }

    fun cancelDuplicateScan() {
        val job = duplicateJob ?: return
        if (duplicateScan.value !is DuplicateScan.Running) return
        job.cancel()
        duplicateJob = null
        duplicateScan.value = DuplicateScan.Idle
        messages.tryEmit("Duplicate check stopped. Nothing was changed.")
    }

    /** Selects or clears one file, never the last unselected copy of its group. */
    fun toggleDuplicate(id: Long) {
        val current = duplicateSelection.value
        val group = duplicateGroups.value.firstOrNull { g -> g.items.any { it.id == id } } ?: return
        duplicateSelection.value = when {
            id in current -> current - id
            com.drivelift.app.data.canSelect(group, current, id) -> current + id
            else -> current
        }
    }

    /** A duplicate cleanup being prepared: files read again, then the plan to confirm. */
    sealed interface DuplicateCleanup {
        data object Checking : DuplicateCleanup
        data class Confirm(val plan: com.drivelift.app.data.DuplicateCleanupPlan) : DuplicateCleanup
    }

    val duplicateCleanup = MutableStateFlow<DuplicateCleanup?>(null)

    private suspend fun planCleanupFor(selected: Set<Long>): com.drivelift.app.data.DuplicateCleanupPlan {
        val groups = duplicateGroups.value
        val uris = groups.flatMap { it.items }.map { it.uri.toString() }
        val rows = uris.chunked(400).flatMap { graph.db.transfers().byMediaUris(it) }
        return com.drivelift.app.data.planDuplicateCleanup(groups, selected, media.value, rows) { digestOf(it) }
    }

    /** Reads the selected files and the copies that stay again, then offers the plan to confirm. */
    fun reviewDuplicateCleanup() {
        if (duplicateCleanup.value != null || duplicateSelection.value.isEmpty()) return
        duplicateCleanup.value = DuplicateCleanup.Checking
        viewModelScope.launch {
            duplicateCleanup.value = DuplicateCleanup.Confirm(planCleanupFor(duplicateSelection.value))
        }
    }

    fun cancelDuplicateCleanup() {
        duplicateCleanup.value = null
        messages.tryEmit("Nothing was moved to the trash.")
    }

    /**
     * The user confirmed. Right before anything is asked of Android, every confirmed file and the
     * copies that stay are read again and transfers are checked again; only files that still pass
     * go to the phone's trash request (the released Phone trash flow, issue #36).
     */
    fun confirmDuplicateCleanup() {
        val shown = (duplicateCleanup.value as? DuplicateCleanup.Confirm)?.plan ?: return
        duplicateCleanup.value = DuplicateCleanup.Checking
        viewModelScope.launch {
            val confirmed = shown.eligible.mapTo(HashSet()) { it.id }
            val fresh = planCleanupFor(confirmed)
            val ids = fresh.eligible.map { it.id }.filter { it in confirmed }
            duplicateCleanup.value = null
            lastCleanupSent = ids
            val dropped = confirmed.size - ids.size
            when {
                ids.isEmpty() -> messages.emit("Nothing was sent to the trash: the files changed or became part of a transfer.")
                dropped > 0 -> messages.emit("$dropped left out because they changed or became part of a transfer since you confirmed.")
            }
            if (ids.isNotEmpty()) requestPhoneTrash(ids)
        }
    }

    /** The ids the last confirmed duplicate cleanup handed to the trash request; read by tests. */
    @Volatile
    var lastCleanupSent: List<Long>? = null
        private set

    /** Transfer rows that could make [files] depend on a transfer: by Drive id, or active by name. */
    private suspend fun transferRowsFor(files: List<com.drivelift.app.drive.DriveFile>): List<com.drivelift.app.data.TransferEntity> {
        val dao = graph.db.transfers()
        return files.chunked(200).flatMap { batch ->
            dao.dependingOnDriveFiles(batch.map { it.id }, batch.map { it.name }.distinct())
        }
    }

    /**
     * Works out a user-directed Drive trash for [files] (issue #36). Nothing is changed. The plan
     * records the connected account; the batch runs for that account only.
     */
    suspend fun planDriveTrash(files: List<com.drivelift.app.drive.DriveFile>): com.drivelift.app.data.DriveTrashPlan =
        com.drivelift.app.data.planDriveTrash(files, transferRowsFor(files), prefs.accountEmail)

    /** A confirmed Drive trash batch: running, then its outcome until the screen applies it. */
    data class DriveTrashRun(val working: Boolean, val outcome: com.drivelift.app.data.DriveTrashOutcome? = null)

    val driveTrashRun = MutableStateFlow<DriveTrashRun?>(null)

    /**
     * Runs a confirmed Drive trash batch in the view model's scope, so a rotation or screen
     * change does not cancel it halfway. The screen observes [driveTrashRun] for the outcome.
     */
    fun startDriveTrash(plan: com.drivelift.app.data.DriveTrashPlan) {
        if (driveTrashRun.value?.working == true) return
        driveTrashRun.value = DriveTrashRun(working = true)
        viewModelScope.launch {
            val outcome = trashDriveFiles(plan.eligible, plan.account)
            driveTrashRun.value = DriveTrashRun(working = false, outcome = outcome)
            messages.emit(outcome.message())
        }
    }

    /** The screen applied the outcome (removed the files from its listing). */
    fun driveTrashApplied() {
        if (driveTrashRun.value?.working == false) driveTrashRun.value = null
    }

    /**
     * Moves exactly [files] to Google Drive's trash as [account], one call per file, and reports
     * each result. For each file, in this order:
     * 1. its copy is reserved in the [com.drivelift.app.transfer.DriveCopyFence], unless an open
     *    phone-removal request relies on it (then it is left alone);
     * 2. transfers are read again: a file a transfer now depends on is left alone;
     * 3. the connected account must still be [account], or this and the remaining files are not
     *    tried; Drive is asked with a token proven to belong to [account];
     * 4. a row verified meanwhile is sent back to be checked, and only then is the copy released.
     * While the copy is reserved no removal request can include a file that relies on it, and the
     * verifier cannot mark it VERIFIED. Stops at an authorization problem.
     */
    suspend fun trashDriveFiles(
        files: List<com.drivelift.app.drive.DriveFile>,
        account: String?,
    ): com.drivelift.app.data.DriveTrashOutcome {
        val trashed = mutableListOf<com.drivelift.app.drive.DriveFile>()
        val failed = mutableListOf<Pair<com.drivelift.app.drive.DriveFile, String>>()
        val skipped = mutableListOf<com.drivelift.app.drive.DriveFile>()
        val fence = graph.driveCopyFence
        fun stop(from: Int, reason: String) =
            com.drivelift.app.data.DriveTrashOutcome(trashed, failed, skipped, files.drop(from), reason)
        if (account.isNullOrBlank()) return stop(0, NOT_CONNECTED)
        for ((i, file) in files.withIndex()) {
            if (!account.equals(prefs.accountEmail, ignoreCase = true)) return stop(i, ACCOUNT_CHANGED)
            if (!fence.beginTrash(file.id)) {
                skipped += file
                continue
            }
            var trashedNow = false
            try {
                // Checked again right before the change: a transfer may have come to depend on this
                // file since the confirmation was shown (for example its upload id was just recorded).
                if (com.drivelift.app.data.transferHolds(file, transferRowsFor(listOf(file)))) {
                    skipped += file
                    continue
                }
                driveFolders.moveToTrash(file.id, account)
                trashedNow = true
                trashed += file
                // A check that read this copy just before it was trashed could have marked it
                // verified meanwhile; send any such row back so the verifier sees the trash.
                if (graph.db.transfers().recheckTrashedCopy(file.id) > 0) graph.transfers.kickVerify()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.drivelift.app.drive.AccountChangedException) {
                return stop(i, ACCOUNT_CHANGED)
            } catch (e: com.drivelift.app.drive.AuthRequiredException) {
                files.drop(i).forEach { failed += it to "Google Drive authorization expired. Reconnect and try again." }
                break
            } catch (e: Exception) {
                failed += file to com.drivelift.app.transfer.describeError(e)
            } finally {
                fence.endTrash(file.id, trashedNow)
            }
        }
        return com.drivelift.app.data.DriveTrashOutcome(trashed, failed, skipped)
    }

    /**
     * Works out a user-directed move of [files] from [fromFolderId] into [toFolderId] (issue #36).
     * Nothing is changed. The plan records the connected account; the batch runs for it only.
     */
    suspend fun planDriveMove(
        files: List<com.drivelift.app.drive.DriveFile>,
        fromFolderId: String,
        toFolderId: String,
        toLabel: String,
    ): com.drivelift.app.data.DriveMovePlan =
        com.drivelift.app.data.planDriveMove(files, transferRowsFor(files), fromFolderId, toFolderId, toLabel, prefs.accountEmail)

    /** A confirmed Drive move batch: running, then its outcome until the screen applies it. */
    data class DriveMoveRun(val working: Boolean, val outcome: com.drivelift.app.data.DriveMoveOutcome? = null)

    val driveMoveRun = MutableStateFlow<DriveMoveRun?>(null)

    /** Runs a confirmed move in the view model's scope, so a rotation does not cancel it halfway. */
    fun startDriveMove(plan: com.drivelift.app.data.DriveMovePlan) {
        if (driveMoveRun.value?.working == true) return
        driveMoveRun.value = DriveMoveRun(working = true)
        viewModelScope.launch {
            val outcome = moveDriveFiles(plan)
            driveMoveRun.value = DriveMoveRun(working = false, outcome = outcome)
            messages.emit(outcome.message())
        }
    }

    /** The screen applied the outcome (removed the moved files from the folder it shows). */
    fun driveMoveApplied() {
        if (driveMoveRun.value?.working == false) driveMoveRun.value = null
    }

    /**
     * Moves exactly the plan's files, one call per file, for the plan's account only, and reports
     * each result. Before each file the transfers are read again (a file a transfer now depends on
     * stays put) and the connected account must still be the plan's, or this and the remaining
     * files are not tried. A file no longer in the source folder is reported, not moved.
     */
    suspend fun moveDriveFiles(plan: com.drivelift.app.data.DriveMovePlan): com.drivelift.app.data.DriveMoveOutcome {
        val files = plan.eligible
        val account = plan.account
        val moved = mutableListOf<com.drivelift.app.drive.DriveFile>()
        val failed = mutableListOf<Pair<com.drivelift.app.drive.DriveFile, String>>()
        val skipped = mutableListOf<com.drivelift.app.drive.DriveFile>()
        fun stop(from: Int, reason: String) =
            com.drivelift.app.data.DriveMoveOutcome(
                plan.toName, moved, failed, skipped, files.drop(from), reason, plan.fromFolderId, plan.toFolderId,
            )
        if (account.isNullOrBlank()) return stop(0, NOT_CONNECTED)
        for ((i, file) in files.withIndex()) {
            if (!account.equals(prefs.accountEmail, ignoreCase = true)) return stop(i, ACCOUNT_CHANGED)
            if (com.drivelift.app.data.transferHolds(file, transferRowsFor(listOf(file)))) {
                skipped += file
                continue
            }
            try {
                driveFolders.moveFile(file.id, plan.fromFolderId, plan.toFolderId, account)
                moved += file
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: com.drivelift.app.drive.AccountChangedException) {
                return stop(i, ACCOUNT_CHANGED)
            } catch (e: com.drivelift.app.drive.AuthRequiredException) {
                files.drop(i).forEach { failed += it to "Google Drive authorization expired. Reconnect and try again." }
                break
            } catch (e: Exception) {
                failed += file to com.drivelift.app.transfer.describeError(e)
            }
        }
        return com.drivelift.app.data.DriveMoveOutcome(
            plan.toName, moved, failed, skipped, fromFolderId = plan.fromFolderId, toFolderId = plan.toFolderId,
        )
    }

    companion object {
        const val TAB_PHONE = 0
        const val TAB_DRIVE = 1
        const val TAB_TRANSFERS = 2
        const val ACCOUNT_CHANGED =
            "the connected Google account changed. Reconnect the account you confirmed with and try again."
        const val NOT_CONNECTED = "Google Drive is not connected. Connect and try again."
    }
}
