package io.github.sparkcyf.voicesipagent.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MmsPduTest {
    @Test
    fun sendReqRoundTrips() {
        val img = ByteArray(5000) { (it % 251).toByte() }
        val p = MmsPdu.parse(MmsPdu.sendReq(listOf("+905551112233", "0555 222 33 44"), "Merhaba ğüş 😀",
            listOf(MmsPdu.Part("image/jpeg", img)), "T1234", "Konu"))
        assertEquals(MmsPdu.SEND_REQ, p.type)
        assertEquals("T1234", p.transactionId)
        assertEquals(listOf("+905551112233", "05552223344"), p.to)
        assertEquals("Konu", p.subject)
        assertEquals("Merhaba ğüş 😀", p.text)
        assertEquals(1, p.media.size)
        assertArrayEquals(img, p.media[0].data)
        assertTrue(p.parts.any { it.isSmil })
    }

    @Test
    fun parsesCarrierNotification() {
        val from = "+905551112233/TYPE=PLMN".toByteArray() + 0
        val pdu = byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0x98.toByte()) + "tx42".toByteArray() +
            byteArrayOf(0, 0x8D.toByte(), 0x92.toByte(), 0x89.toByte(), (1 + from.size).toByte(), 0x80.toByte()) + from +
            byteArrayOf(0x8A.toByte(), 0x80.toByte(), 0x8E.toByte(), 2, 0x1F, 0x40,
                0x88.toByte(), 5, 0x81.toByte(), 3, 0x01, 0x51, 0x80.toByte(), 0x83.toByte()) +
            "http://mms.example/abc".toByteArray() + 0
        val n = MmsPdu.parse(pdu)
        assertEquals(MmsPdu.NOTIFICATION_IND, n.type)
        assertEquals("tx42", n.transactionId)
        assertEquals("+905551112233", n.from)
        assertEquals(0x1F40L, n.messageSize)
        assertEquals("http://mms.example/abc", n.contentLocation)
    }

    @Test
    fun notifyRespCarriesTransaction() {
        val r = MmsPdu.parse(MmsPdu.notifyResp("tx42"))
        assertEquals(MmsPdu.NOTIFYRESP_IND, r.type)
        assertEquals("tx42", r.transactionId)
        assertEquals(0x81, r.status)
    }
}
