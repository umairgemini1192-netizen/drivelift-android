package com.drivelift.app.drive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drivelift.app.data.Prefs
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * The Google Vids render through the real [DriveClient]: request building, the pinned-account
 * token rules, error handling and JSON parsing. Drive is a fake OkHttp interceptor, so nothing
 * leaves the machine, and tokens come from a fake [DriveTokenSource].
 */
@RunWith(RobolectricTestRunner::class)
class DriveClientVidsRenderTest {
    private val me = "me@example.com"
    private val vids = DriveFile("vid1", "Untitled video", GOOGLE_VIDS_MIME, 0, null, false)
    private val trustedLink = "https://www.googleapis.com/drive/v3/files/vid1/render?alt=media"

    private lateinit var context: Context
    private lateinit var prefs: Prefs

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = Prefs(context)
        prefs.accountEmail = me
    }

    private data class Sent(val method: String, val url: okhttp3.HttpUrl, val auth: String?, val bodyBytes: Long)

    /** Tokens per account, the account each token belongs to, and what replaces a rejected one. */
    private class FakeTokens(
        val current: MutableMap<String, String>,
        val replacement: Map<String, String> = emptyMap(),
    ) : DriveTokenSource {
        val invalidated = mutableListOf<String>()
        override suspend fun tokenOrNull(account: String): String? = synchronized(this) { current[account] }
        override suspend fun invalidate(token: String) {
            synchronized(this) {
                invalidated += token
                current.entries.firstOrNull { it.value == token }?.let { e -> replacement[token]?.let { e.setValue(it) } }
            }
        }
    }

    /**
     * A fake Drive. `about` answers with the owner of the token; every other request goes to
     * [route]. Every request is recorded.
     */
    private class FakeDrive(
        val owners: Map<String, String>,
        val route: (Request) -> Pair<Int, String>,
    ) : Interceptor {
        val sent = java.util.Collections.synchronizedList(mutableListOf<Sent>())

        override fun intercept(chain: Interceptor.Chain): Response {
            val r = chain.request()
            sent += Sent(r.method, r.url, r.header("Authorization"), r.body?.contentLength() ?: -1L)
            val token = r.header("Authorization")?.removePrefix("Bearer ")
            val (code, json) = when {
                r.url.encodedPath == "/drive/v3/about" -> owners[token]
                    ?.let { 200 to """{"user":{"emailAddress":"$it"}}""" }
                    ?: (401 to """{"error":{"message":"Invalid Credentials"}}""")
                else -> route(r)
            }
            return Response.Builder()
                .request(r)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("fake")
                .body(json.toResponseBody("application/json".toMediaType()))
                .build()
        }

        /** Requests other than the token ownership check. */
        fun calls(): List<Sent> = synchronized(sent) { sent.filter { it.url.encodedPath != "/drive/v3/about" } }
        fun polls(): List<Sent> = calls().filter { it.url.encodedPath.startsWith("/drive/v3/operations/") }
    }

    private val waits = mutableListOf<Long>()

    private fun client(drive: FakeDrive, tokens: DriveTokenSource) = DriveClient(
        context,
        prefs,
        tokens,
        OkHttpClient.Builder().addInterceptor(drive).build(),
        { waits += it },
    )

    private fun isStart(r: Request) = r.method == "POST" && r.url.encodedPath == "/drive/v3/files/vid1/download"
    private fun isPoll(r: Request) = r.method == "GET" && r.url.encodedPath == "/drive/v3/operations/op1"

    private val pending = 200 to """{"name":"operations/op1","done":false}"""
    private val ready = 200 to """{"name":"operations/op1","done":true,"response":{"downloadUri":"$trustedLink"}}"""

    /** Answers the start with [start] and each poll with the next of [polls], repeating the last. */
    private fun drive(
        start: Pair<Int, String> = pending,
        polls: List<Pair<Int, String>> = listOf(ready),
        owners: Map<String, String> = mapOf("tok-me" to me),
        onPoll: (Int) -> Unit = {},
    ): FakeDrive {
        var next = 0
        return FakeDrive(owners) { r ->
            when {
                isStart(r) -> start
                isPoll(r) -> {
                    val i = next++
                    onPoll(i)
                    polls[minOf(i, polls.lastIndex)]
                }
                else -> 404 to """{"error":{"message":"unexpected ${r.method} ${r.url}"}}"""
            }
        }
    }

    private fun meTokens() = FakeTokens(mutableMapOf(me to "tok-me"))

    @Test
    fun theRenderAsksForMp4ThenPollsWithThePinnedAccountsTokenUntilTheLinkIsReady() = runBlocking<Unit> {
        val fake = drive(polls = listOf(pending, ready))
        val stream = client(fake, meTokens()).openVideoStream(vids)

        assertEquals(trustedLink, stream.url)
        val calls = fake.calls()
        assertEquals(listOf("POST", "GET", "GET"), calls.map { it.method })
        val start = calls[0]
        assertEquals("/drive/v3/files/vid1/download", start.url.encodedPath)
        assertEquals("video/mp4", start.url.queryParameter("mimeType"))
        assertEquals(0L, start.bodyBytes)
        assertEquals(listOf("/drive/v3/operations/op1", "/drive/v3/operations/op1"), calls.drop(1).map { it.url.encodedPath })
        assertTrue(calls.all { it.auth == "Bearer tok-me" })
        assertTrue(fake.sent.all { it.url.host == "www.googleapis.com" })
        // The token's owner is proven with Drive once, then reused for every call of the render.
        assertEquals(1, fake.sent.count { it.url.encodedPath == "/drive/v3/about" })
        assertEquals(listOf(2_000L, 4_000L), waits)
    }

    @Test
    fun aRenderThatIsDoneAtOnceIsNotPolled() = runBlocking<Unit> {
        val fake = drive(start = ready)
        val stream = client(fake, meTokens()).openVideoStream(vids)
        assertEquals(trustedLink, stream.url)
        assertEquals(emptyList<Sent>(), fake.polls())
        assertEquals(emptyList<Long>(), waits)
    }

    @Test
    fun anErrorInTheOperationIsShownAndNotAskedAgain() = runBlocking<Unit> {
        val fake = drive(polls = listOf(200 to """{"name":"operations/op1","done":true,"error":{"code":13,"message":"Render failed"}}"""))
        val e = assertThrows(IOException::class.java) { runBlocking { client(fake, meTokens()).openVideoStream(vids) } }
        assertEquals("Render failed", e.message)
        assertEquals(1, fake.polls().size)
    }

    @Test
    fun aRefusedStartIsADriveErrorAndNothingIsPolled() = runBlocking<Unit> {
        val fake = drive(
            start = 403 to """{"error":{"message":"No access","errors":[{"reason":"insufficientFilePermissions"}]}}""",
        )
        val e = assertThrows(DriveHttpException::class.java) { runBlocking { client(fake, meTokens()).openVideoStream(vids) } }
        assertEquals(403, e.code)
        assertEquals("insufficientFilePermissions", e.reason)
        assertEquals(emptyList<Sent>(), fake.polls())
    }

    @Test
    fun aStartWithoutAnOperationNameIsRefused() = runBlocking<Unit> {
        val fake = drive(start = 200 to """{"done":false}""")
        assertThrows(IOException::class.java) { runBlocking { client(fake, meTokens()).openVideoStream(vids) } }
        assertEquals(emptyList<Sent>(), fake.polls())
    }

    @Test
    fun aFinishedRenderWithoutALinkIsRefused() = runBlocking<Unit> {
        val fake = drive(polls = listOf(200 to """{"name":"operations/op1","done":true,"response":{}}"""))
        val e = assertThrows(IOException::class.java) { runBlocking { client(fake, meTokens()).openVideoStream(vids) } }
        assertEquals("Drive returned no video link", e.message)
    }

    @Test
    fun anUntrustedOrPlainHttpLinkIsRefusedAndNeverRequested() = runBlocking<Unit> {
        for (bad in listOf("https://evil.example.com/v.mp4", "http://www.googleapis.com/drive/v3/files/vid1")) {
            waits.clear()
            val fake = drive(polls = listOf(200 to """{"name":"operations/op1","done":true,"response":{"downloadUri":"$bad"}}"""))
            val e = assertThrows(IOException::class.java) { runBlocking { client(fake, meTokens()).openVideoStream(vids) } }
            assertTrue(e.message.orEmpty().contains("untrusted"))
            assertTrue(fake.sent.all { it.url.host == "www.googleapis.com" && it.url.isHttps })
        }
    }

    @Test
    fun aTemporaryPollErrorIsAskedAgainAndTheRenderStillFinishes() = runBlocking<Unit> {
        val fake = drive(polls = listOf(503 to """{"error":{"message":"Backend Error"}}""", 429 to "{}", ready))
        val stream = client(fake, meTokens()).openVideoStream(vids)
        assertEquals(trustedLink, stream.url)
        assertEquals(3, fake.polls().size)
    }

    @Test
    fun pollErrorsThatKeepComingStopAfterTheRetryLimit() = runBlocking<Unit> {
        val fake = drive(polls = listOf(503 to """{"error":{"message":"Backend Error"}}"""))
        val e = assertThrows(DriveHttpException::class.java) { runBlocking { client(fake, meTokens()).openVideoStream(vids) } }
        assertEquals(503, e.code)
        assertEquals(1 + VIDS_POLL_RETRIES, fake.polls().size)
    }

    @Test
    fun aRefusedPollIsNotAskedAgain() = runBlocking<Unit> {
        val fake = drive(polls = listOf(404 to """{"error":{"message":"Operation not found"}}"""))
        val e = assertThrows(DriveHttpException::class.java) { runBlocking { client(fake, meTokens()).openVideoStream(vids) } }
        assertEquals(404, e.code)
        assertEquals(1, fake.polls().size)
    }

    @Test
    fun anAccountSwitchDuringTheRenderStopsItBeforeTheNextPoll() = runBlocking<Unit> {
        val fake = drive(
            polls = listOf(pending, ready),
            owners = mapOf("tok-me" to me, "tok-other" to "other@example.com"),
            onPoll = { i -> if (i == 0) prefs.accountEmail = "other@example.com" },
        )
        val tokens = FakeTokens(mutableMapOf(me to "tok-me", "other@example.com" to "tok-other"))
        assertThrows(AccountChangedException::class.java) { runBlocking { client(fake, tokens).openVideoStream(vids) } }
        // Not retried as a connection problem, and the other account's token was never sent.
        assertEquals(1, fake.polls().size)
        assertTrue(fake.sent.none { it.auth == "Bearer tok-other" })
    }

    @Test
    fun aTokenThatBelongsToAnotherAccountIsNeverUsedForTheRender() = runBlocking<Unit> {
        val fake = drive(owners = mapOf("tok-me" to "other@example.com"))
        assertThrows(AccountChangedException::class.java) { runBlocking { client(fake, meTokens()).openVideoStream(vids) } }
        assertEquals(emptyList<Sent>(), fake.calls())
    }

    @Test
    fun aRejectedTokenIsReplacedAndTheNewOneIsProvenBeforeTheRenderGoesOn() = runBlocking<Unit> {
        var polls = 0
        val fake = FakeDrive(owners = mapOf("tok-1" to me, "tok-2" to me)) { r ->
            when {
                isStart(r) -> pending
                isPoll(r) -> if (polls++ == 0) 401 to """{"error":{"message":"Invalid Credentials"}}""" else ready
                else -> 404 to "{}"
            }
        }
        val tokens = FakeTokens(mutableMapOf(me to "tok-1"), replacement = mapOf("tok-1" to "tok-2"))
        val stream = client(fake, tokens).openVideoStream(vids)

        assertEquals(trustedLink, stream.url)
        assertEquals(listOf("tok-1"), tokens.invalidated)
        assertEquals(listOf("Bearer tok-1", "Bearer tok-2"), fake.polls().map { it.auth })
        assertTrue(fake.sent.any { it.url.encodedPath == "/drive/v3/about" && it.auth == "Bearer tok-2" })
    }
}
