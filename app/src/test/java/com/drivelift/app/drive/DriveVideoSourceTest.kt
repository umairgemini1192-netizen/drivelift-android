package com.drivelift.app.drive

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class DriveVideoSourceTest {
    private val trustedUri = "https://www.googleapis.com/drive/v3/files/render/content?alt=media"

    /** A fake clock: [wait] advances it, so timeouts are exact without sleeping. */
    private class Harness {
        var clock = 0L
        val waits = mutableListOf<Long>()
        val polledIds = mutableListOf<String>()
        var startCalls = 0
    }

    private fun op(
        name: String? = "operations/op1",
        done: Boolean = false,
        error: String? = null,
        uri: String? = null,
    ) = DriveRenderOperation(name, done, error, uri)

    private suspend fun render(
        h: Harness,
        start: suspend () -> DriveRenderOperation,
        poll: suspend (String) -> DriveRenderOperation = { throw AssertionError("unexpected poll") },
        timeoutMillis: Long = 60_000L,
    ) = awaitVidsRender(
        start = { h.startCalls++; start() },
        poll = { id -> h.polledIds += id; poll(id) },
        wait = { h.waits += it; h.clock += it },
        now = { h.clock },
        timeoutMillis = timeoutMillis,
        isTrusted = ::isTrustedDriveMediaUrl,
    )

    @Test
    fun googleVidsIsClassifiedAsVideoSoItGetsThePlayer() {
        assertEquals(DrivePreviewKind.VIDEO, classifyDriveFile("application/vnd.google-apps.vid"))
        assertEquals(DrivePreviewKind.VIDEO, classifyDriveFile("  Application/Vnd.Google-Apps.Vid "))
    }

    @Test
    fun googleVidsUsesTheDownloadOperationAndEveryOtherVideoUsesAltMedia() {
        assertEquals(
            DrivePlaybackSource.VIDS_DOWNLOAD_OPERATION,
            drivePlaybackSource("application/vnd.google-apps.vid"),
        )
        assertEquals(DrivePlaybackSource.DIRECT_MEDIA, drivePlaybackSource("video/mp4"))
        assertEquals(DrivePlaybackSource.DIRECT_MEDIA, drivePlaybackSource("video/quicktime"))
        assertEquals(DrivePlaybackSource.DIRECT_MEDIA, drivePlaybackSource("application/pdf"))
    }

    @Test
    fun otherGoogleNativeTypesStayOutOfThePlayer() {
        assertEquals(
            DrivePreviewKind.WORKSPACE_PDF_EXPORT,
            classifyDriveFile("application/vnd.google-apps.document"),
        )
        assertEquals(DrivePreviewKind.UNSUPPORTED, classifyDriveFile("application/vnd.google-apps.script"))
    }

    @Test
    fun pollBackoffStartsAtTwoSecondsDoublesAndCapsAtFifteen() {
        assertEquals(2_000L, drivePollDelayMillis(0))
        assertEquals(4_000L, drivePollDelayMillis(1))
        assertEquals(8_000L, drivePollDelayMillis(2))
        assertEquals(15_000L, drivePollDelayMillis(3))
        assertEquals(15_000L, drivePollDelayMillis(50))
        assertEquals(2_000L, drivePollDelayMillis(-1))
    }

    @Test
    fun operationNameIsReducedToItsIdForThePollPath() {
        assertEquals("abc123", driveOperationName("operations/abc123"))
        assertEquals("abc123", driveOperationName("abc123"))
    }

    @Test
    fun immediateCompletionReturnsTheUriWithoutPollingOrWaiting() = runBlocking {
        val h = Harness()
        val uri = render(h, start = { op(done = true, uri = trustedUri) })
        assertEquals(trustedUri, uri.toString())
        assertEquals(emptyList<Long>(), h.waits)
        assertEquals(emptyList<String>(), h.polledIds)
    }

    @Test
    fun delayedCompletionPollsTheOperationIdWithBackoffUntilDone() = runBlocking {
        val h = Harness()
        val pending = listOf(op(done = false), op(done = false), op(done = true, uri = trustedUri))
        var next = 0
        val uri = render(h, start = { op() }, poll = { pending[next++] })
        assertEquals(trustedUri, uri.toString())
        assertEquals(listOf(2_000L, 4_000L, 8_000L), h.waits)
        assertEquals(listOf("op1", "op1", "op1"), h.polledIds)
    }

    @Test
    fun operationErrorDuringPollingIsSurfacedWithoutRetry() = runBlocking {
        val h = Harness()
        val e = assertThrows(IOException::class.java) {
            runBlocking { render(h, start = { op() }, poll = { op(done = true, error = "render failed") }) }
        }
        assertEquals("render failed", e.message)
        assertEquals(1, h.polledIds.size)
    }

    @Test
    fun errorOnStartStopsBeforeAnyPoll() = runBlocking {
        val h = Harness()
        val e = assertThrows(IOException::class.java) {
            runBlocking { render(h, start = { op(error = "not allowed") }) }
        }
        assertEquals("not allowed", e.message)
        assertEquals(emptyList<String>(), h.polledIds)
    }

    @Test
    fun malformedStartWithoutAnOperationNameIsRejected() = runBlocking {
        val h = Harness()
        assertThrows(IOException::class.java) {
            runBlocking { render(h, start = { op(name = null) }) }
        }
        assertEquals(emptyList<String>(), h.polledIds)
    }

    @Test
    fun completionWithoutAnyDownloadLinkIsRejected() = runBlocking {
        val h = Harness()
        assertThrows(IOException::class.java) {
            runBlocking { render(h, start = { op(done = true, uri = null) }) }
        }
    }

    @Test
    fun untrustedOrPlainHttpLinkIsBlockedBeforePlayback() = runBlocking {
        for (bad in listOf("https://evil.example.com/v.mp4", "http://www.googleapis.com/drive/v3/x")) {
            val h = Harness()
            val e = assertThrows(IOException::class.java) {
                runBlocking { render(h, start = { op(done = true, uri = bad) }) }
            }
            assertTrue(e.message.orEmpty().contains("untrusted"))
        }
    }

    @Test
    fun neverCompletingRenderStopsAtTheTimeoutWithBoundedPolls() = runBlocking {
        val h = Harness()
        assertThrows(IOException::class.java) {
            runBlocking { render(h, start = { op() }, poll = { op(done = false) }, timeoutMillis = 10_000L) }
        }
        // Waits 2 s, 4 s, 8 s: the clock reaches 14 s, past the 10 s deadline, after three polls.
        assertEquals(listOf(2_000L, 4_000L, 8_000L), h.waits)
        assertEquals(3, h.polledIds.size)
    }

    @Test
    fun cancellationDuringBackoffStopsTheRenderWithoutAnotherPoll() = runBlocking {
        val h = Harness()
        val job = launch {
            awaitVidsRender(
                start = { op() },
                poll = { h.polledIds += it; op(done = true, uri = trustedUri) },
                wait = { awaitCancellation() },
                now = { 0L },
                timeoutMillis = 60_000L,
                isTrusted = ::isTrustedDriveMediaUrl,
            )
        }
        yield()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), h.polledIds)
    }

    @Test
    fun accountChangeDuringRenderIsRaisedAndNothingIsPlayed() = runBlocking {
        val h = Harness()
        assertThrows(AccountChangedException::class.java) {
            runBlocking {
                render(h, start = { op() }, poll = { throw AccountChangedException() })
            }
        }
        assertEquals(1, h.startCalls)
    }

    @Test
    fun trustedUriFixtureIsAcceptedByTheHostRule() {
        assertTrue(isTrustedDriveMediaUrl(trustedUri.toHttpUrl()))
    }
}
