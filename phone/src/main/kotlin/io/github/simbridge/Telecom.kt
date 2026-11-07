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
    fun isEnabled(context: Context): Boolean = runCatching {
        context.getSystemService(TelecomManager::class.java).callCapablePhoneAccounts.contains(handle(context))
    }.getOrDefault(false)
}
