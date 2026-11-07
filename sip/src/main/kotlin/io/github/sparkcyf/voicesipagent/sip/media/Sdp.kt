package io.github.sparkcyf.voicesipagent.sip.media

/** The single audio stream we care about in an SDP body. */
data class SdpAudio(
    val address: String,
    val port: Int,
    val payloadTypes: List<Int>,
    val rtpmap: Map<Int, String>,
    val direction: String,
    /** First `a=crypto` line we support (suite AES_CM_128_HMAC_SHA1_80): tag and inline key. */
    val crypto: SdpCrypto? = null,
    /** True if the m-line asked for RTP/SAVP (SRTP). */
    val secure: Boolean = false,
) {
    /** The codec a payload type stands for (rtpmap name + clock rate, or a static type). */
    fun codecFor(pt: Int): AudioCodec? {
        val map = rtpmap[pt] ?: return AudioCodec.entries.firstOrNull { !it.isDynamic && it.payloadType == pt }
        val parts = map.split('/')
        val rate = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        val channels = parts.getOrNull(2)?.trim()?.toIntOrNull() ?: 1
        return if (channels == 1) AudioCodec.byName(parts[0].trim(), rate) else null
    }

    /** First payload type in the peer's preference order that we can decode and are allowed to use. */
    fun firstSupportedCodec(allowed: Collection<AudioCodec> = AudioCodec.entries): AudioCodec? =
        payloadTypes.firstNotNullOfOrNull { pt -> codecFor(pt)?.takeIf { it in allowed } }

    fun payloadFor(codec: AudioCodec): Int = payloadTypes.firstOrNull { codecFor(it) == codec } ?: codec.payloadType

    /** RFC 4733 event payload type, preferring one whose clock matches [rate]. */
    fun telephoneEventPt(rate: Int = 8000): Int? {
        val te = rtpmap.entries.filter { it.value.startsWith("telephone-event/", ignoreCase = true) && it.key in payloadTypes }
        return (te.firstOrNull { it.value.substringAfter('/').substringBefore('/').trim() == rate.toString() } ?: te.firstOrNull())?.key
    }

    val telephoneEventPt: Int? get() = telephoneEventPt(8000)

    /** True if the peer is not going to send us audio / doesn't want ours (hold). */
    val isOnHold: Boolean get() = address == "0.0.0.0" || direction == "sendonly" || direction == "inactive"
}

data class SdpCrypto(val tag: Int, val inlineKey: String)

object Sdp {
    const val DEFAULT_TE_PT = 101

    fun build(
        sessionId: Long,
        version: Long,
        localIp: String,
        rtpPort: Int,
        codecs: List<Pair<Int, AudioCodec>>,
        telephoneEventPt: Int?,
        direction: String = "sendrecv",
        /** When set, offer/answer SRTP (RTP/SAVP) with this key. */
        crypto: SdpCrypto? = null,
        /** With [crypto]: true = RTP/SAVP (SRTP required), false = RTP/AVP + a=crypto (best effort). */
        savp: Boolean = true,
    ): String {
        val pts = codecs.map { it.first } + listOfNotNull(telephoneEventPt)
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=- $sessionId $version IN IP4 $localIp\r\n")
        sb.append("s=voice-sip-agent\r\n")
        sb.append("c=IN IP4 $localIp\r\n")
        sb.append("t=0 0\r\n")
        sb.append("m=audio $rtpPort ${if (crypto != null && savp) "RTP/SAVP" else "RTP/AVP"} ${pts.joinToString(" ")}\r\n")
        for ((pt, codec) in codecs) sb.append("a=rtpmap:$pt ${codec.encodingName}/${codec.clockRate}\r\n")
        if (telephoneEventPt != null) {
            // Events use the clock of the first (preferred) codec.
            sb.append("a=rtpmap:$telephoneEventPt telephone-event/${codecs.firstOrNull()?.second?.clockRate ?: 8000}\r\n")
            sb.append("a=fmtp:$telephoneEventPt 0-16\r\n")
        }
        if (crypto != null) sb.append("a=crypto:${crypto.tag} ${SrtpContext.SUITE} inline:${crypto.inlineKey}\r\n")
        sb.append("a=ptime:20\r\n")
        sb.append("a=$direction\r\n")
        return sb.toString()
    }

    fun parse(text: String): SdpAudio? {
        var sessionConn: String? = null
        var mediaConn: String? = null
        var port = -1
        var pts = emptyList<Int>()
        val rtpmap = HashMap<Int, String>()
        var direction = "sendrecv"
        var crypto: SdpCrypto? = null
        var secure = false
        var inAudio = false
        var seenAudio = false
        for (raw in text.split('\n')) {
            val line = raw.trimEnd('\r')
            if (line.length < 2 || line[1] != '=') continue
            val v = line.substring(2)
            when (line[0]) {
                'm' -> {
                    if (seenAudio) { inAudio = false; continue }
                    val parts = v.split(' ')
                    inAudio = parts[0] == "audio" && parts.size >= 4 && parts[1].substringBefore('/').toIntOrNull() != null
                    if (inAudio) {
                        seenAudio = true
                        port = parts[1].substringBefore('/').toInt()
                        pts = parts.drop(3).mapNotNull { it.toIntOrNull() }
                        secure = parts[2].uppercase().contains("SAVP")
                    }
                }
                'c' -> {
                    val addr = v.split(' ').getOrNull(2)?.substringBefore('/')
                    if (inAudio) mediaConn = addr else if (!seenAudio) sessionConn = addr
                }
                'a' -> {
                    if (seenAudio && !inAudio) continue
                    when {
                        v.startsWith("rtpmap:") -> {
                            val pt = v.substring(7).substringBefore(' ').toIntOrNull()
                            if (pt != null) rtpmap[pt] = v.substringAfter(' ').trim()
                        }
                        v == "sendrecv" || v == "sendonly" || v == "recvonly" || v == "inactive" -> direction = v
                        v.startsWith("crypto:") && crypto == null -> {
                            val f = v.substring(7).split(' ')
                            val inline = f.getOrNull(2)
                            if (f.size >= 3 && f[1].equals(SrtpContext.SUITE, true) && inline != null && inline.startsWith("inline:")) {
                                f[0].toIntOrNull()?.let { crypto = SdpCrypto(it, inline.removePrefix("inline:")) }
                            }
                        }
                    }
                }
            }
        }
        if (!seenAudio) return null
        val addr = mediaConn ?: sessionConn ?: return null
        return SdpAudio(addr, port, pts, rtpmap, if (port == 0) "inactive" else direction, crypto, secure)
    }

    /** Direction to answer with, mirroring the offer. */
    fun answerDirection(offerDirection: String): String = when (offerDirection) {
        "sendonly" -> "recvonly"
        "recvonly" -> "sendonly"
        "inactive" -> "inactive"
        else -> "sendrecv"
    }
}
