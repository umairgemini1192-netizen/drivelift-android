package com.drivelift.app.feature.applock.engine

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Salted PBKDF2-HMAC-SHA256 credential representation. Never stores plaintext PINs.
 */
data class HashedPinCredential(
    val saltBase64: String,
    val hashBase64: String,
    val iterations: Int = PinCredentialHasher.DEFAULT_ITERATIONS,
)

/**
 * Derives and verifies App Lock PIN credentials using PBKDF2WithHmacSHA256 with random
 * per-credential salts and constant-time digest comparison.
 */
class PinCredentialHasher(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    /**
     * Hashes [pin] with a fresh random salt. Callers should zero [pin] if they own the array;
     * internal key specifications are cleared immediately after derivation.
     */
    fun hashPin(
        pin: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
    ): HashedPinCredential {
        require(pin.isNotEmpty()) { "PIN must not be empty" }
        require(iterations > 0) { "Iterations must be positive" }

        val salt = ByteArray(SALT_LENGTH_BYTES)
        secureRandom.nextBytes(salt)
        val derived = deriveKey(pin, salt, iterations, KEY_LENGTH_BITS)
        return try {
            HashedPinCredential(
                saltBase64 = Base64.getEncoder().encodeToString(salt),
                hashBase64 = Base64.getEncoder().encodeToString(derived),
                iterations = iterations,
            )
        } finally {
            derived.fill(0)
            salt.fill(0)
        }
    }

    /**
     * Convenience overload for [String] PIN input; copies into a temporary char array and
     * zeroes it immediately after hashing.
     */
    fun hashPin(
        pin: String,
        iterations: Int = DEFAULT_ITERATIONS,
    ): HashedPinCredential {
        val chars = pin.toCharArray()
        return try {
            hashPin(chars, iterations)
        } finally {
            chars.fill('\u0000')
        }
    }

    /**
     * Verifies [pin] against [credential] using constant-time byte comparison.
     */
    fun verifyPin(
        pin: CharArray,
        credential: HashedPinCredential,
    ): Boolean {
        if (pin.isEmpty() || credential.iterations <= 0) {
            return false
        }
        val salt = decodeBase64OrNull(credential.saltBase64) ?: return false
        val expectedHash = decodeBase64OrNull(credential.hashBase64) ?: return false
        if (salt.isEmpty() || expectedHash.isEmpty()) {
            return false
        }

        val candidateHash = deriveKey(pin, salt, credential.iterations, expectedHash.size * 8)
        return try {
            MessageDigest.isEqual(candidateHash, expectedHash)
        } finally {
            candidateHash.fill(0)
            expectedHash.fill(0)
            salt.fill(0)
        }
    }

    /**
     * Convenience overload for [String] PIN verification; zeroes the temporary char array.
     */
    fun verifyPin(
        pin: String,
        credential: HashedPinCredential,
    ): Boolean {
        val chars = pin.toCharArray()
        return try {
            verifyPin(chars, credential)
        } finally {
            chars.fill('\u0000')
        }
    }

    private fun deriveKey(
        pin: CharArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBits: Int,
    ): ByteArray {
        val spec = PBEKeySpec(pin, salt, iterations, keyLengthBits)
        return try {
            val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun decodeBase64OrNull(encoded: String): ByteArray? =
        try {
            Base64.getDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        const val PBKDF2_ALGORITHM: String = "PBKDF2WithHmacSHA256"
        const val DEFAULT_ITERATIONS: Int = 120_000
        const val SALT_LENGTH_BYTES: Int = 16
        const val KEY_LENGTH_BITS: Int = 256
    }
}
