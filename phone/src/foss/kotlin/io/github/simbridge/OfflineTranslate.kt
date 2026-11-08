package io.github.simbridge

import android.content.Context

/** F-Droid build: no Google ML Kit, so translation needs the cloud (Groq key). */
object OfflineTranslate {
    fun translate(@Suppress("UNUSED_PARAMETER") ctx: Context, @Suppress("UNUSED_PARAMETER") text: String,
                  @Suppress("UNUSED_PARAMETER") from: String, @Suppress("UNUSED_PARAMETER") to: String): String? {
        Captions.say("Offline translation isn't in the F-Droid build: add a Groq key in Settings")
        return null
    }

    @Suppress("UNUSED_PARAMETER") fun prepare(ctx: Context, a: String, b: String) = Unit

}
