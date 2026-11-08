package io.github.simbridge

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

/**
 * The "SimBridge" calling account. Once enabled (Phone app > Settings > Calling accounts), Google's
 * Phone app rings for SIM calls with its own call screen, logs them in its history, shows
 * contact names, and dials out through the SIM phone from its keypad.
 */
object Telecom {
    const val EXTRA_LEG = "io.github.simbridge.LEG"

    fun handle(context: Context) =
        PhoneAccountHandle(ComponentName(context, GatewayConnectionService::class.java), "simbridge")

    /** Per-SIM account on a dual-SIM gateway ("SIM 2 · Carrier"); [handle] when [sim] is 0 / null. */
    fun handle(context: Context, sim: Int?): PhoneAccountHandle =
        if (sim == null || sim <= 0) handle(context)
        else PhoneAccountHandle(ComponentName(context, GatewayConnectionService::class.java), "simbridge-$sim")

    /** SIM slot of one of our accounts (0 = the plain SimBridge account). */
    fun simOf(h: PhoneAccountHandle?): Int = h?.id?.removePrefix("simbridge-")?.toIntOrNull() ?: 0

    private var lastSims = ""

    /**
     * Dual-SIM gateway: one calling account per SIM so the Phone app asks which SIM to use, and
     * history shows the line. Single SIM: only the plain account.
     */
    fun syncSims(context: Context, sims: List<SimInfo>) {
        val key = if (sims.size > 1) sims.joinToString { "${it.slot}:${it.carrier}" } else ""
        if (key == lastSims) return
        lastSims = key
        val tm = context.getSystemService(TelecomManager::class.java)
        for (slot in 1..2) runCatching { tm.unregisterPhoneAccount(handle(context, slot)) }
        if (sims.size < 2) return
        for (s in sims) {
            val label = "SIM ${s.slot}${s.carrier.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}"
            val account = PhoneAccount.builder(handle(context, s.slot), label)
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
                .setShortDescription("$label through your SIM phone")
                .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
                .setIcon(Icon.createWithResource(context, R.drawable.ic_link).setTint(if (s.slot == 1) 0xFF1A73E8.toInt() else 0xFF188038.toInt()))
                .setHighlightColor(if (s.slot == 1) 0xFF1A73E8.toInt() else 0xFF188038.toInt())
                .build()
            runCatching { tm.registerPhoneAccount(account) }.onFailure { Log.w("telecom: SIM account failed", it) }
        }
        Log.i("telecom: ${sims.size} SIM accounts registered")
    }

    fun register(context: Context) {
        val tm = context.getSystemService(TelecomManager::class.java)
        val account = PhoneAccount.builder(handle(context), "SimBridge")
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .setShortDescription("Calls through your SIM phone")
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_link).setTint(0xFF1A73E8.toInt()))
            .setHighlightColor(0xFF1A73E8.toInt())
            .build()
        runCatching { tm.registerPhoneAccount(account) }.onFailure { Log.w("telecom: register failed", it) }
    }

    /** Enabled accounts are the ones Telecom lists as call-capable (needs READ_PHONE_STATE). */
    @android.annotation.SuppressLint("MissingPermission")
    fun isEnabled(context: Context, h: PhoneAccountHandle = handle(context)): Boolean = runCatching {
        context.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.contains(h)
    }.getOrDefault(false)
}
