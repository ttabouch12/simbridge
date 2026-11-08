package io.github.simbridge

import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party

/** Telecom's view of relayed SIM calls; the system Phone app draws the UI. */
class GatewayConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val core = LinkApp.core(this)
        val id = request.extras?.getString(Telecom.EXTRA_LEG) ?: core.lastIncomingLeg
        val leg = id?.let { core.legs[it] }
        if (leg == null || leg.state == LegState.ENDED) {
            Log.w("telecom: incoming call vanished before Telecom picked it up")
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.MISSED))
        }
        val c = GatewayConnection(core, leg, incoming = true)
        val num = leg.remote.number
        c.setAddress(Uri.fromParts("tel", num ?: "", null),
            if (num.isNullOrBlank()) TelecomManager.PRESENTATION_UNKNOWN else TelecomManager.PRESENTATION_ALLOWED)
        leg.remote.displayName?.takeIf { it.isNotBlank() && it != num }
            ?.let { c.setCallerDisplayName(it, TelecomManager.PRESENTATION_ALLOWED) }
        c.setRinging()
        c.watch()
        return c
    }

    override fun onCreateIncomingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        val core = LinkApp.core(this)
        val id = request.extras?.getString(Telecom.EXTRA_LEG) ?: core.lastIncomingLeg
        Log.w("telecom: refused incoming call (busy?)")
        id?.let { core.legs.remove(it) }?.hangup(HangupCause.BUSY)
    }

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection {
        val core = LinkApp.core(this)
        val number = request.address?.schemeSpecificPart?.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val ua = core.ua
        if (number.isNullOrBlank()) {
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, "Invalid number",
                "This number can't be called", "invalid number"))
        }
        if (ua == null || !ua.status.ready) {
            core.kick()
            return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR, "SIM phone not connected",
                "Your SIM phone isn't reachable. Check Wi-Fi or the SIM phone's hotspot.", "gateway offline"))
        }
        val leg = ua.dial(number, Party(null, sim = Telecom.simOf(account).takeIf { it > 0 }))
        core.legs[leg.id] = leg
        val c = GatewayConnection(core, leg, incoming = false)
        c.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        c.setDialing()
        c.watch()
        Log.i("telecom: dialing via the SIM phone")
        return c
    }

    override fun onCreateOutgoingConnectionFailed(account: PhoneAccountHandle?, request: ConnectionRequest) {
        Log.w("telecom: outgoing call refused by Telecom")
    }
}

class GatewayConnection(private val core: LinkCore, private val leg: CallLeg, private val incoming: Boolean) : Connection() {
    private val audio = CallAudio()
    private var ringback: ToneGenerator? = null
    @Volatile private var done = false
    @Volatile private var recorder: CallRecorder? = null
    private val label: String get() = leg.remote.number?.takeIf { it.isNotBlank() } ?: leg.target ?: ""
    val isRecording get() = recorder != null
    /** Live translation of this call, for CaptionActivity's mode buttons. */
    val translator get() = audio.translator

    private fun startTranslator() {
        if (audio.translator != null) return
        val f = leg.media?.format ?: return
        Captions.clear()
        audio.translator = CallTranslator(core.context, core.prefs, f.sampleRate, f.frameSamples)
        if (core.prefs.captionBubble) bubble = CaptionBubble(core.context).also { it.show() }
    }
    private var bubble: CaptionBubble? = null

    /** Starts recording both sides to Recordings/SimBridge (Record button or auto-record). */
    @Synchronized fun startRecording() {
        if (recorder != null || done || leg.state != LegState.CONNECTED) return
        val rate = leg.media?.format?.sampleRate ?: 8000
        recorder = CallRecorder(core.context, rate, label).also { audio.recorder = it }
        core.notifier.inCall(label, recording = true)
        Log.i("recording started (${rate}Hz)")
    }

