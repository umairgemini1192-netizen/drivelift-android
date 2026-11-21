package com.drivelift.app.feature.desktopdrop.server

import com.drivelift.app.feature.desktopdrop.data.DropDestinationWriter
import com.drivelift.app.feature.desktopdrop.data.DropPathSanitizer
import com.drivelift.app.feature.desktopdrop.data.SharedItemSource
import com.drivelift.app.feature.desktopdrop.model.DropJson
import com.drivelift.app.feature.desktopdrop.model.DropTransferProgress
import com.drivelift.app.feature.desktopdrop.model.DropTransferStatus
import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import com.drivelift.app.feature.desktopdrop.model.TransferDirection
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.autohead.AutoHeadResponse
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.request.userAgent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.BindException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Embedded Ktor CIO HTTP/WebSocket engine for zero-config LAN and Hotspot transfers.
 */
class DesktopDropServerEngine(
    private val authManager: SessionAuthManager,
    private val uploadStore: ResumableUploadStore,
    private val itemSource: SharedItemSource,
    private val destinationWriter: DropDestinationWriter,
    private val htmlProvider: () -> String,
    private val destinationLabelProvider: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onStateChanged: () -> Unit = {},
) {
    private var serverScope: CoroutineScope? = null
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    private val sharedItems = ConcurrentHashMap<String, SharedDropItem>()
    private val activeTransfers = ConcurrentHashMap<String, DropTransferProgress>()
    private val speedSamples = ConcurrentHashMap<String, SpeedWindow>()
    private val telemetryEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 32)
    private var speedTickerJob: Job? = null

    @Volatile
    var boundPort: Int? = null
        private set

    @Volatile
    var boundHost: String? = null
        private set

    @Volatile
    var lastActivityMs: Long = clock()
        private set

    private data class SpeedWindow(
        var lastWindowStartMs: Long,
        var bytesInWindow: Long,
        var currentSpeedBps: Long,
    )

    fun setSharedItems(items: List<SharedDropItem>) {
        sharedItems.clear()
        items.forEach { sharedItems[it.id] = it }
        notifyChanged()
    }

    fun sharedItemsSnapshot(): List<SharedDropItem> =
        sharedItems.values.sortedBy { it.addedAtMs }

    fun transfersSnapshot(): List<DropTransferProgress> =
        activeTransfers.values.sortedByDescending { it.updatedAtMs }

    fun activeTransferCount(): Int =
        activeTransfers.values.count { it.status == DropTransferStatus.ACTIVE }

    fun aggregateSpeedBytesPerSec(): Long =
        activeTransfers.values
            .filter { it.status == DropTransferStatus.ACTIVE }
            .sumOf { it.speedBytesPerSec }

    suspend fun start(bindHost: String, preferredPort: Int = DEFAULT_PORT): Int {
        stop()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        serverScope = scope
        lastActivityMs = clock()

        val candidates = buildPortCandidates(preferredPort)
        var startedServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val attempt = createEmbeddedServer(bindHost, candidate)
            try {
                attempt.start(wait = false)
                startedServer = attempt
                break
            } catch (e: Throwable) {
                runCatching { attempt.stop(50, 100) }
                if (isBindFailure(e)) {
                    lastError = e
                    continue
                }
                throw e
            }
        }
        val running = startedServer
            ?: throw IllegalStateException("Could not bind Desktop Drop server on $bindHost", lastError)
        server = running
        val resolvedPort = runCatching { running.engine.resolvedConnectors().first().port }
            .getOrNull() ?: candidates.first()
        boundHost = bindHost
        boundPort = resolvedPort

        speedTickerJob = scope.launch {
            while (isActive) {
                delay(500)
                refreshSpeeds()
            }
        }
        notifyChanged()
        return resolvedPort
    }

    fun stop() {
        speedTickerJob?.cancel()
        speedTickerJob = null
        val existing = server
        server = null
        boundPort = null
        boundHost = null
        runCatching { existing?.stop(100, 300) }
        serverScope?.cancel()
        serverScope = null
        notifyChanged()
    }

    fun notifyChanged() {
        telemetryEvents.tryEmit(Unit)
        onStateChanged()
    }

    fun touchActivity() {
        lastActivityMs = clock()
    }

    private fun buildPortCandidates(preferredPort: Int): List<Int> = buildList {
        if (preferredPort in 1024..65535) add(preferredPort)
        for (p in PORT_FALLBACKS) {
            if (p !in this) add(p)
        }
        add(0)
    }

    private fun isBindFailure(error: Throwable): Boolean {
        var cursor: Throwable? = error
        val seen = HashSet<Throwable>()
        while (cursor != null && seen.add(cursor)) {
            if (cursor is BindException) return true
            if (cursor.message?.contains("Address already in use", ignoreCase = true) == true) return true
            cursor = cursor.cause
        }
        return false
    }

    private fun createEmbeddedServer(
        bindHost: String,
        port: Int,
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> =
        embeddedServer(CIO, port = port, host = bindHost) {
            install(AutoHeadResponse)
            install(PartialContent) {
                maxRangeCount = 10
            }
            install(WebSockets)

            intercept(ApplicationCallPipeline.Plugins) {
                val remoteIp = call.request.local.remoteAddress
                if (!authManager.isAllowedRemoteIp(remoteIp)) {
                    call.respondText(
                        """{"error":"LAN access only"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Forbidden,
                    )
                    finish()
                    return@intercept
                }
                touchActivity()
            }

            routing {
                get("/") {
                    val token = call.request.queryParameters["token"]
                    val ua = call.request.userAgent().orEmpty()
                    val remoteIp = call.request.local.remoteAddress
                    if (!authManager.requireApproval && authManager.isValidToken(token)) {
                        val res = authManager.requestAccess(remoteIp, ua, token)
                        if (res?.issuedCookie != null) {
                            appendSessionCookie(call, res.issuedCookie)
                        }
                        notifyChanged()
                    }
                    call.respondText(htmlProvider(), ContentType.Text.Html)
                }

                post("/api/auth/request") {
                    val bodyText = runCatching { call.receiveText() }.getOrDefault("")
                    val json = runCatching { DropJson.parseObject(bodyText) }.getOrNull()
                    val token = json?.get("token")?.toString() ?: call.request.queryParameters["token"]
                    val ua = call.request.userAgent().orEmpty()
                    val remoteIp = call.request.local.remoteAddress
                    val result = authManager.requestAccess(remoteIp, ua, token)
                    if (result == null) {
                        call.respondText(
                            """{"error":"Invalid or expired session token"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                        return@post
                    }
                    notifyChanged()
                    val statusCode = if (result.session.status == PeerApprovalStatus.APPROVED) {
                        result.issuedCookie?.let { appendSessionCookie(call, it) }
                        HttpStatusCode.OK
                    } else {
                        HttpStatusCode.Accepted
                    }
                    call.respondText(
                        """{"status":${DropJson.quoted(result.session.status.name)},"peerId":${DropJson.quoted(result.session.sessionId)},"deviceLabel":${DropJson.quoted(result.session.deviceLabel)}}""",
                        ContentType.Application.Json,
                        statusCode,
                    )
                }

                get("/api/auth/status") {
                    val peerId = call.request.queryParameters["peerId"].orEmpty()
                    val token = call.request.queryParameters["token"]
                    val result = authManager.checkPeerStatus(peerId, token)
                    if (result == null) {
                        call.respondText(
                            """{"error":"Unauthorized peer status query"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                        return@get
                    }
                    if (result.session.status == PeerApprovalStatus.APPROVED && result.issuedCookie != null) {
                        appendSessionCookie(call, result.issuedCookie)
                    }
                    call.respondText(
                        """{"status":${DropJson.quoted(result.session.status.name)},"peerId":${DropJson.quoted(result.session.sessionId)}}""",
                        ContentType.Application.Json,
                        if (result.session.status == PeerApprovalStatus.REVOKED) HttpStatusCode.Forbidden else HttpStatusCode.OK,
                    )
                }

                get("/api/files") {
                    requireAuthorizedPeer(call) ?: return@get
                    val itemsJson = sharedItemsSnapshot().joinToString(",") { item ->
                        buildString {
                            append('{')
                            append("\"id\":${DropJson.quoted(item.id)},")
                            append("\"name\":${DropJson.quoted(item.name)},")
                            append("\"relativePath\":${DropJson.quoted(item.relativePath)},")
                            append("\"sizeBytes\":${item.sizeBytes},")
                            append("\"mimeType\":${DropJson.quoted(item.mimeType)}")
                            append('}')
                        }
                    }
                    call.respondText(
                        """{"files":[$itemsJson],"destinationLabel":${DropJson.quoted(destinationLabelProvider())}}""",
                        ContentType.Application.Json,
                    )
                }

                get("/api/download/{fileId}") {
                    requireAuthorizedPeer(call) ?: return@get
                    val fileId = call.parameters["fileId"].orEmpty()
                    val item = sharedItems[fileId]
                    if (item == null) {
                        call.respondText("""{"error":"File not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@get
                    }
                    val safeName = DropPathSanitizer.sanitizeFilename(item.name)
                    val isHead = call.request.httpMethod == HttpMethod.Head
                    val transferId = if (isHead) null else registerTransferStart(
                        filename = item.name,
                        relativePath = item.relativePath,
                        direction = TransferDirection.PHONE_TO_PC,
                        totalBytes = item.sizeBytes,
                    )
                    val content = SharedItemRangeContent(
                        item = item,
                        safeName = safeName,
                        source = itemSource,
                        onBytesSent = { delta ->
                            if (transferId != null && delta > 0L) {
                                addTransferProgress(transferId, delta)
                            }
                        },
                        onStreamFinished = { ok ->
                            if (transferId != null) {
                                if (ok) {
                                    markTransferCompleted(transferId)
                                } else {
                                    markTransferStatus(transferId, DropTransferStatus.FAILED, "Download interrupted")
                                }
                            }
                        },
                    )
                    call.respond(content)
                }

                get("/api/download-zip") {
                    requireAuthorizedPeer(call) ?: return@get
                    val idsParam = call.request.queryParameters["ids"]
                    val selected = if (idsParam.isNullOrBlank()) {
                        sharedItemsSnapshot()
                    } else {
                        val wanted = idsParam.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                        sharedItemsSnapshot().filter { it.id in wanted }
                    }
                    if (selected.isEmpty()) {
                        call.respondText("""{"error":"No shared files available"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@get
                    }
                    val totalEstimated = selected.sumOf { it.sizeBytes.coerceAtLeast(0L) }
                    val transferId = registerTransferStart(
                        filename = "DriveLift-Drop.zip",
                        relativePath = "${selected.size} files",
                        direction = TransferDirection.PHONE_TO_PC,
                        totalBytes = totalEstimated,
                    )
                    call.response.header(
                        HttpHeaders.ContentDisposition,
                        ContentDisposition.Attachment
                            .withParameter(ContentDisposition.Parameters.FileName, "DriveLift-Drop.zip")
                            .toString(),
                    )
                    try {
                        call.respondOutputStream(ContentType.Application.Zip) {
                            StreamingZipWriter.writeStoredZip(
                                items = selected,
                                source = itemSource,
                                outputStream = this,
                            ) { delta ->
                                addTransferProgress(transferId, delta)
                            }
                        }
                        markTransferCompleted(transferId, actualSizeOverride = activeTransfers[transferId]?.transferredBytes)
                    } catch (e: CancellationException) {
                        markTransferStatus(transferId, DropTransferStatus.CANCELLED, "Cancelled")
                        throw e
                    } catch (e: Exception) {
                        markTransferStatus(transferId, DropTransferStatus.FAILED, e.message ?: "ZIP stream failed")
                        throw e
                    }
                }

                post("/api/upload/init") {
                    requireAuthorizedPeer(call) ?: return@post
                    val bodyText = runCatching { call.receiveText() }.getOrDefault("")
                    val json = runCatching { DropJson.parseObject(bodyText) }.getOrNull()
                    if (json == null) {
                        call.respondText("""{"error":"Invalid JSON body"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val filename = json["filename"]?.toString()?.takeIf { it.isNotBlank() }
                    val totalSize = (json["totalSize"] as? Number)?.toLong()
                    if (filename == null || totalSize == null || totalSize < 0L) {
                        call.respondText("""{"error":"filename and non-negative totalSize are required"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@post
                    }
                    val relPath = json["relativePath"]?.toString()
                    val sha256 = json["sha256"]?.toString()
                    val mimeType = json["mimeType"]?.toString()

                    val init = uploadStore.initUpload(filename, relPath, totalSize, sha256, mimeType)
                    registerOrResumeUploadTransfer(
                        uploadId = init.uploadId,
                        filename = DropPathSanitizer.sanitizeFilename(filename),
                        relativePath = relPath.orEmpty(),
                        totalSize = init.totalSize,
                        receivedBytes = init.receivedBytes,
                    )
                    call.respondText(
                        """{"uploadId":${DropJson.quoted(init.uploadId)},"receivedBytes":${init.receivedBytes},"totalSize":${init.totalSize},"chunkSizeBytes":${init.chunkSizeBytes},"resumed":${init.resumed}}""",
                        ContentType.Application.Json,
                    )
                }

                patch("/api/upload/{uploadId}") {
                    requireAuthorizedPeer(call) ?: return@patch
                    val uploadId = call.parameters["uploadId"].orEmpty()
                    val offsetHeader = call.request.headers["X-Chunk-Offset"]?.toLongOrNull()
                    if (offsetHeader == null || offsetHeader < 0L) {
                        call.respondText("""{"error":"Missing or invalid X-Chunk-Offset header"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@patch
                    }
                    val result = try {
                        uploadStore.appendChunk(uploadId, offsetHeader, call.receiveChannel()) { received, delta ->
                            setUploadProgress(uploadId, received, delta)
                        }
                    } catch (e: IllegalArgumentException) {
                        call.respondText("""{"error":${DropJson.quoted(e.message ?: "Bad chunk")}}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                        return@patch
                    }
                    when (result) {
                        is ResumableUploadStore.ChunkResult.NotFound -> {
                            call.respondText("""{"error":"Upload session not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                        }
                        is ResumableUploadStore.ChunkResult.Conflict -> {
                            call.response.header("X-Expected-Offset", result.expectedOffset.toString())
                            call.respondText(
                                """{"error":"Offset mismatch","expectedOffset":${result.expectedOffset}}""",
                                ContentType.Application.Json,
                                HttpStatusCode.Conflict,
                            )
                        }
                        is ResumableUploadStore.ChunkResult.Appended -> {
                            call.respondText(
                                """{"uploadId":${DropJson.quoted(uploadId)},"receivedBytes":${result.receivedBytes},"totalSize":${result.totalSize}}""",
                                ContentType.Application.Json,
                            )
                        }
                    }
                }

                post("/api/upload/{uploadId}/complete") {
                    requireAuthorizedPeer(call) ?: return@post
                    val uploadId = call.parameters["uploadId"].orEmpty()
                    try {
                        val committed = uploadStore.completeUpload(uploadId, destinationWriter)
                        markTransferCompleted(uploadId, actualSizeOverride = committed.sizeBytes)
                        call.respondText(
                            """{"uploadId":${DropJson.quoted(uploadId)},"savedName":${DropJson.quoted(committed.savedName)},"destinationLabel":${DropJson.quoted(committed.destinationLabel)},"sizeBytes":${committed.sizeBytes}}""",
                            ContentType.Application.Json,
                        )
                    } catch (_: NoSuchElementException) {
                        call.respondText("""{"error":"Upload session not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                    } catch (e: IllegalStateException) {
                        markTransferStatus(uploadId, DropTransferStatus.FAILED, e.message ?: "Verification failed")
                        call.respondText("""{"error":${DropJson.quoted(e.message ?: "Verification failed")}}""", ContentType.Application.Json, HttpStatusCode.Conflict)
                    } catch (e: Exception) {
                        markTransferStatus(uploadId, DropTransferStatus.FAILED, e.message ?: "Save failed")
                        call.respondText("""{"error":${DropJson.quoted(e.message ?: "Save failed")}}""", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }

                delete("/api/upload/{uploadId}") {
                    requireAuthorizedPeer(call) ?: return@delete
                    val uploadId = call.parameters["uploadId"].orEmpty()
                    val deleted = uploadStore.abortUpload(uploadId)
                    markTransferStatus(uploadId, DropTransferStatus.CANCELLED, "Cancelled by sender")
                    call.respondText(
                        """{"uploadId":${DropJson.quoted(uploadId)},"cancelled":$deleted}""",
                        ContentType.Application.Json,
                    )
                }

                webSocket("/ws/telemetry") {
                    val cookieVal = call.request.cookies[SessionAuthManager.SESSION_COOKIE_NAME]
                    val peer = authManager.verifyCookie(cookieVal)
                    if (peer == null) {
                        close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Unauthorized"))
                        return@webSocket
                    }
                    send(Frame.Text(buildTelemetryJson()))
                    val pushJob = launch {
                        telemetryEvents.collect {
                            val livePeer = authManager.verifyCookie(cookieVal)
                            if (livePeer == null) {
                                close(CloseReason(CloseReason.Codes.NORMAL, "Peer disconnected"))
                                return@collect
                            }
                            runCatching { send(Frame.Text(buildTelemetryJson())) }
                        }
                    }
                    try {
                        for (frame in incoming) {
                            touchActivity()
                            if (frame is Frame.Close) break
                        }
                    } finally {
                        pushJob.cancel()
                    }
                }
            }
        }

    private suspend fun requireAuthorizedPeer(call: ApplicationCall): PeerSession? {
        val cookieValue = call.request.cookies[SessionAuthManager.SESSION_COOKIE_NAME]
        val peer = authManager.verifyCookie(cookieValue)
        if (peer == null) {
            call.respondText(
                """{"error":"Unauthorized or awaiting phone approval"}""",
                ContentType.Application.Json,
                HttpStatusCode.Unauthorized,
            )
            return null
        }
        return peer
    }

    private fun appendSessionCookie(call: ApplicationCall, cookieValue: String) {
        call.response.cookies.append(
            Cookie(
                name = SessionAuthManager.SESSION_COOKIE_NAME,
                value = cookieValue,
                httpOnly = true,
                path = "/",
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
    }

    private fun registerTransferStart(
        filename: String,
        relativePath: String,
        direction: TransferDirection,
        totalBytes: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = clock()
        speedSamples[id] = SpeedWindow(now, 0L, 0L)
        activeTransfers[id] = DropTransferProgress(
            transferId = id,
            filename = filename,
            relativePath = relativePath,
            direction = direction,
            totalBytes = totalBytes,
            transferredBytes = 0L,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            status = DropTransferStatus.ACTIVE,
            updatedAtMs = now,
        )
        notifyChanged()
        return id
    }

    private fun registerOrResumeUploadTransfer(
        uploadId: String,
        filename: String,
        relativePath: String,
        totalSize: Long,
        receivedBytes: Long,
    ) {
        val now = clock()
        speedSamples.putIfAbsent(uploadId, SpeedWindow(now, 0L, 0L))
        activeTransfers[uploadId] = DropTransferProgress(
            transferId = uploadId,
            filename = filename,
            relativePath = relativePath,
            direction = TransferDirection.PC_TO_PHONE,
            totalBytes = totalSize,
            transferredBytes = receivedBytes,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            status = DropTransferStatus.ACTIVE,
            updatedAtMs = now,
        )
        notifyChanged()
    }

    private fun addTransferProgress(transferId: String, delta: Long) {
        val existing = activeTransfers[transferId] ?: return
        val newTotal = existing.transferredBytes + delta
        updateTransferBytes(transferId, existing, newTotal, delta)
    }

    private fun setUploadProgress(uploadId: String, totalReceived: Long, delta: Long) {
        val existing = activeTransfers[uploadId] ?: return
        updateTransferBytes(uploadId, existing, totalReceived, delta)
    }

    private fun updateTransferBytes(
        id: String,
        existing: DropTransferProgress,
        newTransferredBytes: Long,
        deltaBytes: Long,
    ) {
        val now = clock()
        touchActivity()
        val win = speedSamples.getOrPut(id) { SpeedWindow(now, 0L, 0L) }
        win.bytesInWindow += deltaBytes
        val elapsedMs = now - win.lastWindowStartMs
        var emitted = false
        if (elapsedMs >= 500L) {
            win.currentSpeedBps = (win.bytesInWindow * 1000L) / elapsedMs.coerceAtLeast(1L)
            win.bytesInWindow = 0L
            win.lastWindowStartMs = now
            emitted = true
        }
        val remaining = (existing.totalBytes - newTransferredBytes).coerceAtLeast(0L)
        val eta = if (win.currentSpeedBps > 0L) remaining / win.currentSpeedBps else 0L
        activeTransfers[id] = existing.copy(
            transferredBytes = newTransferredBytes,
            speedBytesPerSec = win.currentSpeedBps,
            etaSeconds = eta,
            status = DropTransferStatus.ACTIVE,
            updatedAtMs = now,
        )
        if (emitted) notifyChanged()
    }

    private fun refreshSpeeds() {
        val now = clock()
        var dirty = false
        for ((id, item) in activeTransfers) {
            if (item.status != DropTransferStatus.ACTIVE) continue
            val win = speedSamples[id] ?: continue
            val elapsed = now - win.lastWindowStartMs
            if (elapsed >= 1200L && item.speedBytesPerSec > 0L) {
                win.currentSpeedBps = 0L
                win.bytesInWindow = 0L
                win.lastWindowStartMs = now
                activeTransfers[id] = item.copy(speedBytesPerSec = 0L, updatedAtMs = now)
                dirty = true
            }
        }
        if (dirty) notifyChanged()
    }

    private fun markTransferCompleted(id: String, actualSizeOverride: Long? = null) {
        val existing = activeTransfers[id] ?: return
        val finalBytes = actualSizeOverride ?: existing.totalBytes
        activeTransfers[id] = existing.copy(
            totalBytes = maxOf(existing.totalBytes, finalBytes),
            transferredBytes = finalBytes,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            status = DropTransferStatus.COMPLETED,
            errorMessage = null,
            updatedAtMs = clock(),
        )
        speedSamples.remove(id)
        notifyChanged()
    }

    private fun markTransferStatus(id: String, status: DropTransferStatus, message: String? = null) {
        val existing = activeTransfers[id] ?: return
        activeTransfers[id] = existing.copy(
            speedBytesPerSec = 0L,
            status = status,
            errorMessage = message,
            updatedAtMs = clock(),
        )
        speedSamples.remove(id)
        notifyChanged()
    }

    private fun buildTelemetryJson(): String {
        val activeCount = activeTransferCount()
        val speed = aggregateSpeedBytesPerSec()
        val transfersJson = transfersSnapshot().take(12).joinToString(",") { t ->
            buildString {
                append('{')
                append("\"transferId\":${DropJson.quoted(t.transferId)},")
                append("\"filename\":${DropJson.quoted(t.filename)},")
                append("\"direction\":${DropJson.quoted(t.direction.name)},")
                append("\"totalBytes\":${t.totalBytes},")
                append("\"transferredBytes\":${t.transferredBytes},")
                append("\"speedBytesPerSec\":${t.speedBytesPerSec},")
                append("\"etaSeconds\":${t.etaSeconds},")
                append("\"status\":${DropJson.quoted(t.status.name)}")
                append('}')
            }
        }
        return """{"speedBytesPerSec":$speed,"activeTransferCount":$activeCount,"sharedFilesCount":${sharedItems.size},"transfers":[$transfersJson]}"""
    }

    private class SharedItemRangeContent(
        private val item: SharedDropItem,
        safeName: String,
        private val source: SharedItemSource,
        private val onBytesSent: (Long) -> Unit,
        private val onStreamFinished: (Boolean) -> Unit,
    ) : OutgoingContent.ReadChannelContent() {

        override val contentLength: Long = item.sizeBytes.coerceAtLeast(0L)

        override val contentType: ContentType =
            runCatching { ContentType.parse(item.mimeType) }.getOrDefault(ContentType.Application.OctetStream)

        override val headers: Headers = Headers.build {
            append(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(ContentDisposition.Parameters.FileName, safeName)
                    .toString(),
            )
        }

        override fun readFrom(): ByteReadChannel {
            val raw = source.openInputStream(item)
            return TrackingInputStream(BufferedInputStream(raw, 64 * 1024), onBytesSent, onStreamFinished)
                .toByteReadChannel(context = Dispatchers.IO)
        }

        override fun readFrom(range: LongRange): ByteReadChannel {
            val len = (range.last - range.first + 1L).coerceAtLeast(0L)
            val raw = source.openRangedInputStream(item, range.first, len)
            return TrackingInputStream(BufferedInputStream(raw, 64 * 1024), onBytesSent, onStreamFinished)
                .toByteReadChannel(context = Dispatchers.IO)
        }
    }

    private class TrackingInputStream(
        delegate: InputStream,
        private val onBytesRead: (Long) -> Unit,
        private val onFinished: (Boolean) -> Unit,
    ) : FilterInputStream(delegate) {
        private var failed = false
        private var notified = false

        override fun read(): Int = try {
            val b = super.read()
            if (b >= 0) onBytesRead(1L)
            b
        } catch (t: Throwable) {
            failed = true
            throw t
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int = try {
            val count = super.read(b, off, len)
            if (count > 0) onBytesRead(count.toLong())
            count
        } catch (t: Throwable) {
            failed = true
            throw t
        }

        override fun close() {
            try {
                super.close()
            } catch (t: Throwable) {
                failed = true
                throw t
            } finally {
                if (!notified) {
                    notified = true
                    onFinished(!failed)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_PORT = 8080
        val PORT_FALLBACKS = listOf(8080, 8081, 8888)
    }
}
