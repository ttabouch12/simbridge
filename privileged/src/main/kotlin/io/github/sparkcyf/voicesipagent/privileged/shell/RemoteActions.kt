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
    private val APN_SAFE = Regex("[A-Za-z0-9_.,@\\- ]{0,64}")
    private val AP_IFACES = listOf("ap0", "swlan0", "wlan1", "softap0", "wlan2")

    // Bit = 1 << (TelephonyManager.NETWORK_TYPE_x - 1)
    private fun bits(vararg t: Int) = t.fold(0L) { m, x -> m or (1L shl (x - 1)) }
    private val G2 = bits(1, 2, 4, 7, 11, 16)                       // GPRS EDGE CDMA 1xRTT IDEN GSM
    private val G3 = bits(3, 5, 6, 8, 9, 10, 12, 14, 15, 17)        // UMTS EVDO HSPA(+) EHRPD TD-SCDMA
    private val G4 = bits(13, 18, 19)                               // LTE IWLAN LTE-CA
    private val NR = bits(20)
    private val NET_MASKS = linkedMapOf("5g" to (G2 or G3 or G4 or NR), "4g" to (G2 or G3 or G4), "3g" to (G2 or G3), "2g" to G2)

    fun run(ctx: Context, self: String, action: String, arg: String): String = when (action) {
        "mobile-data" -> {
            val on = arg == "on"
            val r = ShellEnv.run("svc", "data", if (on) "enable" else "disable")
            if (r.exit == 0) "mobile data ${if (on) "on" else "off"}" else "error: ${r.out.trim()}"
        }
        "hotspot" -> hotspot(ctx, arg == "on")
        "screen-stream" -> ScreenStream.start(arg)
        "screen-stop" -> ScreenStream.stop()
        "ussd-dial" -> ussdDial(arg)
        "ussd-reply" -> ussdAnswer(arg)
        "ussd-cancel" -> { ussdButton("button2") || run { ShellEnv.run("input", "keyevent", "KEYCODE_BACK"); true }; "ok" }
        // Networks saved on this phone, one SSID per line.
        "wifi-saved" -> ShellEnv.run("cmd", "wifi", "list-networks").out.lineSequence().drop(1)
            .mapNotNull { l -> l.trim().split(Regex("\\s{2,}")).getOrNull(1)?.takeIf { it.isNotBlank() } }.distinct().joinToString("\n")
        // "ssid\npassword" (empty password: open network).
        "wifi-connect" -> {
            val (ssid, pw) = arg.split('\n', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            require(ssid.length in 1..32 && pw.length <= 63 && '\n' !in pw) { "bad network" }
            ShellEnv.run("svc", "wifi", "enable")
            // No password: the network is saved on this phone, which joins it once Wi-Fi is on.
            if (pw.isEmpty()) return "connecting"
            val r = ShellEnv.run("cmd", "wifi", "connect-network", ssid, "wpa2", pw)
            if (r.exit == 0) "connecting" else "error: ${r.out.trim()}"
        }
        "wifi-ssid" -> Regex("connected to \"([^\"]*)\"").find(ShellEnv.run("cmd", "wifi", "status").out)?.groupValues?.get(1).orEmpty()
        // Make [arg] (this app, or the previous holder when restoring) the default SMS app.
        "sms-role" -> {
            require(PKG.matches(arg)) { "bad package" }
            val r = ShellEnv.run("cmd", "role", "add-role-holder", "--user", "0", "android.app.role.SMS", arg, "0")
            if (r.exit == 0) "sms app: $arg" else "error: ${r.out.trim()}"
        }
        "role-holder" -> ShellEnv.run("cmd", "role", "get-role-holders", "--user", "0", "android.app.role.SMS").out.trim()
        // Devices on the hotspot: the neighbour table of the soft-AP interfaces.
        "hotspot-clients" -> ShellEnv.run("ip", "neigh", "show").out.lineSequence()
            .filter { l -> AP_IFACES.any { " dev $it " in l } && ("REACHABLE" in l || "STALE" in l || "DELAY" in l) }
            .joinToString("\n")
        // arg "slot:mode", slot 0-based, mode 5g / 4g / 3g / 2g = highest generation allowed.
        "network-mode" -> {
            val (slot, mode) = arg.split(':', limit = 2).let { it[0].toInt() to it.getOrElse(1) { "" } }
            val mask = NET_MASKS[mode] ?: error("bad mode")
            val r = ShellEnv.run("cmd", "phone", "set-allowed-network-types-for-users", "-s", slot.toString(),
                java.lang.Long.toBinaryString(mask))
            if (r.exit == 0) "network: $mode" else "error: ${r.out.trim()}"
        }
        "network-mode-get" -> {
            val out = ShellEnv.run("cmd", "phone", "get-allowed-network-types-for-users", "-s", arg.toInt().toString()).out
            val mask = Regex("[01]{8,}").find(out)?.value?.toLongOrNull(2) ?: return "?"
            NET_MASKS.entries.firstOrNull { it.value == mask }?.key ?: if (mask and NR != 0L) "5g" else "custom"
        }
        "apn-list" -> ShellEnv.run("content", "query", "--uri", "content://telephony/carriers/current",
            "--projection", "_id:name:apn:type:numeric:current").out.trim()
        "apn-preferred" -> ShellEnv.run("content", "query", "--uri", "content://telephony/carriers/preferapn",
            "--projection", "_id:name:apn").out.trim()
        "apn-select" -> {
            val id = arg.toLong()
            val r = ShellEnv.run("content", "insert", "--uri", "content://telephony/carriers/preferapn", "--bind", "apn_id:l:$id")
            if (r.exit == 0) "apn: $id" else "error: ${r.out.trim()}"
        }
        // arg "name|apn|mcc|mnc|type|user|password"
        "apn-add" -> {
            val f = arg.split('|')
            require(f.size >= 4 && f.all { v -> APN_SAFE.matches(v) }) { "bad APN fields" }
            val binds = mutableListOf("name:s:${f[0]}", "apn:s:${f[1]}", "mcc:s:${f[2]}", "mnc:s:${f[3]}",
                "numeric:s:${f[2]}${f[3]}", "type:s:${f.getOrElse(4) { "default,supl,mms" }.ifBlank { "default,supl,mms" }}")
            f.getOrNull(5)?.takeIf { it.isNotBlank() }?.let { binds += "user:s:$it" }
            f.getOrNull(6)?.takeIf { it.isNotBlank() }?.let { binds += "password:s:$it" }
            val r = ShellEnv.run("content", "insert", "--uri", "content://telephony/carriers", *binds.flatMap { listOf("--bind", it) }.toTypedArray())
            if (r.exit == 0) "apn added" else "error: ${r.out.trim()}"
        }
        // Location for "find my SIM phone": on for the fix, then back as it was.
        "location" -> {
            val r = ShellEnv.run("cmd", "location", "set-location-enabled", if (arg == "on") "true" else "false")
            if (r.exit == 0) "location $arg" else "error: ${r.out.trim()}"
        }
        "location-state" -> ShellEnv.run("cmd", "location", "is-location-enabled").out.trim()
        // Lets the gateway read data usage and its own location (once, idempotent).
        "grant-extras" -> {
            ShellEnv.run("appops", "set", self, "GET_USAGE_STATS", "allow")
            for (p in listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION")) ShellEnv.run("pm", "grant", self, p)
            "granted"
        }
        else -> "error: unknown action"
    }

    /**
     * Wi-Fi hotspot through TetheringManager (a system API the shell user may call). Experimental:
     * some vendors block it. Uses the hotspot settings already saved on the phone.
     */
    private fun hotspot(ctx: Context, on: Boolean): String {
        val tm = runCatching { shellTethering(ctx) }.onFailure { Log.w(TAG, "own TetheringManager failed", it) }.getOrNull()
            ?: ctx.getSystemService("tethering") ?: return "error: no tethering service"
        val cls = tm.javaClass
        if (!on) {
            cls.getMethod("stopTethering", Int::class.javaPrimitiveType).invoke(tm, 0)
            return "hotspot off"
        }
        val builder = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            .getConstructor(Int::class.javaPrimitiveType).newInstance(0)
        // Skip the carrier "may you tether?" check: on many phones it fails or waits for a dialog
        // nobody sees, so the hotspot never starts. The shell user holds TETHER_PRIVILEGED.
        runCatching { builder.javaClass.getMethod("setExemptFromEntitlementCheck", Boolean::class.javaPrimitiveType).invoke(builder, true) }
        runCatching { builder.javaClass.getMethod("setShouldShowEntitlementUi", Boolean::class.javaPrimitiveType).invoke(builder, false) }
        val request = builder.javaClass.getMethod("build").invoke(builder)
        val cbType = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val result = AtomicReference("error: no answer")
        val done = CountDownLatch(1)
        val cb = Proxy.newProxyInstance(cbType.classLoader, arrayOf(cbType)) { _, m, args ->
            when (m.name) {
                "onTetheringStarted" -> { result.set("hotspot on"); done.countDown() }
                "onTetheringFailed" -> {
                    val code = args?.firstOrNull()
                    // 5 = the Wi-Fi chip refused; most phones cannot host a hotspot while joined to Wi-Fi.
                    val wifi = Regex("connected to \"([^\"]*)\"").find(ShellEnv.run("cmd", "wifi", "status").out)?.groupValues?.get(1)
                    result.set(if (code == 5 && !wifi.isNullOrEmpty()) "error: the SIM phone is on Wi-Fi ($wifi); turn its Wi-Fi off first"
                        else "error: tethering failed ($code)")
                    done.countDown()
                }
            }
            null
        }
        val exec = Executor { it.run() }
        cls.getMethod("startTethering", request.javaClass, Executor::class.java, cbType).invoke(tm, request, exec, cb)
        done.await(8, TimeUnit.SECONDS)
        Log.i(TAG, "hotspot: ${result.get()}")
        return result.get()
    }


    /**
     * USSD the way the dialer does it, for networks that reject the background USSD API ("-1"):
     * dial the code, read the answer from the phone's USSD dialog, close it. [arg] = "slot:code".
     */
    private fun ussdDial(arg: String): String {
        val (slot, code) = arg.split(':', limit = 2).let { it[0].toInt() to it[1] }
        require(Regex("[0-9*#+]{2,40}").matches(code)) { "bad code" }
        ShellEnv.run("input", "keyevent", "KEYCODE_WAKEUP")
        ShellEnv.run("am", "start", "-a", "android.intent.action.CALL", "-d", "tel:" + code.replace("#", "%23"),
            "--ei", "com.android.phone.extra.slot", slot.toString(), "--ei", "simSlot", slot.toString(), "--ei", "slot", slot.toString())
        return awaitUssd(null)
    }

    /** Types [arg] into the open USSD menu and presses Send. */
    private fun ussdAnswer(arg: String): String {
        require(Regex("[0-9*#+]{1,40}").matches(arg)) { "bad reply" }
        val xml = dump()
        val before = ussdText(xml)
        val field = nodes(xml).firstOrNull { attr(it, "class") == "android.widget.EditText" } ?: return "error: no USSD menu is open"
        center(field)?.let { (x, y) -> ShellEnv.run("input", "tap", "$x", "$y") }
        ShellEnv.run("input", "text", arg)
        if (!ussdButton("button1")) ShellEnv.run("input", "keyevent", "KEYCODE_ENTER")
        return awaitUssd(before)
    }

    /** Waits for a new, stable USSD dialog text (not [previous]); leaves menus open, closes final answers. */
    private fun awaitUssd(previous: String?): String {
        val deadline = System.currentTimeMillis() + 25_000
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(1000)
            val xml = dump()
            val text = ussdText(xml)?.takeIf { it != previous } ?: continue
            if (text == last) {          // stable twice in a row: the network's answer, not "Running USSD…"
                val menu = nodes(xml).any { attr(it, "class") == "android.widget.EditText" }
                if (menu) return "session:$text"
                if (!ussdButton("button1")) ShellEnv.run("input", "keyevent", "KEYCODE_BACK")
                return text
            }
            last = text
        }
        return if (last.isNotEmpty()) last else "error: no answer from the network"
    }

    private fun dump() = ShellEnv.run("sh", "-c", "uiautomator dump /data/local/tmp/sb_ui.xml >/dev/null && cat /data/local/tmp/sb_ui.xml", timeoutSec = 8).out

    private val PHONE_PKG = Regex("package=\"(com\\.android\\.phone|com\\.samsung[^\"]*tele[^\"]*|com\\.android\\.mms[^\"]*)\"")
    private fun nodes(xml: String) = Regex("<node [^>]*>").findAll(xml).map { it.value }.filter { PHONE_PKG.containsMatchIn(it) }.toList()
    private fun attr(n: String, k: String) = Regex("$k=\"([^\"]*)\"").find(n)?.groupValues?.get(1).orEmpty()
    private fun center(n: String) = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]").find(attr(n, "bounds"))?.groupValues
        ?.let { g -> (g[1].toInt() + g[3].toInt()) / 2 to (g[2].toInt() + g[4].toInt()) / 2 }

    /** Taps the USSD dialog's button1 (Send/OK) or button2 (Cancel). */
    private fun ussdButton(id: String): Boolean {
        val n = nodes(dump()).firstOrNull { attr(it, "resource-id").endsWith("id/$id") } ?: return false
        val (x, y) = center(n) ?: return false
        ShellEnv.run("input", "tap", "$x", "$y")
        return true
    }

    /** The message of a USSD dialog in a uiautomator dump, if one is showing. */
    internal fun ussdText(xml: String): String? {
        val nodes = nodes(xml)
        val msg = nodes.firstOrNull { attr(it, "resource-id").endsWith("id/message") }?.let { attr(it, "text") }
            ?: nodes.filter { attr(it, "class") != "android.widget.EditText" }.map { attr(it, "text") }.filter { it.length > 12 }.maxByOrNull { it.length }
        return msg?.takeIf { it.isNotBlank() }?.replace("&#10;", "\n")?.replace("&amp;", "&")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&quot;", "\"")?.replace("&apos;", "'")
    }

    /**
     * A TetheringManager that names "com.android.shell" as the caller. The system one reports this
     * helper's context package, which does not match the shell uid, so tethering answers error 14
     * (no permission) even though the shell holds TETHER_PRIVILEGED.
     */
    private fun shellTethering(ctx: Context): Any {
        val shellCtx = object : android.content.ContextWrapper(ctx) {
            override fun getOpPackageName() = "com.android.shell"
            override fun getPackageName() = "com.android.shell"
            override fun getAttributionTag(): String? = null
            override fun getApplicationContext(): Context = this
        }
        val getService = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
        val ctor = Class.forName("android.net.TetheringManager")
            .getDeclaredConstructor(Context::class.java, java.util.function.Supplier::class.java).apply { isAccessible = true }
        return ctor.newInstance(shellCtx, java.util.function.Supplier { getService.invoke(null, "tethering") as android.os.IBinder })
    }
}
