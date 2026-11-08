package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.voice.AbstractCallLeg
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import io.github.sparkcyf.voicesipagent.voice.LegDirection
import io.github.sparkcyf.voicesipagent.voice.LegState
import io.github.sparkcyf.voicesipagent.voice.MediaPort
import io.github.sparkcyf.voicesipagent.voice.Party
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture

/** Server mode: one registered main phone; [ip] is its binding key (see SipUserAgent.bindingKey). */
internal class PhoneBinding(val ip: String, @Volatile var contactUri: String, @Volatile var addr: InetSocketAddress) {
    val since = System.nanoTime()
    var expiry: ScheduledFuture<*>? = null
}

/**
 * An outgoing call rung on several phones at once. The first phone to answer wins: the others
 * are cancelled and this leg then behaves exactly like the winner. Declining on one phone leaves
 * the others ringing; the call only fails when every phone has given up.
 */
internal class ForkedCall(id: String, remote: Party, target: String?) :
    AbstractCallLeg(id, "sip", LegDirection.OUTBOUND, remote, target) {
    private val children = CopyOnWriteArrayList<SipCall>()
    @Volatile private var winner: SipCall? = null
    @Volatile private var hungUp = false

    fun add(child: SipCall) {
        children += child
        child.addListener(object : CallLeg.Listener {
            override fun onStateChanged(leg: CallLeg, state: LegState) = onChild(child, state)
            override fun onMediaChanged(leg: CallLeg, media: MediaPort?) { if (winner === child) publishMedia(media) }
            override fun onDtmf(leg: CallLeg, digit: Char, durationMs: Int) { if (winner === child) publishDtmf(digit, durationMs) }
        })
    }

    private fun onChild(child: SipCall, state: LegState) {
        when (state) {
            LegState.PROCEEDING -> transition(LegState.PROCEEDING)
            LegState.RINGING -> transition(LegState.RINGING)
            LegState.CONNECTED -> {
                val won = synchronized(this) { if (winner == null && !hungUp) { winner = child; true } else false }
                if (!won) { if (winner !== child) child.hangup(HangupCause.ANSWERED_ELSEWHERE); return }
                children.filter { it !== child }.forEach { it.hangup(HangupCause.ANSWERED_ELSEWHERE) }
                transition(LegState.CONNECTED)
                child.media?.let { publishMedia(it) }
            }
            LegState.ENDED -> {
                val w = winner
                when {
                    w === child -> transition(LegState.ENDED, child.endCause)
                    w == null && children.all { it.state == LegState.ENDED } ->
                        transition(LegState.ENDED, bestCause())
                }
            }
            else -> Unit
        }
    }

    /** Busy anywhere means busy; otherwise no answer. */
    private fun bestCause(): HangupCause {
        val causes = children.mapNotNull { it.endCause }
        return when {
            hungUp -> causes.firstOrNull() ?: HangupCause.NORMAL
            HangupCause.BUSY in causes -> HangupCause.BUSY
            HangupCause.REJECTED in causes && causes.all { it == HangupCause.REJECTED } -> HangupCause.REJECTED
            HangupCause.NO_ANSWER in causes -> HangupCause.NO_ANSWER
            else -> causes.firstOrNull() ?: HangupCause.NO_ANSWER
        }
    }

    override fun ring() = Unit
    override fun answer() = Unit

    override fun hangup(cause: HangupCause) {
        hungUp = true
        val w = winner
        if (w != null) w.hangup(cause) else children.forEach { it.hangup(cause) }
        transition(LegState.ENDED, cause)
    }

    override fun sendDtmf(digit: Char, durationMs: Int) { winner?.sendDtmf(digit, durationMs) }

    /** The phone that answered (for stats); null while ringing. */
    val answered: SipCall? get() = winner
}
