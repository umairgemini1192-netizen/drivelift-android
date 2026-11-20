package com.drivelift.app.ui

import com.drivelift.app.drive.AccountChangedException
import com.drivelift.app.drive.DriveHttpException
import com.drivelift.app.drive.VIDS_STILL_PREPARING
import com.drivelift.app.drive.VIDS_UNTRUSTED_LINK
import com.drivelift.app.drive.VidsRenderException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException

/**
 * The words on the Could not play video card (finding 3 of the Preview 27 audit). A Vids render
 * problem is not a connection problem, and a missing video is not a missing destination folder.
 */
class DriveVideoErrorTest {
    @Test
    fun aVidsRenderProblemIsShownInItsOwnWordsNotAsAConnectionProblem() {
        assertEquals(VIDS_STILL_PREPARING, describeVideoError(VidsRenderException(VIDS_STILL_PREPARING, true)))
        assertEquals(VIDS_UNTRUSTED_LINK, describeVideoError(VidsRenderException(VIDS_UNTRUSTED_LINK)))
        assertEquals("Render failed", describeVideoError(VidsRenderException("Render failed")))
        for (e in listOf(VidsRenderException(VIDS_STILL_PREPARING, true), VidsRenderException(VIDS_UNTRUSTED_LINK))) {
            assertFalse(describeVideoError(e).startsWith("Connection problem"))
        }
    }

    @Test
    fun aMissingVideoIsNotCalledAMissingFolder() {
        val message = describeVideoError(DriveHttpException(404, "notFound", "File not found: vid1"))
        assertEquals("This video was not found in Google Drive, or this account cannot open it.", message)
    }

    @Test
    fun aRefusalAndARateLimitAreToldApart() {
        assertEquals(
            "Google Drive did not allow this account to play this video.",
            describeVideoError(DriveHttpException(403, "insufficientFilePermissions", "No access")),
        )
        assertEquals(
            "Drive asked to slow down",
            describeVideoError(DriveHttpException(403, "userRateLimitExceeded", "Rate limit")),
        )
    }

    @Test
    fun anAccountChangeSaysToOpenTheVideoAgain() {
        assertEquals(
            "The connected Google account changed. Open the video again.",
            describeVideoError(AccountChangedException()),
        )
    }

    @Test
    fun aRealConnectionProblemStillSaysSo() {
        assertEquals("Connection problem: timeout", describeVideoError(IOException("timeout")))
        assertEquals("Drive is temporarily unavailable", describeVideoError(DriveHttpException(503, null, "Backend Error")))
    }
}
