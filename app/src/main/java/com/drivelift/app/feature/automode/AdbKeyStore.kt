package com.drivelift.app.feature.automode

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * DriveLift's ADB client authentication identity (2048-bit RSA key pair + self-signed X.509 cert).
 */
data class AdbIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val deviceName: String = DEFAULT_DEVICE_NAME,
) {
    companion object {
        const val DEFAULT_DEVICE_NAME = "DriveLift"
    }
}

/**
 * Storage contract for DriveLift's embedded ADB RSA identity.
 */
interface AdbKeyStorage {
    fun hasKeyPair(): Boolean
    fun loadKeyPair(): AdbIdentity?
    fun loadOrCreateKeyPair(): AdbIdentity
    fun deleteKeyPair()
}

/**
 * Stores DriveLift's 2048-bit RSA ADB key pair in app-private no-backup storage encrypted at rest
 * with an Android Keystore AES-256-GCM wrapping key.
 *
 * Wrapping the RSA key with an Android Keystore master key keeps the private key hardware-protected
 * at rest while remaining directly usable by Conscrypt TLSv1.3 during Wireless Debugging pairing
 * and local ADB connections on Android 11-17.
 */
class AndroidKeystoreAdbKeyStorage(
    private val context: Context,
    private val deviceName: String = AdbIdentity.DEFAULT_DEVICE_NAME,
) : AdbKeyStorage {

    private val storageDir: File
        get() = File(context.noBackupFilesDir, DIR_NAME).apply { mkdirs() }

    private val encryptedKeyFile: File
        get() = File(storageDir, PRIVATE_KEY_FILE)

    private val certificateFile: File
        get() = File(storageDir, CERTIFICATE_FILE)

    @Synchronized
    override fun hasKeyPair(): Boolean {
        return encryptedKeyFile.isFile &&
            certificateFile.isFile &&
            loadWrappingKeyOrNull() != null
    }

    @Synchronized
    override fun loadKeyPair(): AdbIdentity? {
        if (!hasKeyPair()) return null
        return runCatching {
            val wrappingKey = loadWrappingKeyOrNull() ?: return null
            val wrappedBytes = encryptedKeyFile.readBytes()
            val pkcs8Bytes = decrypt(wrappingKey, wrappedBytes)
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))
            pkcs8Bytes.fill(0)

            val certBytes = certificateFile.readBytes()
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            AdbIdentity(
                privateKey = privateKey,
                certificate = cert,
                deviceName = deviceName,
            )
        }.getOrNull()
    }

    @Synchronized
    override fun loadOrCreateKeyPair(): AdbIdentity {
        loadKeyPair()?.let { return it }
        deleteKeyPair()

        val generated = SelfSignedX509Generator.generate(commonName = "DriveLift Auto Mode")
        val wrappingKey = getOrCreateWrappingKey()
        val pkcs8Bytes = requireNotNull(generated.privateKey.encoded) {
            "RSA private key encoding unavailable."
        }
        val encrypted = encrypt(wrappingKey, pkcs8Bytes)
        pkcs8Bytes.fill(0)

        storageDir.mkdirs()
        encryptedKeyFile.writeBytes(encrypted)
        certificateFile.writeBytes(generated.certificate.encoded)

        return AdbIdentity(
            privateKey = generated.privateKey,
            certificate = generated.certificate,
            deviceName = deviceName,
        )
    }

    @Synchronized
    override fun deleteKeyPair() {
        runCatching {
            if (encryptedKeyFile.exists()) encryptedKeyFile.delete()
        }
        runCatching {
            if (certificateFile.exists()) certificateFile.delete()
        }
        runCatching {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEYSTORE_ALIAS)) {
                ks.deleteEntry(KEYSTORE_ALIAS)
            }
        }
    }

    private fun loadWrappingKeyOrNull(): SecretKey? = runCatching {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun getOrCreateWrappingKey(): SecretKey {
        loadWrappingKeyOrNull()?.let { return it }
        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    private fun encrypt(key: SecretKey, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(4 + iv.size + ciphertext.size)
            .putInt(iv.size)
            .put(iv)
            .put(ciphertext)
            .array()
    }

    private fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        val buffer = ByteBuffer.wrap(blob)
        val ivLength = buffer.int
        require(ivLength in 12..32) { "Invalid IV length: $ivLength" }
        val iv = ByteArray(ivLength)
        buffer.get(iv)
        val ciphertext = ByteArray(buffer.remaining())
        buffer.get(ciphertext)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEYSTORE_ALIAS = "drivelift_adb_wrap_v1"
        const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val DIR_NAME = "adb_auto"
        const val PRIVATE_KEY_FILE = "private.key.enc"
        const val CERTIFICATE_FILE = "certificate.der"
    }
}
