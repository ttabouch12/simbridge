package io.github.simbridge

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import java.util.concurrent.TimeUnit

/** Up to 3 short replies for the conversation, made on this phone (ML Kit; English and a few others). */
object SmartReplies {
    private val client by lazy { SmartReply.getClient() }

    /** Call off the main thread. [history]: oldest first. */
    fun suggest(history: List<Msg>): List<String> = runCatching {
        if (history.isEmpty() || history.last().outgoing) return emptyList()
        val conv = history.takeLast(10).filter { it.body.isNotBlank() }.map { m ->
            if (m.outgoing) TextMessage.createForLocalUser(m.body, m.time)
            else TextMessage.createForRemoteUser(m.body, m.time, m.address)
        }
        val r = Tasks.await(client.suggestReplies(conv), 3, TimeUnit.SECONDS)
        if (r.status != SmartReplySuggestionResult.STATUS_SUCCESS) emptyList() else r.suggestions.map { it.text }
    }.getOrDefault(emptyList())
}
