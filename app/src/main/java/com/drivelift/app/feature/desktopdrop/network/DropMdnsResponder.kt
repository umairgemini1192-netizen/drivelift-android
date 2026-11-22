package com.drivelift.app.feature.desktopdrop.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

/** Wire format of the few mDNS (RFC 6762) messages the Drop responder reads and writes. */
object MdnsPacket {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_NSEC = 47
    const val TYPE_ANY = 255
    const val CLASS_IN = 1
    private const val CLASS_ANY = 255
    private const val CACHE_FLUSH = 0x8000
    private const val UNICAST_BIT = 0x8000
    private const val HEADER_BYTES = 12
    private const val FLAGS_RESPONSE_AUTHORITATIVE = 0x8400
    private const val MAX_POINTER_JUMPS = 8
    private const val MAX_LABEL = 63

    data class Question(val name: String, val type: Int, val wantsUnicast: Boolean)

    /** What to answer for a query. */
    enum class Reply { NONE, ADDRESS, NO_IPV6 }

    /**
     * The questions of a query packet. A response, a truncated packet or garbage gives an empty
     * list; nothing here throws on malformed input.
     */
    fun parseQuestions(data: ByteArray, length: Int = data.size): List<Question> {
        val end = minOf(length, data.size)
        if (end < HEADER_BYTES) return emptyList()
        val flags = u16(data, 2)
        if (flags and 0x8000 != 0) return emptyList()
        val count = u16(data, 4)
        if (count == 0 || count > 32) return emptyList()
        var pos = HEADER_BYTES
        val out = ArrayList<Question>(count)
        repeat(count) {
            val (name, next) = readName(data, end, pos) ?: return out
            if (next + 4 > end) return out
            val type = u16(data, next)
            val cls = u16(data, next + 2)
            val classCode = cls and 0x7FFF
            if (classCode == CLASS_IN || classCode == CLASS_ANY) {
                out += Question(name, type, cls and UNICAST_BIT != 0)
            }
            pos = next + 4
        }
        return out
    }

    /** What this responder should send for [questions] when it owns [hostName]. */
    fun replyFor(questions: List<Question>, hostName: String): Reply {
        val mine = questions.filter { it.name.equals(hostName, ignoreCase = true) }
        return when {
            mine.any { it.type == TYPE_A || it.type == TYPE_ANY } -> Reply.ADDRESS
            mine.any { it.type == TYPE_AAAA } -> Reply.NO_IPV6
            else -> Reply.NONE
        }
    }

    /** An unsolicited-style answer: one A record for [hostName], cache-flush set. */
    fun buildAddressAnswer(hostName: String, ipv4: ByteArray, ttlSeconds: Int = 120): ByteArray {
        require(ipv4.size == 4) { "IPv4 address must be 4 bytes" }
        val out = ByteArrayOutputStream()
        writeHeader(out, answers = 1)
        out.write(encodeName(hostName))
        writeU16(out, TYPE_A)
        writeU16(out, CLASS_IN or CACHE_FLUSH)
        writeU32(out, ttlSeconds)
        writeU16(out, ipv4.size)
        out.write(ipv4)
        return out.toByteArray()
    }

    /** NSEC saying [hostName] has no AAAA record (only A), so IPv6 lookups end at once. */
    fun buildNoIpv6Answer(hostName: String, ttlSeconds: Int = 120): ByteArray {
        val name = encodeName(hostName)
        val bitmap = byteArrayOf(0x00, 0x01, 0x40) // window 0, 1 byte, bit for type 1 (A)
        val out = ByteArrayOutputStream()
        writeHeader(out, answers = 1)
        out.write(name)
        writeU16(out, TYPE_NSEC)
        writeU16(out, CLASS_IN or CACHE_FLUSH)
        writeU32(out, ttlSeconds)
        writeU16(out, name.size + bitmap.size)
        out.write(name)
        out.write(bitmap)
        return out.toByteArray()
    }

    fun encodeName(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (label in name.trim('.').split('.')) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            require(bytes.isNotEmpty() && bytes.size <= MAX_LABEL) { "Bad DNS label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        return out.toByteArray()
    }

    private fun writeHeader(out: ByteArrayOutputStream, answers: Int) {
        writeU16(out, 0) // id is zero in multicast responses
        writeU16(out, FLAGS_RESPONSE_AUTHORITATIVE)
        writeU16(out, 0)
        writeU16(out, answers)
        writeU16(out, 0)
        writeU16(out, 0)
    }

    private fun readName(data: ByteArray, end: Int, start: Int): Pair<String, Int>? {
        val labels = ArrayList<String>()
        var pos = start
        var resume = -1
        var jumps = 0
        while (true) {
            if (pos >= end) return null
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> {
                    return labels.joinToString(".").lowercase() to (if (resume >= 0) resume else pos + 1)
                }
                len and 0xC0 == 0xC0 -> {
                    if (pos + 1 >= end) return null
                    if (++jumps > MAX_POINTER_JUMPS) return null
                    if (resume < 0) resume = pos + 2
                    pos = ((len and 0x3F) shl 8) or (data[pos + 1].toInt() and 0xFF)
                }
                len and 0xC0 != 0 -> return null
                else -> {
                    if (pos + 1 + len > end) return null
                    labels += String(data, pos + 1, len, Charsets.US_ASCII)
                    pos += 1 + len
                }
            }
        }
    }

