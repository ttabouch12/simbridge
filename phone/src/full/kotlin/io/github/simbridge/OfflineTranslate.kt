package io.github.simbridge

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** On-device translation with Google ML Kit (free, offline once the ~30 MB models are downloaded). */
object OfflineTranslate {
    private val clients = ConcurrentHashMap<String, Translator>()

    private fun client(from: String, to: String) = clients.getOrPut("$from>$to") {
        Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build())
    }

    /** Worker thread only: blocks until translated (downloads the model the first time). */
    fun translate(@Suppress("UNUSED_PARAMETER") ctx: Context, text: String, from: String, to: String): String? = runCatching {
        val t = client(from, to)
        Tasks.await(t.downloadModelIfNeeded(), 90, TimeUnit.SECONDS)
        Tasks.await(t.translate(text), 10, TimeUnit.SECONDS)
    }.onFailure { Log.w("translate: on-device translation failed", it); Captions.say("On-device translation failed: ${it.message}") }.getOrNull()

    /** Downloads both directions in the background. */
    fun prepare(@Suppress("UNUSED_PARAMETER") ctx: Context) {
        client("tr", "en").downloadModelIfNeeded()
        client("en", "tr").downloadModelIfNeeded()
    }

}
