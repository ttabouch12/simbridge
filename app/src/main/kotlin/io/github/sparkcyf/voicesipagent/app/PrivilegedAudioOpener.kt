package io.github.sparkcyf.voicesipagent.app

import android.os.ParcelFileDescriptor
import io.github.sparkcyf.voicesipagent.privileged.PcmSocketPort
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.telephony.CallAudioHandle
import io.github.sparkcyf.voicesipagent.telephony.CallAudioOpener
import io.github.sparkcyf.voicesipagent.voice.PcmFormat

/** Glue: the telephony layer's audio source, backed by the Shizuku shell service. */
class PrivilegedAudioOpener(
    private val shizuku: ShizukuConnector,
    private val method: () -> Int,
    private val muteEarpiece: () -> Boolean,
    private val format: () -> PcmFormat,
    private val createPort: (ParcelFileDescriptor, PcmFormat, (String) -> Unit) -> PcmSocketPort = ::PcmSocketPort,
) : CallAudioOpener {
    private val lock = Any()
    private var generation = 0L

    override fun open(onLost: (String) -> Unit): CallAudioHandle = synchronized(lock) {
        val svc = shizuku.service ?: error("privileged service not connected (${shizuku.state})")
        val pcm0 = format()
        val selectedMethod = method()
        val suppressLocal = muteEarpiece()
        val ticket = ++generation
        // HD voice: try the call's rate first; if this phone's tap can't do 16 kHz, fall back to
        // 8 kHz (the bridge resamples).
        var pcm = pcm0
        val pfd = try {
            svc.openCallAudio(selectedMethod, pcm.sampleRate)
        } catch (e: Exception) {
            if (pcm.sampleRate == 8000) throw e
            AppLog.w("16 kHz call audio unavailable (${e.message}); using 8 kHz")
            runCatching { svc.closeCallAudio() }
            pcm = PcmFormat.NARROWBAND
            svc.openCallAudio(selectedMethod, 8000)
        }
        var constructed: PcmSocketPort? = null
        val port = try {
            createPort(pfd, pcm, onLost).also { constructed = it; it.start() }
        } catch (e: Throwable) {
            runCatching { constructed?.close() }.exceptionOrNull()?.let(e::addSuppressed)
            runCatching { pfd.close() }
            runCatching { svc.closeCallAudio() }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        // A diagnostic Binder transaction must never turn an open tap into an unowned resource.
        runCatching { AppLog.i("call audio tap opened: ${runCatching { svc.audioStats() }.getOrDefault("stats unavailable")}") }
        if (suppressLocal) {
            // Best effort: a failure here only means the handset stays audible.
            AppLog.i("earpiece mute: ${runCatching { svc.setLocalOutputMuted(true) }.getOrElse { "failed: ${it.message}" }}")
        }
        object : CallAudioHandle {
            private var closed = false
            override val port = port
            override fun close() = synchronized(lock) closeHandle@{
                if (closed) return@closeHandle
                closed = true
                try { port.close() } finally {
                    if (ticket == generation) runCatching { svc.closeCallAudio() }
                }
                AppLog.i("call audio tap closed: in=${port.framesIn} out=${port.framesOut} ${runCatching { svc.audioStats() }.getOrDefault("")}")
            }
        }
    }
}
