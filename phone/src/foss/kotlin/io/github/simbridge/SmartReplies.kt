package io.github.simbridge

/** F-Droid build: no Google ML Kit, so no suggested replies. */
object SmartReplies {
    @Suppress("UNUSED_PARAMETER") fun suggest(history: List<Msg>): List<String> = emptyList()
}
