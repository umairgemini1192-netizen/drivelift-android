package com.drivelift.app.feature.desktopdrop.controller

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.drivelift.app.feature.desktopdrop.data.AndroidDropDestinationWriter
import com.drivelift.app.feature.desktopdrop.data.AndroidDropThumbnailProvider
import com.drivelift.app.feature.desktopdrop.data.DesktopDropPrefs
import com.drivelift.app.feature.desktopdrop.data.DropPathSanitizer
import com.drivelift.app.feature.desktopdrop.data.SharedItemSource
import com.drivelift.app.feature.desktopdrop.model.BiometricGateMode
import com.drivelift.app.feature.desktopdrop.model.ConnectivityMode
import com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import com.drivelift.app.feature.desktopdrop.network.AndroidMulticastLock
import com.drivelift.app.feature.desktopdrop.network.DropMdnsResponder
import com.drivelift.app.feature.desktopdrop.network.LanNetworkMonitor
import com.drivelift.app.feature.desktopdrop.network.LocalHotspotController
import com.drivelift.app.feature.desktopdrop.server.DesktopDropServerEngine
import com.drivelift.app.feature.desktopdrop.server.KeystoreHmacCookieSigner
import com.drivelift.app.feature.desktopdrop.server.PrefsTrustStore
import com.drivelift.app.feature.desktopdrop.server.ResumableUploadStore
import com.drivelift.app.feature.desktopdrop.server.SessionAuthManager
import com.drivelift.app.feature.cockpit.widget.CockpitGlanceWidget
import com.drivelift.app.feature.desktopdrop.service.DesktopDropService
import com.drivelift.app.feature.desktopdrop.service.DesktopDropTrampolineActivity
import com.drivelift.app.feature.desktopdrop.service.DropApprovalNotifier
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Coordinates the Desktop Drop HTTP server, LAN vs Direct Hotspot network selection, peer approval,
 * shared local queue, and Foreground Service lifecycle.
 */
