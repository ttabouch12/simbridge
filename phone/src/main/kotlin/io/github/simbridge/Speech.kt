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
import androidx.annotation.StringRes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Text translation engines. Cloud ones are free tiers with an OpenAI-compatible API. */
enum class Engine(val base: String, val model: String, @StringRes val title: Int, @StringRes val note: Int, val keyUrl: String) {
    GROQ_120B("https://api.groq.com/openai/v1/", "openai/gpt-oss-120b", R.string.eng_groq120, R.string.eng_groq120_n, "https://console.groq.com/keys"),
    GROQ_20B("https://api.groq.com/openai/v1/", "openai/gpt-oss-20b", R.string.eng_groq20, R.string.eng_groq20_n, "https://console.groq.com/keys"),
    CEREBRAS("https://api.cerebras.ai/v1/", "gpt-oss-120b", R.string.eng_cerebras, R.string.eng_cerebras_n, "https://cloud.cerebras.ai"),
    GEMINI("https://generativelanguage.googleapis.com/v1beta/openai/", "gemini-2.5-flash-lite", R.string.eng_gemini, R.string.eng_gemini_n, "https://aistudio.google.com/apikey"),
    ON_DEVICE("", "", R.string.eng_device, R.string.eng_device_n, ""),
}

/** Languages every engine handles (Whisper, the LLMs, ML Kit, Android TTS and recognizer). */
object Langs {
    val all = listOf("tr", "en", "ar", "fa", "ru", "uk", "fr", "de", "es", "it", "pt", "nl", "pl", "zh", "ja", "ko", "hi", "ur", "id")
    private val region = mapOf("tr" to "tr-TR", "en" to "en-US", "ar" to "ar-SA", "fa" to "fa-IR", "ru" to "ru-RU", "uk" to "uk-UA",
        "fr" to "fr-FR", "de" to "de-DE", "es" to "es-ES", "it" to "it-IT", "pt" to "pt-BR", "nl" to "nl-NL", "pl" to "pl-PL",
        "zh" to "zh-CN", "ja" to "ja-JP", "ko" to "ko-KR", "hi" to "hi-IN", "ur" to "ur-PK", "id" to "id-ID")

    fun tag(code: String) = region[code] ?: code
    fun locale(code: String): Locale = Locale.forLanguageTag(tag(code))
    /** English name, for prompts. */
    fun name(code: String): String = Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH)
    /** Name in that language ("Türkçe"), for pickers. */
    fun label(code: String): String = Locale.forLanguageTag(code).let { it.getDisplayLanguage(it).replaceFirstChar { c -> c.titlecase(it) } }
}

/**
 * Speech to text (Groq Whisper, or the on-device recognizer) and translation (the chosen engine).
 * Any cloud failure (offline, rate limit) falls back to on-device for that line.
 */
class Speech(private val ctx: Context, private val prefs: Prefs) {
    private val cloudSpeech get() = !prefs.translateOnDevice && prefs.groqKey.isNotBlank()

    fun transcribe(pcm: ShortArray, rate: Int, lang: String): String? {
        if (cloudSpeech) runCatching { return groqTranscribe(pcm, rate, lang) }
            .onFailure { Log.w("translate: cloud speech failed, using on-device", it); Captions.say("Cloud speech failed (${it.message}); using on-device") }
        return OnDeviceStt.transcribe(ctx, pcm, rate, lang)
    }

    /** [context]: the last few lines of the call, so short fragments translate correctly. */
    fun translate(text: String, from: String, to: String, context: List<String>): String? {
        val e = prefs.translateEngine
        val key = prefs.keyFor(e)
        if (!prefs.translateOnDevice && e != Engine.ON_DEVICE && key.isNotBlank()) runCatching { return chat(e, key, text, from, to, context) }
            .onFailure { Log.w("translate: cloud translation failed, using on-device", it); Captions.say("Cloud translation failed (${it.message}); using on-device") }
        return OfflineTranslate.translate(ctx, text, from, to)
    }

    /** Opens the TLS connections at call start, so the first line doesn't pay the handshake. */
    fun warmUp() {
        val e = prefs.translateEngine
        val hosts = buildList {
            if (cloudSpeech) add(Engine.GROQ_120B.base to prefs.groqKey)
            if (!prefs.translateOnDevice && e != Engine.ON_DEVICE && prefs.keyFor(e).isNotBlank()) add(e.base to prefs.keyFor(e))
        }.distinctBy { it.first }
        for ((base, key) in hosts) runCatching {
            val c = (URL(base + "models").openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000; readTimeout = 6000; setRequestProperty("Authorization", "Bearer $key")
            }
            read(c)
        }
    }

    private fun groqTranscribe(pcm: ShortArray, rate: Int, lang: String): String {
        val boundary = "simbridge" + System.nanoTime()
        val c = post(Engine.GROQ_120B.base + "audio/transcriptions", prefs.groqKey, "multipart/form-data; boundary=$boundary")
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

    private fun chat(e: Engine, key: String, text: String, from: String, to: String, context: List<String>): String {
        val model = prefs.translateModel.ifBlank { e.model }
        val earlier = if (context.isEmpty()) "" else
            "\nEarlier in this call (context only, do not translate):\n" + context.joinToString("\n")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("max_tokens", 600)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content",
                    "Translate the user's message from ${Langs.name(from)} to ${Langs.name(to)}. It is speech recognized from a phone call: " +
                        "keep meaning and tone, fix obvious recognition mistakes, keep names and numbers. Reply with the translation only.$earlier"))
                .put(JSONObject().put("role", "user").put("content", text)))
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low")
        val c = post(e.base + "chat/completions", key, "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        return JSONObject(read(c)).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun post(url: String, key: String, type: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 4000
            readTimeout = 12000
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", type)
        }

    /** Reads the whole body and closes the stream without disconnect(), so the connection is reused. */
    private fun read(c: HttpURLConnection): String {
        val code = c.responseCode
        if (code !in 200..299) {
            val err = c.errorStream?.use { it.bufferedReader().readText() }.orEmpty()
            throw IOException("HTTP $code ${err.take(160)}")
        }
        return c.inputStream.use { it.bufferedReader().readText() }
    }

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
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Langs.tag(lang))

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
                            Captions.say("Downloading the offline ${Langs.name(lang)} speech model…")
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

    /** Asks the system to fetch offline models for these languages (call from the main thread). */
    fun prepare(ctx: Context, langs: List<String>) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) return
        val r = SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
        langs.forEach { runCatching { r.triggerModelDownload(intent(it)) } }
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

    /** Speech for [text] as mono PCM at [rate], or null if the voice isn't installed. One at a time. */
    @Synchronized fun synth(text: String, lang: String, rate: Int): ShortArray? {
        if (!ready.await(5, TimeUnit.SECONDS) || !ok) { Captions.say("Text-to-speech isn't available"); return null }
        val r = tts.setLanguage(Langs.locale(lang))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            Captions.say("Install the ${Langs.name(lang)} voice: Settings > System > Languages > Text-to-speech"); return null
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
