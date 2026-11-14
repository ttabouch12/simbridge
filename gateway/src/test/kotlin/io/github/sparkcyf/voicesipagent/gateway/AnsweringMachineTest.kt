package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsweringMachineTest {
    private val f = PcmFormat.NARROWBAND
    private fun loud() = ShortArray(f.frameSamples) { 5000 }
    private fun quiet() = ShortArray(f.frameSamples)

    @Test
    fun playsPromptAndBeepThenRecordsUntilSilence() {
        var got: ShortArray? = null
        val prompt = ShortArray(f.frameSamples * 5) { 1 }
        val p = RecorderPort(f, prompt, maxMs = 60_000, silenceMs = 1000) { got = it }
        // Prompt (5 frames) + beep (400 ms = 20 frames) are played first; speech then is ignored.
        repeat(25) { assertEquals(f.frameSamples, p.readFrame().size); p.writeFrame(loud()) }
        repeat(50) { p.readFrame(); p.writeFrame(loud()) }       // 1 s of speech
        repeat(60) { p.readFrame(); p.writeFrame(quiet()) }      // then quiet: stops after 1 s
        assertTrue(p.done)
        val ms = got!!.size * 1000 / f.sampleRate
        assertTrue("recorded $ms ms", ms in 1200..1400)          // speech + 300 ms of the quiet
    }

    @Test
    fun hangupKeepsWhatWasSaidAndSilenceGivesNothing() {
        var got: ShortArray? = null
        val p = RecorderPort(f, ShortArray(0), maxMs = 60_000, silenceMs = 3000) { got = it }
        repeat(20) { p.writeFrame(quiet()); p.readFrame() }      // beep (said during it: not kept)
        repeat(30) { p.writeFrame(loud()) }
        p.finish(); p.finish()
        assertEquals(30 * f.frameSamples, got!!.size)

        var empty: ShortArray? = null
        val q = RecorderPort(f, ShortArray(0), maxMs = 60_000, silenceMs = 3000) { empty = it }
        repeat(20) { q.readFrame() }
        repeat(40) { q.writeFrame(quiet()) }
        q.finish()
        assertEquals(0, empty!!.size)
    }

    @Test
    fun voicemailTakesTheCallerOffTheRingingPhone() {
        val cell = FakeEndpoint("cellular")
        val sip = FakeEndpoint("sip").apply { onDial = { it.remoteRinging() } }
        val bridge = CallBridge(cell, sip, { "phone" }, { it.target }, GatewayConfig(cellularInboundTarget = "phone"))
        bridge.start()
        val caller = cell.incoming("+905551112233")
        bridge.drain()
        val session = bridge.session!!
        var message: ShortArray? = null
        bridge.takeOver(session) { leg -> Voicemail.record(leg, { ShortArray(0) }, 30_000, { _, a, _, _ -> message = a }) }
        bridge.drain()
        assertTrue(sip.dialed.first().actions.contains("hangup:CANCELLED"))
        assertEquals(LegState.CONNECTED, caller.state)
        val port = FakePort(isClockSource = true, fill = 6000)
        caller.mediaUp(port)
        repeat(200) { port.clock!!.onFrame() }                    // beep, then 3.6 s of speech
        caller.remoteEnded(HangupCause.NORMAL)
        assertTrue(message!!.isNotEmpty())
        bridge.stop()
    }

    @Test
    fun screenedCallsAreAnsweredRecordedThenPutThrough() {
        val cell = FakeEndpoint("cellular")
        var screened: ShortArray? = null
        var handed = false
        val ep = ScreeningEndpoint(cell, { it.remote.number == "+1" }, { ShortArray(0) }, { _, a, _ -> screened = a })
        ep.setIncomingCallHandler { handed = true }
        val leg = FakeLeg("cellular", LegDirection.INBOUND, Party("+1"), null)
        cell.handler!!.onIncomingCall(leg)
        assertTrue(leg.actions.contains("answer"))
        val port = FakePort(isClockSource = true, fill = 6000)
        leg.mediaUp(port)
        repeat(20 + 350) { port.clock!!.onFrame() }               // beep, then 7 s max
        assertTrue(screened!!.isNotEmpty())
        assertTrue(handed)

        handed = false
        ep.setIncomingCallHandler { handed = true }
        val other = FakeLeg("cellular", LegDirection.INBOUND, Party("+2"), null)
        cell.handler!!.onIncomingCall(other)
        assertTrue(handed && other.actions.isEmpty())
    }
}
