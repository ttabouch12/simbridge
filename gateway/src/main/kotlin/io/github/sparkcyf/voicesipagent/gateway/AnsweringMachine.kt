package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.CallEndpoint
import io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener
import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.IncomingCallHandler
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import io.github.sparkcyf.voicesipagent.voice.VoiceFormat
import kotlin.math.PI
import kotlin.math.sin

/**
 * The far end of an answered call when nobody is at the phone: plays [prompt], then a short beep,
 * then records what the caller says until [maxMs], [silenceMs] of quiet after some speech, or the
 * caller hangs up. Afterwards it plays [after] (a hold tone, or nothing) until it is replaced.
 *
 * It is a clockless [MediaPort]: a [MediaBridge] with the cellular port drives it.
 */
class RecorderPort(
    override val format: PcmFormat,
    prompt: ShortArray,
    private val maxMs: Int,
    private val silenceMs: Int,
    private val after: ShortArray? = null,
    private val onDone: (ShortArray) -> Unit,
) : MediaPort {
    private val out: ShortArray = prompt + beep(format)
    private var outPos = 0
    private val rec = ArrayList<ShortArray>()
    private var spokeMs = 0
    private var quietMs = 0
    private var afterPos = 0
    @Volatile var done = false
        private set

    override val isClockSource = false
    override fun setClockListener(listener: FrameClockListener?) {}

    /** What the caller hears. */
    override fun readFrame(): ShortArray {
        val n = format.frameSamples
        if (outPos < out.size) {
            val f = ShortArray(n)
            System.arraycopy(out, outPos, f, 0, minOf(n, out.size - outPos))
            outPos += n
            return f
        }
        val a = after
        if (done && a != null && a.isNotEmpty()) {
            val f = ShortArray(n) { a[(afterPos + it) % a.size] }
            afterPos = (afterPos + n) % a.size
            return f
        }
        return format.silence()
    }

    /** What the caller says; recorded once the prompt and beep are over. */
    override fun writeFrame(frame: ShortArray) {
        if (done || outPos < out.size) return
        rec += frame.copyOf()
        val ms = VoiceFormat.FRAME_MS
        if (VoiceFormat.peak(frame) > SPEECH_PEAK) { spokeMs += ms; quietMs = 0 } else quietMs += ms
        val total = rec.size * ms
        if (total >= maxMs || (spokeMs >= 400 && quietMs >= silenceMs)) finish()
    }

    /** Ends the recording now (the caller hung up). Safe to call more than once. */
    @Synchronized fun finish() {
        if (done) return
        done = true
        // Cut the trailing quiet, keep a little.
        val keep = (rec.size - (quietMs - 300).coerceAtLeast(0) / VoiceFormat.FRAME_MS).coerceIn(0, rec.size)
        val all = ShortArray(keep * format.frameSamples)
        for (i in 0 until keep) System.arraycopy(rec[i], 0, all, i * format.frameSamples, format.frameSamples)
        onDone(if (spokeMs >= 400) all else ShortArray(0))
    }

    companion object {
        private const val SPEECH_PEAK = 1500

        /** 400 ms at 1 kHz, like an answering machine. */
        fun beep(f: PcmFormat): ShortArray = ShortArray(f.sampleRate * 4 / 10) { i ->
            (sin(2 * PI * 1000 * i / f.sampleRate) * 9000).toInt().toShort()
        }

        /** A soft ring-back style tone (0.4 s on, 2.6 s off) for the caller waiting to be put through. */
        fun holdTone(f: PcmFormat): ShortArray = ShortArray(f.sampleRate * 3) { i ->
            if (i < f.sampleRate * 4 / 10) ((sin(2 * PI * 425 * i / f.sampleRate)) * 4000).toInt().toShort() else 0
        }
    }
}

/**
 * Wraps the cellular endpoint so some calls are screened before they ring the main phone: the
 * call is answered, the caller hears [prompt] and says who they are, the recording goes to
 * [onScreened], and then the call is put through as usual (the caller hears a waiting tone).
 */
class ScreeningEndpoint(
    private val inner: CallEndpoint,
    private val shouldScreen: (CallLeg) -> Boolean,
    private val prompt: (PcmFormat) -> ShortArray?,
    private val onScreened: (leg: CallLeg, audio: ShortArray, format: PcmFormat) -> Unit,
    private val log: (String) -> Unit = {},
) : CallEndpoint by inner {
    override fun setIncomingCallHandler(handler: IncomingCallHandler?) {
        if (handler == null) { inner.setIncomingCallHandler(null); return }
        inner.setIncomingCallHandler { leg ->
            if (!runCatching { shouldScreen(leg) }.getOrDefault(false)) handler.onIncomingCall(leg)
            else screen(leg, handler)
        }
    }

    private fun screen(leg: CallLeg, then: IncomingCallHandler) {
        var bridge: MediaBridge? = null
        var handed = false
        fun handOver() {
            if (handed) return
            handed = true
            // The bridge's own media takes the cellular clock over; ours just stops being called.
            if (leg.state != LegState.ENDED) then.onIncomingCall(leg)
        }
        val listener = object : CallLeg.Listener {
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) {
                if (media == null || bridge != null) return
                val p = prompt(media.format)
                if (p == null) { handOver(); return }
                val port = RecorderPort(media.format, p, maxMs = 7000, silenceMs = 1200, after = RecorderPort.holdTone(media.format)) { audio ->
                    log("screening: ${audio.size * 1000L / media.format.sampleRate} ms recorded")
                    if (audio.isNotEmpty()) runCatching { onScreened(leg, audio, media.format) }
                    handOver()
                }
                bridge = MediaBridge(media, port).also { it.start() }
            }

            override fun onStateChanged(leg: CallLeg, state: LegState) {
                if (state == LegState.ENDED) { bridge?.stop(); leg.removeListener(this) }
            }
        }
        leg.addListener(listener)
        log("screening an incoming call")
        leg.answer()
        leg.media?.let { listener.onMediaChanged(leg, it) }
    }
}

/**
 * Takes an unanswered call off the main phone and records a message: answers it, plays the
 * greeting and beep, records, hangs up, and hands the recording to [onMessage].
 */
object Voicemail {
    fun record(leg: CallLeg, greeting: (PcmFormat) -> ShortArray, maxMs: Int, onMessage: (Party, ShortArray, PcmFormat, Long) -> Unit,
               log: (String) -> Unit = {}) {
        val at = System.currentTimeMillis()
        var bridge: MediaBridge? = null
        var port: RecorderPort? = null
        val listener = object : CallLeg.Listener {
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) {
                if (media == null || bridge != null) return
                val p = RecorderPort(media.format, greeting(media.format), maxMs, silenceMs = 3500) { audio ->
                    log("voicemail: ${audio.size * 1000L / media.format.sampleRate} ms recorded")
                    if (audio.isNotEmpty()) runCatching { onMessage(leg.remote, audio, media.format, at) }
                    if (leg.state != LegState.ENDED) leg.hangup(HangupCause.NORMAL)
                }
                port = p
                bridge = MediaBridge(media, p).also { it.start() }
            }

            override fun onStateChanged(leg: CallLeg, state: LegState) {
                if (state != LegState.ENDED) return
                bridge?.stop()
                port?.finish()          // the caller hung up: keep what was said
                leg.removeListener(this)
            }
        }
        leg.addListener(listener)
        log("voicemail: answering")
        if (leg.state == LegState.CONNECTED) leg.media?.let { listener.onMediaChanged(leg, it) } else leg.answer()
    }
}
