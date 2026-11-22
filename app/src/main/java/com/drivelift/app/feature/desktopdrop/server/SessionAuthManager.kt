package com.drivelift.app.feature.desktopdrop.server

import com.drivelift.app.feature.desktopdrop.model.BiometricGateMode
import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import java.net.Inet4Address
import java.net.InetAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID

/**
 * Knock-to-approve browser sessions for Desktop Drop.
 *
 * A browser that opens the fixed address "knocks": it gets a random match code and waits. The
 * session only becomes usable after the owner approves it on the phone, and the UI must call
 * [approveKnock] only after a successful BiometricPrompt. Approval mints a signed HttpOnly cookie
 * that the browser collects exactly once ([claimCookie]).
 *
 * Cookie value: base64url(payload) "." base64url(HMAC-SHA256(payload)), payload
 * `v1:sessionId:issuedMs:expiresMs:S|T:serverEpoch`. A cookie is accepted only when its MAC
 * verifies, it has not expired, and the session is approved and not revoked. "S" cookies also need
 * the current server epoch, so they die when the server stops. "T" (trusted 7 days) cookies
 * survive a restart through the [TrustStore].
 */
class SessionAuthManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowLoopbackForTest: Boolean = false,
    private val signer: CookieSigner = JvmHmacCookieSigner(),
    private val trustStore: TrustStore = InMemoryTrustStore(),
) {
    private val rng = SecureRandom()
    private val lock = Any()
    private val sessionsById = LinkedHashMap<String, InternalPeer>()

    @Volatile
    var gateMode: BiometricGateMode = BiometricGateMode.APPROVE_EVERY_START

    @Volatile
    private var serverEpoch: String = randomHex(EPOCH_BYTES)

    private class InternalPeer(
        var session: PeerSession,
        var cookieValue: String?,
    )

    /** Ends every session of this server run. Trusted browsers stay in the [TrustStore]. */
    fun resetSessions() {
        synchronized(lock) {
            sessionsById.clear()
            serverEpoch = randomHex(EPOCH_BYTES)
        }
    }

    fun isAllowedRemoteIp(remoteHost: String): Boolean =
        isPrivateLanIp(remoteHost, allowLoopback = allowLoopbackForTest)

    /**
     * Registers a browser waiting for approval. A browser that reloads the page gets the same
     * pending knock (same match code) back instead of a second one.
     */
    fun openKnock(peerIp: String, userAgent: String): PeerSession {
        val now = clock()
        synchronized(lock) {
            expireStaleLocked(now)
            val existing = sessionsById.values.firstOrNull {
                it.session.status == PeerApprovalStatus.PENDING &&
                    it.session.peerIp == peerIp && it.session.userAgent == userAgent
            }
            if (existing != null) {
                existing.session = existing.session.copy(lastSeenMs = now)
                return existing.session
            }
            val pending = sessionsById.values.filter { it.session.status == PeerApprovalStatus.PENDING }
            if (pending.size >= MAX_PENDING_KNOCKS) {
                pending.first().let { it.session = it.session.copy(status = PeerApprovalStatus.EXPIRED, lastSeenMs = now) }
            }
            val id = UUID.randomUUID().toString()
            val session = PeerSession(
                sessionId = id,
                peerIp = peerIp,
                userAgent = userAgent,
                deviceLabel = parseDeviceLabel(userAgent),
                connectedAtMs = now,
                lastSeenMs = now,
                status = PeerApprovalStatus.PENDING,
                matchCode = newMatchCode(),
            )
            sessionsById[id] = InternalPeer(session, null)
            return session
        }
    }

    /** The current state of a knock, applying expiry first. Null for an unknown id. */
    fun knockState(knockId: String): PeerSession? {
        val now = clock()
        synchronized(lock) {
            expireStaleLocked(now)
            return sessionsById[knockId]?.session
        }
    }

    /**
     * Call only after the owner passed the phone's biometric check. Only a pending knock can be
     * approved; a denied, expired or revoked one never comes back.
     */
    fun approveKnock(knockId: String): PeerSession? {
        val now = clock()
        synchronized(lock) {
            expireStaleLocked(now)
            val entry = sessionsById[knockId] ?: return null
            if (entry.session.status != PeerApprovalStatus.PENDING) return null
            val trusted = gateMode == BiometricGateMode.TRUST_7_DAYS
            entry.cookieValue = mintCookie(entry.session.sessionId, trusted, now)
            entry.session = entry.session.copy(
                status = PeerApprovalStatus.APPROVED,
                trusted = trusted,
                lastSeenMs = now,
            )
            if (trusted) {
                trustStore.put(
                    TrustedBrowser(
                        sessionId = entry.session.sessionId,
                        deviceLabel = entry.session.deviceLabel,
                        peerIp = entry.session.peerIp,
                        userAgent = entry.session.userAgent,
                        expiresMs = now + TRUST_TTL_MS,
                    ),
                )
            }
            return entry.session
        }
    }

    data class IssuedCookie(val value: String, val session: PeerSession, val maxAgeSeconds: Int?)

    /** Hands the approved browser its cookie once. A second claim, or another IP, gets null. */
    fun claimCookie(knockId: String, peerIp: String): IssuedCookie? {
        synchronized(lock) {
            val entry = sessionsById[knockId] ?: return null
            if (entry.session.status != PeerApprovalStatus.APPROVED) return null
            if (entry.session.peerIp != peerIp) return null
            val cookie = entry.cookieValue ?: return null
            entry.cookieValue = null
            val maxAge = if (entry.session.trusted) (TRUST_TTL_MS / 1000L).toInt() else null
            return IssuedCookie(cookie, entry.session, maxAge)
        }
    }

    /** Disconnect, or deny a pending knock. Also forgets a trusted browser immediately. */
    fun revokePeer(sessionId: String): PeerSession? {
        val now = clock()
        synchronized(lock) {
            trustStore.remove(sessionId)
            val entry = sessionsById[sessionId] ?: return null
            entry.cookieValue = null
            entry.session = entry.session.copy(status = PeerApprovalStatus.REVOKED, trusted = false, lastSeenMs = now)
            return entry.session
        }
    }

    fun verifyCookie(cookieValue: String?): PeerSession? {
        if (cookieValue.isNullOrBlank()) return null
        val now = clock()
        val claims = parseCookie(cookieValue, now) ?: return null
        synchronized(lock) {
            val entry = sessionsById[claims.sessionId]
            if (entry != null) {
                if (entry.session.status != PeerApprovalStatus.APPROVED) return null
                if (!claims.trusted && claims.epoch != serverEpoch) return null
                entry.session = entry.session.copy(lastSeenMs = now)
                return entry.session
            }
            // After a restart only a trusted browser still on the trust list comes back.
            if (!claims.trusted) return null
            val trusted = trustStore.get(claims.sessionId, now) ?: return null
            val session = PeerSession(
                sessionId = trusted.sessionId,
                peerIp = trusted.peerIp,
                userAgent = trusted.userAgent,
                deviceLabel = trusted.deviceLabel,
                connectedAtMs = now,
                lastSeenMs = now,
                status = PeerApprovalStatus.APPROVED,
                trusted = true,
            )
            sessionsById[trusted.sessionId] = InternalPeer(session, null)
            return session
        }
    }

    fun allPeers(): List<PeerSession> = synchronized(lock) {
        sessionsById.values.map { it.session }
    }

    fun pendingApproval(): PeerSession? {
        val now = clock()
        return synchronized(lock) {
            expireStaleLocked(now)
            sessionsById.values.firstOrNull { it.session.status == PeerApprovalStatus.PENDING }?.session
        }
    }

    private fun expireStaleLocked(now: Long) {
        for (entry in sessionsById.values) {
            val s = entry.session
            if (s.status == PeerApprovalStatus.PENDING && now - s.connectedAtMs >= KNOCK_TTL_MS) {
                entry.session = s.copy(status = PeerApprovalStatus.EXPIRED, lastSeenMs = now)
            }
        }
        sessionsById.values.removeAll {
            (it.session.status == PeerApprovalStatus.REVOKED || it.session.status == PeerApprovalStatus.EXPIRED) &&
                now - it.session.lastSeenMs > RETAIN_TERMINAL_MS
        }
    }

    private fun newMatchCode(): String =
        String.format(Locale.ROOT, "%02d-%02d", rng.nextInt(100), rng.nextInt(100))

    private fun randomHex(bytes: Int): String {
        val raw = ByteArray(bytes)
        rng.nextBytes(raw)
        return raw.joinToString("") { "%02x".format(it) }
    }

    internal fun mintCookie(sessionId: String, trusted: Boolean, now: Long): String {
        val expires = now + if (trusted) TRUST_TTL_MS else SESSION_TTL_MS
        val payload = "v1:$sessionId:$now:$expires:${if (trusted) "T" else "S"}:$serverEpoch"
        val encoded = ENCODER.encodeToString(payload.toByteArray(Charsets.UTF_8))
        val mac = ENCODER.encodeToString(signer.sign(encoded.toByteArray(Charsets.US_ASCII)))
        return "$encoded.$mac"
    }

    private data class CookieClaims(
        val sessionId: String,
        val expiresMs: Long,
        val trusted: Boolean,
        val epoch: String,
    )

    private fun parseCookie(value: String, now: Long): CookieClaims? {
        val dot = value.indexOf('.')
        if (dot <= 0 || dot == value.length - 1) return null
        val encoded = value.substring(0, dot)
        val presentedMac = runCatching { DECODER.decode(value.substring(dot + 1)) }.getOrNull() ?: return null
        val expectedMac = signer.sign(encoded.toByteArray(Charsets.US_ASCII))
        if (!MessageDigest.isEqual(expectedMac, presentedMac)) return null
        val payload = runCatching { String(DECODER.decode(encoded), Charsets.UTF_8) }.getOrNull() ?: return null
        val fields = payload.split(':')
        if (fields.size != PAYLOAD_FIELDS || fields[0] != "v1") return null
        val expires = fields[3].toLongOrNull() ?: return null
        if (now >= expires) return null
        return CookieClaims(
            sessionId = fields[1],
            expiresMs = expires,
            trusted = fields[4] == "T",
            epoch = fields[5],
        )
    }

    companion object {
        const val SESSION_COOKIE_NAME = "dl_drop_session"
        const val KNOCK_TTL_MS = 120_000L
        const val SESSION_TTL_MS = 12L * 60L * 60L * 1000L
        const val TRUST_TTL_MS = 7L * 24L * 60L * 60L * 1000L
        private const val MAX_PENDING_KNOCKS = 8
        private const val RETAIN_TERMINAL_MS = 600_000L
        private const val EPOCH_BYTES = 8
        private const val PAYLOAD_FIELDS = 6
        private val ENCODER = Base64.getUrlEncoder().withoutPadding()
        private val DECODER = Base64.getUrlDecoder()

        fun isPrivateLanIp(rawHost: String, allowLoopback: Boolean = false): Boolean {
            var host = rawHost.trim().trimStart('/').substringBefore('%')
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length - 1)
            }
            if (host.startsWith("::ffff:", ignoreCase = true)) {
                host = host.substring(7)
            }
            if (allowLoopback && (host == "::1" || host == "0:0:0:0:0:0:0:1" || host == "localhost")) {
                return true
            }
            val parsed = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
            if (allowLoopback && parsed.isLoopbackAddress) return true
            val v4 = parsed as? Inet4Address ?: return false
            val b = v4.address
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            return when {
                first == 10 -> true
                first == 172 && second in 16..31 -> true
                first == 192 && second == 168 -> true
                first == 169 && second == 254 -> true
                allowLoopback && first == 127 -> true
                else -> false
            }
        }

        fun parseDeviceLabel(userAgent: String?): String {
            if (userAgent.isNullOrBlank()) return "Desktop browser"
            val ua = userAgent.lowercase()
            val browser = when {
                "edg/" in ua -> "Edge"
                "opr/" in ua || "opera" in ua -> "Opera"
                "firefox/" in ua -> "Firefox"
                "chrome/" in ua && "edg/" !in ua -> "Chrome"
                "safari/" in ua && "chrome/" !in ua -> "Safari"
                else -> "Browser"
            }
            val os = when {
                "windows" in ua -> "Windows"
                "macintosh" in ua || "mac os x" in ua -> "macOS"
                "cros" in ua -> "ChromeOS"
                "android" in ua -> "Android"
                "iphone" in ua || "ipad" in ua -> "iOS"
                "linux" in ua -> "Linux"
                else -> "LAN device"
            }
            return "$browser on $os"
        }
    }
}
