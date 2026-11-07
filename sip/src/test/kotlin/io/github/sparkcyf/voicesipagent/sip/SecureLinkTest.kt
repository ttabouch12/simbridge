package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Gateway + phone user agents on loopback: pinned TLS, digest on every request, SRTP audio,
 * SIP MESSAGE both ways. Needs `keytool` from the running JDK to make a test key.
 */
class SecureLinkTest {
    private val log = object : SipLog {
        override fun info(msg: String) {}
        override fun warn(msg: String, t: Throwable?) {}
    }

    private fun gatewayKey(): KeyStore? {
        val f = File.createTempFile("gwkey", ".p12").apply { delete(); deleteOnExit() }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").takeIf { it.exists() } ?: return null
        val p = ProcessBuilder(keytool.path, "-genkeypair", "-alias", "gw", "-keyalg", "EC", "-groupname", "secp256r1",
            "-dname", "CN=test", "-validity", "30", "-storetype", "PKCS12", "-keystore", f.path,
            "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        if (p.waitFor() != 0) return null
        return KeyStore.getInstance("PKCS12").apply { f.inputStream().use { load(it, "changeit".toCharArray()) } }
    }

    private fun gateway(ks: KeyStore, received: MutableList<Pair<String, String>>, legOut: AtomicReference<CallLeg>): SipUserAgent {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "changeit".toCharArray()) }
        val gw = SipUserAgent(SipConfig(
            SipAccount(server = "", username = "gateway", password = ""), localPort = 0, serverMode = true,
            registrarPassword = "s3cret", tls = TlsSettings(TlsPinning.serverContext(kmf.keyManagers), 0),
            udpEnabled = false, srtp = SrtpMode.REQUIRED), log)
        gw.setIncomingCallHandler { leg -> legOut.set(leg); leg.answer() }
        gw.outgoingMessageHandler = { to, text -> received += to to text; true }
        gw.start()
        repeat(50) { if (gw.tlsPort == null) Thread.sleep(20) }
        return gw
    }

    private fun phone(port: Int, pin: String, password: String = "s3cret"): SipUserAgent = SipUserAgent(SipConfig(
        SipAccount("127.0.0.1", port, "phone", password, domain = "127.0.0.1"), localPort = 0,
        tls = TlsSettings(TlsPinning.clientContext(pin), 0), srtp = SrtpMode.REQUIRED, registerExpiresSec = 120), log)

    private fun awaitReady(ua: SipUserAgent, ms: Long): Boolean {
        val l = CountDownLatch(1)
        ua.addStatusListener { _, st -> if (st.ready) l.countDown() }
        if (ua.status.ready) return true
        return l.await(ms, TimeUnit.MILLISECONDS)
    }

    @Test
    fun registerTextAndSecureCallOverPinnedTls() {
        val ks = gatewayKey() ?: return   // no keytool: skip
        val pin = TlsPinning.fingerprint(ks.getCertificate("gw") as X509Certificate)
        val texts = CopyOnWriteArrayList<Pair<String, String>>()
        val gwLeg = AtomicReference<CallLeg>()
        val gw = gateway(ks, texts, gwLeg)
        val ph = phone(gw.tlsPort!!, pin)
        val inbox = CopyOnWriteArrayList<String>()
        ph.incomingMessageHandler = { from, name, text -> inbox += "$from|$name|$text"; true }
        try {
            ph.start()
            assertTrue("phone registers over TLS", awaitReady(ph, 5000))
            assertTrue(awaitReady(gw, 2000))

            // Text phone -> SIM (challenged, then accepted)
            val code = AtomicInteger(-1); val sent = CountDownLatch(1)
            ph.sendMessageTo("5555", "KALAN") { code.set(it ?: 0); sent.countDown() }
            assertTrue(sent.await(5, TimeUnit.SECONDS))
            assertTrue("MESSAGE accepted, got ${code.get()}", code.get() in 200..299)
            assertEquals(listOf("5555" to "KALAN"), texts.toList())

            // Text SIM -> phone
            assertTrue(gw.sendMessage("+905551112233", "merhaba", "Ali"))
            repeat(50) { if (inbox.isEmpty()) Thread.sleep(20) }
            assertEquals("+905551112233|Ali|merhaba", inbox.firstOrNull())

            // Call phone -> gateway with SRTP audio
            val leg = ph.dial("4441444", Party(null))
            repeat(150) { if (leg.state != LegState.CONNECTED || gwLeg.get()?.state != LegState.CONNECTED) Thread.sleep(20) }
            assertEquals(LegState.CONNECTED, leg.state)
            val g = gwLeg.get(); assertNotNull(g)
            val pm = leg.media!!; val gm = g.media!!
            var peak = 0
            for (i in 0 until 60) {
                val f = ShortArray(VoiceFormat.FRAME_SAMPLES) { k -> (8000 * sin(2 * PI * 440 * (i * 160 + k) / 8000.0)).toInt().toShort() }
                pm.writeFrame(f)
                gm.writeFrame(ShortArray(VoiceFormat.FRAME_SAMPLES))
                Thread.sleep(20)
                for (s in gm.readFrame()) peak = maxOf(peak, abs(s.toInt()))
            }
            assertTrue("audio crossed the SRTP link (peak=$peak)", peak > 3000)
            assertTrue((leg as SipCall).mediaStats, leg.mediaStats.contains("srtp authFail=0"))
            leg.hangup()
            repeat(100) { if (g.state != LegState.ENDED) Thread.sleep(20) }
            assertEquals(LegState.ENDED, g.state)
        } finally {
            ph.stop(500); gw.stop(500)
        }
    }

    @Test
    fun wrongPinOrPasswordNeverRegisters() {
        val ks = gatewayKey() ?: return
        val pin = TlsPinning.fingerprint(ks.getCertificate("gw") as X509Certificate)
        val gw = gateway(ks, CopyOnWriteArrayList(), AtomicReference())
        val impostorPin = pin.reversed()
        val a = phone(gw.tlsPort!!, impostorPin)
        val b = phone(gw.tlsPort!!, pin, password = "wrong")
        try {
            a.start(); b.start()
            assertFalse("pinned TLS rejects another key", awaitReady(a, 2500))
            assertFalse("bad password rejected", awaitReady(b, 500))
            assertFalse(gw.status.ready)
        } finally {
            a.stop(300); b.stop(300); gw.stop(300)
        }
    }

    @Test
    fun forgedUdpInviteAndMessageFromRegisteredAddressAreChallengedNotExecuted() {
        val texts = CopyOnWriteArrayList<Pair<String, String>>()
        val calls = AtomicInteger()
        val gw = SipUserAgent(SipConfig(SipAccount(server = "", username = "gateway", password = ""), localPort = 0,
            serverMode = true, registrarPassword = "s3cret"), log)
        gw.setIncomingCallHandler { calls.incrementAndGet() }
        gw.outgoingMessageHandler = { to, text -> texts += to to text; true }
        gw.start()
        repeat(50) { if (gw.udpPort == null) Thread.sleep(20) }
        val ph = SipUserAgent(SipConfig(SipAccount("127.0.0.1", gw.udpPort!!, "phone", "s3cret", domain = "127.0.0.1"),
            localPort = 0, registerExpiresSec = 120), log)
        java.net.DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { attacker ->
            try {
                ph.start()
                assertTrue(awaitReady(ph, 5000))
                // Same source IP as the registered phone (loopback), no credentials.
                attacker.soTimeout = 2000
                fun forge(method: String, body: String, ctype: String): Int {
                    val msg = "$method sip:900123@127.0.0.1 SIP/2.0\r\n" +
                        "Via: SIP/2.0/UDP 127.0.0.1:${attacker.localPort};branch=z9hG4bK${method}x1\r\n" +
                        "From: <sip:phone@127.0.0.1>;tag=evil\r\nTo: <sip:900123@127.0.0.1>\r\n" +
                        "Call-ID: forged-$method\r\nCSeq: 1 $method\r\nMax-Forwards: 70\r\n" +
                        "Contact: <sip:phone@127.0.0.1:${attacker.localPort}>\r\nContent-Type: $ctype\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"
                    val b = msg.toByteArray()
                    attacker.send(java.net.DatagramPacket(b, b.size, java.net.InetSocketAddress("127.0.0.1", gw.udpPort!!)))
                    val buf = ByteArray(4096); val pkt = java.net.DatagramPacket(buf, buf.size)
                    while (true) {
                        attacker.receive(pkt)
                        val r = SipParser.parse(buf, pkt.length) as? SipResponse ?: continue
                        if (r.code >= 200) return r.code
                    }
                }
                assertEquals(401, forge("MESSAGE", "premium sms", "text/plain"))
                val sdp = io.github.sparkcyf.voicesipagent.sip.media.Sdp.build(1, 1, "127.0.0.1", 40000,
                    listOf(0 to io.github.sparkcyf.voicesipagent.sip.media.AudioCodec.PCMU), 101)
                assertEquals(401, forge("INVITE", sdp, "application/sdp"))
                Thread.sleep(200)
                assertEquals(0, texts.size)
                assertEquals(0, calls.get())
            } finally {
                ph.stop(300); gw.stop(300)
            }
        }
    }
}
