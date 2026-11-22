package com.drivelift.app.feature.desktopdrop

import com.drivelift.app.feature.desktopdrop.model.BiometricGateMode
import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import com.drivelift.app.feature.desktopdrop.server.CookieSigner
import com.drivelift.app.feature.desktopdrop.server.InMemoryTrustStore
import com.drivelift.app.feature.desktopdrop.server.JvmHmacCookieSigner
import com.drivelift.app.feature.desktopdrop.server.SessionAuthManager
import com.drivelift.app.feature.desktopdrop.server.TrustStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/** Knock-to-approve state transitions and the signed session cookie. */
class SessionAuthManagerTest {

    private val now = AtomicLong(1_000_000L)
    private val sharedSecret = ByteArray(32) { 7 }

    private fun manager(
        store: TrustStore = InMemoryTrustStore(),
        signer: CookieSigner = JvmHmacCookieSigner(sharedSecret),
    ) = SessionAuthManager(
        clock = { now.get() },
        allowLoopbackForTest = true,
        signer = signer,
        trustStore = store,
    )

    private fun SessionAuthManager.approvedBrowser(ip: String = IP): Pair<PeerSession, String> {
        val knock = openKnock(ip, UA)
        assertNotNull(approveKnock(knock.sessionId))
        val issued = claimCookie(knock.sessionId, ip)
        assertNotNull(issued)
        return knock to issued!!.value
    }

    @Test
    fun knockStartsPendingWithMatchCodeAndAReloadKeepsTheSameKnock() {
        val auth = manager()
        val first = auth.openKnock(IP, UA)
        assertEquals(PeerApprovalStatus.PENDING, first.status)
        assertTrue("Match code looks like 74-29: ${first.matchCode}", Regex("\\d\\d-\\d\\d").matches(first.matchCode))
        assertEquals("Chrome on Windows", first.deviceLabel)
        assertEquals(first.sessionId, auth.pendingApproval()?.sessionId)

        val reload = auth.openKnock(IP, UA)
        assertEquals(first.sessionId, reload.sessionId)
        assertEquals(first.matchCode, reload.matchCode)

        val other = auth.openKnock("192.168.1.51", UA)
        assertNotEquals(first.sessionId, other.sessionId)
    }

    @Test
    fun onlyAPendingKnockCanBeApprovedAndADeniedOneNeverComesBack() {
        val auth = manager()
        val knock = auth.openKnock(IP, UA)
        val approved = auth.approveKnock(knock.sessionId)
        assertEquals(PeerApprovalStatus.APPROVED, approved?.status)
        assertNull("A second approval must not mint another cookie", auth.approveKnock(knock.sessionId))

        val denied = auth.openKnock("192.168.1.60", UA)
        auth.revokePeer(denied.sessionId)
        assertEquals(PeerApprovalStatus.REVOKED, auth.knockState(denied.sessionId)?.status)
        assertNull(auth.approveKnock(denied.sessionId))
        assertNull(auth.claimCookie(denied.sessionId, "192.168.1.60"))
        assertNull(auth.approveKnock("no-such-knock"))
    }

    @Test
    fun aKnockExpiresAfterItsWindowAndCannotBeApproved() {
        val auth = manager()
        val knock = auth.openKnock(IP, UA)
        now.addAndGet(SessionAuthManager.KNOCK_TTL_MS - 1)
        assertEquals(PeerApprovalStatus.PENDING, auth.knockState(knock.sessionId)?.status)
        now.addAndGet(1)
        assertEquals(PeerApprovalStatus.EXPIRED, auth.knockState(knock.sessionId)?.status)
        assertNull(auth.pendingApproval())
        assertNull(auth.approveKnock(knock.sessionId))
    }

    @Test
    fun theCookieIsHandedOverOnceAndOnlyToTheKnockingAddress() {
        val auth = manager()
        val knock = auth.openKnock(IP, UA)
        assertNull("Not approved yet", auth.claimCookie(knock.sessionId, IP))
        auth.approveKnock(knock.sessionId)
        assertNull("Another address must not claim it", auth.claimCookie(knock.sessionId, "192.168.1.99"))
        val issued = auth.claimCookie(knock.sessionId, IP)
        assertNotNull(issued)
        assertNull("Session cookies carry no persistent max-age", issued!!.maxAgeSeconds)
        assertNull("Second claim gets nothing", auth.claimCookie(knock.sessionId, IP))
    }

