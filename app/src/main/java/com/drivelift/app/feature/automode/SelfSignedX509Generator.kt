package com.drivelift.app.feature.automode

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure Kotlin generator for a 2048-bit RSA key pair and RFC 5280 self-signed X.509 v3 certificate
 * used to authenticate DriveLift's embedded Wireless Debugging ADB client.
 */
object SelfSignedX509Generator {

    private const val RSA_KEY_BITS = 2048
    private const val VALIDITY_SECONDS = 25L * 365L * 24L * 3600L
    private const val CLOCK_SKEW_LEEWAY_SECONDS = 300L

    private val UTC_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
    private val GENERALIZED_TIME_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

    // sha256WithRSAEncryption: 1.2.840.113549.1.1.11
    private val OID_SHA256_WITH_RSA = byteArrayOf(
        0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B,
    )

    // commonName (CN): 2.5.4.3
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    // subjectKeyIdentifier: 2.5.29.14
    private val OID_SUBJECT_KEY_ID = byteArrayOf(0x55, 0x1D, 0x0E)

    // basicConstraints: 2.5.29.19
    private val OID_BASIC_CONSTRAINTS = byteArrayOf(0x55, 0x1D, 0x13)

    data class GeneratedIdentity(
        val privateKey: PrivateKey,
        val publicKey: PublicKey,
        val certificate: X509Certificate,
    )

    fun generate(
        commonName: String = "DriveLift Auto Mode",
        nowEpochSeconds: Long = Instant.now().epochSecond,
        random: SecureRandom = SecureRandom(),
    ): GeneratedIdentity {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_BITS, random)
        val keyPair = generator.generateKeyPair()
        val cert = buildSelfSignedCertificate(
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            commonName = commonName,
            notBefore = Instant.ofEpochSecond(nowEpochSeconds - CLOCK_SKEW_LEEWAY_SECONDS),
            notAfter = Instant.ofEpochSecond(nowEpochSeconds + VALIDITY_SECONDS),
            random = random,
        )
        return GeneratedIdentity(
            privateKey = keyPair.private,
            publicKey = keyPair.public,
            certificate = cert,
        )
    }

    internal fun buildSelfSignedCertificate(
        privateKey: PrivateKey,
        publicKey: PublicKey,
        commonName: String,
        notBefore: Instant,
        notAfter: Instant,
        random: SecureRandom = SecureRandom(),
    ): X509Certificate {
        val serialBytes = ByteArray(16).also { random.nextBytes(it) }
        // Force a positive, non-zero 127-bit serial number.
        serialBytes[0] = ((serialBytes[0].toInt() and 0x7F) or 0x01).toByte()
        val serial = BigInteger(1, serialBytes)

        val sigAlgId = derSequence(derOid(OID_SHA256_WITH_RSA), derNull())
        val name = buildCommonName(commonName)
        val validity = derSequence(derTime(notBefore), derTime(notAfter))
        val spki = requireNotNull(publicKey.encoded) { "RSA public key encoding unavailable." }
        val extensions = buildExtensions(spki)

        val tbsCertificate = derSequence(
            // [0] EXPLICIT Version ::= INTEGER { v3(2) }
            derExplicitTag(0, derInteger(BigInteger.valueOf(2))),
            derInteger(serial),
            sigAlgId,
            name,
            validity,
            name,
            spki,
            // [3] EXPLICIT Extensions
            derExplicitTag(3, extensions),
        )

        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey, random)
        signer.update(tbsCertificate)
        val signatureBytes = signer.sign()

        val certDer = derSequence(
            tbsCertificate,
            sigAlgId,
            derBitString(signatureBytes),
        )

        val factory = CertificateFactory.getInstance("X.509")
        return factory.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    private fun buildCommonName(commonName: String): ByteArray {
        val attrTypeAndValue = derSequence(
            derOid(OID_COMMON_NAME),
            derUtf8String(commonName),
        )
        return derSequence(derSet(attrTypeAndValue))
    }

    private fun buildExtensions(spki: ByteArray): ByteArray {
        val skiHash = MessageDigest.getInstance("SHA-256").digest(spki).copyOf(20)
        val skiExtension = derSequence(
            derOid(OID_SUBJECT_KEY_ID),
            derOctetString(derOctetString(skiHash)),
        )
        val basicConstraintsExtension = derSequence(
            derOid(OID_BASIC_CONSTRAINTS),
            derOctetString(derSequence()),
        )
        return derSequence(skiExtension, basicConstraintsExtension)
    }

    private fun derTime(instant: Instant): ByteArray {
        val year = instant.atZone(ZoneOffset.UTC).year
        return if (year in 1950..2049) {
            derTag(0x17, UTC_TIME_FORMAT.format(instant).toByteArray(Charsets.US_ASCII))
        } else {
            derTag(0x18, GENERALIZED_TIME_FORMAT.format(instant).toByteArray(Charsets.US_ASCII))
        }
    }

    private fun derInteger(value: BigInteger): ByteArray = derTag(0x02, value.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size + 1)
        out[0] = 0x00 // zero unused bits
        System.arraycopy(bytes, 0, out, 1, bytes.size)
        return derTag(0x03, out)
    }

    private fun derOctetString(bytes: ByteArray): ByteArray = derTag(0x04, bytes)

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derOid(encodedOid: ByteArray): ByteArray = derTag(0x06, encodedOid)

    private fun derUtf8String(text: String): ByteArray = derTag(0x0C, text.toByteArray(Charsets.UTF_8))

    private fun derSequence(vararg items: ByteArray): ByteArray = derTag(0x30, concat(*items))

    private fun derSet(vararg items: ByteArray): ByteArray = derTag(0x31, concat(*items))

    private fun derExplicitTag(tagNumber: Int, inner: ByteArray): ByteArray =
        derTag(0xA0 or (tagNumber and 0x1F), inner)

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(content.size + 8)
        out.write(tag)
        writeDerLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun writeDerLength(out: ByteArrayOutputStream, length: Int) {
        if (length < 0x80) {
            out.write(length)
            return
        }
        val bytes = mutableListOf<Byte>()
        var remaining = length
        while (remaining > 0) {
            bytes.add((remaining and 0xFF).toByte())
            remaining = remaining ushr 8
        }
        out.write(0x80 or bytes.size)
        for (i in bytes.lastIndex downTo 0) {
            out.write(bytes[i].toInt() and 0xFF)
        }
    }

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val total = chunks.sumOf { it.size }
        val result = ByteArray(total)
        var offset = 0
        for (chunk in chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.size)
            offset += chunk.size
        }
        return result
    }
}
