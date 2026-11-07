package io.github.sparkcyf.voicesipagent.sip

import io.github.sparkcyf.voicesipagent.sip.media.AudioCodec
import java.net.InetAddress
import javax.net.ssl.SSLContext

data class SipAccount(
    /** Registrar / proxy host (IP or name). */
    val server: String,
    val port: Int = 5060,
    val username: String,
    val password: String,
    val authUsername: String = username,
    /** SIP domain for AoR and Request-URIs; defaults to [server]. */
    val domain: String = server,
    val displayName: String? = null,
) {
    override fun toString() = "SipAccount($username@$server:$port)"
}

data class SipConfig(
    val account: SipAccount,
    /** Local SIP UDP port; 0 picks an ephemeral one. */
    val localPort: Int = 0,
    val registerExpiresSec: Int = 300,
    val userAgent: String = "voice-sip-agent/0.1",
    val codecs: List<AudioCodec> = listOf(AudioCodec.PCMU, AudioCodec.PCMA),
    val rtpPortRange: IntRange = 10000..20000,
    /** Hang up a connected call after this long without RTP from the peer. */
    val rtpTimeoutMs: Long = 30_000,
    /** Give up on an unanswered outbound INVITE after this long. */
    val ringTimeoutMs: Long = 60_000,
    /** Only accept SIP/RTP from the registrar's address (LAN deployment). */
    val acceptOnlyFromServer: Boolean = true,
    /**
     * Device-in-itself mode: act as the SIP registrar. Instead of registering outward to a PBX,
     * accept REGISTER from a single softphone and route cellular calls to its registered contact.
     * When on, [acceptOnlyFromServer] is ignored (the peer address is learned from REGISTER).
     */
    val serverMode: Boolean = false,
    /**
     * Server mode: lease (seconds) granted to the softphone's REGISTER. The phone refreshes at
     * roughly half this, so a longer value survives background Doze on the softphone. We hold the
     * last contact for about twice this before giving up, tolerating a few missed refreshes.
     */
    val registrarLeaseSec: Int = 600,
    /**
     * Server mode: password the softphone must present to REGISTER (SIP digest auth, RFC 3261).
     * Empty disables authentication — anyone who can reach the port may register and place calls
     * through the SIM, so leave it empty only on a trusted LAN. When set, an unauthenticated
     * REGISTER is answered with 401 and a challenge; the phone replies with its digest response.
     * The username is taken from the phone's own REGISTER (shared secret, single phone).
     */
    val registrarPassword: String = "",
    /** Server mode: realm advertised in the auth challenge. */
    val registrarRealm: String = "voice-sip-agent",
    /** SRTP for call audio. REQUIRED refuses calls whose peer doesn't offer/accept SRTP. */
    val srtp: SrtpMode = SrtpMode.OFF,
    /**
     * SIP over TLS. Server mode: listen on [TlsSettings.port] with the context's key.
     * Client mode: connect to `account.server:account.port` over TLS (the context pins the server).
     */
    val tls: TlsSettings? = null,
    /** Server mode: also listen for plain SIP/UDP on [localPort] (other SIP apps). */
    val udpEnabled: Boolean = true,
    /** Server mode: drop packets/connections from sources this rejects (e.g. mobile data). */
    val sourceFilter: ((InetAddress) -> Boolean)? = null,
    /** Server mode: require digest credentials on every out-of-dialog INVITE and MESSAGE too. */
    val authEveryRequest: Boolean = true,
    /** Client TLS: keep-alive ping interval (RFC 5626 CRLF). */
    val keepAliveSec: Int = 120,
)

data class TlsSettings(val sslContext: SSLContext, val port: Int)

/** SRTP policy for calls (RFC 3711 + SDES keys in SDP; only safe over an encrypted signalling transport). */
enum class SrtpMode { OFF, OPTIONAL, REQUIRED }

/** Minimal logging hook so the stack stays free of platform dependencies. */
interface SipLog {
    fun info(msg: String)
    fun warn(msg: String, t: Throwable? = null)

    /** Full SIP message dumps; off unless explicitly enabled (they contain numbers). */
    fun trace(msg: String) {}

    object Stdout : SipLog {
        override fun info(msg: String) = println("[sip] $msg")
        override fun warn(msg: String, t: Throwable?) = println("[sip] WARN $msg${t?.let { ": $it" } ?: ""}")
    }
}
