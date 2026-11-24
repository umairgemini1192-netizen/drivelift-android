package com.drivelift.app.feature.automode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.interfaces.RSAPublicKey
import java.time.Instant

class SelfSignedX509GeneratorTest {

    @Test
    fun generatesValid2048BitRsaKeyPairAndVerifiableSelfSignedX509V3Certificate() {
        // Year 2026 -> notBefore uses UTCTime (2026) and notAfter uses GeneralizedTime (2051).
        val fixedNow = Instant.parse("2026-11-25T00:00:00Z").epochSecond
        val identity = SelfSignedX509Generator.generate(
            commonName = "DriveLift Auto Mode",
            nowEpochSeconds = fixedNow,
        )

        val rsaPub = identity.publicKey as RSAPublicKey
        assertEquals(2048, rsaPub.modulus.bitLength())
        assertEquals(3, identity.certificate.version)
        assertTrue(identity.certificate.subjectX500Principal.name.contains("CN=DriveLift Auto Mode"))
        assertEquals(identity.certificate.subjectX500Principal, identity.certificate.issuerX500Principal)

        // Signature must verify cleanly against the generated public key.
        identity.certificate.verify(identity.publicKey)
        assertTrue(identity.certificate.notAfter.toInstant().isAfter(Instant.ofEpochSecond(fixedNow)))
    }
}
