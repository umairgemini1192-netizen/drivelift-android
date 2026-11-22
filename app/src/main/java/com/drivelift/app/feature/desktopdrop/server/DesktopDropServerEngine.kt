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
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.net.BindException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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

    @Volatile
    private var sharedItemsMap: Map<String, SharedDropItem> = emptyMap()
    private val activeTransfers = ConcurrentHashMap<String, DropTransferProgress>()
    private val progressTrackers = ConcurrentHashMap<String, TransferProgressTracker>()
    private val wsClients = ConcurrentHashMap.newKeySet<WsClient>()
    private val lastProgressEmitMs = AtomicLong(Long.MIN_VALUE)
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

    private sealed interface WsFrameEvent {
        data object Telemetry : WsFrameEvent
        data class QueueUpdated(val sharedFilesCount: Int) : WsFrameEvent
    }

    private class WsClient {
        val queueChannel = Channel<Int>(Channel.CONFLATED)
        val telemetryChannel = Channel<Unit>(Channel.CONFLATED)

        fun close() {
            queueChannel.close()
            telemetryChannel.close()
        }
    }

    private class TransferProgressTracker(
        @Volatile var totalBytes: Long,
        var lastWindowStartMs: Long,
        var lastEmitMs: Long,
        var bytesInWindow: Long = 0L,
        var transferredBytes: Long = 0L,
        var currentSpeedBps: Long = 0L,
    )

    fun setSharedItems(items: List<SharedDropItem>) {
        val next = LinkedHashMap<String, SharedDropItem>(items.size)
        items.forEach { next[it.id] = it }
        sharedItemsMap = next
        broadcastQueueUpdated(next.size)
        notifyChanged()
    }

    fun sharedItemsSnapshot(): List<SharedDropItem> =
        sharedItemsMap.values.sortedBy { it.addedAtMs }

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
        lastProgressEmitMs.set(Long.MIN_VALUE)

        val candidates = buildPortCandidates(preferredPort)
        var startedServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
        var lastError: Throwable? = null
        for (candidate in candidates) {
            val attempt = createEmbeddedServer(bindHost, candidate, scope)
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
        wsClients.forEach { it.close() }
        wsClients.clear()
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
        broadcastTelemetry()
        onStateChanged()
    }

    private fun broadcastQueueUpdated(sharedCount: Int) {
        for (client in wsClients) {
            client.queueChannel.trySend(sharedCount)
        }
    }

    private fun broadcastTelemetry() {
        for (client in wsClients) {
            client.telemetryChannel.trySend(Unit)
        }
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
        scope: CoroutineScope,
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
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText(htmlProvider(), ContentType.Text.Html)
                }

                get("/api/auth/session") {
                    val peer = authManager.verifyCookie(call.request.cookies[SessionAuthManager.SESSION_COOKIE_NAME])
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    if (peer == null) {
                        call.respondText(
                            """{"authenticated":false}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                    } else {
                        call.respondText(
                            """{"authenticated":true,"deviceLabel":${DropJson.quoted(peer.deviceLabel)},"trusted":${peer.trusted}}""",
                            ContentType.Application.Json,
                        )
                    }
                }

                post("/api/auth/knock") {
                    val ua = call.request.userAgent().orEmpty()
                    val remoteIp = call.request.local.remoteAddress
                    val knock = authManager.openKnock(remoteIp, ua)
                    notifyChanged()
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText(
                        """{"knockId":${DropJson.quoted(knock.sessionId)},"matchCode":${DropJson.quoted(knock.matchCode)},"deviceLabel":${DropJson.quoted(knock.deviceLabel)},"status":${DropJson.quoted(knockStatusName(knock.status))}}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Accepted,
                    )
                }

                get("/api/auth/wait") {
                    val knockId = call.request.queryParameters["knockId"].orEmpty()
                    val remoteIp = call.request.local.remoteAddress
                    val initial = authManager.knockState(knockId)
                    if (initial == null || initial.peerIp != remoteIp) {
                        call.respondText(
                            """{"error":"Unknown knock"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.NotFound,
                        )
                        return@get
                    }
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.response.header("X-Accel-Buffering", "no")
                    call.respondTextWriter(ContentType.Text.EventStream) {
                        write("retry: 1500\n\n")
                        flush()
                        var lastSent: String? = null
                        var ticks = 0
                        while (true) {
                            val name = knockStatusName(authManager.knockState(knockId)?.status)
                            if (name != lastSent) {
                                write("event: status\ndata: {\"status\":\"$name\"}\n\n")
                                flush()
                                lastSent = name
                            }
                            if (name != "PENDING") break
                            delay(KNOCK_POLL_MS)
                            ticks++
                            if (ticks % KEEPALIVE_TICKS == 0) {
                                write(": keepalive\n\n")
                                flush()
                            }
                        }
                    }
                }

                post("/api/auth/claim") {
                    val bodyText = runCatching { call.receiveText() }.getOrDefault("")
                    val json = runCatching { DropJson.parseObject(bodyText) }.getOrNull()
                    val knockId = json?.get("knockId")?.toString().orEmpty()
                    val remoteIp = call.request.local.remoteAddress
                    val issued = authManager.claimCookie(knockId, remoteIp)
                    if (issued == null) {
                        call.respondText(
                            """{"error":"Not approved on the phone yet, or already claimed"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.Unauthorized,
                        )
                        return@post
                    }
                    appendSessionCookie(call, issued.value, issued.maxAgeSeconds)
                    notifyChanged()
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText(
                        """{"status":"APPROVED","deviceLabel":${DropJson.quoted(issued.session.deviceLabel)},"trusted":${issued.session.trusted}}""",
                        ContentType.Application.Json,
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
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.respondText(
                        """{"files":[$itemsJson],"destinationLabel":${DropJson.quoted(destinationLabelProvider())}}""",
                        ContentType.Application.Json,
                    )
                }

                get("/api/download/{fileId}") {
                    requireAuthorizedPeer(call) ?: return@get
                    val fileId = call.parameters["fileId"].orEmpty()
                    val item = sharedItemsMap[fileId]
                    if (item == null) {
                        call.respondText("""{"error":"File not found"}""", ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@get
                    }
                    val safeName = DropPathSanitizer.sanitizeFilename(item.name)
                    val isHead = call.request.httpMethod == HttpMethod.Head
                    val content = SharedItemRangeContent(
                        scope = scope,
                        item = item,
                        safeName = safeName,
                        source = itemSource,
                        onStreamStarted = { expectedBytes ->
                            if (isHead) {
                                null
                            } else {
                                registerTransferStart(
                                    filename = item.name,
                                    relativePath = item.relativePath,
                                    direction = TransferDirection.PHONE_TO_PC,
                                    totalBytes = if (expectedBytes > 0L) expectedBytes else item.sizeBytes,
                                )
                            }
                        },
                        onBytesSent = { transferId, delta ->
                            if (delta > 0L) {
                                recordTransferProgress(transferId, delta)
                            }
                        },
                        onStreamFinished = { transferId, ok, cancelled, bytesSent ->
                            if (transferId != null) {
                                when {
                                    ok -> markTransferCompleted(transferId, actualSizeOverride = bytesSent)
                                    cancelled -> markTransferStatus(transferId, DropTransferStatus.CANCELLED, "Download cancelled")
                                    else -> markTransferStatus(transferId, DropTransferStatus.FAILED, "Download interrupted")
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
                            withContext(Dispatchers.IO) {
                                StreamingZipWriter.writeStoredZip(
                                    items = selected,
                                    source = itemSource,
                                    outputStream = this@respondOutputStream,
                                ) { delta ->
                                    recordTransferProgress(transferId, delta)
                                }
                            }
                        }
                        markTransferCompleted(transferId)
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
                            recordTransferProgress(uploadId, deltaBytes = delta, absoluteTransferredBytes = received)
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
                        markTransferCompleted(
                            uploadId,
                            actualSizeOverride = committed.sizeBytes,
                            savedUri = committed.uriString,
                            mimeType = committed.mimeType,
                        )
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
                    val client = WsClient()
                    wsClients.add(client)
                    var pushJob: Job? = null
                    try {
                        send(Frame.Text(buildTelemetryJson()))
                        pushJob = launch {
                            try {
                                while (isActive) {
                                    val event: WsFrameEvent = select {
                                        client.queueChannel.onReceive { count -> WsFrameEvent.QueueUpdated(count) }
                                        client.telemetryChannel.onReceive { WsFrameEvent.Telemetry }
                                    }
                                    val livePeer = authManager.verifyCookie(cookieVal)
                                    if (livePeer == null) {
                                        close(CloseReason(CloseReason.Codes.NORMAL, "Peer disconnected"))
                                        break
                                    }
                                    val payload = when (event) {
                                        WsFrameEvent.Telemetry -> buildTelemetryJson()
                                        is WsFrameEvent.QueueUpdated ->
                                            """{"type":"queue_updated","sharedFilesCount":${event.sharedFilesCount}}"""
                                    }
                                    runCatching { send(Frame.Text(payload)) }
                                }
                            } catch (_: ClosedReceiveChannelException) {
                                // Channel closed when server stops or session disconnects.
                            }
                        }
                        for (frame in incoming) {
                            touchActivity()
                            if (frame is Frame.Close) break
                        }
                    } finally {
                        pushJob?.cancel()
                        wsClients.remove(client)
                        client.close()
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

    private fun knockStatusName(status: PeerApprovalStatus?): String = when (status) {
        PeerApprovalStatus.PENDING -> "PENDING"
        PeerApprovalStatus.APPROVED -> "APPROVED"
        PeerApprovalStatus.EXPIRED -> "EXPIRED"
        else -> "DENIED"
    }

    private fun appendSessionCookie(call: ApplicationCall, cookieValue: String, maxAgeSeconds: Int?) {
        call.response.cookies.append(
            Cookie(
                name = SessionAuthManager.SESSION_COOKIE_NAME,
                value = cookieValue,
                maxAge = maxAgeSeconds ?: 0,
                httpOnly = true,
                path = "/",
                extensions = mapOf("SameSite" to "Strict"),
            ),
        )
    }

    internal fun registerTransferStart(
        filename: String,
        relativePath: String,
        direction: TransferDirection,
        totalBytes: Long,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = clock()
        progressTrackers[id] = TransferProgressTracker(
            totalBytes = totalBytes,
            lastWindowStartMs = now,
            lastEmitMs = now,
        )
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
        progressTrackers.compute(uploadId) { _, existing ->
            (existing ?: TransferProgressTracker(totalSize, now, now, transferredBytes = receivedBytes)).apply {
                totalBytes = totalSize
                transferredBytes = maxOf(transferredBytes, receivedBytes)
                lastWindowStartMs = now
                lastEmitMs = now
                bytesInWindow = 0L
                currentSpeedBps = 0L
            }
        }
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

    internal fun recordTransferProgress(
        id: String,
        deltaBytes: Long,
        absoluteTransferredBytes: Long? = null,
    ) {
        val tracker = progressTrackers[id] ?: return
        val now = clock()
        lastActivityMs = now

        var shouldEmit = false
        var snapshotTransferred = 0L
        var snapshotSpeed = 0L
        var snapshotEta = 0L

        synchronized(tracker) {
            tracker.transferredBytes = if (absoluteTransferredBytes != null) {
                maxOf(tracker.transferredBytes, absoluteTransferredBytes)
            } else {
                tracker.transferredBytes + deltaBytes
            }
            tracker.bytesInWindow += deltaBytes
            if (now - tracker.lastEmitMs >= TELEMETRY_THROTTLE_MS) {
                val windowMs = (now - tracker.lastWindowStartMs).coerceAtLeast(1L)
                tracker.currentSpeedBps = (tracker.bytesInWindow * 1000L) / windowMs
                tracker.bytesInWindow = 0L
                tracker.lastWindowStartMs = now
                tracker.lastEmitMs = now

                snapshotTransferred = tracker.transferredBytes
                snapshotSpeed = tracker.currentSpeedBps
                val remaining = (tracker.totalBytes - snapshotTransferred).coerceAtLeast(0L)
                snapshotEta = if (snapshotSpeed > 0L) remaining / snapshotSpeed else 0L
                shouldEmit = true
            }
        }

        if (shouldEmit) {
            val existing = activeTransfers[id]
            if (existing != null && existing.status == DropTransferStatus.ACTIVE) {
                activeTransfers[id] = existing.copy(
                    transferredBytes = snapshotTransferred,
                    speedBytesPerSec = snapshotSpeed,
                    etaSeconds = snapshotEta,
                    updatedAtMs = now,
                )
            }
            emitThrottledProgress(now)
        }
    }

    private fun emitThrottledProgress(now: Long) {
        while (true) {
            val prev = lastProgressEmitMs.get()
            if (prev != Long.MIN_VALUE && now - prev < TELEMETRY_THROTTLE_MS) {
                return
            }
            if (lastProgressEmitMs.compareAndSet(prev, now)) {
                broadcastTelemetry()
                onStateChanged()
                return
            }
        }
    }

    private fun refreshSpeeds() {
        val now = clock()
        var dirty = false
        for ((id, item) in activeTransfers) {
            if (item.status != DropTransferStatus.ACTIVE) continue
            val tracker = progressTrackers[id] ?: continue
            val stale = synchronized(tracker) {
                val elapsed = now - tracker.lastWindowStartMs
                if (elapsed >= 1200L && tracker.currentSpeedBps > 0L) {
                    tracker.currentSpeedBps = 0L
                    tracker.bytesInWindow = 0L
                    tracker.lastWindowStartMs = now
                    tracker.lastEmitMs = now
                    true
                } else {
                    false
                }
            }
            if (stale) {
                activeTransfers[id] = item.copy(speedBytesPerSec = 0L, updatedAtMs = now)
                dirty = true
            }
        }
        if (dirty) notifyChanged()
    }

    internal fun markTransferCompleted(
        id: String,
        actualSizeOverride: Long? = null,
        savedUri: String? = null,
        mimeType: String? = null,
    ) {
        val existing = activeTransfers[id] ?: return
        val tracker = progressTrackers.remove(id)
        val trackedBytes = tracker?.let { synchronized(it) { it.transferredBytes } }
        val finalBytes = actualSizeOverride ?: trackedBytes ?: existing.totalBytes
        activeTransfers[id] = existing.copy(
            totalBytes = maxOf(existing.totalBytes, finalBytes),
            transferredBytes = finalBytes,
            speedBytesPerSec = 0L,
            etaSeconds = 0L,
            status = DropTransferStatus.COMPLETED,
            errorMessage = null,
            updatedAtMs = clock(),
            savedUri = savedUri ?: existing.savedUri,
            mimeType = mimeType ?: existing.mimeType,
        )
        notifyChanged()
    }

    internal fun markTransferStatus(id: String, status: DropTransferStatus, message: String? = null) {
        val existing = activeTransfers[id] ?: return
        progressTrackers.remove(id)
        activeTransfers[id] = existing.copy(
            speedBytesPerSec = 0L,
            status = status,
            errorMessage = message,
            updatedAtMs = clock(),
        )
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
        return """{"type":"telemetry","speedBytesPerSec":$speed,"activeTransferCount":$activeCount,"sharedFilesCount":${sharedItemsMap.size},"transfers":[$transfersJson]}"""
    }

    private class SharedItemRangeContent(
        private val scope: CoroutineScope,
        private val item: SharedDropItem,
        safeName: String,
        private val source: SharedItemSource,
        private val onStreamStarted: (expectedBytes: Long) -> String?,
        private val onBytesSent: (transferId: String, delta: Long) -> Unit,
        private val onStreamFinished: (transferId: String?, ok: Boolean, cancelled: Boolean, bytesSent: Long) -> Unit,
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

        override fun readFrom(): ByteReadChannel =
            streamRange(startOffset = 0L, expectedLength = contentLength, ranged = false)

        override fun readFrom(range: LongRange): ByteReadChannel {
            val start = range.first.coerceAtLeast(0L)
            val len = (range.last - start + 1L).coerceAtLeast(0L)
            return streamRange(startOffset = start, expectedLength = len, ranged = true)
        }

        private fun streamRange(startOffset: Long, expectedLength: Long, ranged: Boolean): ByteReadChannel =
            scope.writer(Dispatchers.IO, autoFlush = false) {
                var ok = false
                var cancelled = false
                var bytesSent = 0L
                var transferId: String? = null
                try {
                    transferId = onStreamStarted(expectedLength)
                    val localFile = source.localFileOrNull(item)
                    if (localFile != null) {
                        FileInputStream(localFile).use { fis ->
                            val fileChannel = fis.channel
                            if (startOffset > 0L) {
                                fileChannel.position(startOffset)
                            }
                            val byteBuffer = ByteBuffer.allocate(STREAM_BUFFER_BYTES)
                            var remaining = expectedLength
                            while (!ranged || remaining > 0L) {
                                byteBuffer.clear()
                                if (ranged && remaining < byteBuffer.capacity()) {
                                    byteBuffer.limit(remaining.toInt())
                                }
                                val read = fileChannel.read(byteBuffer)
                                if (read < 0) break
                                if (read == 0) continue
                                byteBuffer.flip()
                                channel.writeFully(byteBuffer)
                                bytesSent += read
                                if (ranged) remaining -= read
                                transferId?.let { onBytesSent(it, read.toLong()) }
                            }
                        }
                    } else {
                        val stream = if (ranged) {
                            source.openRangedInputStream(item, startOffset, expectedLength)
                        } else {
                            source.openInputStream(item)
                        }
                        stream.use { input ->
                            val buffer = ByteArray(STREAM_BUFFER_BYTES)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (read == 0) continue
                                channel.writeFully(buffer, startIndex = 0, endIndex = read)
                                bytesSent += read
                                transferId?.let { onBytesSent(it, read.toLong()) }
                            }
                        }
                    }
                    ok = true
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                } finally {
                    onStreamFinished(transferId, ok, cancelled, bytesSent)
                }
            }.channel
    }

    companion object {
        const val DEFAULT_PORT = 8080
        val PORT_FALLBACKS = listOf(8080, 8081, 8888)
        const val STREAM_BUFFER_BYTES = 512 * 1024
        const val TELEMETRY_THROTTLE_MS = 300L
        const val KNOCK_POLL_MS = 75L
        const val KEEPALIVE_TICKS = 200
    }
}
