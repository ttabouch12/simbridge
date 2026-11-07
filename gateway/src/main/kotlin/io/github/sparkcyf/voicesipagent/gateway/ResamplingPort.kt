package io.github.sparkcyf.voicesipagent.gateway

import io.github.sparkcyf.voicesipagent.voice.FrameClockListener
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.PcmFormat

/**
 * Presents an 8 kHz port as 16 kHz or the reverse, so a wideband phone link still bridges when
 * the cellular tap only delivers narrowband (and vice versa). 2x linear interpolation up,
 * 2-tap average down; cheap and good enough for speech that was band-limited at the source.
 */
class ResamplingPort(private val inner: MediaPort, override val format: PcmFormat) : MediaPort {
    init {
        require(setOf(inner.format.sampleRate, format.sampleRate) == setOf(8000, 16000)) {
            "only 8 kHz <-> 16 kHz: ${inner.format} -> $format"
        }
    }
    private val up = format.sampleRate > inner.format.sampleRate
    private var lastIn = 0          // previous inner sample, for interpolation across frames

    override val isClockSource: Boolean get() = inner.isClockSource
    override fun setClockListener(listener: FrameClockListener?) = inner.setClockListener(listener)

    override fun readFrame(): ShortArray {
        val f = inner.readFrame()
        return if (up) upsample(f) else downsample(f)
    }

    override fun writeFrame(frame: ShortArray) = inner.writeFrame(if (up) downsample(frame) else upsampleOut(frame))

    private fun upsample(f: ShortArray): ShortArray {
        val out = ShortArray(f.size * 2)
        for (i in f.indices) {
            val cur = f[i].toInt()
            out[2 * i] = ((lastIn + cur) / 2).toShort()
            out[2 * i + 1] = cur.toShort()
            lastIn = cur
        }
        return out
    }

    private var lastOut = 0
    private fun upsampleOut(f: ShortArray): ShortArray {
        val out = ShortArray(f.size * 2)
        for (i in f.indices) {
            val cur = f[i].toInt()
            out[2 * i] = ((lastOut + cur) / 2).toShort()
            out[2 * i + 1] = cur.toShort()
            lastOut = cur
        }
        return out
    }

    private fun downsample(f: ShortArray): ShortArray =
        ShortArray(f.size / 2) { i -> ((f[2 * i].toInt() + f[2 * i + 1].toInt()) / 2).toShort() }
}
