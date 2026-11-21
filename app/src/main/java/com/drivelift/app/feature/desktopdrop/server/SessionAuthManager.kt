package com.drivelift.app.feature.desktopdrop.server

import com.drivelift.app.feature.desktopdrop.model.PeerApprovalStatus
import com.drivelift.app.feature.desktopdrop.model.PeerSession
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the per-server ephemeral 8-character session token, browser peer approval states,
 * HttpOnly session cookies, and private LAN IP enforcement.
 */
class SessionAuthManager(
    private val clock: () -> Long = System::currentTimeMillis,
    private val allowLoopbackForTest: Boolean = false,
) {
    private val rng = SecureRandom()
    private val lock = Any()
    private val sessionsById = LinkedHashMap<String, InternalPeer>()
    private val cookieToPeerId = ConcurrentHashMap<String, String>()

    @Volatile
    var sessionToken: String = generateToken()
        private set

    @Volatile
    var requireApproval: Boolean = true

    private data class InternalPeer(
        var session: PeerSession,
        var cookieValue: String?,
    )

    fun rotateToken(): String {
        synchronized(lock) {
            sessionsById.clear()
            cookieToPeerId.clear()
            sessionToken = generateToken()
            return sessionToken
        }
    }

    fun resetSessions() {
        synchronized(lock) {
            sessionsById.clear()
            cookieToPeerId.clear()
        }
    }

    fun isAllowedRemoteIp(remoteHost: String): Boolean =
        isPrivateLanIp(remoteHost, allowLoopback = allowLoopbackForTest)

    fun isValidToken(candidate: String?): Boolean {
        if (candidate.isNullOrBlank()) return false
        return constantTimeEquals(candidate.trim(), sessionToken)
    }

    data class AuthAttemptResult(
        val session: PeerSession,
        val issuedCookie: String?,
    )

    /**
     * Registers or looks up a peer presenting a validated ephemeral token. When [requireApproval]
     * is disabled, immediately approves the peer and mints an HttpOnly session cookie.
     */
    fun requestAccess(peerIp: String, userAgent: String, suppliedToken: String?): AuthAttemptResult? {
        if (!isValidToken(suppliedToken)) return null
        val now = clock()
        val label = parseDeviceLabel(userAgent)
        synchronized(lock) {
            val existing = sessionsById.values.firstOrNull {
                it.session.peerIp == peerIp && it.session.userAgent == userAgent && it.session.status != PeerApprovalStatus.REVOKED
            }
            if (existing != null) {
                existing.session = existing.session.copy(lastSeenMs = now)
                if (!requireApproval && existing.session.status == PeerApprovalStatus.PENDING) {
                    return approveLocked(existing)
                }
                return AuthAttemptResult(existing.session, existing.cookieValue)
            }
            val initialStatus = if (requireApproval) PeerApprovalStatus.PENDING else PeerApprovalStatus.APPROVED
            val id = UUID.randomUUID().toString()
            val session = PeerSession(
                sessionId = id,
                peerIp = peerIp,
                userAgent = userAgent,
                deviceLabel = label,
                connectedAtMs = now,
                lastSeenMs = now,
                status = initialStatus,
            )
            val entry = InternalPeer(session, null)
            sessionsById[id] = entry
            return if (initialStatus == PeerApprovalStatus.APPROVED) {
                approveLocked(entry)
            } else {
                AuthAttemptResult(session, null)
            }
        }
    }

    fun checkPeerStatus(peerId: String, suppliedToken: String?): AuthAttemptResult? {
        if (!isValidToken(suppliedToken)) return null
        synchronized(lock) {
            val entry = sessionsById[peerId] ?: return null
            entry.session = entry.session.copy(lastSeenMs = clock())
            return AuthAttemptResult(
                session = entry.session,
                issuedCookie = if (entry.session.status == PeerApprovalStatus.APPROVED) entry.cookieValue else null,
            )
        }
    }

    fun approvePeer(sessionId: String): PeerSession? {
        synchronized(lock) {
            val entry = sessionsById[sessionId] ?: return null
            if (entry.session.status == PeerApprovalStatus.REVOKED) return null
            return approveLocked(entry).session
        }
    }

    fun revokePeer(sessionId: String): PeerSession? {
        synchronized(lock) {
            val entry = sessionsById[sessionId] ?: return null
            entry.cookieValue?.let { cookieToPeerId.remove(it) }
            entry.cookieValue = null
            entry.session = entry.session.copy(status = PeerApprovalStatus.REVOKED, lastSeenMs = clock())
            return entry.session
        }
    }

    fun verifyCookie(cookieValue: String?): PeerSession? {
        if (cookieValue.isNullOrBlank()) return null
        val peerId = cookieToPeerId[cookieValue] ?: return null
        synchronized(lock) {
            val entry = sessionsById[peerId] ?: return null
            if (entry.session.status != PeerApprovalStatus.APPROVED) return null
            if (entry.cookieValue != cookieValue) return null
            entry.session = entry.session.copy(lastSeenMs = clock())
            return entry.session
        }
    }

    fun allPeers(): List<PeerSession> = synchronized(lock) {
        sessionsById.values.map { it.session }
    }

    fun pendingApproval(): PeerSession? = synchronized(lock) {
        sessionsById.values.firstOrNull { it.session.status == PeerApprovalStatus.PENDING }?.session
    }

    private fun approveLocked(entry: InternalPeer): AuthAttemptResult {
        val cookie = entry.cookieValue ?: generateCookieValue().also { newCookie ->
            entry.cookieValue = newCookie
            cookieToPeerId[newCookie] = entry.session.sessionId
        }
        entry.session = entry.session.copy(status = PeerApprovalStatus.APPROVED, lastSeenMs = clock())
        return AuthAttemptResult(entry.session, cookie)
    }

    private fun generateToken(): String = buildString(TOKEN_LENGTH) {
        repeat(TOKEN_LENGTH) { append(TOKEN_ALPHABET[rng.nextInt(TOKEN_ALPHABET.length)]) }
    }

    private fun generateCookieValue(): String {
        val bytes = ByteArray(24)
        rng.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SESSION_COOKIE_NAME = "dl_drop_session"
        private const val TOKEN_LENGTH = 8
        private const val TOKEN_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

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

        private fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.length != b.length) return false
            var diff = 0
            for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
            return diff == 0
        }
    }
}
