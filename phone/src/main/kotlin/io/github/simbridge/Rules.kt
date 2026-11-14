package io.github.simbridge

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * What happens to an incoming text: rules first (sender, keyword, SIM), then the spam filter,
 * then quiet hours. Each rule's action: ALERT (always notify, even in quiet hours), SILENT (keep,
 * no sound), ARCHIVE (keep out of the list, no notification), BLOCK (drop).
 */
object Rules {
    enum class Action { ALERT, SILENT, ARCHIVE, BLOCK }

    /** [from]: part of the number or contact name (empty = anyone); [word]: part of the text. */
    data class Rule(val from: String, val word: String, val sim: Int, val action: Action) {
        fun json(): JSONObject = JSONObject().put("from", from).put("word", word).put("sim", sim).put("action", action.name)

        fun matches(number: String, name: String?, text: String, simSlot: Int): Boolean =
            (from.isBlank() || number.contains(from.filter { it.isDigit() || it == '+' }.ifEmpty { "\u0000" }) ||
                name?.contains(from, ignoreCase = true) == true) &&
                (word.isBlank() || text.contains(word, ignoreCase = true)) &&
                (sim == 0 || sim == simSlot)

        companion object {
            fun parse(o: JSONObject) = Rule(o.optString("from"), o.optString("word"), o.optInt("sim"),
                runCatching { Action.valueOf(o.optString("action")) }.getOrDefault(Action.SILENT))
        }
    }

    fun load(json: String): List<Rule> = runCatching { JSONArray(json) }.getOrDefault(JSONArray())
        .let { a -> (0 until a.length()).map { Rule.parse(a.getJSONObject(it)) } }

    fun save(list: List<Rule>): String = JSONArray().apply { list.forEach { put(it.json()) } }.toString()

    /** The decision for one text; null = the normal behaviour. */
    data class Decision(val action: Action, val why: String)

    fun decide(prefs: Prefs, number: String, name: String?, text: String, sim: Int, now: Calendar = Calendar.getInstance()): Decision? {
        load(prefs.rules).firstOrNull { it.matches(number, name, text, sim) }?.let { return Decision(it.action, "rule") }
        val vip = prefs.isVip(number)
        if (prefs.spamFilter && !vip && name == null && Spam.looksLike(number, text)) return Decision(Action.ARCHIVE, "spam")
        if (!vip && Quiet.now(prefs, now)) return Decision(Action.SILENT, "quiet")
        return null
    }
}

/** Unknown senders with promotion words or links (codes are never spam). */
object Spam {
    private val WORDS = Regex("(?i)(kampanya|indirim|fırsat|kazandınız|hediye|bonus|bahis|casino|kredi\\s*başvur|tıkla|" +
        "promo|discount|winner|you won|free gift|claim now|bet|loan offer|unsubscribe|iptal için|ret\\s*yaz|smsiptal)")
    private val LINK = Regex("(?i)(https?://|www\\.|\\b[a-z0-9-]+\\.(?:ly|gl|cc|xyz|top|click|link|site|info)\\b/?)")

    fun looksLike(number: String, text: String): Boolean {
        if (Otp.find(text) != null) return false
        // Alphanumeric senders (brands) and short codes are where promotions come from.
        val bulk = number.any { it.isLetter() } || number.count { it.isDigit() } in 3..6
        val words = WORDS.containsMatchIn(text)
        val link = LINK.containsMatchIn(text)
        return words && (link || bulk) || (link && bulk)
    }
}

/** Quiet hours: notifications and calls stay silent between two times (VIP contacts excepted). */
object Quiet {
    fun now(prefs: Prefs, c: Calendar = Calendar.getInstance()): Boolean {
        if (!prefs.quietOn) return false
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val s = prefs.quietStart; val e = prefs.quietEnd
        return if (s <= e) m in s until e else m >= s || m < e   // across midnight: 22:00–07:00
    }
}
