package com.drivelift.app.drive

import org.junit.Assert.assertEquals
import org.junit.Test

class DriveVideoSourceTest {
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
}
