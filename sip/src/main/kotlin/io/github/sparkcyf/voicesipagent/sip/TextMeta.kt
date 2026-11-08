package io.github.sparkcyf.voicesipagent.sip

/**
 * SimBridge extras carried on a text MESSAGE as X- headers (ignored by other SIP apps):
 * [ref] lets the gateway report delivery back for one text, [sim] picks or names the SIM slot
 * (1 or 2) on a dual-SIM phone.
 */
data class TextMeta(val ref: String? = null, val sim: Int? = null) {
    internal fun writeTo(h: SipHeaders) {
        ref?.let { h.set(REF, it) }
        sim?.let { h.set(SIM, it.toString()) }
    }

    companion object {
        const val REF = "X-SimBridge-Ref"
        const val SIM = "X-SimBridge-Sim"
        private val SAFE_REF = Regex("[A-Za-z0-9_-]{1,40}")

        internal fun readFrom(h: SipHeaders) = TextMeta(
            ref = h[REF]?.trim()?.takeIf { SAFE_REF.matches(it) },
            sim = h[SIM]?.trim()?.toIntOrNull()?.takeIf { it in 1..4 },
        )
    }
}
