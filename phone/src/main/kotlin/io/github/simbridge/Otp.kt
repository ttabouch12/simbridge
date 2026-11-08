package io.github.simbridge

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle

/**
 * One-time codes in texts ("Your code is 482913", "Doğrulama kodunuz: 482913"). Copied to the
 * clipboard (marked sensitive) so the keyboard offers it in the app asking for it.
 */
object Otp {
    private val HINT = Regex("(?i)(code|kod|otp|şifre|sifre|parola|pin|verif|doğrula|dogrula|password|passcode|token|código|codigo|code de|код|رمز|کد)")
    private val CODE = Regex("(?<![\\d+])(\\d{4,8}|\\d{3}[ -]\\d{3})(?!\\d)")

    /** The code in [text], or null if it doesn't look like a one-time code message. */
    fun find(text: String): String? {
        if (!HINT.containsMatchIn(text)) return null
        return CODE.findAll(text).map { it.value.filter(Char::isDigit) }.firstOrNull { it.length in 4..8 }
    }

    fun copy(ctx: Context, code: String) {
        val clip = ClipData.newPlainText("code", code).apply {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        runCatching { ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }
    }
}

class OtpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("code")?.let { Otp.copy(context, it) }
    }
}