class DesktopDropController(
    private val app: Application,
    val prefs: DesktopDropPrefs,
    private val itemSource: SharedItemSource,
    private val lanMonitor: LanNetworkMonitor,
    private val hotspotController: LocalHotspotController,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told whenever the server flips between running and stopped, so the Home Screen widget can flip Drop and Stop. */
    private val onRunningChanged: (Boolean) -> Unit = { running -> CockpitGlanceWidget.syncDropState(app, running) },
) {
    /**
     * Nothing launched here may take the app process down: the same process hosts the Cockpit
     * accessibility service, so an unhandled exception in a Drop coroutine would also kill the
     * Dynamic Island.
     */
    private val scope: CoroutineScope by lazy {
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, error ->
                    runCatching { Log.e(TAG, "Desktop Drop task failed: ${error.javaClass.simpleName}") }
                },
        )
    }

    @Volatile
    private var lastReportedRunning = false
    private val partsDir = File(app.cacheDir, "desktop_drop_parts")
    private val authManager = SessionAuthManager(
        clock = clock,
        signer = KeystoreHmacCookieSigner(),
        trustStore = PrefsTrustStore(app),
    ).apply {
        gateMode = prefs.current.gateMode
    }
    private val mdnsResponder = DropMdnsResponder(AndroidMulticastLock(app))

    @Volatile
    private var appInForeground = false

    @Volatile
    private var lastNotifiedKnock: String? = null

    private val _knockPromptNonce = MutableStateFlow(0)

    /** Bumped when the owner taps the approval notification, so the biometric prompt opens again. */
    val knockPromptNonce: StateFlow<Int> = _knockPromptNonce.asStateFlow()
    private val uploadStore = ResumableUploadStore(partsDir, clock = clock)
    private val destinationWriter = AndroidDropDestinationWriter(app, prefs)
    private val cachedHtml: String by lazy {
        runCatching {
            app.assets.open("desktop_drop/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
        }.getOrElse {
            "<html><body><h2>DriveLift Desktop Drop</h2><p>Missing bundled web UI asset.</p></body></html>"
        }
    }

    private val engine = DesktopDropServerEngine(
        authManager = authManager,
        uploadStore = uploadStore,
        itemSource = itemSource,
        destinationWriter = destinationWriter,
        htmlProvider = { cachedHtml },
        destinationLabelProvider = { prefs.current.destinationLabel },
        clock = clock,
        onStateChanged = { syncSnapshot() },
        thumbnailProvider = AndroidDropThumbnailProvider(app, itemSource),
    )

    private val _snapshot = MutableStateFlow(DesktopDropSnapshot(prefs = prefs.current))
    val snapshot: StateFlow<DesktopDropSnapshot> = _snapshot.asStateFlow()

    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private fun setSnapshot(next: DesktopDropSnapshot) {
        updateSnapshot { next }
    }

    private fun updateSnapshot(transform: (DesktopDropSnapshot) -> DesktopDropSnapshot) {
        val updated = _snapshot.updateAndGet(transform)
        val running = updated.status == ServerStatus.RUNNING || updated.status == ServerStatus.STARTING
        _isServerRunning.value = running
        reportRunningTransition(running)
    }

    private fun reportRunningTransition(running: Boolean) {
        val changed = synchronized(this) {
            if (running == lastReportedRunning) false else { lastReportedRunning = running; true }
        }
        if (changed) runCatching { onRunningChanged(running) }
    }

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    fun missingHotspotPermissions(): List<String> = hotspotController.missingPermissions()

    fun startServer(requestedMode: ConnectivityMode = _snapshot.value.mode) {
        scope.launch {
            startServerInternal(requestedMode)
        }
    }

    fun switchMode(newMode: ConnectivityMode) {
        val current = _snapshot.value
        if (current.mode == newMode && current.status == ServerStatus.RUNNING) return
        if (current.status == ServerStatus.RUNNING || current.status == ServerStatus.STARTING) {
            scope.launch {
                stopServerInternal(note = null)
                startServerInternal(newMode)
            }
        } else {
            updateSnapshot { it.copy(mode = newMode, errorMessage = null) }
        }
    }

    fun stopServer(note: String? = null) {
        scope.launch {
            stopServerInternal(note)
        }
    }

    private suspend fun startServerInternal(mode: ConnectivityMode) {
        if (_snapshot.value.status == ServerStatus.STARTING) return
        authManager.gateMode = prefs.current.gateMode
        authManager.resetSessions()
        updateSnapshot {
            it.copy(
                status = ServerStatus.STARTING,
                mode = mode,
                errorMessage = null,
                statusNote = "Starting server...",
            )
        }
        var serviceNote: String? = null
        if (!DesktopDropService.startService(app) && !DesktopDropTrampolineActivity.launch(app)) {
            serviceNote = "Android blocked the background service, keep DriveLift open while you transfer"
        }

        try {
            val hotspotCreds = if (mode == ConnectivityMode.DIRECT_HOTSPOT) {
                hotspotController.startHotspot()
            } else {
                hotspotController.stopHotspot()
                null
            }

            val bindIp = if (mode == ConnectivityMode.DIRECT_HOTSPOT) {
                awaitHotspotIpv4()
                    ?: throw IllegalStateException("Direct Hotspot started, but local IP address is not ready yet")
            } else {
                lanMonitor.resolveLanIpv4(preferHotspotAp = false)
                    ?: throw IllegalStateException("Connect to a Wi-Fi network or switch to Direct Hotspot")
            }

            val actualPort = engine.start(bindIp, prefs.current.preferredPort)
            // Name discovery is optional. Neither call may stop the server from running.
            if (mode == ConnectivityMode.SAME_WIFI) {
                runCatching { lanMonitor.registerMdns(actualPort) }
            }
            val nameResolves = startMdnsSafely(bindIp)
            val lanUrl = "http://$bindIp:$actualPort"
            val bookmarkUrl = "http://${DropMdnsResponder.HOST_NAME}:$actualPort"
            val notes = buildList {
                if (actualPort != prefs.current.preferredPort) {
                    add("Port ${prefs.current.preferredPort} was busy, using $actualPort")
                }
                if (!nameResolves) add("${DropMdnsResponder.HOST_NAME} is unavailable, use the IP address")
                serviceNote?.let { add(it) }
            }
            updateSnapshot {
                it.copy(
                    status = ServerStatus.RUNNING,
                    mode = mode,
                    bindIp = bindIp,
                    port = actualPort,
                    dropUrl = lanUrl,
                    bookmarkUrl = bookmarkUrl,
                    hotspotCredentials = hotspotCreds,
                    peers = authManager.allPeers(),
                    pendingApproval = authManager.pendingApproval(),
                    prefs = prefs.current,
                    errorMessage = null,
                    statusNote = notes.joinToString(". ").ifEmpty { null },
                )
            }
            syncSnapshot()
        } catch (e: Exception) {
            runCatching { hotspotController.stopHotspot() }
            runCatching { mdnsResponder.stop() }
            runCatching { lanMonitor.unregisterMdns() }
            runCatching { engine.stop() }
            DesktopDropService.stopService(app)
            val msg = e.message ?: "Could not start Desktop Drop server"
            updateSnapshot {
                it.copy(
                    status = ServerStatus.ERROR,
                    bindIp = null,
                    port = null,
                    dropUrl = null,
                    bookmarkUrl = null,
                    hotspotCredentials = null,
                    errorMessage = msg,
                    statusNote = null,
                )
            }
            _messages.tryEmit(msg)
        }
    }

    private suspend fun awaitHotspotIpv4(): String? {
        repeat(20) {
            val ip = lanMonitor.resolveLanIpv4(preferHotspotAp = true)
            if (ip != null) return ip
            delay(150L)
        }
        return null
    }

    /** True when drivelift.local is being answered. Any failure is swallowed and reported as false. */
    private fun startMdnsSafely(bindIp: String): Boolean = try {
        mdnsResponder.start(bindIp)
    } catch (e: Throwable) {
        runCatching { Log.w(TAG, "mDNS responder crashed on start: ${e.javaClass.simpleName}") }
        runCatching { mdnsResponder.stop() }
        false
    }

    private fun stopServerInternal(note: String?) {
        runCatching { mdnsResponder.stop() }
        runCatching { lanMonitor.unregisterMdns() }
        runCatching { hotspotController.stopHotspot() }
        runCatching { engine.stop() }
        runCatching { authManager.resetSessions() }
        runCatching { uploadStore.cleanupAllParts() }
        DesktopDropService.stopService(app)
        updateSnapshot {
            it.copy(
                status = ServerStatus.STOPPED,
                bindIp = null,
                port = null,
                dropUrl = null,
                bookmarkUrl = null,
                hotspotCredentials = null,
                peers = emptyList(),
                pendingApproval = null,
                errorMessage = null,
                statusNote = note,
            )
        }
    }

    fun checkIdleAutoStop(): Boolean {
        val snap = _snapshot.value
        if (snap.status != ServerStatus.RUNNING) return false
        if (engine.activeTransferCount() > 0) return false
        val timeoutMs = prefs.current.idleTimeoutMinutes * 60_000L
        if (clock() - engine.lastActivityMs >= timeoutMs) {
            stopServerInternal("Stopped after ${prefs.current.idleTimeoutMinutes} min idle")
            return true
        }
        return false
    }

    /** Call only after the phone's BiometricPrompt succeeded for this knock. */
    fun approveKnockAfterBiometric(knockId: String) {
        authManager.approveKnock(knockId)
        DropApprovalNotifier.cancel(app)
        syncSnapshot()
        engine.notifyChanged()
    }

    /** Denies a pending knock or disconnects an approved browser, trusted or not. */
    fun revokePeer(sessionId: String) {
        authManager.revokePeer(sessionId)
        DropApprovalNotifier.cancel(app)
        syncSnapshot()
        engine.notifyChanged()
    }

    fun setGateMode(mode: BiometricGateMode) {
        prefs.setGateMode(mode)
        authManager.gateMode = mode
        syncSnapshot()
    }

    /** MainActivity reports whether it is on screen, so a knock only posts a notification when it is not. */
    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
    }

    fun requestKnockPrompt() {
        _knockPromptNonce.value = _knockPromptNonce.value + 1
    }

    fun addSharedDocumentUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> resolveDocumentItem(uri) }
            }
            if (added.isNotEmpty()) {
                val existing = engine.sharedItemsSnapshot()
                val existingUris = existing.mapNotNullTo(HashSet()) { it.sourceUri }
                val merged = existing + added.filter { it.sourceUri !in existingUris }
                engine.setSharedItems(merged)
                syncSnapshot()
                _messages.tryEmit(
                    if (added.size == 1) "Added ${added.first().name} to Share Queue"
                    else "${added.size} files added to Share Queue",
                )
            }
        }
    }

    fun addSharedMediaFiles(items: List<SharedDropItem>) {
        if (items.isEmpty()) return
        val existing = engine.sharedItemsSnapshot()
        val seenIds = existing.mapTo(HashSet()) { it.id }
        val merged = existing + items.filter { it.id !in seenIds }
        engine.setSharedItems(merged)
        syncSnapshot()
    }

    fun addSharedFolderTree(treeUri: Uri) {
        scope.launch {
            val added = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val root = DocumentFile.fromTreeUri(app, treeUri) ?: return@withContext emptyList()
                val collected = ArrayList<SharedDropItem>()
                fun walk(dir: DocumentFile, prefix: String) {
                    for (child in dir.listFiles()) {
                        val childName = child.name ?: continue
                        if (child.isDirectory) {
                            val nextPrefix = if (prefix.isEmpty()) childName else "$prefix/$childName"
                            walk(child, nextPrefix)
                        } else if (child.isFile) {
                            val cleanName = DropPathSanitizer.sanitizeFilename(childName)
                            val mime = DropPathSanitizer.resolveMimeType(cleanName, child.type)
                            collected += SharedDropItem(
                                id = UUID.randomUUID().toString(),
                                name = cleanName,
                                relativePath = prefix,
                                sizeBytes = child.length().coerceAtLeast(0L),
                                mimeType = mime,
                                sourceUri = child.uri.toString(),
                                addedAtMs = clock(),
                            )
                        }
                    }
                }
                val rootFolder = root.name?.takeIf { it.isNotBlank() } ?: "Shared"
                walk(root, DropPathSanitizer.sanitizeFilename(rootFolder))
                collected
            }
            if (added.isNotEmpty()) {
                addSharedMediaFiles(added)
                _messages.tryEmit(
                    if (added.size == 1) "Added 1 file from folder" else "Added ${added.size} files from folder",
                )
            } else {
                _messages.tryEmit("Selected folder has no readable files")
            }
        }
    }

    fun removeSharedItem(itemId: String) {
        engine.setSharedItems(engine.sharedItemsSnapshot().filterNot { it.id == itemId })
        syncSnapshot()
    }

    fun clearSharedItems() {
        engine.setSharedItems(emptyList())
        syncSnapshot()
    }

    fun setDestinationTree(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.takePersistableUriPermission(uri, flags) }
        val label = DocumentFile.fromTreeUri(app, uri)?.name?.takeIf { it.isNotBlank() }
            ?: "Custom folder"
        prefs.setDestination(uri.toString(), label)
        syncSnapshot()
    }

    fun resetDestinationToDefault() {
        prefs.resetDestination()
        syncSnapshot()
    }

    fun updateSettings(preferredPort: Int, idleTimeoutMinutes: Int) {
        prefs.setPreferredPort(preferredPort)
        prefs.setIdleTimeoutMinutes(idleTimeoutMinutes)
        syncSnapshot()
    }

    fun notifyMessage(text: String) {
        _messages.tryEmit(text)
    }

    /** Seeds deterministic synthetic state for Robolectric UI tests without opening sockets. */
    internal fun seedPreviewStateForTest(snapshot: DesktopDropSnapshot) {
        engine.setSharedItems(snapshot.sharedItems)
        setSnapshot(snapshot)
    }

    private fun resolveDocumentItem(uri: Uri): SharedDropItem? {
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val resolver = app.contentResolver
        var displayName: String? = null
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) displayName = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        }
        if (size < 0L) {
            size = runCatching {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }
        val cleanName = DropPathSanitizer.sanitizeFilename(displayName ?: uri.lastPathSegment ?: "shared_file")
        val mime = DropPathSanitizer.resolveMimeType(cleanName, resolver.getType(uri))
        return SharedDropItem(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            relativePath = "",
            sizeBytes = size.coerceAtLeast(0L),
            mimeType = mime,
            sourceUri = uri.toString(),
            addedAtMs = clock(),
        )
    }

    private fun maybeNotifyKnock(pending: PeerSession?) {
        if (pending == null) {
            lastNotifiedKnock = null
            return
        }
        if (pending.sessionId == lastNotifiedKnock) return
        lastNotifiedKnock = pending.sessionId
        if (!appInForeground) DropApprovalNotifier.post(app, pending)
    }

    private fun syncSnapshot() {
        val pending = authManager.pendingApproval()
        maybeNotifyKnock(pending)
        updateSnapshot { current ->
            current.copy(
                peers = authManager.allPeers(),
                pendingApproval = pending,
                sharedItems = engine.sharedItemsSnapshot(),
                transfers = engine.transfersSnapshot(),
                aggregateSpeedBytesPerSec = engine.aggregateSpeedBytesPerSec(),
                activeTransferCount = engine.activeTransferCount(),
                prefs = prefs.current,
            )
        }
    }

    private companion object {
        const val TAG = "DesktopDropController"
    }
}
