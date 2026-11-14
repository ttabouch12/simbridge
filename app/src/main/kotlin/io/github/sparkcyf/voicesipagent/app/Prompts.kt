package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.github.sparkcyf.voicesipagent.voice.PcmFormat
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spoken prompts for the answering machine and call screening, made with this phone's
 * text-to-speech and kept in memory per text and rate. [get] never waits on the call path:
 * it returns null until [prepare] has made the prompt.
 */
class Prompts(private val app: Context) {
    private val cache = ConcurrentHashMap<String, ShortArray>()

    fun get(text: String, f: PcmFormat): ShortArray? = cache["${f.sampleRate}|$text"]

    /** Makes both rates of [text] in the background (a few seconds the first time). */
    fun prepare(text: String) {
        if (text.isBlank() || listOf(8000, 16000).all { cache.containsKey("$it|$text") }) return
        Thread({
            val pcm = synchronized(this) { synth(text) } ?: return@Thread
            listOf(PcmFormat.NARROWBAND, PcmFormat.WIDEBAND).forEach { f -> cache["${f.sampleRate}|$text"] = Wav.resample(pcm.first, pcm.second, f.sampleRate) }
            AppLog.i("prompt ready (${pcm.first.size * 1000L / pcm.second} ms)")
        }, "prompts").start()
    }

    private fun synth(text: String): Pair<ShortArray, Int>? {
        val ready = CountDownLatch(1)
        var ok = false
        val tts = TextToSpeech(app) { ok = it == TextToSpeech.SUCCESS; ready.countDown() }
        try {
            if (!ready.await(10, TimeUnit.SECONDS) || !ok) return null
            val out = File(app.cacheDir, "prompt-${text.hashCode()}.wav")
            val done = CountDownLatch(1)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done.countDown()
                @Deprecated("old API") override fun onError(id: String?) = done.countDown()
            })
            tts.synthesizeToFile(text, null, out, "p")
            if (!done.await(20, TimeUnit.SECONDS)) return null
            return Wav.read(out.readBytes()).also { out.delete() }
        } finally {
            tts.shutdown()
        }
    }
}

/** 16-bit mono WAV in and out, and a simple resampler. */
object Wav {
    fun encode(pcm: ShortArray, rate: Int): ByteArray {
        val b = java.nio.ByteBuffer.allocate(44 + pcm.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size * 2).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(pcm.size * 2)
        pcm.forEach { b.putShort(it) }
        return b.array()
    }

    /** First channel of a 16-bit PCM WAV, with its rate. */
    fun read(bytes: ByteArray): Pair<ShortArray, Int>? {
        val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 44) return null
        b.position(12)
        var rate = 0; var channels = 1
        while (b.remaining() >= 8) {
            val id = String(ByteArray(4).also { b.get(it) }); val len = b.int
            when (id) {
                "fmt " -> { b.short; channels = b.short.toInt(); rate = b.int; b.position(b.position() + len - 8) }
                "data" -> {
                    val n = minOf(len, b.remaining()) / 2 / channels
                    return ShortArray(n) { val v = b.short; repeat(channels - 1) { b.short }; v } to rate
                }
                else -> b.position(minOf(b.limit(), b.position() + len))
            }
        }
        return null
    }

    fun resample(x: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || x.isEmpty() || from <= 0) return x
        val ratio = from.toDouble() / to
        return ShortArray((x.size / ratio).toInt()) { i ->
            val p = i * ratio; val j = p.toInt(); val t = p - j
            val a = x[minOf(j, x.size - 1)]; val c = x[minOf(j + 1, x.size - 1)]
            (a + (c - a) * t).toInt().toShort()
        }
    }
}
