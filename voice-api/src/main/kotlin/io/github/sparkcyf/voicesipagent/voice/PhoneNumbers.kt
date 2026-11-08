package io.github.sparkcyf.voicesipagent.voice

/** One rule for "is this the same phone number", shared by both apps (blocking, groups). */
object PhoneNumbers {
    /**
     * Compares the last 9 digits, so "+90 555 111 22 33" matches "0555 111 22 33". Short numbers
     * (fewer than 9 digits on either side) must match exactly.
     */
    fun same(a: String?, b: String?): Boolean {
        val da = a?.filter(Char::isDigit).orEmpty(); val db = b?.filter(Char::isDigit).orEmpty()
        val k = minOf(9, da.length, db.length)
        return k >= 3 && da.takeLast(k) == db.takeLast(k) && (k >= 9 || da.length == db.length)
    }
}
