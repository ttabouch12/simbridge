package io.github.simbridge.ui

import android.content.Context
import android.content.ContextWrapper
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import io.github.simbridge.LinkCore
import io.github.simbridge.R

/**
 * Runs an action on the SIM phone only after the fingerprint (or screen lock) when "Lock SIM
 * phone controls" is on. One unlock is good for 2 minutes.
 */
@Composable
fun rememberGuard(core: LinkCore): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    return remember(core) { { action -> Guard.run(ctx, core, action) } }
}

internal object Guard {
    @Volatile private var okUntil = 0L

    fun run(ctx: Context, core: LinkCore, action: () -> Unit) {
        if (!core.prefs.lockControls || System.currentTimeMillis() < okUntil) { action(); return }
        val auth = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        // No lock set up on this phone: nothing to ask for.
        if (ctx.getSystemService(BiometricManager::class.java).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { action(); return }
        BiometricPrompt.Builder(ctx)
            .setTitle(ctx.getString(R.string.guard_title))
            .setSubtitle(ctx.getString(R.string.guard_sub))
            .setAllowedAuthenticators(auth)
            .build()
            .authenticate(CancellationSignal(), ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    okUntil = System.currentTimeMillis() + 120_000
                    action()
                }
            })
    }

    tailrec fun activity(c: Context): ComponentActivity? = when (c) {
        is ComponentActivity -> c
        is ContextWrapper -> activity(c.baseContext)
        else -> null
    }
}
