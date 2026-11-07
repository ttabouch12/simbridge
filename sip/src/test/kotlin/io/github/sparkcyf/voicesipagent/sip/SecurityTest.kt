package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.sip.media.AudioCodec
import io.github.sparkcyf.voicesipagent.sip.media.Sdp
import io.github.sparkcyf.voicesipagent.sip.media.SdpCrypto
import io.github.sparkcyf.voicesipagent.sip.media.SrtpContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.util.Base64

class SecurityTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun srtpKeyDerivationMatchesRfc3711() {
        // RFC 3711 Appendix B.3
        val key = hex("E1F97A0D3E018BE0D64FA32C06DE4139")
        val salt = hex("0EC675AD498AFEEBB6960B3AABE6")
        assertArrayEquals(hex("C61E7A93744F39EE10734AFE3FF7A087"), SrtpContext.derive(key, salt, 0x00, 16))
        assertArrayEquals(hex("30CBBC08863D8C85D49DB34A9AE1"), SrtpContext.derive(key, salt, 0x02, 14))
        assertArrayEquals(hex("CEBE321F6FF7716B6FD4AB49AF256A156D38BAA4"), SrtpContext.derive(key, salt, 0x01, 20))
    }

    private fun rtp(seq: Int, payload: ByteArray, ssrc: Int = 0x12345678): ByteArray {
        val b = ByteArray(12 + payload.size + SrtpContext.TAG_LEN)
        b[0] = 0x80.toByte(); b[1] = 0
        b[2] = (seq shr 8).toByte(); b[3] = seq.toByte()
        b[8] = (ssrc shr 24).toByte(); b[9] = (ssrc shr 16).toByte(); b[10] = (ssrc shr 8).toByte(); b[11] = ssrc.toByte()
        System.arraycopy(payload, 0, b, 12, payload.size)
        return b
    }

    @Test
    fun srtpRoundTripTamperAndReplay() {
        val key = SrtpContext.newInlineKey()
        val tx = SrtpContext.fromInline(key)!!
        val rx = SrtpContext.fromInline(key)!!
        val payload = ByteArray(160) { (it * 7).toByte() }
        val p = rtp(100, payload)
        val n = tx.protect(p, 12 + payload.size)
        assertEquals(12 + payload.size + 10, n)
        assertFalse("payload must be encrypted", p.copyOfRange(12, 12 + payload.size).contentEquals(payload))
        val copy = p.copyOf()
        assertEquals(12 + payload.size, rx.unprotect(p, n))
        assertArrayEquals(payload, p.copyOfRange(12, 12 + payload.size))
        // Replay of the same packet is dropped.
        assertEquals(-1, rx.unprotect(copy.copyOf(), n))
        // Tampering is detected.
        val p2 = rtp(101, payload); val n2 = tx.protect(p2, 12 + payload.size)
        p2[20] = (p2[20].toInt() xor 1).toByte()
        assertEquals(-1, rx.unprotect(p2, n2))
        // Wrong key is rejected.
        val other = SrtpContext.fromInline(SrtpContext.newInlineKey())!!
        val p3 = rtp(102, payload); val n3 = tx.protect(p3, 12 + payload.size)
        assertEquals(-1, other.unprotect(p3, n3))
    }

    @Test
    fun srtpSurvivesSequenceWrapAndReordering() {
        val key = SrtpContext.newInlineKey()
        val tx = SrtpContext.fromInline(key)!!
        val rx = SrtpContext.fromInline(key)!!
        val payload = ByteArray(20) { it.toByte() }
        val packets = ArrayList<Pair<ByteArray, Int>>()
        var seq = 65530
        repeat(12) {
            val p = rtp(seq and 0xffff, payload)
            packets += p to tx.protect(p, 12 + payload.size)
            seq++
        }
        // Deliver with two packets swapped around the wrap.
        val order = (0 until 12).toMutableList().also { java.util.Collections.swap(it, 5, 6) }
        for (i in order) {
            val (p, n) = packets[i]
            assertEquals("packet $i", 12 + payload.size, rx.unprotect(p, n))
            assertArrayEquals(payload, p.copyOfRange(12, 12 + payload.size))
        }
    }

    @Test
    fun sdpCarriesCryptoAndProfile() {
        val key = SrtpContext.newInlineKey()
        val text = Sdp.build(1, 1, "10.0.0.2", 4000, listOf(0 to AudioCodec.PCMU), 101, "sendrecv", SdpCrypto(1, key))
        assertTrue(text.contains("RTP/SAVP"))
        val sdp = Sdp.parse(text)!!
        assertTrue(sdp.secure)
        assertEquals(SdpCrypto(1, key), sdp.crypto)
        val plain = Sdp.parse(Sdp.build(1, 1, "10.0.0.2", 4000, listOf(0 to AudioCodec.PCMU), 101))!!
        assertFalse(plain.secure)
        assertEquals(null, plain.crypto)
        val optional = Sdp.build(1, 1, "10.0.0.2", 4000, listOf(0 to AudioCodec.PCMU), 101, "sendrecv", SdpCrypto(1, key), savp = false)
        assertTrue(optional.contains("RTP/AVP 0") && optional.contains("a=crypto:1"))
    }

    @Test
    fun streamFramingHandlesKeepAlivesAndBodies() {
        val m1 = "MESSAGE sip:a@b SIP/2.0\r\nContent-Length: 5\r\n\r\nhello"
        val m2 = "SIP/2.0 200 OK\r\nl: 0\r\n\r\n"
        val r = SipStreamReader(ByteArrayInputStream(("\r\n\r\n" + m1 + "\r\n" + m2).toByteArray()))
        var pings = 0
        val a = r.next { if (it) pings++ }!!
        assertEquals(m1, String(a))
        val b = r.next { }!!
        assertEquals(m2, String(b))
        assertEquals(null, r.next { })
        assertEquals(1, pings)
        val parsed = SipParser.parse(a, a.size) as SipRequest
        assertEquals("hello", parsed.bodyText)
    }

    @Test
    fun lanFilterAcceptsOnlyPrivateAddresses() {
        for (a in listOf("192.168.1.5", "10.58.140.103", "172.20.0.1", "100.100.1.1", "fe80::1", "fd12::1", "127.0.0.1")) {
            assertTrue(a, LanSourceFilter.isPrivate(InetAddress.getByName(a)))
        }
        for (a in listOf("8.8.8.8", "2a02:ff0::1", "100.128.0.1", "172.32.0.1")) {
            assertFalse(a, LanSourceFilter.isPrivate(InetAddress.getByName(a)))
        }
        assertTrue(LanSourceFilter.notMobile("wlan0"))
        assertTrue(LanSourceFilter.notMobile("swlan0"))
        assertFalse(LanSourceFilter.notMobile("rmnet_data0"))
        assertFalse(LanSourceFilter.notMobile("ccmni1"))
    }

    @Test
    fun pinsAreStableBase64Url() {
        val k = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        assertNotNull(TlsPinning.clientContext(k))
        assertEquals(43, k.length)
    }
}
