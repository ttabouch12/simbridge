package io.github.sparkcyf.voicesipagent.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.github.sparkcyf.voicesipagent.voice.redactNumber
import org.json.JSONArray
import org.json.JSONObject

/**
 * The SimBridge control channel on the SIM phone: JSON messages to and from the main phone over
 * the authenticated SIP link (status dashboard, USSD, remote switches, block rules), plus the
 * events this phone reports (missed and blocked calls, SMS delivery). Events wait in a small
 * persistent queue while no phone is registered.
 */
class GatewayControl(private val app: Context, private val rt: GatewayRuntime) {
    private val prefs = app.getSharedPreferences("control", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ incoming from the phone

    fun handle(json: String, from: java.net.InetSocketAddress? = null) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("op")) {
            "status" -> send(status(), from)
            "ussd" -> ussd(o.optString("code"), o.optInt("sim", 0).takeIf { it > 0 }, from)
            "ussd-reply" -> rt.worker { ussdReply("", privilegedRaw("ussd-reply", o.optString("text").take(40)), from) }
            "ussd-cancel" -> rt.worker { privilegedRaw("ussd-cancel", "") }
            "speed-start" -> {
                val token = java.util.UUID.randomUUID().toString().replace("-", "")
                runCatching { SpeedServer.start(token) }.onSuccess {
                    send(JSONObject().put("op", "speed-ready").put("port", SpeedServer.PORT).put("token", token), from)
                }.onFailure { AppLog.w("speed test: ${it.message}") }
            }
            "control" -> control(o.optString("action"), o.optBoolean("on", true), from)
            "block-rules" -> saveRules(o)
            "mms-send" -> MmsGateway.sendFromPhone(app, o) { st, detail -> o.optString("ref").ifBlank { null }?.let { smsStatus(it, st, detail) } }
            "sms-app" -> smsApp(o.optBoolean("on", true))
            "network-mode" -> {
                val slot = o.optInt("sim", 1)
                privileged("network-mode", "${slot - 1}:${o.optString("mode")}")
                main.postDelayed({ send(status(), from) }, 3000)
            }
            "apn-list" -> send(apns(), from)
            "apn-select" -> { privileged("apn-select", o.optLong("id").toString()); send(apns(), from) }
            "apn-add" -> {
                val f = listOf("name", "apn", "mcc", "mnc", "type", "user", "password").joinToString("|") { o.optString(it).replace("|", "") }
                privileged("apn-add", f); send(apns(), from)
            }
            "locate" -> locate(from)
            "voicemail" -> { prefs.edit().putInt("no_answer_sec", o.optInt("seconds").coerceIn(0, 120)).apply(); send(status(), from) }
            "contacts" -> ContactNames.importFromPhone(app, o.optJSONArray("list"))
            "data-detail" -> send(dataDetail(), from)
            "screen-start" -> screenStart(o.optInt("w", 1080), o.optInt("bitrate", 6_000_000), o.optInt("flags", 1), from)
            "screen-stop" -> privilegedRaw("screen-stop", "")
            "wifi-saved" -> send(JSONObject().put("op", "wifi-saved").put("list",
                JSONArray(privilegedRaw("wifi-saved", "").takeUnless { it.startsWith("error") }.orEmpty().lines().filter { it.isNotBlank() })), from)
            "places" -> prefs.edit().putString("places", o.toString()).apply()
            "at-place" -> atPlace(o.optString("ssid"), o.optString("pw"), from)
            else -> AppLog.w("control: unknown op ${o.optString("op").take(20)}")
        }
    }

    /** A reply (best effort), to the phone that asked when [to] is known. */
    fun send(o: JSONObject, to: java.net.InetSocketAddress? = null): Boolean = rt.sipAgent?.sendControl(o.toString(), to) == true

    /**
     * An event that must not be lost: sent now if a phone confirms it (2xx), otherwise kept and
     * sent again when a phone registers. Big ones (MMS) wait as files.
     */
    fun deliver(o: JSONObject) {
        val big = o.toString().length > 32_000
        val ok = rt.sipAgent?.sendControl(o.toString()) { code -> if (code !in 200..299) keep(o, big) } == true
        if (!ok) keep(o, big)
    }

    /** A relayed text that no phone confirmed. */
    fun keepSms(from: String?, text: String, sim: Int?) =
        keep(JSONObject().put("op", "__sms").put("from", from ?: "").put("text", text).apply { sim?.let { put("sim", it) } }, false)

    private fun keep(o: JSONObject, big: Boolean) {
        if (big) { queueBig(o); return }
        synchronized(this) {
            val q = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrDefault(JSONArray())
            q.put(o.put("qid", System.nanoTime()))
            while (q.length() > 200) q.remove(0)
            prefs.edit().putString("queue", q.toString()).apply()
        }
    }

    @Synchronized private fun unqueue(qid: Long) {
        val q = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrDefault(JSONArray())
        val left = JSONArray()
        for (i in 0 until q.length()) q.getJSONObject(i).let { if (it.optLong("qid") != qid) left.put(it) }
        prefs.edit().putString("queue", left.toString()).apply()
    }

    // ------------------------------------------------------------ events to the phone

    /** Missed / blocked call while the main phone could not be reached (or by rule). */
    fun callEvent(kind: String, number: String?, sim: Int?) {
        val o = JSONObject().put("op", kind).put("from", number ?: "").put("at", System.currentTimeMillis())
        sim?.let { o.put("sim", it) }
        ContactNames.lookup(app, number)?.let { o.put("name", it) }
        deliver(o)
        AppLog.i("control: $kind call from ${redactNumber(number)}")
    }

    fun smsStatus(ref: String, status: String, detail: String? = null) {
        val o = JSONObject().put("op", "sms-status").put("ref", ref).put("status", status)
        detail?.let { o.put("detail", it) }
        deliver(o)
    }

    /** Large events (MMS) wait as files rather than in preferences. */
    fun queueBig(o: JSONObject) {
        val d = java.io.File(app.filesDir, "outbox").apply { mkdirs() }
        java.io.File(d, "${System.currentTimeMillis()}-${System.nanoTime() % 1000}.json").writeText(o.toString())
        d.listFiles()?.sortedBy { it.name }?.dropLast(30)?.forEach { it.delete() }
    }

    /** Make this app the SIM phone's SMS app (for MMS), or give the role back. */
    fun smsApp(on: Boolean) {
        val svc = rt.shizuku.service
        if (svc == null) { AppLog.w("SMS app switch needs Shizuku; use the button on the SIM phone"); return }
        val current = runCatching { svc.remoteAction("role-holder", "") }.getOrDefault("").lineSequence().firstOrNull()?.trim().orEmpty()
        val target = if (on) {
            if (current.isNotEmpty() && current != app.packageName) prefs.edit().putString("prev_sms_app", current).apply()
            app.packageName
        } else prefs.getString("prev_sms_app", null) ?: "com.google.android.apps.messaging"
        AppLog.i("control: SMS app -> ${runCatching { svc.remoteAction("sms-role", target) }.getOrElse { "error: ${it.message}" }}")
    }

    /** Called when a phone registers: resend everything no phone has confirmed yet. */
    fun flush() {
        val ua = rt.sipAgent ?: return
        val q = synchronized(this) { runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrDefault(JSONArray()) }
        for (i in 0 until q.length()) {
            val o = q.getJSONObject(i)
            val qid = o.optLong("qid")
            if (o.optString("op") == "__sms") {
                val from = o.optString("from").ifBlank { null }
                ua.sendMessage(from, o.optString("text"), ContactNames.lookup(app, from),
                    io.github.sparkcyf.voicesipagent.sip.TextMeta(sim = o.optInt("sim", 0).takeIf { it > 0 })) { ok -> if (ok) unqueue(qid) }
            } else {
                val body = JSONObject(o.toString()).apply { remove("qid") }.toString()
                ua.sendControl(body) { code -> if (code in 200..299) unqueue(qid) }
            }
        }
        java.io.File(app.filesDir, "outbox").listFiles()?.sortedBy { it.name }?.forEach { f ->
            val body = runCatching { f.readText() }.getOrNull() ?: return@forEach
            ua.sendControl(body) { code -> if (code in 200..299) f.delete() }
        }
    }

    /** Seconds an unanswered call rings before it goes to the carrier's voicemail (0 = off). */
    val noAnswerSec: Int get() = prefs.getInt("no_answer_sec", 0)

    // ------------------------------------------------------------ call blocking

    private fun saveRules(o: JSONObject) {
        val nums = o.optJSONArray("numbers") ?: JSONArray()
        val clean = JSONArray()
        for (i in 0 until minOf(nums.length(), 500)) normalize(nums.optString(i))?.let { clean.put(it) }
        prefs.edit().putString("blocked", clean.toString()).putBoolean("block_hidden", o.optBoolean("hidden", false)).apply()
        AppLog.i("control: ${clean.length()} blocked numbers, hidden=${o.optBoolean("hidden", false)}")
    }

    /** True if an incoming cellular call from [number] should be rejected without ringing the phone. */
    fun isBlocked(number: String?): Boolean {
        val n = normalize(number)
        if (n == null) return prefs.getBoolean("block_hidden", false)
        val list = runCatching { JSONArray(prefs.getString("blocked", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until list.length()) if (io.github.sparkcyf.voicesipagent.voice.PhoneNumbers.same(n, list.getString(i))) return true
        return false
    }

    private fun normalize(n: String?): String? = n?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) >= 3 }


    // ------------------------------------------------------------ dashboard

    @SuppressLint("MissingPermission")
    fun status(): JSONObject {
        val o = JSONObject().put("op", "status").put("at", System.currentTimeMillis())
        BatteryInfo.read(app)?.let { b ->
            o.put("battery", b.percent).put("charging", b.charging)
            if (b.charging) runCatching { app.getSystemService(android.os.BatteryManager::class.java).computeChargeTimeRemaining() }
                .getOrNull()?.takeIf { it > 0 }?.let { o.put("chargeMin", (it / 60_000).toInt()) }
            else BatteryEstimate.minutesLeft(android.os.SystemClock.elapsedRealtime())?.let { o.put("batteryMin", it) }
        }
        o.put("powerSaver", rt.companionPower.value).put("quiet", rt.quietFlags.value)
        o.put("uptimeMin", SystemClock.elapsedRealtime() / 60_000)
        o.put("version", runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "")
        o.put("hotspot", hotspotOn())
        o.put("smsApp", MmsGateway.isDefaultSmsApp(app))
        o.put("noAnswerSec", noAnswerSec)
        if (hasPhoneState()) runCatching { app.getSystemService(TelephonyManager::class.java).voiceMailNumber }.getOrNull()?.let { o.put("voicemail", it) }
        o.put("model", "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}")
        // Marketing name ("Galaxy A56 5G") for the phone picture on the dashboard.
        runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull()?.let { o.put("deviceName", it) }
        o.put("android", android.os.Build.VERSION.RELEASE)
        dataUsage()?.let { (today, month) -> o.put("dataToday", today).put("dataMonth", month) }
        o.put("clients", hotspotClients())
        val tm = app.getSystemService(TelephonyManager::class.java)
        o.put("dataOn", runCatching { tm.isDataEnabled }.getOrDefault(false))
        o.put("sims", JSONArray().apply { Sims.list(app).forEach { s ->
            val t = runCatching { tm.createForSubscriptionId(s.subId) }.getOrDefault(tm)
            val sig = runCatching { t.signalStrength }.getOrNull()
            put(JSONObject()
                .put("slot", s.slot).put("name", s.name).put("carrier", runCatching { t.networkOperatorName }.getOrNull() ?: s.carrier)
                .put("level", sig?.level ?: -1)
                .put("dbm", sig?.cellSignalStrengths?.firstOrNull()?.dbm ?: 0)
                .put("network", if (hasPhoneState()) networkName(runCatching { t.dataNetworkType }.getOrDefault(0)) else "")
                .put("roaming", runCatching { t.isNetworkRoaming }.getOrDefault(false))
                .put("number", runCatching { app.getSystemService(SubscriptionManager::class.java).getPhoneNumber(s.subId) }.getOrNull() ?: "")
                .put("esim", s.esim)
                .put("mode", rt.shizuku.service?.let { svc -> runCatching { svc.remoteAction("network-mode-get", "${s.slot - 1}") }.getOrNull() } ?: ""))
        } })
        return o
    }

    /** Mobile data used today and this month (bytes), or null without usage access. */
    private fun dataUsage(): Pair<Long, Long>? = runCatching {
        val nsm = app.getSystemService(android.app.usage.NetworkStatsManager::class.java)
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val day = cal.timeInMillis
        cal.set(java.util.Calendar.DAY_OF_MONTH, 1)
        fun used(from: Long) = nsm.querySummaryForDevice(android.net.ConnectivityManager.TYPE_MOBILE, null, from, now)
            .let { it.rxBytes + it.txBytes }
        used(day) to used(cal.timeInMillis)
    }.getOrNull()

    /** Devices on this phone's hotspot (IP, short MAC), marking the paired main phones. */
    private fun hotspotClients(): JSONArray {
        val out = JSONArray()
        val raw = runCatching { rt.shizuku.service?.remoteAction("hotspot-clients", "") }.getOrNull().orEmpty()
        raw.lineSequence().filter { it.isNotBlank() }.forEach { l ->
            val ip = l.substringBefore(' ')
            val mac = Regex("lladdr ([0-9a-f:]{17})").find(l)?.groupValues?.get(1) ?: ""
            out.put(JSONObject().put("ip", ip).put("mac", mac.takeLast(5))
                .put("simbridge", rt.sipAgent?.isRegisteredAddress(ip) == true))
        }
        return out
    }

    /** APNs of the active SIM: [{id,name,apn,type,current}] and the preferred one. */
    private fun apns(): JSONObject {
        val svc = rt.shizuku.service
        val rows = runCatching { svc?.remoteAction("apn-list", "") }.getOrNull().orEmpty()
        val pref = runCatching { svc?.remoteAction("apn-preferred", "") }.getOrNull().orEmpty()
        val prefId = Regex("_id=(\\d+)").find(pref)?.groupValues?.get(1)?.toLongOrNull()
        fun field(row: String, k: String) = Regex("(?:^|, )$k=([^,]*)").find(row)?.groupValues?.get(1)?.trim().orEmpty()
        val list = JSONArray()
        rows.lineSequence().filter { it.startsWith("Row:") }.forEach { r ->
            val id = field(r, "_id").toLongOrNull() ?: return@forEach
            list.put(JSONObject().put("id", id).put("name", field(r, "name")).put("apn", field(r, "apn"))
                .put("type", field(r, "type")).put("preferred", id == prefId))
        }
        return JSONObject().put("op", "apns").put("list", list).put("ok", svc != null)
    }

    /** "Find my SIM phone": one location fix, turning location on just for it. */
    @SuppressLint("MissingPermission")
    private fun locate(from: java.net.InetSocketAddress?) {
        val svc = rt.shizuku.service
        runCatching { svc?.remoteAction("grant-extras", "") }
        val wasOn = runCatching { svc?.remoteAction("location-state", "") }.getOrNull()?.contains("true") != false
        if (!wasOn) runCatching { svc?.remoteAction("location", "on") }
        val lm = app.getSystemService(android.location.LocationManager::class.java)
        fun reply(l: android.location.Location?) {
            if (!wasOn) runCatching { svc?.remoteAction("location", "off") }
            send(if (l == null) JSONObject().put("op", "location").put("ok", false)
                else JSONObject().put("op", "location").put("ok", true).put("lat", l.latitude).put("lon", l.longitude)
                    .put("acc", l.accuracy.toDouble()).put("at", l.time), from)
            AppLog.i("control: location ${if (l == null) "not found" else "sent"}")
        }
        val provider = listOf("fused", android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { lm.isProviderEnabled(it) || it == "fused" }.getOrDefault(false) && lm.allProviders.contains(it) }
        if (provider == null) { reply(runCatching { lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER) }.getOrNull()); return }
        val cancel = android.os.CancellationSignal()
        var done = false
        main.postDelayed({ if (!done) { done = true; cancel.cancel(); reply(runCatching { lm.getLastKnownLocation(provider) }.getOrNull()) } }, 40_000)
        main.post {
            runCatching {
                lm.getCurrentLocation(provider, cancel, app.mainExecutor) { l -> if (!done) { done = true; reply(l) } }
            }.onFailure { if (!done) { done = true; reply(null) } }
        }
    }

    private fun hasPhoneState() = app.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    private fun hotspotOn(): Boolean = runCatching {
        val wm = app.getSystemService(WifiManager::class.java)
        wm.javaClass.getMethod("isWifiApEnabled").invoke(wm) as Boolean
    }.getOrDefault(false)

    private fun networkName(t: Int) = when (t) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "4G"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS -> "2G"
        TelephonyManager.NETWORK_TYPE_UNKNOWN -> ""
        else -> "?"
    }

    // ------------------------------------------------------------ USSD

    @SuppressLint("MissingPermission")
    private fun ussd(code: String, sim: Int?, from: java.net.InetSocketAddress?) {
        val clean = code.trim()
        if (!Regex("[0-9*#+]{2,40}").matches(clean) || !clean.endsWith("#")) { ussdReply(clean, "error: Not a USSD code", from); return }
        AppLog.i("control: USSD request")
        // Like the dialer: dial the code and read the network's dialog, so menus can be answered.
        if (rt.shizuku.service != null) { rt.worker { ussdReply(clean, privilegedRaw("ussd-dial", "${(sim ?: 1) - 1}:$clean"), from) }; return }
        // Without Shizuku: the background USSD API (single answers only).
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            ussdReply(clean, "error: The SIM phone needs the Phone permission", from); return
        }
        val base = app.getSystemService(TelephonyManager::class.java)
        val tm = sim?.let { Sims.subIdForSlot(app, it) }?.let { runCatching { base.createForSubscriptionId(it) }.getOrNull() } ?: base
        main.post {
            runCatching {
                tm.sendUssdRequest(clean, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) =
                        ussdReply(clean, response.toString(), from)
                    override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) =
                        ussdReply(clean, "error: The network did not answer ($failureCode)", from)
                }, main)
            }.onFailure { ussdReply(clean, "error: ${it.message ?: "USSD failed"}", from) }
        }
    }

    /** [r]: the dialog text, "session:" + text when the network waits for a reply, or "error: …". */
    private fun ussdReply(code: String, r: String, from: java.net.InetSocketAddress?) {
        val err = r.startsWith("error")
        val session = r.startsWith("session:")
        send(JSONObject().put("op", "ussd-result").put("code", code).put("ok", !err).put("session", session)
            .put("text", r.removePrefix("error: ").removePrefix("session:")), from)
    }

    // ------------------------------------------------------------ remote switches

    private fun control(action: String, on: Boolean, from: java.net.InetSocketAddress? = null) {
        AppLog.i("control: $action ${if (on) "on" else "off"}")
        when (action) {
            "power-saver" -> rt.setCompanionPower(on)
            "mobile-data", "hotspot" -> privileged(action, if (on) "on" else "off")
            "find" -> if (on) FindPhone.start(app) else FindPhone.stop()
            "restart" -> rt.restartSip()
            else -> return
        }
        if (action !in setOf("mobile-data", "hotspot"))
            send(JSONObject().put("op", "control-result").put("action", action).put("ok", true), from)
        // Report the new state now, and again once things settle.
        send(status(), from)
        main.postDelayed({ send(status(), from) }, if (action == "hotspot") 4000 else 1500)
    }

    /** Runs a privileged action and always reports the outcome, so the phone's spinner stops. */
    private fun privileged(action: String, arg: String) {
        val r = privilegedRaw(action, arg)
        AppLog.i("control: $action -> $r")
        send(JSONObject().put("op", "control-result").put("action", action).put("ok", !r.startsWith("error")).put("text", r.removePrefix("error: ")))
    }

    private fun privilegedRaw(action: String, arg: String): String {
        val svc = rt.shizuku.service ?: return "error: Shizuku is not running"
        return runCatching { svc.remoteAction(action, arg) }.getOrElse { "error: ${it.message}" }
    }

    // ------------------------------------------------------------ remote screen

    /** Opens the encrypted screen stream socket and tells the phone where and with which key. */
    private fun screenStart(w: Int, bitRate: Int, flags: Int, from: java.net.InetSocketAddress?) {
        val rnd = java.security.SecureRandom()
        fun hex() = ByteArray(16).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }
        val key = hex(); val ivd = hex(); val ivu = hex()
        val port = 5066
        val r = privilegedRaw("screen-stream", "$port $key $ivd $ivu $w $bitRate $flags")
        if (r.startsWith("error")) {
            send(JSONObject().put("op", "control-result").put("action", "screen").put("ok", false).put("text", r.removePrefix("error: ")), from)
            return
        }
        send(JSONObject().put("op", "screen-ready").put("port", port).put("key", key).put("ivd", ivd).put("ivu", ivu), from)
    }

    // ------------------------------------------------------------ home / work Wi-Fi

    /**
     * The main phone sees a saved place's Wi-Fi: join it here too and turn the hotspot off, so
     * both phones meet on that network. When this phone loses Wi-Fi again, the hotspot comes back.
     */
    private fun atPlace(ssid: String, pw: String, from: java.net.InetSocketAddress?) {
        if (ssid.isBlank()) return
        if (privilegedRaw("wifi-ssid", "") == ssid) { leaveHotspot(ssid, from); return }
        val r = privilegedRaw("wifi-connect", "$ssid\n$pw")
        AppLog.i("control: at-place -> $r")
        if (r.startsWith("error")) return
        main.postDelayed({ rt.worker { if (privilegedRaw("wifi-ssid", "") == ssid) leaveHotspot(ssid, from) } }, 10_000)
    }

    private fun leaveHotspot(ssid: String, from: java.net.InetSocketAddress?) {
        prefs.edit().putString("at_place", ssid).apply()
        if (hotspotOn()) privileged("hotspot", "off")
        watchWifiLoss()
        send(status(), from)
    }

    private var wifiWatch: android.net.ConnectivityManager.NetworkCallback? = null

    /** Called once when the gateway starts. */
    fun start() {
        watchWifiLoss()
        // After a restart away from every saved place: make sure the main phone can reach us.
        main.postDelayed({ rt.worker { hotspotIfNoWifi("started without Wi-Fi") } }, 45_000)
    }

    /** "Hotspot when Wi-Fi is lost" is on (the Places switch) and at least one place is saved. */
    private fun autoHotspot(): Boolean = runCatching {
        val p = JSONObject(prefs.getString("places", "{}")!!)
        p.optBoolean("auto", true) && (p.optJSONArray("list")?.length() ?: 0) > 0
    }.getOrDefault(false)

    private fun hotspotIfNoWifi(why: String) {
        if (!autoHotspot() || hotspotOn()) return
        val ssid = privilegedRaw("wifi-ssid", "")
        if (ssid.startsWith("error") || ssid.isNotEmpty()) return
        prefs.edit().remove("at_place").apply()
        AppLog.i("control: $why, hotspot on")
        privileged("hotspot", "on")
    }

    private fun watchWifiLoss() {
        if (wifiWatch != null) return
        val cm = app.getSystemService(android.net.ConnectivityManager::class.java)
        val req = android.net.NetworkRequest.Builder().addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI).build()
        wifiWatch = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onLost(network: android.net.Network) {
                if (!autoHotspot()) return
                // Brief drops happen; only act if Wi-Fi stays gone.
                main.postDelayed({ rt.worker { hotspotIfNoWifi("Wi-Fi lost") } }, 20_000)
            }
        }
        runCatching { cm.registerNetworkCallback(req, wifiWatch!!) }
    }

    // ------------------------------------------------------------ data usage detail

    /** Mobile bytes per day for the last 30 days and the top apps over the same period. */
    private fun dataDetail(): JSONObject {
        val o = JSONObject().put("op", "data-detail")
        runCatching {
            val nsm = app.getSystemService(android.app.usage.NetworkStatsManager::class.java)
            val cal = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                add(java.util.Calendar.DAY_OF_MONTH, -29)
            }
            val start = cal.timeInMillis
            val days = JSONArray()
            repeat(30) {
                val from = cal.timeInMillis
                cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
                val b = nsm.querySummaryForDevice(android.net.ConnectivityManager.TYPE_MOBILE, null, from, minOf(cal.timeInMillis, System.currentTimeMillis()))
                days.put(JSONObject().put("day", from).put("bytes", b.rxBytes + b.txBytes))
            }
            o.put("days", days)
            val perUid = HashMap<Int, Long>()
            nsm.querySummary(android.net.ConnectivityManager.TYPE_MOBILE, null, start, System.currentTimeMillis()).use { st ->
                val b = android.app.usage.NetworkStats.Bucket()
                while (st.hasNextBucket()) { st.getNextBucket(b); perUid.merge(b.uid, b.rxBytes + b.txBytes, Long::plus) }
            }
            val pm = app.packageManager
            o.put("apps", JSONArray().apply {
                perUid.entries.sortedByDescending { it.value }.take(10).forEach { (uid, bytes) ->
                    val name = when (uid) {
                        android.app.usage.NetworkStats.Bucket.UID_TETHERING -> "Hotspot"
                        android.app.usage.NetworkStats.Bucket.UID_REMOVED -> "Removed apps"
                        android.os.Process.SYSTEM_UID -> "System"
                        else -> pm.getPackagesForUid(uid)?.firstOrNull()?.let { p ->
                            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
                        } ?: "uid $uid"
                    }
                    put(JSONObject().put("name", name).put("bytes", bytes))
                }
            })
        }.onFailure { AppLog.w("control: data detail failed: ${it.message}") }
        return o
    }
}

