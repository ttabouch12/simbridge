package io.github.simbridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.max
import kotlin.math.sqrt

/** OFF: nothing. CAPTIONS: the caller's speech shows translated. VOICE: also, my speech is spoken to them translated. */
enum class TranslateMode { OFF, CAPTIONS, VOICE }

/** One line: who spoke, what was heard, and its translation (null while translating). */
data class Caption(val id: Long, val fromCaller: Boolean, val heard: String, val translated: String?)

/** Captions of the current (or last) call, shown by CaptionActivity and the floating bubble. Main thread only. */
object Captions {
    val lines = mutableStateListOf<Caption>()
    var info by mutableStateOf("")
    var mode by mutableStateOf(TranslateMode.OFF)
    private val main = Handler(Looper.getMainLooper())
    fun add(c: Caption) { main.post { lines += c; if (lines.size > 300) lines.removeAt(0) } }
    fun update(id: Long, translated: String) {
        main.post { val i = lines.indexOfFirst { it.id == id }; if (i >= 0) lines[i] = lines[i].copy(translated = translated) }
    }
    /** The line being spoken right now (one per direction); the final caption replaces it. */
    fun live(fromCaller: Boolean, heard: String, translated: String?) {
        main.post {
            val c = Caption(if (fromCaller) LIVE_CALLER else LIVE_ME, fromCaller, heard, translated)
            val i = lines.indexOfFirst { it.id == c.id }
            if (i >= 0) lines[i] = c else lines += c
        }
    }
    fun endLive(fromCaller: Boolean) { main.post { lines.removeAll { it.id == if (fromCaller) LIVE_CALLER else LIVE_ME } } }
    private const val LIVE_CALLER = -1L
    private const val LIVE_ME = -2L
    fun say(s: String) { main.post { info = s } }
    fun showMode(m: TranslateMode) { main.post { mode = m } }
    fun clear() { main.post { lines.clear(); info = "" } }
}

/**
 * Translates one call. The audio thread only measures loudness and queues finished utterances;
 * recognition, translation and speech run on one worker per direction, so the call never stutters.
 */
class CallTranslator(private val ctx: Context, private val prefs: Prefs, private val rate: Int, private val frame: Int) {
    private val callerLang = prefs.callerLang
    private val myLang = prefs.myLang
    private val speech = Speech(ctx, prefs)
    private val callerJobs = Executors.newSingleThreadExecutor()
    private val myJobs = Executors.newSingleThreadExecutor()
    // Translation and speech of a finished line run apart from recognition, so the next
    // sentence is already being recognized while this one is translated.
    private val callerMt = Executors.newSingleThreadExecutor()
    private val myMt = Executors.newSingleThreadExecutor()
    // Live captions: what is being said right now, before the sentence ends.
    private val liveJobs = Executors.newSingleThreadExecutor()
    private val liveBusy = java.util.concurrent.atomic.AtomicBoolean()
    private val live = prefs.liveCaptions && speech.canLive
    /** Sentence counters (caller, me): a live result for a sentence that already ended is dropped. */
    private val callerGen = AtomicLong()
    private val myGen = AtomicLong()
    private val theirs = Segmenter(rate, prefs.pauseMs, onPartial = if (live) ({ pcm: ShortArray -> partial(pcm, fromCaller = true) }) else null) { pcm ->
        callerGen.incrementAndGet()
        runCatching { callerJobs.execute { handle(pcm, fromCaller = true) } }
    }
    private val mine = Segmenter(rate, prefs.pauseMs, onPartial = if (live) ({ pcm: ShortArray -> partial(pcm, fromCaller = false) }) else null) { pcm ->
        myGen.incrementAndGet()
        runCatching { myJobs.execute { handle(pcm, fromCaller = false) } }
    }
    /** Translated speech for the caller, and for me, in call-sized frames (up to 60 s each). */
    private val toCaller = LinkedBlockingQueue<ShortArray>(3000)
    private val toMe = LinkedBlockingQueue<ShortArray>(3000)
    private val silence = ShortArray(frame)
    private val recent = ArrayDeque<String>()
    private val seq = AtomicLong()
    private var voice: Voice? = null

    @Volatile var mode: TranslateMode = prefs.translateMode
        set(v) {
            field = v
            Captions.showMode(v)
            if (v == TranslateMode.VOICE || prefs.speakToMe) runCatching { myJobs.execute { voice() } }   // warm up TTS
        }

    init {
        Captions.showMode(mode)
        if (mode == TranslateMode.VOICE || prefs.speakToMe) myJobs.execute { voice() }
        Thread({ speech.warmUp() }, "translate-warmup").start()
    }

    /** Audio thread: one frame of the caller's voice, as received. */
    fun heard(down: ShortArray) {
        if (mode != TranslateMode.OFF) theirs.push(down)
    }