    private fun u16(data: ByteArray, at: Int): Int =
        ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)

    private fun writeU16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        writeU16(out, (v ushr 16) and 0xFFFF)
        writeU16(out, v and 0xFFFF)
    }
}

/** Holds Wi-Fi multicast reception open. Real one wraps WifiManager.MulticastLock. */
interface MulticastLockPort {
    fun acquire()
    fun release()
}

class AndroidMulticastLock(context: Context) : MulticastLockPort {
    private val appContext = context.applicationContext ?: context
    private var lock: WifiManager.MulticastLock? = null

    @Synchronized
    override fun acquire() {
        val current = lock ?: appContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("DriveLift:DropMdns")
            ?.apply { setReferenceCounted(false) }
            ?.also { lock = it }
        if (current != null && !current.isHeld) runCatching { current.acquire() }
    }

    @Synchronized
    override fun release() {
        lock?.let { held -> runCatching { if (held.isHeld) held.release() } }
        lock = null
    }
}

/**
 * Answers mDNS queries for a single-label name such as drivelift.local with the phone's current
 * IPv4 address, so a bookmark to http://drivelift.local:8080 keeps working when the router hands
 * the phone a new address. The multicast lock is held only between [start] and [stop].
 */
class DropMdnsResponder(
    private val lock: MulticastLockPort,
    private val hostName: String = HOST_NAME,
    private val port: Int = MDNS_PORT,
    private val groupAddress: String = MDNS_GROUP,
) {
    private val lockHeld = AtomicBoolean(false)

    @Volatile
    private var socket: MulticastSocket? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var answer: ByteArray = ByteArray(0)
    private val noIpv6: ByteArray = MdnsPacket.buildNoIpv6Answer(hostName)

    val isRunning: Boolean get() = socket != null

    /** True when listening. On false the lock is already released again. */
    @Synchronized
    fun start(ipv4: String): Boolean {
        stop()
        return try {
            // Acquiring is inside the guard: a lock that throws must end in "false", not a crash.
            if (lockHeld.compareAndSet(false, true)) lock.acquire()
            val ip = InetAddress.getByName(ipv4).address
            require(ip.size == 4) { "Not an IPv4 address" }
            answer = MdnsPacket.buildAddressAnswer(hostName, ip)
            val group = InetAddress.getByName(groupAddress)
            val iface = interfaceFor(ipv4)
            val sock = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(port))
                timeToLive = MDNS_TTL
                if (iface != null) networkInterface = iface
                joinGroup(InetSocketAddress(group, 0), iface)
            }
            socket = sock
            worker = Thread({ listen(sock, group) }, "DriveLift-mDNS").apply {
                isDaemon = true
                start()
            }
            true
        } catch (e: Throwable) {
            warn("mDNS responder could not start: ${e.message}")
            runCatching { stop() }
            false
        }
    }

    /** Idempotent. Always leaves the multicast lock released. */
    @Synchronized
    fun stop() {
        val sock = socket
        socket = null
        worker = null
        if (sock != null) runCatching { sock.close() }
        if (lockHeld.compareAndSet(true, false)) runCatching { lock.release() }
    }

    private fun listen(sock: MulticastSocket, group: InetAddress) {
        // A thread that dies with an uncaught Throwable would take the whole app process with it.
        try {
            listenLoop(sock, group)
        } catch (e: Throwable) {
            warn("mDNS listener stopped: ${e.message}")
        }
    }

    private fun listenLoop(sock: MulticastSocket, group: InetAddress) {
        val buffer = ByteArray(MAX_PACKET)
        runCatching { send(sock, answer, group, port) }
        while (!sock.isClosed) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                sock.receive(packet)
                val questions = MdnsPacket.parseQuestions(packet.data, packet.length)
                val reply = when (MdnsPacket.replyFor(questions, hostName)) {
                    MdnsPacket.Reply.ADDRESS -> answer
                    MdnsPacket.Reply.NO_IPV6 -> noIpv6
                    MdnsPacket.Reply.NONE -> continue
                }
                send(sock, reply, group, port)
                if (questions.any { it.wantsUnicast }) {
                    send(sock, reply, packet.address, packet.port)
                }
            } catch (e: Exception) {
                if (sock.isClosed) break
                warn("mDNS receive failed: ${e.message}")
            }
        }
    }

    private fun warn(message: String) {
        // Plain JVM unit tests have no android.util.Log.
        runCatching { Log.w(TAG, message) }
    }

    private fun send(sock: MulticastSocket, data: ByteArray, to: InetAddress, toPort: Int) {
        if (data.isEmpty()) return
        sock.send(DatagramPacket(data, data.size, to, toPort))
    }

    private fun interfaceFor(ipv4: String): NetworkInterface? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).firstOrNull { nif ->
            Collections.list(nif.inetAddresses).any { it.hostAddress == ipv4 }
        }
    }.getOrNull()

    companion object {
        const val HOST_NAME = "drivelift.local"
        const val MDNS_PORT = 5353
        const val MDNS_GROUP = "224.0.0.251"
        private const val MDNS_TTL = 255
        private const val MAX_PACKET = 1500
        private const val TAG = "DropMdns"
    }
}
