package io.github.simbridge

/** Finds the tappable parts of a text: web links, e-mail addresses, phone numbers and one-time codes. */
object Linkify {
    enum class Kind { URL, EMAIL, PHONE, CODE }

    /** [target] is what opens (a full URL, mailto: or tel:) or, for a code, what is copied. */
    data class Span(val start: Int, val end: Int, val kind: Kind, val target: String)

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}")
    private const val TLDS = "com|net|org|io|co|me|app|dev|info|biz|gov|edu|ly|gl|tr|uk|de|fr|es|ru|ir|ae|sa|eu|us|ca|in|nl|it"
    private val URL = Regex(
        "(?i)(?<![@\\w.])(?:(?:https?://|www\\.)[^\\s<>\"]+" +                       // explicit links
            "|[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.(?:$TLDS)(?![a-z0-9-])(?:/[^\\s<>\"]*)?" +   // bank.com.tr, bit.ly/x
            "|[a-z0-9-]+(?:\\.[a-z0-9-]+)+/[^\\s<>\"]+)")                             // any.host/path
    private val PHONE = Regex("(?<![\\w/.:=-])\\+?\\(?\\d[\\d ()-]{6,}\\d(?![\\w/])")

    fun spans(text: String): List<Span> {
        val out = ArrayList<Span>()
        fun free(s: Int, e: Int) = out.none { s < it.end && it.start < e }
        EMAIL.findAll(text).forEach { m -> out += Span(m.range.first, m.range.last + 1, Kind.EMAIL, "mailto:" + m.value) }
        URL.findAll(text).forEach { m ->
            // Sentence punctuation after a link is not part of it.
            val v = m.value.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '\'', '"')
            val s = m.range.first; val e = s + v.length
            if (v.length > 3 && free(s, e)) out += Span(s, e, Kind.URL, if (v.startsWith("http", true)) v else "https://$v")
        }
        PHONE.findAll(text).forEach { m ->
            val digits = m.value.count { it.isDigit() }
            val s = m.range.first; val e = m.range.last + 1
            if (digits in 8..15 && free(s, e)) out += Span(s, e, Kind.PHONE, "tel:" + m.value.filter { it.isDigit() || it == '+' })
        }
        Otp.find(text)?.let { code ->
            // The code may be written "123 456" in the text.
            val m = Regex("(?<!\\d)" + code.toCharArray().joinToString("[ -]?") + "(?!\\d)").find(text)
            if (m != null && free(m.range.first, m.range.last + 1)) out += Span(m.range.first, m.range.last + 1, Kind.CODE, code)
        }
        return out.sortedBy { it.start }
    }
}
