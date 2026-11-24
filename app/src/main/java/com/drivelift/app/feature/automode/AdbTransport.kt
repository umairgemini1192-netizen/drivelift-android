package com.drivelift.app.feature.automode

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Result of executing a shell command over DriveLift's embedded localhost ADB connection.
 */
data class AdbCommandResult(
    val exitCode: Int,
    val stdout: String,
) {
    val isSuccess: Boolean
        get() = exitCode == 0
}

/**
 * Wraps and parses single-command ADB shell executions so exit codes are reliably checked
 * even over ADB's legacy single-stream `shell:` service.
 */
object AdbShellOutputParser {
    private const val EXIT_MARKER = "__DRIVELIFT_EXIT__="

    fun wrapWithExitCode(command: String): String {
        val trimmed = command.trim()
        require(trimmed.isNotEmpty()) { "ADB shell command must not be blank." }
        require(trimmed.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "ADB shell command must be a single line without control characters."
        }
        return "$trimmed; echo $EXIT_MARKER\$?"
    }

    fun parse(rawOutput: String): Result<AdbCommandResult> {
        val normalized = rawOutput.replace("\r\n", "\n").replace("\r", "\n")
        val lines = normalized.split('\n')
        val markerIdx = lines.indexOfLast { it.trim().startsWith(EXIT_MARKER) }
        if (markerIdx < 0) {
            return Result.failure(
                IOException("ADB shell stream ended without exit status marker."),
            )
        }
        val codeText = lines[markerIdx].trim().removePrefix(EXIT_MARKER).trim()
        val exitCode = codeText.toIntOrNull()
            ?: return Result.failure(IOException("Invalid ADB shell exit code: '$codeText'."))

        val stdoutLines = lines.subList(0, markerIdx)
        val cleanStdout = stdoutLines.joinToString("\n").trimEnd()
        return Result.success(AdbCommandResult(exitCode = exitCode, stdout = cleanStdout))
    }
}

/**
 * Transport contract for DriveLift's embedded Wireless Debugging ADB client.
 * Implementations MUST communicate exclusively with `127.0.0.1` and MUST NEVER store or log
 * pairing codes.
 */
interface AdbTransport {
    suspend fun discoverPairingPort(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): Result<Int>
    suspend fun discoverConnectPort(timeoutMs: Long = DEFAULT_DISCOVERY_TIMEOUT_MS): Result<Int>
    suspend fun pair(port: Int, pairingCode: String): Result<Unit>
    suspend fun connect(port: Int): Result<Unit>
    suspend fun runShell(command: String): Result<AdbCommandResult>
    suspend fun disconnect()
    fun isConnected(): Boolean

    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val SERVICE_TYPE_TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val SERVICE_TYPE_TLS_CONNECT = "_adb-tls-connect._tcp"
        const val DEFAULT_DISCOVERY_TIMEOUT_MS = 8_000L
        const val DEFAULT_CONNECT_TIMEOUT_MS = 8_000L
    }
}

/**
 * Concrete [AbsAdbConnectionManager] pinned strictly to loopback `127.0.0.1`.
 */
private class DriveLiftAdbConnectionManager(
    private val identity: AdbIdentity,
    connectTimeoutMs: Long = AdbTransport.DEFAULT_CONNECT_TIMEOUT_MS,
) : AbsAdbConnectionManager() {

    init {
        setApi(Build.VERSION.SDK_INT)
        setHostAddress(AdbTransport.LOOPBACK_HOST)
        setTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS)
        setThrowOnUnauthorised(true)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    override fun getDeviceName(): String = identity.deviceName
}

/**
 * Production [AdbTransport] powered by `libadb-android` and Android's [NsdManager].
 * Always connects strictly to `127.0.0.1` and never logs or persists pairing codes.
 */