/** Active SIM cards: 1-based slot numbers, as the user sees them. */
object Sims {
    data class Sim(val slot: Int, val subId: Int, val name: String, val carrier: String, val esim: Boolean = false)

    @SuppressLint("MissingPermission")
    fun list(ctx: Context): List<Sim> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching { sm.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList()).map {
            Sim(it.simSlotIndex + 1, it.subscriptionId, it.displayName?.toString().orEmpty(), it.carrierName?.toString().orEmpty(), it.isEmbedded)
        }.sortedBy { it.slot }
    }

    fun subIdForSlot(ctx: Context, slot: Int): Int? = list(ctx).firstOrNull { it.slot == slot }?.subId
    fun slotForSubId(ctx: Context, subId: Int): Int? = list(ctx).firstOrNull { it.subId == subId }?.slot
    fun dual(ctx: Context) = list(ctx).size > 1
}

/** Loud ring on the SIM phone so it can be found; stops by itself after a minute. */
object FindPhone {
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val main = Handler(Looper.getMainLooper())

    fun start(ctx: Context) = main.post {
        if (player != null) return@post
        val am = ctx.getSystemService(AudioManager::class.java)
        savedVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        runCatching { am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                setDataSource(ctx, uri); isLooping = true; prepare(); start()
            }
        }.onFailure { AppLog.w("find: no sound", it) }.getOrNull()
        runCatching {
            ctx.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        main.postDelayed({ stop(ctx) }, 60_000)
        this.ctx = ctx.applicationContext
    }

    private var ctx: Context? = null

    fun stop(c: Context? = ctx) = main.post {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        c?.let {
            runCatching { it.getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
            if (savedVolume >= 0) runCatching { it.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0) }
        }
        savedVolume = -1
    }
}
