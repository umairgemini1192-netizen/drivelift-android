package com.drivelift.app.feature.desktopdrop.server

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Signs session cookie payloads. The key never leaves the signer. */
interface CookieSigner {
    fun sign(data: ByteArray): ByteArray
}

/** HMAC-SHA256 with an in-memory key. Used by JVM tests and as the Keystore failure fallback. */
class JvmHmacCookieSigner(
    secret: ByteArray = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) },
) : CookieSigner {
    private val key = SecretKeySpec(secret, HMAC)

    override fun sign(data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC)
        mac.init(key)
        return mac.doFinal(data)
    }

    companion object {
        const val HMAC = "HmacSHA256"
        private const val KEY_BYTES = 32
    }
}

/**
 * HMAC-SHA256 whose key is generated inside the Android Keystore and cannot be exported. If the
 * Keystore refuses the key (rare broken devices), cookies fall back to a per-process random key,
 * which only means browsers must be approved again after the app process restarts.
 */
class KeystoreHmacCookieSigner(private val alias: String = DEFAULT_ALIAS) : CookieSigner {
    private val fallback by lazy { JvmHmacCookieSigner() }

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var keystoreFailed = false

    val usingKeystore: Boolean get() = !keystoreFailed

    override fun sign(data: ByteArray): ByteArray {
        val key = if (keystoreFailed) null else keystoreKey()
        if (key != null) {
            try {
                val mac = Mac.getInstance(JvmHmacCookieSigner.HMAC)
                mac.init(key)
                return mac.doFinal(data)
            } catch (e: Exception) {
                Log.w(TAG, "Keystore HMAC failed, using in-memory key", e)
                keystoreFailed = true
            }
        }
        return fallback.sign(data)
    }

    @Synchronized
    private fun keystoreKey(): SecretKey? {
        cachedKey?.let { return it }
        return try {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            val existing = store.getKey(alias, null) as? SecretKey
            val key = existing ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER).run {
                init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
                generateKey()
            }
            cachedKey = key
            key
        } catch (e: Exception) {
            Log.w(TAG, "Keystore HMAC key unavailable", e)
            keystoreFailed = true
            null
        }
    }

    companion object {
        const val DEFAULT_ALIAS = "drivelift_drop_cookie_hmac"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TAG = "DropCookieSigner"
    }
}

/** A browser the owner chose to trust for a while, remembered across server restarts. */
data class TrustedBrowser(
    val sessionId: String,
    val deviceLabel: String,
    val peerIp: String,
    val userAgent: String,
    val expiresMs: Long,
)

interface TrustStore {
    fun put(browser: TrustedBrowser)

    /** The record, or null when absent or expired at [nowMs] (an expired record is dropped). */
    fun get(sessionId: String, nowMs: Long): TrustedBrowser?

    fun remove(sessionId: String)
    fun clear()
}

class InMemoryTrustStore : TrustStore {
    private val map = ConcurrentHashMap<String, TrustedBrowser>()

    override fun put(browser: TrustedBrowser) {
        map[browser.sessionId] = browser
    }

    override fun get(sessionId: String, nowMs: Long): TrustedBrowser? {
        val found = map[sessionId] ?: return null
        if (found.expiresMs <= nowMs) {
            map.remove(sessionId)
            return null
        }
        return found
    }

    override fun remove(sessionId: String) {
        map.remove(sessionId)
    }

    override fun clear() {
        map.clear()
    }
}

/** Trusted browsers in app-private preferences. Holds no secret: cookies are signed separately. */
class PrefsTrustStore(context: Context) : TrustStore {
    private val prefs = (context.applicationContext ?: context)
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun put(browser: TrustedBrowser) {
        prefs.edit().putString(browser.sessionId, encode(browser)).apply()
    }

    override fun get(sessionId: String, nowMs: Long): TrustedBrowser? {
        val raw = prefs.getString(sessionId, null) ?: return null
        val parsed = decode(sessionId, raw)
        if (parsed == null || parsed.expiresMs <= nowMs) {
            prefs.edit().remove(sessionId).apply()
            return null
        }
        return parsed
    }

    override fun remove(sessionId: String) {
        prefs.edit().remove(sessionId).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun encode(b: TrustedBrowser): String =
        listOf(b.deviceLabel, b.peerIp, b.userAgent, b.expiresMs.toString())
            .joinToString(SEP) { it.replace(SEP, " ") }

    private fun decode(sessionId: String, raw: String): TrustedBrowser? {
        val parts = raw.split(SEP, limit = FIELDS)
        if (parts.size != FIELDS) return null
        val expires = parts[3].toLongOrNull() ?: return null
        return TrustedBrowser(sessionId, parts[0], parts[1], parts[2], expires)
    }

    private companion object {
        const val PREFS_NAME = "drivelift_desktop_drop_trust"
        const val SEP = "\t"
        const val FIELDS = 4
    }
}