    /** Audio thread: what I hear; translations read to me are mixed over the (quieter) caller. */
    fun downlink(down: ShortArray): ShortArray {
        if (mode == TranslateMode.OFF) { toMe.clear(); return down }
        val t = toMe.poll() ?: return down
        return ShortArray(down.size) { i -> (down[i] * 0.3 + t[i]).toInt().coerceIn(-32768, 32767).toShort() }
    }

    /** Audio thread: one frame from my mic; returns what the caller hears instead. */
    fun uplink(mic: ShortArray): ShortArray {
        if (mode != TranslateMode.VOICE) { toCaller.clear(); return mic }
        mine.push(mic)
        return toCaller.poll() ?: silence
    }

    fun close() {
        callerJobs.shutdownNow(); myJobs.shutdownNow(); callerMt.shutdownNow(); myMt.shutdownNow(); liveJobs.shutdownNow()
        synchronized(this) { voice?.shutdown(); voice = null }
    }

    @Synchronized private fun voice(): Voice = voice ?: Voice(ctx).also { voice = it }

    /** Audio thread: the sentence so far. One live job at a time; extra ones are skipped. */
    private fun partial(pcm: ShortArray, fromCaller: Boolean) {
        if (mode == TranslateMode.OFF || !liveBusy.compareAndSet(false, true)) return
        val gen = if (fromCaller) callerGen else myGen
        val g = gen.get()
        runCatching {
            liveJobs.execute {
                try {
                    val from = if (fromCaller) callerLang else myLang
                    val to = if (fromCaller) myLang else callerLang
                    val heard = speech.transcribeLive(pcm, rate, from)?.trim().orEmpty()
                    if (heard.isNotEmpty() && !Speech.isJunk(heard) && gen.get() == g) {
                        Captions.live(fromCaller, heard, null)
                        speech.translateLive(heard, from, to) { soFar -> if (gen.get() == g) Captions.live(fromCaller, heard, soFar) }
                    }
                } finally {
                    liveBusy.set(false)
                }
            }
        }.onFailure { liveBusy.set(false) }
    }

    private fun handle(pcm: ShortArray, fromCaller: Boolean) {
        if (mode == TranslateMode.OFF) return
        val from = if (fromCaller) callerLang else myLang
        val t0 = System.currentTimeMillis()
        val heard = speech.transcribe(pcm, rate, from)?.trim().orEmpty()
        val t1 = System.currentTimeMillis()
        Captions.endLive(fromCaller)
        if (heard.isEmpty() || Speech.isJunk(heard)) return
        val id = seq.incrementAndGet()
        Captions.add(Caption(id, fromCaller, heard, null))   // show what was heard right away
        val audioMs = pcm.size * 1000L / rate
        runCatching { (if (fromCaller) callerMt else myMt).execute { finish(id, heard, fromCaller, audioMs, t1 - t0) } }
    }

    /** Translates one recognized line (shown as it is written) and speaks it where asked. */
    private fun finish(id: Long, heard: String, fromCaller: Boolean, audioMs: Long, sttMs: Long) {
        val from = if (fromCaller) callerLang else myLang
        val to = if (fromCaller) myLang else callerLang
        val t1 = System.currentTimeMillis()
        val context = synchronized(recent) { recent.toList() }
        val said = speech.translate(heard, from, to, context) { soFar -> Captions.update(id, soFar) }?.trim()
        val t2 = System.currentTimeMillis()
        Captions.update(id, said ?: "(not translated)")
        synchronized(recent) { recent.addLast(heard); while (recent.size > 3) recent.removeFirst() }
        var tts = 0L
        if (!said.isNullOrEmpty()) {
            val target = when {
                !fromCaller && mode == TranslateMode.VOICE -> toCaller
                fromCaller && prefs.speakToMe -> toMe
                else -> null
            }
            if (target != null) {
                val out = voice().synth(said, to, rate)
                tts = System.currentTimeMillis() - t2
                if (out != null) enqueue(out, target)
            }
        }
        Log.i("translate: ${if (fromCaller) "caller" else "me"} audio=${audioMs}ms stt=${sttMs}ms mt=${t2 - t1}ms tts=${tts}ms")
    }

    private fun enqueue(out: ShortArray, q: LinkedBlockingQueue<ShortArray>) {
        var i = 0
        while (i < out.size) {
            val f = ShortArray(frame)
            System.arraycopy(out, i, f, 0, minOf(frame, out.size - i))
            if (!q.offer(f)) break
            i += frame
        }
    }
}

/**
 * Cuts 20 ms frames into utterances by loudness: starts on speech (with 300 ms lead-in),
 * ends after [pauseMs] of quiet (350 ms once a sentence runs past 4 s) or 12 s. The noise floor adapts so line hiss doesn't count as speech.
 */
