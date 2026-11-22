package com.drivelift.app.feature.desktopdrop

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import com.drivelift.app.DriveLiftApp
import com.drivelift.app.Graph
import com.drivelift.app.feature.desktopdrop.model.BiometricGateMode
import com.drivelift.app.feature.desktopdrop.model.ConnectivityMode
import com.drivelift.app.feature.desktopdrop.model.DesktopDropSnapshot
import com.drivelift.app.feature.desktopdrop.model.DropTransferProgress
import com.drivelift.app.feature.desktopdrop.model.DropTransferStatus
import com.drivelift.app.feature.desktopdrop.model.HotspotCredentials
import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.feature.desktopdrop.model.ServerStatus
import com.drivelift.app.feature.desktopdrop.model.SharedDropItem
import com.drivelift.app.feature.desktopdrop.model.TransferDirection
import com.drivelift.app.feature.desktopdrop.ui.DesktopDropScreen
import com.drivelift.app.feature.desktopdrop.ui.DesktopDropViewModel
import com.drivelift.app.ui.AppViewModel
import com.drivelift.app.ui.DriveLiftTheme
import com.drivelift.app.ui.GlassBackdrop
import com.drivelift.app.ui.Tokens
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DesktopDropUiTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: DriveLiftApp get() = ApplicationProvider.getApplicationContext()
    private val recordDd = System.getProperty("roborazzi.test.record") == "true"

    private enum class Variant(val qualifiers: String, val fontScale: Float) {
        STANDARD("w360dp-h780dp-xhdpi", 1f),
        LARGE_FONT("w360dp-h780dp-xhdpi", 1.5f),
        COMPACT("w320dp-h640dp-xhdpi", 1f),
    }

    @Before
    fun setUp() {
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        RuntimeEnvironment.setFontScale(1f)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        Graph.get(app).clock = { FIXED_TIME_MS }
    }

    @After
    fun tearDown() {
        Graph.get(app).desktopDrop.prefs.setGateMode(BiometricGateMode.APPROVE_EVERY_START)
        Graph.get(app).desktopDrop.controller.seedPreviewStateForTest(DesktopDropSnapshot())
        Graph.get(app).clock = System::currentTimeMillis
        RuntimeEnvironment.setFontScale(1f)
        RuntimeEnvironment.setQualifiers("w360dp-h780dp-xhdpi")
        pump(10)
    }

    private fun seedWifiRunning(withPendingPeer: Boolean = false) {
        val controller = Graph.get(app).desktopDrop.controller
        val pending = if (withPendingPeer) {
            PeerSession(
                sessionId = "peer-pending",
                peerIp = "192.168.1.104",
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0",
                deviceLabel = "Chrome on Windows",
                connectedAtMs = FIXED_TIME_MS,
                lastSeenMs = FIXED_TIME_MS,
                status = PeerApprovalStatus.PENDING,
                matchCode = "74-29",
            )
        } else {
            null
        }
        controller.seedPreviewStateForTest(
            DesktopDropSnapshot(
                status = ServerStatus.RUNNING,
                mode = ConnectivityMode.SAME_WIFI,
                bindIp = "192.168.1.44",
                port = 8080,
                dropUrl = "http://192.168.1.44:8080",
                bookmarkUrl = "http://drivelift.local:8080",
                peers = listOf(
                    PeerSession(
                        sessionId = "peer-1",
                        peerIp = "192.168.1.88",
                        userAgent = "Mozilla/5.0 (Windows NT 10.0) Edg/131.0",
                        deviceLabel = "Edge on Windows",
                        connectedAtMs = FIXED_TIME_MS - 30_000L,
                        lastSeenMs = FIXED_TIME_MS,
                        status = PeerApprovalStatus.APPROVED,
                        trusted = true,
                    ),
                ),
                pendingApproval = pending,
                sharedItems = listOf(
                    SharedDropItem(
                        id = "s-1",
                        name = "SYNTH_DROP_ARCHIVE.zip",
                        relativePath = "Exports",
                        sizeBytes = 420_000_000L,
                        mimeType = "application/zip",
                        addedAtMs = FIXED_TIME_MS,
                    ),
                ),
                transfers = listOf(
                    DropTransferProgress(
                        transferId = "x-1",
                        filename = "SYNTH_PROJECT_RAW.mkv",
                        relativePath = "",
                        direction = TransferDirection.PC_TO_PHONE,
                        totalBytes = 2_400_000_000L,
                        transferredBytes = 1_200_000_000L,
                        speedBytesPerSec = 58_000_000L,
                        etaSeconds = 21L,
                        status = DropTransferStatus.ACTIVE,
                        updatedAtMs = FIXED_TIME_MS,
                    ),
                ),
                aggregateSpeedBytesPerSec = 58_000_000L,
                activeTransferCount = 1,
            ),
        )
    }

    private fun seedHotspotRunning() {
        val controller = Graph.get(app).desktopDrop.controller
        controller.seedPreviewStateForTest(
            DesktopDropSnapshot(
                status = ServerStatus.RUNNING,
                mode = ConnectivityMode.DIRECT_HOTSPOT,
                bindIp = "192.168.43.1",
                port = 8080,
                dropUrl = "http://192.168.43.1:8080",
                bookmarkUrl = "http://drivelift.local:8080",
                hotspotCredentials = HotspotCredentials(
                    ssid = "AndroidShare_9421",
                    passphrase = "synth-pass-8410",
                    wifiQrPayload = "WIFI:T:WPA;S:AndroidShare_9421;P:synth-pass-8410;;",
                ),
                sharedItems = listOf(
                    SharedDropItem(
                        id = "s-1",
                        name = "SYNTH_DROP_ARCHIVE.zip",
                        relativePath = "Exports",
                        sizeBytes = 420_000_000L,
                        mimeType = "application/zip",
                        addedAtMs = FIXED_TIME_MS,
                    ),
                ),
            ),
        )
    }

    private fun pump(times: Int = 8) = repeat(times) {
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(64)
        Thread.sleep(10)
    }

    private fun waitForTag(tag: String, timeoutMs: Long = 10_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            pump(2)
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
        }
        compose.onNodeWithTag(tag).assertExists()
    }

    @Test
    fun desktopDropRendersAllVariantsTroubleshootBannerAndHotspotQrFlow() {
        for (variant in Variant.entries) {
            seedWifiRunning(withPendingPeer = false)
            RuntimeEnvironment.setQualifiers(variant.qualifiers)
            RuntimeEnvironment.setFontScale(variant.fontScale)
            compose.mainClock.autoAdvance = false
            val appVm = AppViewModel(app)
            val dropVm = DesktopDropViewModel(app)
            pump(12)

            ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val bars = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                    activity.setContent {
                        DriveLiftTheme {
                            GlassBackdrop {
                                DesktopDropScreen(appViewModel = appVm, vm = dropVm)
                            }
                        }
                    }
                }
                waitForTag("dd-screen")
                pump(4)
                compose.onNodeWithText("Desktop drop").assertIsDisplayed()
                compose.onNodeWithTag("dd-server-toggle").assertHeightIsAtLeast(Tokens.TouchTarget)
                // The bookmarkable address leads the screen, with no token in it.
                compose.onNodeWithTag("dd-address-card").assertExists()
                compose.onNodeWithTag("dd-bookmark-chip").assertExists()
                compose.onNodeWithText("http://drivelift.local:8080").assertExists()
                assertTrue(compose.onAllNodesWithTag("dd-url-qr").fetchSemanticsNodes().isEmpty())

                if (recordDd) {
                    compose.onRoot().captureRoboImage(
                        "build/outputs/dd-screenshots/dd-hub-${variant.name.lowercase()}.png",
                    )
                }

                if (variant == Variant.STANDARD) {
                    // The LAN IP fallback chip carries no token, and the pill unfolds the QR.
                    compose.onNodeWithTag("dd-lan-chip").assertExists()
                    compose.onNodeWithText("http://192.168.1.44:8080").assertExists()
                    compose.onNodeWithTag("dd-list").performScrollToKey("qr-flow")
                    pump(4)
                    compose.onNodeWithTag("dd-pair-pill").assertHeightIsAtLeast(Tokens.TouchTarget)
                    compose.onNodeWithTag("dd-pair-pill").performClick()
                    waitForTag("dd-url-qr")
                    compose.onNodeWithTag("dd-pair-pill").performClick()
                    pump(4)
                    assertTrue(compose.onAllNodesWithTag("dd-url-qr").fetchSemanticsNodes().isEmpty())

                    // The Biometric gate offers both modes and changes the saved mode.
                    compose.onNodeWithTag("dd-list").performScrollToKey("gate")
                    pump(4)
                    compose.onNodeWithTag("dd-gate-panel").assertExists()
                    compose.onNodeWithText("Approve on every Start").assertExists()
                    compose.onNodeWithText("Trust this browser (7 days)").assertExists()
                    compose.onNodeWithTag("dd-gate-TRUST_7_DAYS").performClick()
                    pump(4)
                    assertTrue(Graph.get(app).desktopDrop.prefs.current.gateMode == BiometricGateMode.TRUST_7_DAYS)
                    Graph.get(app).desktopDrop.prefs.setGateMode(BiometricGateMode.APPROVE_EVERY_START)
                    seedWifiRunning(withPendingPeer = false)
                    pump(4)

                    // Expand troubleshooting banner and verify VPN tip + Direct Hotspot switch CTA.
                    compose.onNodeWithTag("dd-list").performScrollToKey("troubleshoot")
                    pump(4)
                    compose.onNodeWithTag("dd-troubleshoot-toggle").performClick()
                    waitForTag("dd-troubleshoot-body")
                    compose.onNodeWithTag("dd-switch-hotspot").assertHeightIsAtLeast(Tokens.TouchTarget)

                    // Scroll down and verify connected peer, destination, share queue, and transfer HUD.
                    compose.onNodeWithTag("dd-list").performScrollToKey("peer-peer-1")
                    pump(4)
                    compose.onNodeWithText("Edge on Windows").assertExists()

                    compose.onNodeWithTag("dd-list").performScrollToKey("xfer-x-1")
                    pump(4)
                    compose.onNodeWithText("SYNTH_PROJECT_RAW.mkv").assertExists()

                    // Verify Direct Hotspot 2-step QR flow.
                    seedHotspotRunning()
                    compose.onNodeWithTag("dd-list").performScrollToKey("qr-flow")
                    pump(6)
                    waitForTag("dd-hotspot-qr")
                    compose.onNodeWithText("Step 1: Join direct Wi-Fi hotspot").assertIsDisplayed()
                    if (recordDd) {
                        compose.onRoot().captureRoboImage(
                            "build/outputs/dd-screenshots/dd-hotspot-standard.png",
                        )
                    }

                    // Verify One-Tap Peer Approval dialog renders cleanly.
                    seedWifiRunning(withPendingPeer = true)
                    waitForTag("dd-peer-approval-dialog")
                    compose.onNodeWithText("Approve laptop with biometrics?").assertIsDisplayed()
                    compose.onNodeWithText("Chrome on Windows").assertIsDisplayed()
                    compose.onNodeWithTag("dd-match-code").assertIsDisplayed()
                    compose.onNodeWithText("Match code 74-29").assertIsDisplayed()
                    compose.onNodeWithTag("dd-verify-biometric").assertHeightIsAtLeast(Tokens.TouchTarget)
                    compose.onNodeWithText("Deny").performClick()
                    pump(4)
                    assertTrue(compose.onAllNodesWithTag("dd-peer-approval-dialog").fetchSemanticsNodes().isEmpty())
                }
            }
        }
    }

    companion object {
        private const val FIXED_TIME_MS = 1_773_583_500_000L
    }
}
