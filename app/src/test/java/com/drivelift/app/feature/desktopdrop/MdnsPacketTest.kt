package com.drivelift.app.feature.desktopdrop

import com.drivelift.app.feature.desktopdrop.network.DropMdnsResponder
import com.drivelift.app.feature.desktopdrop.network.MdnsPacket
import com.drivelift.app.feature.desktopdrop.network.MulticastLockPort
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/** mDNS packet framing for drivelift.local and the multicast lock lifecycle. */
class MdnsPacketTest {

    private fun u16(out: ByteArrayOutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun query(vararg questions: Triple<String, Int, Int>, flags: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        u16(out, 0x1234)
        u16(out, flags)
        u16(out, questions.size)
        u16(out, 0)
        u16(out, 0)
        u16(out, 0)
        for ((name, type, cls) in questions) {
            out.write(MdnsPacket.encodeName(name))
            u16(out, type)
            u16(out, cls)
        }
        return out.toByteArray()
    }

    @Test
    fun aQueryForTheHostIsRecognisedAndAnsweredWithTheAddress() {
        val packet = query(Triple("drivelift.local", MdnsPacket.TYPE_A, 1))
        val questions = MdnsPacket.parseQuestions(packet)
        assertEquals(1, questions.size)
        assertEquals("drivelift.local", questions[0].name)
        assertEquals(MdnsPacket.TYPE_A, questions[0].type)
        assertFalse(questions[0].wantsUnicast)
        assertEquals(MdnsPacket.Reply.ADDRESS, MdnsPacket.replyFor(questions, "drivelift.local"))
    }

    @Test
    fun nameMatchingIgnoresCaseAndTheUnicastBitIsReported() {
        val packet = query(Triple("DriveLift.LOCAL", MdnsPacket.TYPE_ANY, 0x8001))
        val questions = MdnsPacket.parseQuestions(packet)
        assertEquals(1, questions.size)
        assertTrue(questions[0].wantsUnicast)
        assertEquals(MdnsPacket.Reply.ADDRESS, MdnsPacket.replyFor(questions, "drivelift.local"))
    }

    @Test
    fun otherNamesAndForeignClassesGetNoReplyAndIpv6GetsANegativeAnswer() {
        val other = MdnsPacket.parseQuestions(query(Triple("printer.local", MdnsPacket.TYPE_A, 1)))
        assertEquals(MdnsPacket.Reply.NONE, MdnsPacket.replyFor(other, "drivelift.local"))

        val chaos = MdnsPacket.parseQuestions(query(Triple("drivelift.local", MdnsPacket.TYPE_A, 3)))
        assertTrue("Class CH is ignored", chaos.isEmpty())

        val v6 = MdnsPacket.parseQuestions(query(Triple("drivelift.local", MdnsPacket.TYPE_AAAA, 1)))
        assertEquals(MdnsPacket.Reply.NO_IPV6, MdnsPacket.replyFor(v6, "drivelift.local"))

        val mixed = MdnsPacket.parseQuestions(
            query(
                Triple("drivelift.local", MdnsPacket.TYPE_AAAA, 1),
                Triple("drivelift.local", MdnsPacket.TYPE_A, 1),
            ),
        )
        assertEquals("A wins over AAAA", MdnsPacket.Reply.ADDRESS, MdnsPacket.replyFor(mixed, "drivelift.local"))
    }

    @Test
    fun responsesAndBrokenPacketsAreIgnoredWithoutThrowing() {
        val response = query(Triple("drivelift.local", MdnsPacket.TYPE_A, 1), flags = 0x8400)
        assertTrue(MdnsPacket.parseQuestions(response).isEmpty())

        val good = query(Triple("drivelift.local", MdnsPacket.TYPE_A, 1))
        for (cut in 0 until good.size) {
            // Every truncation must be survivable and must not invent a question.
            val parsed = MdnsPacket.parseQuestions(good, cut)
            assertTrue("cut=$cut", parsed.isEmpty())
        }
        val noise = ByteArray(64) { (it * 37 + 11).toByte() }
        MdnsPacket.parseQuestions(noise)
        // A name pointer that points at itself must stop, not loop.
        val loop = ByteArray(18)
        loop[5] = 1
        loop[12] = 0xC0.toByte()
        loop[13] = 12
        assertTrue(MdnsPacket.parseQuestions(loop).isEmpty())
    }

    @Test
    fun compressedNamesAreFollowed() {
        val out = ByteArrayOutputStream()
        u16(out, 1)
        u16(out, 0)
        u16(out, 2)
        u16(out, 0)
        u16(out, 0)
        u16(out, 0)
        out.write(MdnsPacket.encodeName("drivelift.local"))
        u16(out, MdnsPacket.TYPE_AAAA)
        u16(out, 1)
        out.write(0xC0)
        out.write(12)
        u16(out, MdnsPacket.TYPE_A)
        u16(out, 1)
        val questions = MdnsPacket.parseQuestions(out.toByteArray())
        assertEquals(2, questions.size)
        assertEquals("drivelift.local", questions[1].name)
        assertEquals(MdnsPacket.TYPE_A, questions[1].type)
    }

    @Test
    fun theAddressAnswerHasTheExactWireLayout() {
        val ip = byteArrayOf(192.toByte(), 168.toByte(), 1, 44)
        val packet = MdnsPacket.buildAddressAnswer("drivelift.local", ip, ttlSeconds = 120)
        val name = MdnsPacket.encodeName("drivelift.local")
        assertEquals(17, name.size)
        assertEquals(12 + name.size + 14, packet.size)
        assertArrayEquals(byteArrayOf(0, 0, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0), packet.copyOfRange(0, 12))
        assertArrayEquals(name, packet.copyOfRange(12, 12 + name.size))
        val tail = packet.copyOfRange(12 + name.size, packet.size)
        assertArrayEquals(
            byteArrayOf(0, 1, 0x80.toByte(), 1, 0, 0, 0, 120, 0, 4, 192.toByte(), 168.toByte(), 1, 44),
            tail,
        )
    }

    @Test
    fun theNoIpv6AnswerIsAnNsecThatListsOnlyTheARecord() {
        val packet = MdnsPacket.buildNoIpv6Answer("drivelift.local", ttlSeconds = 120)
        val name = MdnsPacket.encodeName("drivelift.local")
        assertEquals(12 + name.size + 10 + name.size + 3, packet.size)
        val typeAt = 12 + name.size
        assertEquals(MdnsPacket.TYPE_NSEC, ((packet[typeAt].toInt() and 0xFF) shl 8) or (packet[typeAt + 1].toInt() and 0xFF))
        assertArrayEquals(byteArrayOf(0x00, 0x01, 0x40), packet.copyOfRange(packet.size - 3, packet.size))
    }

    @Test
    fun badLabelsAreRejectedWhenEncoding() {
        try {
            MdnsPacket.encodeName("drivelift..local")
            fail("An empty label must be rejected")
        } catch (_: IllegalArgumentException) {
        }
        try {
            MdnsPacket.encodeName("a".repeat(64) + ".local")
            fail("A 64 byte label must be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    private class FakeLock : MulticastLockPort {
        var held = false
        var acquires = 0
        var releases = 0
        override fun acquire() {
            held = true
            acquires++
        }

        override fun release() {
            held = false
            releases++
        }
    }

    @Test
    fun theMulticastLockIsHeldOnlyWhileTheResponderRuns() {
        val lock = FakeLock()
        val responder = DropMdnsResponder(lock, port = 0)
        assertFalse(lock.held)
        assertEquals(0, lock.acquires)

        val started = responder.start("127.0.0.1")
        assertEquals("Lock is taken once on start", 1, lock.acquires)
        if (started) {
            assertTrue(lock.held)
            assertTrue(responder.isRunning)
        } else {
            // This machine cannot join a multicast group: the lock must already be given back.
            assertFalse(lock.held)
        }

        responder.stop()
        assertFalse("Lock is released on stop", lock.held)
        assertFalse(responder.isRunning)
        responder.stop()
        assertEquals("Stopping twice releases once", 1, lock.releases)
        assertEquals(lock.acquires, lock.releases)
    }
}
