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

    fun handle(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("op")) {
            "status" -> send(status())
            "ussd" -> ussd(o.optString("code"), o.optInt("sim", 0).takeIf { it > 0 })
            "control" -> control(o.optString("action"), o.optBoolean("on", true))
            "block-rules" -> saveRules(o)
            "mms-send" -> MmsGateway.sendFromPhone(app, o) { st, detail -> o.optString("ref").ifBlank { null }?.let { smsStatus(it, st, detail) } }
            "sms-app" -> smsApp(o.optBoolean("on", true))
            else -> AppLog.w("control: unknown op ${o.optString("op").take(20)}")
        }
    }

    fun send(o: JSONObject): Boolean = rt.sipAgent?.sendControl(o.toString()) == true

    // ------------------------------------------------------------ events to the phone

    /** Missed / blocked call while the main phone could not be reached (or by rule). Queued if offline. */
    fun callEvent(kind: String, number: String?, sim: Int?) {
        val o = JSONObject().put("op", kind).put("from", number ?: "").put("at", System.currentTimeMillis())
        sim?.let { o.put("sim", it) }
        ContactNames.lookup(app, number)?.let { o.put("name", it) }
        if (!send(o)) enqueue(o)
        AppLog.i("control: $kind call from ${redactNumber(number)}${if (rt.sipAgent?.status?.ready == true) "" else " (queued)"}")
    }

    fun smsStatus(ref: String, status: String, detail: String? = null) {
        val o = JSONObject().put("op", "sms-status").put("ref", ref).put("status", status)
        detail?.let { o.put("detail", it) }
        if (!send(o)) enqueue(o)
    }

    @Synchronized private fun enqueue(o: JSONObject) {
        val q = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrDefault(JSONArray())
        q.put(o)
        while (q.length() > 50) q.remove(0)
        prefs.edit().putString("queue", q.toString()).apply()
    }

    /** Large events (MMS) wait as files rather than in preferences. */
    fun queueBig(o: JSONObject) {
        val d = java.io.File(app.filesDir, "outbox").apply { mkdirs() }
        java.io.File(d, "${System.currentTimeMillis()}.json").writeText(o.toString())
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
        } else prefs.getString("prev_sms_app", null) ?: "com.samsung.android.messaging"
        AppLog.i("control: SMS app -> ${runCatching { svc.remoteAction("sms-role", target) }.getOrElse { "error: ${it.message}" }}")
    }

    /** Called when a phone registers. */
    @Synchronized fun flush() {
        java.io.File(app.filesDir, "outbox").listFiles()?.sortedBy { it.name }?.forEach { f ->
            val o = runCatching { JSONObject(f.readText()) }.getOrNull()
            if (o == null || send(o)) f.delete() else return@forEach
        }
        val q = runCatching { JSONArray(prefs.getString("queue", "[]")) }.getOrDefault(JSONArray())
        if (q.length() == 0) return
        val left = JSONArray()
        for (i in 0 until q.length()) { val o = q.getJSONObject(i); if (!send(o)) left.put(o) }
        prefs.edit().putString("queue", left.toString()).apply()
    }

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
        for (i in 0 until list.length()) if (sameNumber(n, list.getString(i))) return true
        return false
    }

    private fun normalize(n: String?): String? = n?.filter { it.isDigit() || it == '+' }?.takeIf { it.count(Char::isDigit) >= 3 }

    /** Compares the last 9 digits so "+90 555…" matches "0555…". */
    private fun sameNumber(a: String, b: String): Boolean {
        val da = a.filter(Char::isDigit); val db = b.filter(Char::isDigit)
        val k = minOf(9, da.length, db.length)
        return k >= 3 && da.takeLast(k) == db.takeLast(k) && (k >= 9 || da.length == db.length)
    }

    // ------------------------------------------------------------ dashboard

    @SuppressLint("MissingPermission")
    fun status(): JSONObject {
        val o = JSONObject().put("op", "status").put("at", System.currentTimeMillis())
        BatteryInfo.read(app)?.let { o.put("battery", it.percent).put("charging", it.charging) }
        o.put("powerSaver", rt.companionPower.value).put("quiet", rt.quietFlags.value)
        o.put("uptimeMin", SystemClock.elapsedRealtime() / 60_000)
        o.put("version", runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "")
        o.put("hotspot", hotspotOn())
        o.put("smsApp", MmsGateway.isDefaultSmsApp(app))
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
                .put("roaming", runCatching { t.isNetworkRoaming }.getOrDefault(false)))
        } })
        return o
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
    private fun ussd(code: String, sim: Int?) {
        val clean = code.trim()
        fun reply(ok: Boolean, text: String) = send(JSONObject().put("op", "ussd-result").put("code", clean).put("ok", ok).put("text", text))
        if (!Regex("[0-9*#+]{2,40}").matches(clean) || !clean.endsWith("#")) { reply(false, "Not a USSD code"); return }
        if (app.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            reply(false, "The SIM phone needs the Phone permission"); return
        }
        val base = app.getSystemService(TelephonyManager::class.java)
        val tm = sim?.let { Sims.subIdForSlot(app, it) }?.let { runCatching { base.createForSubscriptionId(it) }.getOrNull() } ?: base
        AppLog.i("control: USSD request")
        main.post {
            runCatching {
                tm.sendUssdRequest(clean, object : TelephonyManager.UssdResponseCallback() {
                    override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) {
                        reply(true, response.toString())
                    }
                    override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) {
                        reply(false, "The network did not answer ($failureCode)")
                    }
                }, main)
            }.onFailure { reply(false, it.message ?: "USSD failed") }
        }
    }

    // ------------------------------------------------------------ remote switches

    private fun control(action: String, on: Boolean) {
        AppLog.i("control: $action ${if (on) "on" else "off"}")
        when (action) {
            "power-saver" -> rt.setCompanionPower(on)
            "mobile-data", "hotspot" -> privileged(action, if (on) "on" else "off")
            "find" -> if (on) FindPhone.start(app) else FindPhone.stop()
            "restart" -> rt.restartSip()
            else -> return
        }
        // Report the new state once things settle.
        main.postDelayed({ send(status()) }, if (action == "hotspot") 6000 else 2500)
    }

    private fun privileged(action: String, arg: String) {
        val svc = rt.shizuku.service
        val r = if (svc == null) "error: Shizuku is not running" else runCatching { svc.remoteAction(action, arg) }.getOrElse { "error: ${it.message}" }
        AppLog.i("control: $action -> $r")
        if (r.startsWith("error")) send(JSONObject().put("op", "control-result").put("action", action).put("ok", false).put("text", r.removePrefix("error: ")))
    }
}

/** Active SIM cards: 1-based slot numbers, as the user sees them. */
object Sims {
    data class Sim(val slot: Int, val subId: Int, val name: String, val carrier: String)

    @SuppressLint("MissingPermission")
    fun list(ctx: Context): List<Sim> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val sm = ctx.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        return runCatching { sm.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList()).map {
            Sim(it.simSlotIndex + 1, it.subscriptionId, it.displayName?.toString().orEmpty(), it.carrierName?.toString().orEmpty())
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