    @Test
    fun aValidCookieIsAcceptedAndTamperedOrForgedOnesAreRejected() {
        val auth = manager()
        val (knock, cookie) = auth.approvedBrowser()
        assertEquals(knock.sessionId, auth.verifyCookie(cookie)?.sessionId)

        val (payload, mac) = cookie.split('.')
        // The first character carries real bits; the last one has unused padding bits.
        val flippedMac = (if (mac.first() == 'A') 'B' else 'A') + mac.drop(1)
        assertNull("Changed MAC", auth.verifyCookie("$payload.$flippedMac"))
        val flippedPayload = (if (payload.first() == 'A') 'B' else 'A') + payload.drop(1)
        assertNull("Changed payload", auth.verifyCookie("$flippedPayload.$mac"))
        assertNull(auth.verifyCookie(payload))
        assertNull(auth.verifyCookie("$payload."))
        assertNull(auth.verifyCookie(".$mac"))
        assertNull(auth.verifyCookie("garbage"))
        assertNull(auth.verifyCookie(""))
        assertNull(auth.verifyCookie(null))

        val stranger = manager(signer = JvmHmacCookieSigner(ByteArray(32) { 9 }))
        assertNull("Signed with another key", stranger.verifyCookie(cookie))
    }

    @Test
    fun aSessionCookieExpiresAndRevocationIsImmediate() {
        val auth = manager()
        val (knock, cookie) = auth.approvedBrowser()
        now.addAndGet(SessionAuthManager.SESSION_TTL_MS - 1)
        assertNotNull(auth.verifyCookie(cookie))
        now.addAndGet(1)
        assertNull("Expired", auth.verifyCookie(cookie))

        now.set(2_000_000L)
        val (second, secondCookie) = auth.approvedBrowser("192.168.1.70")
        assertNotNull(auth.verifyCookie(secondCookie))
        auth.revokePeer(second.sessionId)
        assertNull("Revoked", auth.verifyCookie(secondCookie))
        assertEquals(PeerApprovalStatus.REVOKED, auth.allPeers().first { it.sessionId == second.sessionId }.status)
        assertNotEquals(knock.sessionId, second.sessionId)
    }

    @Test
    fun approveOnEveryStartCookieDoesNotSurviveAServerRestart() {
        val store = InMemoryTrustStore()
        val auth = manager(store)
        val (_, cookie) = auth.approvedBrowser()
        assertNotNull(auth.verifyCookie(cookie))

        auth.resetSessions()
        assertNull("Server stop ends the session", auth.verifyCookie(cookie))

        val afterRestart = manager(store)
        assertNull("A new process has no such session", afterRestart.verifyCookie(cookie))
    }

    @Test
    fun trustedBrowserSurvivesARestartUntilRevokedOrExpired() {
        val store = InMemoryTrustStore()
        val auth = manager(store)
        auth.gateMode = BiometricGateMode.TRUST_7_DAYS
        val knock = auth.openKnock(IP, UA)
        val approved = auth.approveKnock(knock.sessionId)
        assertTrue(approved!!.trusted)
        val issued = auth.claimCookie(knock.sessionId, IP)!!
        assertEquals((SessionAuthManager.TRUST_TTL_MS / 1000L).toInt(), issued.maxAgeSeconds)

        auth.resetSessions()
        val restarted = manager(store)
        restarted.gateMode = BiometricGateMode.TRUST_7_DAYS
        val back = restarted.verifyCookie(issued.value)
        assertNotNull("Trusted browser returns after restart", back)
        assertTrue(back!!.trusted)
        assertEquals("Chrome on Windows", back.deviceLabel)

        // Disconnect removes trust at once, including after another restart.
        restarted.revokePeer(knock.sessionId)
        assertNull(restarted.verifyCookie(issued.value))
        assertNull(manager(store).verifyCookie(issued.value))
    }

    @Test
    fun trustExpiresAfterSevenDaysAndTheGateModeAppliesToTheNextApproval() {
        val store = InMemoryTrustStore()
        val auth = manager(store)
        auth.gateMode = BiometricGateMode.TRUST_7_DAYS
        val trusted = auth.openKnock(IP, UA)
        auth.approveKnock(trusted.sessionId)
        val trustedCookie = auth.claimCookie(trusted.sessionId, IP)!!.value

        auth.gateMode = BiometricGateMode.APPROVE_EVERY_START
        val ordinary = auth.openKnock("192.168.1.80", UA)
        val approved = auth.approveKnock(ordinary.sessionId)
        assertFalse("Switching the gate does not retro-trust", approved!!.trusted)
        assertTrue(auth.allPeers().first { it.sessionId == trusted.sessionId }.trusted)

        now.addAndGet(SessionAuthManager.TRUST_TTL_MS)
        assertNull("Seven days later the trusted cookie is dead", auth.verifyCookie(trustedCookie))
        assertNull(manager(store).verifyCookie(trustedCookie))
    }

    private companion object {
        const val IP = "192.168.1.50"
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/131.0 Safari/537.36"
    }
}
