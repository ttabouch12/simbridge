package io.github.sparkcyf.voicesipagent.privileged.shell

import android.media.AudioManager
import android.util.Log

/**
 * Keeps the gateway phone itself silent while a call is bridged.
 *
 * Muting STREAM_VOICE_CALL isn't enough on some devices (Samsung clamps it to index 1 and the
 * modem keeps driving the earpiece), so where the platform allows it this also sets the HAL
 * voice volume to 0 through AudioSystem.setVoiceVolume (blocked by hidden-API rules on e.g.
 * Android 17 / One UI, where the voice-call stream stays at its minimum). AudioService re-applies its own value on route or mode changes,
 * so we re-assert it every 1.5 s. Local DTMF feedback tones are silenced via STREAM_DTMF.
 * The modem's downlink tap is taken before this volume stage, so the main phone still hears
 * everything.
 */
internal class DeepSilence(private val audio: AudioManager) {
    @Volatile private var active = false
    private var thread: Thread? = null
    private var savedDtmf = -1

    @Synchronized fun start(): String {
        if (active) return "already silent"
        active = true
        savedDtmf = runCatching { audio.getStreamVolume(AudioManager.STREAM_DTMF) }.getOrDefault(-1)
        runCatching { audio.setStreamVolume(AudioManager.STREAM_DTMF, 0, 0) }
        val first = setVoiceVolume(0f)
        thread = Thread({
            while (active) {
                try { Thread.sleep(1500) } catch (_: InterruptedException) { break }
                if (active) setVoiceVolume(0f)
            }
        }, "deep-silence").apply { isDaemon = true; start() }
        return "voice volume 0 (status $first), dtmf $savedDtmf->0"
    }

    @Synchronized fun stop(): String {
        if (!active) return "not active"
        active = false
        thread?.interrupt()
        thread = null
        if (savedDtmf >= 0) runCatching { audio.setStreamVolume(AudioManager.STREAM_DTMF, savedDtmf, 0) }
        // Hand the HAL voice volume back to AudioService's own value for the voice-call stream.
        val idx = runCatching { audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(0)
        val max = runCatching { audio.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) }.getOrDefault(1).coerceAtLeast(1)
        setVoiceVolume(idx.toFloat() / max)
        return "restored (dtmf $savedDtmf)"
    }

    private var method: java.lang.reflect.Method? = null
    private var looked = false

    /** Finds AudioSystem.setVoiceVolume if the platform lets this process see it. */
    private fun voiceMethod(): java.lang.reflect.Method? {
        if (looked) return method
        looked = true
        method = try {
            // Hidden API: works where the helper process isn't subject to hidden-API filtering
            // (older Android, some ROMs). Elsewhere STREAM_VOICE_CALL mute + DTMF silence still apply.
            val all = Class.forName("android.media.AudioSystem").declaredMethods.toList()
            all.firstOrNull { it.name == "setVoiceVolume" }.also {
                if (it == null) Log.i("VsaSilence", "setVoiceVolume not accessible here; using stream mute only")
                else Log.i("VsaSilence", "using ${it.name}${it.parameterTypes.contentToString()}")
            }?.apply { isAccessible = true }
        } catch (t: Throwable) {
            Log.w("VsaSilence", "lookup failed", t); null
        }
        return method
    }

    private fun setVoiceVolume(v: Float): Int = try {
        val m = voiceMethod() ?: return -2
        val args: Array<Any?> = when (m.parameterTypes.size) {
            1 -> arrayOf(v)
            2 -> arrayOf(v, 0)   // (volume, sessionId/flags) variants
            else -> return -3
        }
        (m.invoke(null, *args) as? Int) ?: 0
    } catch (t: Throwable) {
        Log.w("VsaSilence", "setVoiceVolume failed", t); -1
    }
}