    @Synchronized fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        audio.recorder = null
        if (!done) core.notifier.inCall(label, recording = false)
        val who = label
        Thread { r.stop()?.let { core.notifier.recordingSaved(who, it) } }.start()
    }

    init {
        connectionCapabilities = CAPABILITY_MUTE
        setAudioModeIsVoip(true)
    }

    fun watch() {
        leg.addListener(object : CallLeg.Listener {
            override fun onStateChanged(leg: CallLeg, state: LegState) {
                when (state) {
                    LegState.RINGING -> if (!incoming) startRingback()
                    LegState.CONNECTED -> {
                        stopRingback()
                        setActive()
                        core.onCallActive()
                        // Take the microphone first (if the service started without it), then open audio.
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            LinkService.onCallActive()
                            leg.media?.let(audio::start)
                            startTranslator()
                            ActiveCall.current = this@GatewayConnection
                            core.notifier.inCall(label, recording = false)
                            if (core.prefs.autoRecord) startRecording()
                        }
                    }
                    LegState.ENDED -> remoteEnded(leg.endCause)
                    else -> Unit
                }
            }
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) {
                if (media != null && leg.state == LegState.CONNECTED) audio.start(media)
            }
        })
        if (leg.state == LegState.ENDED) remoteEnded(leg.endCause)
    }

    override fun onAnswer() { leg.answer() }

    override fun onReject() = localEnd(HangupCause.REJECTED, DisconnectCause(DisconnectCause.REJECTED))
    override fun onDisconnect() = localEnd(HangupCause.NORMAL, DisconnectCause(DisconnectCause.LOCAL))
    override fun onAbort() = localEnd(HangupCause.NORMAL, DisconnectCause(DisconnectCause.LOCAL))

    override fun onPlayDtmfTone(c: Char) { leg.sendDtmf(c) }
    override fun onStopDtmfTone() = Unit

    override fun onMuteStateChanged(isMuted: Boolean) { audio.muted = isMuted }

    private fun localEnd(cause: HangupCause, dc: DisconnectCause) {
        if (done) return
        done = true
        leg.hangup(cause)
        finish(dc)
    }

    private fun remoteEnded(cause: HangupCause?) {
        if (done) return
        done = true
        val dc = when (cause) {
            HangupCause.NORMAL, null -> DisconnectCause(DisconnectCause.REMOTE)
            HangupCause.BUSY -> DisconnectCause(DisconnectCause.BUSY)
            HangupCause.CANCELLED -> DisconnectCause(if (incoming) DisconnectCause.MISSED else DisconnectCause.CANCELED)
            HangupCause.ANSWERED_ELSEWHERE -> DisconnectCause(DisconnectCause.ANSWERED_ELSEWHERE)
            HangupCause.NO_ANSWER -> DisconnectCause(if (incoming) DisconnectCause.MISSED else DisconnectCause.REMOTE)
            HangupCause.REJECTED -> DisconnectCause(DisconnectCause.REMOTE)
            else -> DisconnectCause(DisconnectCause.ERROR, "Call failed", "The SIM phone couldn't complete the call", cause.name)
        }
        finish(dc)
    }

    private fun finish(dc: DisconnectCause) {
        stopRecording()
        if (ActiveCall.current === this) { ActiveCall.current = null; core.notifier.cancelInCall() }
        stopRingback()
        bubble?.let { b -> android.os.Handler(android.os.Looper.getMainLooper()).post { b.hide() } }
        bubble = null
        Thread { audio.stop(); audio.translator?.close(); audio.translator = null }.start()
        core.legs.remove(leg.id)
        setDisconnected(dc)
        destroy()
        core.onCallEnded()
    }

    @Synchronized private fun startRingback() {
        if (ringback != null) return
        ringback = runCatching { ToneGenerator(AudioManager.STREAM_VOICE_CALL, 70).apply { startTone(ToneGenerator.TONE_SUP_RINGTONE) } }.getOrNull()
    }

    @Synchronized private fun stopRingback() {
        ringback?.let { runCatching { it.stopTone(); it.release() } }
        ringback = null
    }
}

/** The call in progress, for the Record / Stop buttons in the notification. */
object ActiveCall {
    @Volatile var current: GatewayConnection? = null
}

class RecordReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        val c = ActiveCall.current ?: return
        when (intent.action) {
            START -> c.startRecording()
            STOP -> c.stopRecording()
        }
    }

    companion object {
        const val START = "io.github.simbridge.REC_START"
        const val STOP = "io.github.simbridge.REC_STOP"
    }
}
