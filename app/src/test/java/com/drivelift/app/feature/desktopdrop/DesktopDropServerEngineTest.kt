package com.drivelift.app.feature.desktopdrop

import com.drivelift.app.feature.desktopdrop.data.DirectoryDropDestinationWriter
import com.drivelift.app.feature.desktopdrop.data.FileSharedItemSource
import com.drivelift.app.feature.desktopdrop.data.SharedItemSource
import com.drivelift.app.feature.desktopdrop.model.DropJson
import com.drivelift.app.feature.desktopdrop.model.DropTransferStatus
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import com.drivelift.app.feature.desktopdrop.model.TransferDirection
import com.drivelift.app.feature.desktopdrop.server.DesktopDropServerEngine
import com.drivelift.app.feature.desktopdrop.server.ResumableUploadStore
import com.drivelift.app.feature.desktopdrop.server.SessionAuthManager
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class DesktopDropServerEngineTest {

    private lateinit var tempRoot: File
    private lateinit var partsDir: File
    private lateinit var destDir: File
    private lateinit var shareDir: File
    private lateinit var authManager: SessionAuthManager
    private lateinit var uploadStore: ResumableUploadStore
    private lateinit var engine: DesktopDropServerEngine
    private var baseUrl: String = ""

    @Before
    fun setUp() {
        tempRoot = Files.createTempDirectory("desktop-drop-test").toFile()
        partsDir = File(tempRoot, "parts").apply { mkdirs() }
        destDir = File(tempRoot, "dest").apply { mkdirs() }
        shareDir = File(tempRoot, "share").apply { mkdirs() }
        authManager = SessionAuthManager(allowLoopbackForTest = true)
        uploadStore = ResumableUploadStore(partsDir)
        engine = DesktopDropServerEngine(
            authManager = authManager,
            uploadStore = uploadStore,
            itemSource = FileSharedItemSource(),
            destinationWriter = DirectoryDropDestinationWriter(destDir, "DropDest"),
            htmlProvider = { "<html><body>Desktop Drop Test</body></html>" },
            destinationLabelProvider = { "DropDest" },
        )
    }

    @After
    fun tearDown() {
        engine.stop()
        tempRoot.deleteRecursively()
    }

    private fun startLoopbackServer(preferredPort: Int = 0): Int = runBlocking {
        val port = engine.start("127.0.0.1", preferredPort)
        baseUrl = "http://127.0.0.1:$port"
        port
    }

    private class MemoryCookieJar : CookieJar {
        private val store = ArrayList<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (c in cookies) {
                store.removeAll { it.name == c.name }
                store += c
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = store.toList()
    }

    private fun clientWithJar(): Pair<OkHttpClient, MemoryCookieJar> {
        val jar = MemoryCookieJar()
        val client = OkHttpClient.Builder()
            .cookieJar(jar)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return client to jar
    }

    private val jsonType = "application/json".toMediaType()

    /**
     * The full knock flow a browser goes through, with the phone's biometric success simulated by
     * [SessionAuthManager.approveKnock]. Leaves the session cookie in [client]'s jar.
     */
    private fun knockAndApprove(
        client: OkHttpClient,
        base: String = baseUrl,
        auth: SessionAuthManager = authManager,
    ): String {
        val knock = client.newCall(
            Request.Builder().url("$base/api/auth/knock").post(ByteArray(0).toRequestBody()).build(),
        ).execute().use { resp ->
            assertEquals(202, resp.code)
            DropJson.parseObject(resp.body!!.string())
        }
        val knockId = knock["knockId"] as String
        assertTrue(Regex("\\d\\d-\\d\\d").matches(knock["matchCode"] as String))
        assertNotNull(auth.approveKnock(knockId))
        client.newCall(
            Request.Builder().url("$base/api/auth/claim").post("""{"knockId":"$knockId"}""".toRequestBody(jsonType)).build(),
        ).execute().use { claim ->
            assertEquals(200, claim.code)
            val setCookie = claim.header("Set-Cookie").orEmpty()
            assertTrue("Session cookie must be HttpOnly", "HttpOnly" in setCookie)
            assertTrue("Session cookie must be SameSite=Strict", "SameSite=Strict" in setCookie)
        }
        return knockId
    }

    @Test
    fun knockToApproveIssuesCookieOnlyAfterPhoneApprovalIsSingleUseAndHonorsRevocation() {
        startLoopbackServer()
        val (client, _) = clientWithJar()

        // No token anywhere: the root page is served clean and the API is locked.
        client.newCall(Request.Builder().url("$baseUrl/").build()).execute().use { page ->
            assertEquals(200, page.code)
        }
        client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { assertEquals(401, it.code) }
        client.newCall(Request.Builder().url("$baseUrl/api/auth/session").build()).execute().use { assertEquals(401, it.code) }

        // A forged or tampered cookie is rejected.
        client.newCall(
            Request.Builder().url("$baseUrl/api/files").header("Cookie", "dl_drop_session=forged.value").build(),
        ).execute().use { assertEquals(401, it.code) }

        val knock = client.newCall(
            Request.Builder().url("$baseUrl/api/auth/knock").post(ByteArray(0).toRequestBody()).build(),
        ).execute().use { resp ->
            assertEquals(202, resp.code)
            DropJson.parseObject(resp.body!!.string())
        }
        val knockId = knock["knockId"] as String
        assertEquals("PENDING", knock["status"])
        assertNotNull(authManager.pendingApproval())

        // A pending browser cannot list files and cannot claim a cookie yet.
        client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { assertEquals(401, it.code) }
        client.newCall(
            Request.Builder().url("$baseUrl/api/auth/claim").post("""{"knockId":"$knockId"}""".toRequestBody(jsonType)).build(),
        ).execute().use { assertEquals(401, it.code) }

        // The SSE stream reports PENDING first and APPROVED soon after the phone approves.
        val streamText = AtomicReference<String?>(null)
        val streamThread = Thread {
            client.newCall(Request.Builder().url("$baseUrl/api/auth/wait?knockId=$knockId").build()).execute().use { sse ->
                streamText.set(sse.body!!.string())
            }
        }
        streamThread.start()
        Thread.sleep(300L)
        val approvedAt = System.nanoTime()
        assertNotNull(authManager.approveKnock(knockId))
        streamThread.join(5_000L)
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - approvedAt)
        val body = streamText.get().orEmpty()
        assertTrue("SSE should announce PENDING then APPROVED: $body", "PENDING" in body && "APPROVED" in body)
        assertTrue("Unlock should follow approval quickly, took ${elapsedMs}ms", elapsedMs < 1_000L)

        client.newCall(
            Request.Builder().url("$baseUrl/api/auth/claim").post("""{"knockId":"$knockId"}""".toRequestBody(jsonType)).build(),
        ).execute().use { claim ->
            assertEquals(200, claim.code)
            val setCookie = claim.header("Set-Cookie").orEmpty()
            assertTrue("Session cookie must be HttpOnly", "HttpOnly" in setCookie)
            assertTrue("Session cookie must be SameSite=Strict", "SameSite=Strict" in setCookie)
        }
        // The cookie is handed over once.
        client.newCall(
            Request.Builder().url("$baseUrl/api/auth/claim").post("""{"knockId":"$knockId"}""".toRequestBody(jsonType)).build(),
        ).execute().use { assertEquals(401, it.code) }

        client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { assertEquals(200, it.code) }
        client.newCall(Request.Builder().url("$baseUrl/api/auth/session").build()).execute().use { assertEquals(200, it.code) }

        // Instant revocation invalidates the session cookie.
        authManager.revokePeer(knockId)
        client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { assertEquals(401, it.code) }
        client.newCall(Request.Builder().url("$baseUrl/api/auth/session").build()).execute().use { assertEquals(401, it.code) }
    }

    @Test
    fun deniedKnockReportsDeniedAndNeverGetsACookie() {
        startLoopbackServer()
        val (client, _) = clientWithJar()
        val knock = client.newCall(
            Request.Builder().url("$baseUrl/api/auth/knock").post(ByteArray(0).toRequestBody()).build(),
        ).execute().use { DropJson.parseObject(it.body!!.string()) }
        val knockId = knock["knockId"] as String
        authManager.revokePeer(knockId)
        client.newCall(Request.Builder().url("$baseUrl/api/auth/wait?knockId=$knockId").build()).execute().use { sse ->
            assertTrue(sse.body!!.string().contains("DENIED"))
        }
        client.newCall(
            Request.Builder().url("$baseUrl/api/auth/claim").post("""{"knockId":"$knockId"}""".toRequestBody(jsonType)).build(),
        ).execute().use { assertEquals(401, it.code) }
    }

    @Test
    fun resumableChunkUploadAssemblesMultiChunkBinaryVerifiesHashAndCleansPartFile() {
        startLoopbackServer()
        val (client, _) = clientWithJar()
        knockAndApprove(client)

        val payload = ByteArray(150_000) { idx -> ((idx * 31 + 7) and 0xFF).toByte() }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

        val initPayload = """{"filename":"big_clip.mp4","relativePath":"Backups/Pixel/big_clip.mp4","totalSize":${payload.size},"sha256":"$sha256"}"""
        val init1 = client.newCall(
            Request.Builder().url("$baseUrl/api/upload/init").post(initPayload.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            DropJson.parseObject(resp.body!!.string())
        }
        val uploadId = init1["uploadId"] as String
        assertEquals(0L, (init1["receivedBytes"] as Number).toLong())

        // Send first 60,000 bytes.
        val chunk1 = payload.copyOfRange(0, 60_000)
        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload/$uploadId")
                .addHeader("X-Chunk-Offset", "0")
                .patch(chunk1.toRequestBody("application/octet-stream".toMediaType()))
                .build(),
        ).execute().use { assertEquals(200, it.code) }

        // Out-of-order future offset triggers 409 Conflict with X-Expected-Offset=60000.
        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload/$uploadId")
                .addHeader("X-Chunk-Offset", "80000")
                .patch(ByteArray(1024).toRequestBody("application/octet-stream".toMediaType()))
                .build(),
        ).execute().use { conflict ->
            assertEquals(409, conflict.code)
            assertEquals("60000", conflict.header("X-Expected-Offset"))
        }

        // Simulating Wi-Fi drop/reconnect: init returns existing uploadId and receivedBytes=60000.
        val initResume = client.newCall(
            Request.Builder().url("$baseUrl/api/upload/init").post(initPayload.toRequestBody("application/json".toMediaType())).build(),
        ).execute().use { resp ->
            assertEquals(200, resp.code)
            DropJson.parseObject(resp.body!!.string())
        }
        assertEquals(uploadId, initResume["uploadId"])
        assertEquals(60_000L, (initResume["receivedBytes"] as Number).toLong())
        assertEquals(true, initResume["resumed"])

        // Send remaining bytes from offset 60,000.
        val chunk2 = payload.copyOfRange(60_000, payload.size)
        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload/$uploadId")
                .addHeader("X-Chunk-Offset", "60000")
                .patch(chunk2.toRequestBody("application/octet-stream".toMediaType()))
                .build(),
        ).execute().use { assertEquals(200, it.code) }

        // Commit file cleanly.
        client.newCall(
            Request.Builder().url("$baseUrl/api/upload/$uploadId/complete").post(ByteArray(0).toRequestBody()).build(),
        ).execute().use { done ->
            assertEquals(200, done.code)
            val json = DropJson.parseObject(done.body!!.string())
            assertEquals("big_clip.mp4", json["savedName"])
        }

        val saved = File(destDir, "Backups/Pixel/big_clip.mp4")
        assertTrue(saved.isFile)
        assertArrayEquals(payload, saved.readBytes())
        assertEquals(0, partsDir.listFiles()?.count { it.name.endsWith(".drivelift.part") } ?: 0)
    }

    @Test
    fun abortUploadDeletesTemporaryPartFileImmediately() {
        startLoopbackServer()
        val (client, _) = clientWithJar()
        knockAndApprove(client)

        val init = client.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload/init")
                .post("""{"filename":"abort_me.bin","totalSize":4096}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { DropJson.parseObject(it.body!!.string()) }
        val uploadId = init["uploadId"] as String

        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/upload/$uploadId")
                .addHeader("X-Chunk-Offset", "0")
                .patch(ByteArray(1024).toRequestBody("application/octet-stream".toMediaType()))
                .build(),
        ).execute().close()

        assertTrue(File(partsDir, "$uploadId.drivelift.part").isFile)

        client.newCall(Request.Builder().url("$baseUrl/api/upload/$uploadId").delete().build()).execute().use { resp ->
            assertEquals(200, resp.code)
        }
        assertFalse(File(partsDir, "$uploadId.drivelift.part").exists())
    }

    @Test
    fun rangeDownloadSupports206PartialContentHeadAndStreamingStoredZipHasZeroTempZipFiles() {
        val file1 = File(shareDir, "alpha.bin").apply {
            writeBytes(ByteArray(2048) { idx -> (idx and 0xFF).toByte() })
        }
        val file2 = File(shareDir, "notes.txt").apply {
            writeText("Hello from Pixel 8 Pro LAN transfer")
        }
        engine.setSharedItems(
            listOf(
                SharedDropItem("id-1", "alpha.bin", "Camera", file1.length(), "application/octet-stream", localFilePath = file1.absolutePath, addedAtMs = 1000L),
                SharedDropItem("id-2", "notes.txt", "", file2.length(), "text/plain", localFilePath = file2.absolutePath, addedAtMs = 2000L),
            ),
        )
        startLoopbackServer()
        val (client, _) = clientWithJar()
        knockAndApprove(client)

        // HEAD reports full Content-Length without body.
        client.newCall(Request.Builder().url("$baseUrl/api/download/id-1").head().build()).execute().use { head ->
            assertEquals(200, head.code)
            assertEquals("2048", head.header("Content-Length"))
        }

        // Byte range returns exact 206 Partial Content slice.
        client.newCall(
            Request.Builder().url("$baseUrl/api/download/id-1").addHeader("Range", "bytes=100-199").build(),
        ).execute().use { partial ->
            assertEquals(206, partial.code)
            val slice = partial.body!!.bytes()
            assertArrayEquals(file1.readBytes().copyOfRange(100, 200), slice)
        }

        // Streaming STORED ZIP archive verifies entry method, bytes, and zero temp .zip files created.
        client.newCall(Request.Builder().url("$baseUrl/api/download-zip").build()).execute().use { zipResp ->
            assertEquals(200, zipResp.code)
            val zipBytes = zipResp.body!!.bytes()
            val unpacked = LinkedHashMap<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                while (true) {
                    val entry = zis.nextEntry ?: break
                    assertEquals(ZipEntry.STORED, entry.method)
                    unpacked[entry.name] = zis.readBytes()
                }
            }
            assertArrayEquals(file1.readBytes(), unpacked["Camera/alpha.bin"])
            assertArrayEquals(file2.readBytes(), unpacked["notes.txt"])
        }
        assertEquals(0, tempRoot.walkTopDown().count { it.extension.equals("zip", ignoreCase = true) })
    }

    private fun awaitCondition(timeoutMs: Long = 3_000L, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(20)
        }
        assertTrue("Condition was not satisfied within ${timeoutMs}ms", predicate())
    }

    private fun openTelemetryWebSocket(
        client: OkHttpClient,
        port: Int,
    ): Triple<WebSocket, LinkedBlockingQueue<String>, AtomicReference<String?>> {
        val frames = LinkedBlockingQueue<String>()
        val statusNote = AtomicReference<String?>(null)
        val opened = CountDownLatch(1)
        val ws = client.newWebSocket(
            Request.Builder().url("ws://127.0.0.1:$port/ws/telemetry").build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    frames.offer(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    statusNote.compareAndSet(null, "closing($code:$reason)")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    statusNote.compareAndSet(null, "closed($code:$reason)")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    statusNote.compareAndSet(null, "failure(${t::class.java.simpleName}:${t.message},http=${response?.code})")
                    opened.countDown()
                }
            },
        )
        assertTrue("WebSocket did not open within 5s (${statusNote.get()})", opened.await(5, TimeUnit.SECONDS))
        assertEquals("WebSocket failed on open: ${statusNote.get()}", null, statusNote.get())
        return Triple(ws, frames, statusNote)
    }

    private fun pollMatchingFrame(
        frames: LinkedBlockingQueue<String>,
        statusNote: AtomicReference<String?>,
        timeoutMs: Long = 5_000L,
        predicate: (Map<String, Any?>) -> Boolean,
    ): Map<String, Any?> {
        val seen = ArrayList<String>()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1L)
            val raw = frames.poll(remainingMs, TimeUnit.MILLISECONDS) ?: break
            seen += raw
            val parsed = DropJson.parseObject(raw)
            if (predicate(parsed)) return parsed
        }
        throw AssertionError("Timed out waiting for matching WebSocket frame; note=${statusNote.get()}, seen=$seen")
    }

    @Test
    fun queueMutationBroadcastsQueueUpdatedWebSocketEvent() {
        val port = startLoopbackServer()
        val (client, _) = clientWithJar()
        knockAndApprove(client)

        val (ws, frames, wsNote) = openTelemetryWebSocket(client, port)
        try {
            val initial = pollMatchingFrame(frames, wsNote) { it["type"] == "telemetry" }
            assertEquals(0L, (initial["sharedFilesCount"] as Number).toLong())

            val sampleFile = File(shareDir, "PXL_20261120_180255203.mp4").apply {
                writeBytes(ByteArray(1024) { 0x2A })
            }
            engine.setSharedItems(
                listOf(
                    SharedDropItem(
                        id = "pxl-1",
                        name = "PXL_20261120_180255203.mp4",
                        relativePath = "",
                        sizeBytes = sampleFile.length(),
                        mimeType = "video/mp4",
                        localFilePath = sampleFile.absolutePath,
                        addedAtMs = 1000L,
                    ),
                ),
            )

            val addedEvent = pollMatchingFrame(frames, wsNote) { it["type"] == "queue_updated" }
            assertEquals(1L, (addedEvent["sharedFilesCount"] as Number).toLong())

            client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { filesResp ->
                assertEquals(200, filesResp.code)
                assertEquals("no-store", filesResp.header("Cache-Control"))
                assertTrue(filesResp.body!!.string().contains("PXL_20261120_180255203.mp4"))
            }

            engine.setSharedItems(emptyList())
            val clearedEvent = pollMatchingFrame(frames, wsNote) {
                it["type"] == "queue_updated" && (it["sharedFilesCount"] as? Number)?.toLong() == 0L
            }
            assertEquals(0L, (clearedEvent["sharedFilesCount"] as Number).toLong())
        } finally {
            ws.close(1000, "done")
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun largeBufferStreamingPreservesExactBytesAndRangeIntegrityForFileAndSafStreams() {
        val largeBytes = ByteArray(1_600_000) { idx -> ((idx * 17 + 5) and 0xFF).toByte() }
        val bigFile = File(shareDir, "PXL_20261120_180255203.mp4").apply { writeBytes(largeBytes) }
        engine.setSharedItems(
            listOf(
                SharedDropItem(
                    id = "big-file",
                    name = "PXL_20261120_180255203.mp4",
                    relativePath = "Camera",
                    sizeBytes = largeBytes.size.toLong(),
                    mimeType = "video/mp4",
                    localFilePath = bigFile.absolutePath,
                    addedAtMs = 1000L,
                ),
            ),
        )
        startLoopbackServer()
        val (client, _) = clientWithJar()
        knockAndApprove(client)

        // Direct FileChannel 512 KB streaming path: full body.
        client.newCall(Request.Builder().url("$baseUrl/api/download/big-file").build()).execute().use { resp ->
            assertEquals(200, resp.code)
            assertEquals("1600000", resp.header("Content-Length"))
            assertArrayEquals(largeBytes, resp.body!!.bytes())
        }
        awaitCondition { engine.activeTransferCount() == 0 }

        // Multi-buffer unaligned Range (206 Partial Content) across 512 KB boundaries.
        client.newCall(
            Request.Builder().url("$baseUrl/api/download/big-file").addHeader("Range", "bytes=600000-1350000").build(),
        ).execute().use { partial ->
            assertEquals(206, partial.code)
            assertEquals("bytes 600000-1350000/1600000", partial.header("Content-Range"))
            assertEquals("750001", partial.header("Content-Length"))
            assertArrayEquals(largeBytes.copyOfRange(600_000, 1_350_001), partial.body!!.bytes())
        }
        awaitCondition { engine.activeTransferCount() == 0 }

        // Tail slice Range check.
        client.newCall(
            Request.Builder().url("$baseUrl/api/download/big-file").addHeader("Range", "bytes=1599900-1599999").build(),
        ).execute().use { tail ->
            assertEquals(206, tail.code)
            assertArrayEquals(largeBytes.copyOfRange(1_599_900, 1_600_000), tail.body!!.bytes())
        }
        awaitCondition { engine.activeTransferCount() == 0 }

        // Streaming STORED ZIP archive over large buffer preserves exact bytes.
        client.newCall(Request.Builder().url("$baseUrl/api/download-zip?ids=big-file").build()).execute().use { zipResp ->
            assertEquals(200, zipResp.code)
            ZipInputStream(ByteArrayInputStream(zipResp.body!!.bytes())).use { zis ->
                val entry = zis.nextEntry
                assertNotNull(entry)
                assertEquals("Camera/PXL_20261120_180255203.mp4", entry!!.name)
                assertEquals(ZipEntry.STORED, entry.method)
                assertArrayEquals(largeBytes, zis.readBytes())
            }
        }
        awaitCondition { engine.activeTransferCount() == 0 }
        assertEquals(0, tempRoot.walkTopDown().count { it.extension.equals("zip", ignoreCase = true) })

        // SAF / ContentResolver InputStream path (localFileOrNull returns null) with 512 KB ByteArray.
        val safAuth = SessionAuthManager(allowLoopbackForTest = true)
        val safSource = object : SharedItemSource {
            override fun localFileOrNull(item: SharedDropItem): File? = null

            override fun openInputStream(item: SharedDropItem): InputStream =
                ByteArrayInputStream(largeBytes)

            override fun openRangedInputStream(item: SharedDropItem, startOffset: Long, length: Long): InputStream =
                ByteArrayInputStream(largeBytes, startOffset.toInt(), length.toInt())
        }
        val safEngine = DesktopDropServerEngine(
            authManager = safAuth,
            uploadStore = ResumableUploadStore(File(tempRoot, "saf-parts").apply { mkdirs() }),
            itemSource = safSource,
            destinationWriter = DirectoryDropDestinationWriter(File(tempRoot, "saf-dest").apply { mkdirs() }, "SafDest"),
            htmlProvider = { "<html></html>" },
            destinationLabelProvider = { "SafDest" },
        )
        try {
            safEngine.setSharedItems(
                listOf(
                    SharedDropItem(
                        id = "saf-big",
                        name = "saf_video.mp4",
                        relativePath = "",
                        sizeBytes = largeBytes.size.toLong(),
                        mimeType = "video/mp4",
                        sourceUri = "content://synthetic/saf-big",
                        addedAtMs = 1000L,
                    ),
                ),
            )
            val safPort = runBlocking { safEngine.start("127.0.0.1", 0) }
            val safBaseUrl = "http://127.0.0.1:$safPort"
            val (safClient, _) = clientWithJar()
            knockAndApprove(safClient, safBaseUrl, safAuth)

            safClient.newCall(Request.Builder().url("$safBaseUrl/api/download/saf-big").build()).execute().use { resp ->
                assertEquals(200, resp.code)
                assertEquals("1600000", resp.header("Content-Length"))
                assertArrayEquals(largeBytes, resp.body!!.bytes())
            }
            awaitCondition { safEngine.activeTransferCount() == 0 }

            safClient.newCall(
                Request.Builder().url("$safBaseUrl/api/download/saf-big").addHeader("Range", "bytes=600000-1350000").build(),
            ).execute().use { partial ->
                assertEquals(206, partial.code)
                assertEquals("bytes 600000-1350000/1600000", partial.header("Content-Range"))
                assertArrayEquals(largeBytes.copyOfRange(600_000, 1_350_001), partial.body!!.bytes())
            }
            awaitCondition { safEngine.activeTransferCount() == 0 }
        } finally {
            safEngine.stop()
        }
    }

    @Test
    fun telemetryThrottlingPreventsPerChunkEmissionFloods() {
        val fakeClock = AtomicLong(10_000L)
        val stateChangedCount = AtomicInteger(0)
        val throttledAuth = SessionAuthManager(clock = { fakeClock.get() }, allowLoopbackForTest = true)
        val throttledEngine = DesktopDropServerEngine(
            authManager = throttledAuth,
            uploadStore = ResumableUploadStore(File(tempRoot, "t-parts").apply { mkdirs() }, clock = { fakeClock.get() }),
            itemSource = FileSharedItemSource(),
            destinationWriter = DirectoryDropDestinationWriter(File(tempRoot, "t-dest").apply { mkdirs() }, "TDest"),
            htmlProvider = { "<html></html>" },
            destinationLabelProvider = { "TDest" },
            clock = { fakeClock.get() },
            onStateChanged = { stateChangedCount.incrementAndGet() },
        )
        var ws: WebSocket? = null
        val (client, _) = clientWithJar()
        try {
            val port = runBlocking { throttledEngine.start("127.0.0.1", 0) }
            knockAndApprove(client, "http://127.0.0.1:$port", throttledAuth)

            val (socket, frames, wsNote) = openTelemetryWebSocket(client, port)
            ws = socket
            pollMatchingFrame(frames, wsNote) { it["type"] == "telemetry" }

            val transferId = throttledEngine.registerTransferStart(
                filename = "PXL_20261120_180255203.mp4",
                relativePath = "",
                direction = TransferDirection.PHONE_TO_PC,
                totalBytes = 20L * 1024L * 1024L,
            )
            pollMatchingFrame(frames, wsNote) {
                it["type"] == "telemetry" && (it["activeTransferCount"] as? Number)?.toInt() == 1
            }
            frames.clear()
            val baselineEmits = stateChangedCount.get()

            // Phase 1: 50 rapid chunks within 200 ms (< 300 ms threshold) produce zero progress emissions.
            repeat(50) {
                fakeClock.addAndGet(4L)
                throttledEngine.recordTransferProgress(transferId, 65_536L)
            }
            assertEquals(baselineEmits, stateChangedCount.get())
            assertEquals(1, throttledEngine.activeTransferCount())

            // Phase 2: 200 more chunks spanning 1000 ms cross the 300 ms gate only 4 times.
            repeat(200) {
                fakeClock.addAndGet(5L)
                throttledEngine.recordTransferProgress(transferId, 65_536L)
            }
            val deltaEmits = stateChangedCount.get() - baselineEmits
            assertEquals(4, deltaEmits)

            Thread.sleep(150L)
            val wsTelemetryFramesDuringBurst = generateSequence { frames.poll() }.count { raw ->
                DropJson.parseObject(raw)["type"] == "telemetry"
            }
            assertTrue(
                "Expected <= 5 WebSocket telemetry frames during 250 chunks, got $wsTelemetryFramesDuringBurst",
                wsTelemetryFramesDuringBurst <= 5,
            )

            throttledEngine.markTransferCompleted(transferId)
            assertTrue(stateChangedCount.get() > baselineEmits + deltaEmits)
            assertEquals(0, throttledEngine.activeTransferCount())
            val completed = throttledEngine.transfersSnapshot().first { it.transferId == transferId }
            assertEquals(DropTransferStatus.COMPLETED, completed.status)
            assertEquals(250L * 65_536L, completed.transferredBytes)
        } finally {
            ws?.close(1000, "done")
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
            throttledEngine.stop()
        }
    }

    @Test
    fun portFallbackSkipsAlreadyBoundPreferredPort() {
        ServerSocket(0).use { occupied ->
            val busyPort = occupied.localPort
            val boundPort = startLoopbackServer(preferredPort = busyPort)
            assertNotEquals(busyPort, boundPort)
            assertTrue(boundPort > 0)
        }
    }
}
