package io.github.sparkcyf.voicesipagent.privileged.shell

import android.content.Context
import android.util.Log
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shell-level switches the main phone may flip remotely. Only these fixed actions exist; the
 * argument is checked, never passed to a shell unvalidated.
 */
internal object RemoteActions {
    private const val TAG = "VsaRemote"
    private val PKG = Regex("[A-Za-z0-9_.]+")

    fun run(ctx: Context, self: String, action: String, arg: String): String = when (action) {
        "mobile-data" -> {
            val on = arg == "on"
            val r = ShellEnv.run("svc", "data", if (on) "enable" else "disable")
            if (r.exit == 0) "mobile data ${if (on) "on" else "off"}" else "error: ${r.out.trim()}"
        }
        "hotspot" -> hotspot(ctx, arg == "on")
        // Make [arg] (this app, or the previous holder when restoring) the default SMS app.
        "sms-role" -> {
            require(PKG.matches(arg)) { "bad package" }
            val r = ShellEnv.run("cmd", "role", "add-role-holder", "--user", "0", "android.app.role.SMS", arg, "0")
            if (r.exit == 0) "sms app: $arg" else "error: ${r.out.trim()}"
        }
        "role-holder" -> ShellEnv.run("cmd", "role", "get-role-holders", "--user", "0", "android.app.role.SMS").out.trim()
        else -> "error: unknown action"
    }

    /**
     * Wi-Fi hotspot through TetheringManager (a system API the shell user may call). Experimental:
     * some vendors block it. Uses the hotspot settings already saved on the phone.
     */
    private fun hotspot(ctx: Context, on: Boolean): String {
        val tm = ctx.getSystemService("tethering") ?: return "error: no tethering service"
        val cls = tm.javaClass
        if (!on) {
            cls.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, 0)
            return "hotspot off"
        }
        val builder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(0)
        val request = builder.javaClass.getMethod("build").invoke(builder)
        val cbType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val result = AtomicReference("error: no answer")
        val done = CountDownLatch(1)
        val cb = Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { _, m, args ->
            when (m.name) {
                "onTetheringStarted" -> { result.set("hotspot on"); done.countDown() }
                "onTetheringFailed" -> { result.set("error: tethering failed (${args?.firstOrNull()})"); done.countDown() }
            }
            null
        }
        val exec = Executor { it.run() }
        cls.getMethod("startTethering", request.javaClass, Executor::class.java, cbType).invoke(tm, request, exec, cb)
        done.await(8, TimeUnit.SECONDS)
        Log.i(TAG, "hotspot: ${result.get()}")
        return result.get()
    }
}