class LibAdbTransport(
    private val context: Context,
    private val keyStorage: AdbKeyStorage,
    private val connectTimeoutMs: Long = AdbTransport.DEFAULT_CONNECT_TIMEOUT_MS,
) : AdbTransport {

    private val lock = Any()

    @Volatile
    private var manager: DriveLiftAdbConnectionManager? = null

    private fun getOrCreateManager(): DriveLiftAdbConnectionManager = synchronized(lock) {
        val existing = manager
        if (existing != null) return existing
        val identity = keyStorage.loadOrCreateKeyPair()
        DriveLiftAdbConnectionManager(identity, connectTimeoutMs).also { manager = it }
    }

    override suspend fun discoverPairingPort(timeoutMs: Long): Result<Int> =
        discoverLocalPort(AdbTransport.SERVICE_TYPE_TLS_PAIRING, timeoutMs)

    override suspend fun discoverConnectPort(timeoutMs: Long): Result<Int> =
        discoverLocalPort(AdbTransport.SERVICE_TYPE_TLS_CONNECT, timeoutMs)

    override suspend fun pair(port: Int, pairingCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(port in 1..65535) { "Invalid pairing port: $port" }
            val digits = pairingCode.filter { it.isDigit() }
            require(digits.length == 6) { "Pairing code must be 6 digits." }
            val adb = getOrCreateManager()
            val paired = adb.pair(AdbTransport.LOOPBACK_HOST, port, digits)
            if (!paired) {
                throw IOException("Wireless debugging pairing was rejected.")
            }
        }
    }

    override suspend fun connect(port: Int): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(port in 1..65535) { "Invalid ADB port: $port" }
            val adb = getOrCreateManager()
            if (adb.isConnected) {
                runCatching { adb.disconnect() }
            }
            val connected = adb.connect(AdbTransport.LOOPBACK_HOST, port)
            if (!connected && !adb.isConnected) {
                throw IOException("Could not connect to localhost ADB daemon on port $port.")
            }
        }
    }

    override suspend fun runShell(command: String): Result<AdbCommandResult> = withContext(Dispatchers.IO) {
        runCatching {
            val adb = synchronized(lock) { manager }
                ?: throw IOException("Not connected to localhost ADB daemon.")
            if (!adb.isConnected) {
                throw IOException("Not connected to localhost ADB daemon.")
            }
            val wrapped = AdbShellOutputParser.wrapWithExitCode(command)
            val rawOutput = adb.openStream("shell:$wrapped").use { stream ->
                BufferedReader(InputStreamReader(stream.openInputStream(), Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }
            AdbShellOutputParser.parse(rawOutput).getOrThrow()
        }
    }

    override suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                runCatching { manager?.disconnect() }
            }
        }
    }

    fun resetSession() {
        synchronized(lock) {
            runCatching { manager?.disconnect() }
            manager = null
        }
    }

    override fun isConnected(): Boolean = synchronized(lock) {
        runCatching { manager?.isConnected == true }.getOrDefault(false)
    }

    private suspend fun discoverLocalPort(serviceType: String, timeoutMs: Long): Result<Int> =
        withContext(Dispatchers.IO) {
            val nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
                ?: return@withContext Result.failure(IOException("Android NsdManager is unavailable."))
            val localAddresses = collectLocalIpAddresses()
            val discoveredPort = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Int?> { continuation ->
                    val completed = AtomicBoolean(false)
                    val registeredCallbacks = CopyOnWriteArrayList<Any>()
                    val resolveExecutor = Executors.newSingleThreadExecutor()

                    fun finish(port: Int?) {
                        if (completed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(port)
                        }
                    }

                    fun matchesThisDevice(addresses: List<InetAddress>): Boolean {
                        if (addresses.isEmpty()) return true
                        return addresses.any { addr ->
                            addr.isLoopbackAddress || addr.hostAddress in localAddresses
                        }
                    }

                    val discoveryListener = object : NsdManager.DiscoveryListener {
                        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                            finish(null)
                        }

                        override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit

                        override fun onDiscoveryStarted(regType: String) = Unit

                        override fun onDiscoveryStopped(type: String) = Unit

                        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

                        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                            if (completed.get()) return
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                val callback = object : NsdManager.ServiceInfoCallback {
                                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit

                                    override fun onServiceUpdated(info: NsdServiceInfo) {
                                        if (info.port in 1..65535 && matchesThisDevice(info.hostAddresses)) {
                                            finish(info.port)
                                        }
                                    }

                                    override fun onServiceLost() = Unit

                                    override fun onServiceInfoCallbackUnregistered() = Unit
                                }
                                registeredCallbacks.add(callback)
                                runCatching {
                                    nsdManager.registerServiceInfoCallback(serviceInfo, resolveExecutor, callback)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                nsdManager.resolveService(
                                    serviceInfo,
                                    object : NsdManager.ResolveListener {
                                        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

                                        override fun onServiceResolved(info: NsdServiceInfo) {
                                            @Suppress("DEPRECATION")
                                            val host = info.host
                                            val addrs = listOfNotNull(host)
                                            if (info.port in 1..65535 && matchesThisDevice(addrs)) {
                                                finish(info.port)
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }

                    continuation.invokeOnCancellation {
                        cleanupDiscovery(nsdManager, discoveryListener, registeredCallbacks, resolveExecutor)
                    }

                    runCatching {
                        nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                    }.onFailure {
                        cleanupDiscovery(nsdManager, discoveryListener, registeredCallbacks, resolveExecutor)
                        finish(null)
                    }
                }
            }

            if (discoveredPort != null && discoveredPort in 1..65535) {
                Result.success(discoveredPort)
            } else {
                Result.failure(
                    IOException("Timed out waiting for local Wireless debugging service ($serviceType)."),
                )
            }
        }

    private fun cleanupDiscovery(
        nsdManager: NsdManager,
        discoveryListener: NsdManager.DiscoveryListener,
        callbacks: CopyOnWriteArrayList<Any>,
        executor: java.util.concurrent.ExecutorService,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            for (cb in callbacks) {
                (cb as? NsdManager.ServiceInfoCallback)?.let { typed ->
                    runCatching { nsdManager.unregisterServiceInfoCallback(typed) }
                }
            }
            callbacks.clear()
        }
        runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
        runCatching { executor.shutdownNow() }
    }

    private fun collectLocalIpAddresses(): Set<String> = runCatching {
        val addresses = mutableSetOf(AdbTransport.LOOPBACK_HOST, "::1")
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return addresses
        for (iface in Collections.list(interfaces)) {
            for (addr in Collections.list(iface.inetAddresses)) {
                addr.hostAddress?.substringBefore('%')?.let { addresses.add(it) }
            }
        }
        addresses
    }.getOrDefault(setOf(AdbTransport.LOOPBACK_HOST, "::1"))
}