class Segmenter(private val rate: Int, private val pauseMs: Int, private val onPartial: ((ShortArray) -> Unit)? = null,
                private val onUtterance: (ShortArray) -> Unit) {
    private var nextPartialMs = FIRST_PARTIAL_MS
    private val lead = ArrayDeque<ShortArray>()
    private val out = ArrayList<ShortArray>()
    private var speaking = false
    private var loudMs = 0
    private var quietMs = 0
    private var floor = 200.0

    fun push(frame: ShortArray) {
        val f = frame.copyOf()
        val ms = f.size * 1000 / rate
        val level = rms(f)
        val loud = level > max(MIN_LEVEL, floor * 3)
        if (!speaking) {
            if (!loud) floor = floor * 0.95 + level * 0.05
            lead.addLast(f)
            while (lead.size * ms > 300) lead.removeFirst()
            if (loud) { speaking = true; loudMs = ms; quietMs = 0; nextPartialMs = FIRST_PARTIAL_MS; out.addAll(lead); lead.clear() }
            return
        }
        out += f
        if (loud) { loudMs += ms; quietMs = 0 } else quietMs += ms
        val lenMs = out.size * ms
        // A long sentence ends on a shorter pause, so its translation doesn't wait for a full stop.
        val pause = if (lenMs > 4000) minOf(pauseMs, 350) else pauseMs
        if (quietMs >= pause || lenMs >= 12_000) {
            if (loudMs >= 300) onUtterance(Pcm.concat(out))
            speaking = false
            out.clear()
        } else if (onPartial != null && loud && lenMs >= nextPartialMs) {
            nextPartialMs = lenMs + PARTIAL_EVERY_MS
            onPartial(Pcm.concat(out))
        }
    }

    private fun rms(f: ShortArray): Double {
        var s = 0.0
        for (v in f) s += v.toDouble() * v
        return sqrt(s / max(1, f.size))
    }

    private companion object {
        const val MIN_LEVEL = 300.0
        const val FIRST_PARTIAL_MS = 2000
        const val PARTIAL_EVERY_MS = 3000
    }
}

/** Small PCM16 helpers: join, resample, WAV in and out. */
object Pcm {
    fun concat(parts: List<ShortArray>): ShortArray {
        val all = ShortArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { System.arraycopy(p, 0, all, o, p.size); o += p.size }
        return all
    }

    /** Linear resampling; when going down, averages each window first so 24 kHz TTS doesn't alias at 8 kHz. */
    fun resample(x: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to || x.isEmpty()) return x
        val ratio = from.toDouble() / to
        val n = (x.size / ratio).toInt()
        return ShortArray(n) { i ->
            val p = i * ratio
            if (ratio > 1) {
                val a = p.toInt(); val b = minOf(x.size, (p + ratio).toInt().coerceAtLeast(a + 1))
                var s = 0L
                for (j in a until b) s += x[j]
                (s / (b - a)).toInt().toShort()
            } else {
                val j = p.toInt(); val t = p - j
                val a = x[minOf(j, x.size - 1)]; val b = x[minOf(j + 1, x.size - 1)]
                (a + (b - a) * t).toInt().toShort()
            }
        }
    }

    fun bytes(x: ShortArray): ByteArray {
        val b = ByteArray(x.size * 2)
        for (i in x.indices) { b[2 * i] = x[i].toInt().toByte(); b[2 * i + 1] = (x[i].toInt() shr 8).toByte() }
        return b
    }

    fun wav(x: ShortArray, rate: Int): ByteArray {
        val data = bytes(x)
        val h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(data.size)
        return h.array() + data
    }

    /** Reads a 16-bit PCM WAV (first channel) and resamples it to [rate]. */
    fun readWav(file: java.io.File, rate: Int): ShortArray? {
        val b = java.nio.ByteBuffer.wrap(file.readBytes()).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        if (b.remaining() < 12) return null
        b.position(12)
        var src = 0; var ch = 1
        while (b.remaining() >= 8) {
            val id = ByteArray(4).also { b.get(it) }.decodeToString()
            var len = b.int
            if (id == "fmt ") {
                val start = b.position()
                b.short; ch = b.short.toInt(); src = b.int
                b.position(start + len)
            } else if (id == "data") {
                if (len <= 0 || len > b.remaining()) len = b.remaining()
                if (src == 0) return null
                val n = len / 2 / ch
                val x = ShortArray(n) { i -> b.getShort(b.position() + i * 2 * ch) }
                return resample(x, src, rate)
            } else {
                b.position(minOf(b.limit(), b.position() + len + (len and 1)))
            }
        }
        return null
    }
}
