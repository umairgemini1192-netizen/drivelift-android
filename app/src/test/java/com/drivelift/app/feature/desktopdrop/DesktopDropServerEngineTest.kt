package com.drivelift.app.feature.desktopdrop

import com.drivelift.app.feature.desktopdrop.data.DirectoryDropDestinationWriter
import com.drivelift.app.feature.desktopdrop.data.FileSharedItemSource
import com.drivelift.app.feature.desktopdrop.model.DropJson
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
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
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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

    private fun authenticateWithOneTapApproval(client: OkHttpClient): String {
        val token = authManager.sessionToken
        val body = """{"token":"$token"}""".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$baseUrl/api/auth/request").post(body).build()).execute().use { resp ->
            assertEquals(202, resp.code)
            val parsed = DropJson.parseObject(resp.body!!.string())
            assertEquals("PENDING", parsed["status"])
            val peerId = parsed["peerId"] as String

            // Pending browser still cannot list files before approval.
            client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { denied ->
                assertEquals(401, denied.code)
            }

            // Approve peer from Android screen.
            authManager.approvePeer(peerId)

            client.newCall(
                Request.Builder().url("$baseUrl/api/auth/status?peerId=$peerId&token=$token").build(),
            ).execute().use { approved ->
                assertEquals(200, approved.code)
                val setCookie = approved.header("Set-Cookie").orEmpty()
                assertTrue("Session cookie must be HttpOnly", "HttpOnly" in setCookie)
            }
            return peerId
        }
    }

    @Test
    fun authRejectsMissingOrInvalidTokensIssuesHttpOnlyCookieAndHonorsRevocation() {
        authManager.requireApproval = true
        startLoopbackServer()
        val (client, _) = clientWithJar()

        // Unauthenticated API access fails with 401.
        client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        // Wrong token returns 401.
        val badBody = """{"token":"WRONG999"}""".toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url("$baseUrl/api/auth/request").post(badBody).build()).execute().use { resp ->
            assertEquals(401, resp.code)
        }

        val peerId = authenticateWithOneTapApproval(client)

        // Approved cookie accesses files cleanly.
        client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { ok ->
            assertEquals(200, ok.code)
        }

        // Instant revocation invalidates session cookie immediately.
        authManager.revokePeer(peerId)
        client.newCall(Request.Builder().url("$baseUrl/api/files").build()).execute().use { revoked ->
            assertEquals(401, revoked.code)
        }
    }

    @Test
    fun resumableChunkUploadAssemblesMultiChunkBinaryVerifiesHashAndCleansPartFile() {
        authManager.requireApproval = false
        startLoopbackServer()
        val (client, _) = clientWithJar()
        val token = authManager.sessionToken
        client.newCall(
            Request.Builder()
                .url("$baseUrl/api/auth/request")
                .post("""{"token":"$token"}""".toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { assertEquals(200, it.code) }

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
        authManager.requireApproval = false
        startLoopbackServer()
        val (client, _) = clientWithJar()
        val token = authManager.sessionToken
        client.newCall(
            Request.Builder().url("$baseUrl/api/auth/request").post("""{"token":"$token"}""".toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()

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
        authManager.requireApproval = false
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
        val token = authManager.sessionToken
        client.newCall(
            Request.Builder().url("$baseUrl/api/auth/request").post("""{"token":"$token"}""".toRequestBody("application/json".toMediaType())).build(),
        ).execute().close()

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
