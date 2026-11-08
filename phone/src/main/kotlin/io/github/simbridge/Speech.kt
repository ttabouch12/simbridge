package io.github.simbridge

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Speech to text and translation. Cloud (Groq's free tier: Whisper + an LLM) when a key is set and
 * on-device-only is off; any cloud failure (offline, rate limit) falls back to on-device for that line.
 */
class Speech(private val ctx: Context, private val prefs: Prefs) {
    private val cloud get() = !prefs.translateOnDevice && prefs.groqKey.isNotBlank()

    fun transcribe(pcm: ShortArray, rate: Int, lang: String): String? {
        if (cloud) runCatching { return groqTranscribe(pcm, rate, lang) }
            .onFailure { Log.w("translate: cloud speech failed, using on-device", it); Captions.say("Cloud speech failed (${it.message}); using on-device") }
        return OnDeviceStt.transcribe(ctx, pcm, rate, lang)
    }

    fun translate(text: String, from: String, to: String): String? {
        if (cloud) runCatching { return groqTranslate(text, from, to) }
            .onFailure { Log.w("translate: cloud translation failed, using on-device", it); Captions.say("Cloud translation failed (${it.message}); using on-device") }
        return OfflineTranslate.translate(ctx, text, from, to)
    }

    private fun groqTranscribe(pcm: ShortArray, rate: Int, lang: String): String {
        val boundary = "simbridge" + System.nanoTime()
        val c = open("audio/transcriptions", "multipart/form-data; boundary=$boundary")
        c.outputStream.use { o ->
            fun field(name: String, value: String) =
                o.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            field("model", "whisper-large-v3-turbo")
            field("language", lang)
            field("temperature", "0")
            field("response_format", "json")
            o.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            o.write(Pcm.wav(pcm, rate))
            o.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return JSONObject(read(c)).getString("text")
    }

    private fun groqTranslate(text: String, from: String, to: String): String {
        val model = prefs.translateModel
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_completion_tokens", 600)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "Translate the user's message from ${name(from)} to ${name(to)}. It is speech recognized from a phone call: " +
                        "keep meaning and tone, fix obvious recognition mistakes, keep names and numbers. Reply with the translation only."))
                .put(JSONObject().put("role", "user").put("content", text)))
        if (model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", "low")
        val c = open("chat/completions", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return JSONObject(read(c)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun open(path: String, type: String): HttpURLConnection =
        (URL("https://api.groq.com/openai/v1/$path").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 4000
            readTimeout = 12000
            setRequestProperty("Authorization", "Bearer ${prefs.groqKey}")
            setRequestProperty("Content-Type", type)
        }

    private fun read(c: HttpURLConnection): String = try {
        val code = c.responseCode
        if (code !in 200..299) throw IOException("HTTP $code " + (c.errorStream?.bufferedReader()?.readText()?.take(160) ?: ""))
        c.inputStream.bufferedReader().readText()
    } finally { c.disconnect() }

    private fun name(code: String) = if (code == "tr") "Turkish" else "English"

    companion object {
        /** Lines Whisper invents from silence or noise (video-subtitle habits). */
        private val junk = listOf("altyazı", "izlediğiniz için", "abone ol", "thank you for watching", "thanks for watching",
            "subtitles by", "amara.org")

        fun isJunk(text: String): Boolean {
            val t = text.lowercase(Locale.ROOT).trim(' ', '.', '!', '?')
            return t.length < 2 || (t.length < 60 && junk.any { t.contains(it) })
        }
    }
}

/** Android's on-device recognizer, fed our PCM through a pipe (no second microphone user). */
object OnDeviceStt {
    private val main = Handler(Looper.getMainLooper())

    private fun intent(lang: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "tr") "tr-TR" else "en-US")

    fun transcribe(ctx: Context, pcm: ShortArray, rate: Int, lang: String): String? {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
            Captions.say("This phone has no on-device speech recognizer: add a Groq key in Settings"); return null
        }
        val audio = Pcm.resample(pcm, rate, 16000)
        val pipe = ParcelFileDescriptor.createPipe()
        val req = intent(lang)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
        val done = CountDownLatch(1)
        var text: String? = null
        var rec: SpeechRecognizer? = null
        main.post {
            rec = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx) }.getOrNull()?.apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(b: Bundle) {
                        text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); done.countDown()
                    }
                    override fun onError(error: Int) {
                        if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                            runCatching { triggerModelDownload(intent(lang)) }
                            Captions.say("Downloading the offline ${if (lang == "tr") "Turkish" else "English"} speech model…")
                        } else if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                            Log.w("translate: on-device recognizer error $error")
                        }
                        done.countDown()
                    }
                    override fun onReadyForSpeech(p: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(v: Float) = Unit
                    override fun onBufferReceived(b: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(b: Bundle?) = Unit
                    override fun onEvent(t: Int, b: Bundle?) = Unit
                })
                startListening(req)
            }
            if (rec == null) done.countDown()
        }
        Thread {
            runCatching { ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { it.write(Pcm.bytes(audio)) } }
        }.start()
        done.await(15, TimeUnit.SECONDS)
        main.post { rec?.destroy() }
        runCatching { pipe[0].close() }
        return text
    }

    /** Asks the system to fetch the Turkish and English offline models (call from the main thread). */
    fun prepare(ctx: Context) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        listOf("tr", "en").forEach { runCatching { r.triggerModelDownload(intent(it)) } }
        main.postDelayed({ r.destroy() }, 2000)
    }
}

/** Android text-to-speech, rendered to a file so it can go into the call instead of the speaker. */
class Voice(ctx: Context) {
    private val dir = ctx.cacheDir
    private val ready = CountDownLatch(1)
    @Volatile private var ok = false
    @Volatile private var spoken = CountDownLatch(1)
    private val tts = TextToSpeech(ctx.applicationContext) { ok = it == TextToSpeech.SUCCESS; ready.countDown() }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { spoken.countDown() }
            @Deprecated("Deprecated in Java") override fun onError(id: String?) { spoken.countDown() }
        })
    }

    /** Speech for [text] as mono PCM at [rate], or null if the voice isn't installed. */
    fun synth(text: String, locale: Locale, rate: Int): ShortArray? {
        if (!ready.await(5, TimeUnit.SECONDS) || !ok) { Captions.say("Text-to-speech isn't available"); return null }
        val lang = tts.setLanguage(locale)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Captions.say("Install the Turkish voice: Settings > System > Languages > Text-to-speech"); return null
        }
        val f = File.createTempFile("tts", ".wav", dir)
        try {
            spoken = CountDownLatch(1)
            if (tts.synthesizeToFile(text, Bundle(), f, "u" + System.nanoTime()) != TextToSpeech.SUCCESS) return null
            if (!spoken.await(15, TimeUnit.SECONDS)) return null
            return Pcm.readWav(f, rate)
        } finally { f.delete() }
    }

    fun shutdown() = tts.shutdown()
}
