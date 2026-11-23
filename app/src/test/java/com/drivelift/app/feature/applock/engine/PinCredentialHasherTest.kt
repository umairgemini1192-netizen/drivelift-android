package com.drivelift.app.feature.applock.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinCredentialHasherTest {

    private val hasher = PinCredentialHasher()

    @Test
    fun hashAndVerify_succeedsForMatchingPinAndFailsForWrongPin() {
        val credential = hasher.hashPin("246810", iterations = 10_000)

        assertTrue(hasher.verifyPin("246810", credential))
        assertFalse(hasher.verifyPin("246811", credential))
        assertFalse(hasher.verifyPin("000000", credential))
        assertFalse(hasher.verifyPin("", credential))
    }

    @Test
    fun hashPin_usesUniqueRandomSaltPerCallAndNeverStoresPlaintext() {
        val pin = "135790"
        val first = hasher.hashPin(pin, iterations = 10_000)
        val second = hasher.hashPin(pin, iterations = 10_000)

        assertNotEquals(first.saltBase64, second.saltBase64)
        assertNotEquals(first.hashBase64, second.hashBase64)
        assertFalse(first.hashBase64.contains(pin))
        assertFalse(first.saltBase64.contains(pin))
        assertTrue(hasher.verifyPin(pin.toCharArray(), first))
        assertTrue(hasher.verifyPin(pin.toCharArray(), second))
    }

    @Test
    fun verifyPin_rejectsMalformedOrInvalidCredentialsSafely() {
        val valid = hasher.hashPin("4242", iterations = 10_000)

        assertFalse(
            hasher.verifyPin(
                "4242",
                valid.copy(saltBase64 = "!!!not-base64!!!"),
            ),
        )
        assertFalse(
            hasher.verifyPin(
                "4242",
                valid.copy(hashBase64 = "!!!not-base64!!!"),
            ),
        )
        assertFalse(
            hasher.verifyPin(
                "4242",
                valid.copy(iterations = 0),
            ),
        )
        assertFalse(
            hasher.verifyPin(
                "4242",
                valid.copy(hashBase64 = ""),
            ),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun hashPin_rejectsEmptyPin() {
        hasher.hashPin(charArrayOf())
    }
}
