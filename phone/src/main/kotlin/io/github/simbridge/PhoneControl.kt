package io.github.simbridge

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.BlockedNumberContract
import android.provider.CallLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One SIM card on the SIM phone, as reported by its dashboard. */
data class SimInfo(val slot: Int, val name: String, val carrier: String, val level: Int, val dbm: Int, val network: String,
                   val roaming: Boolean, val number: String = "", val esim: Boolean = false, val mode: String = "")

data class HotspotClient(val ip: String, val mac: String, val simbridge: Boolean)
data class Apn(val id: Long, val name: String, val apn: String, val type: String, val preferred: Boolean)
data class GatewayLocation(val ok: Boolean, val lat: Double, val lon: Double, val accuracy: Double, val at: Long)

data class GatewayStatus(
    val at: Long,
    val battery: Int?,
    val charging: Boolean,
    /** Minutes until empty (from real drain) or until full; null while not known yet. */
    val batteryMin: Int? = null,
    val chargeMin: Int? = null,
    /** Samsung battery protection: "off", or the percent charging stops at. Null when unknown. */
    val chargeLimit: String? = null,
    val dailyReport: Boolean = true,
    val powerSaver: Boolean,
    val dataOn: Boolean,
    val hotspot: Boolean,
    val uptimeMin: Long,
    val version: String,
    val sims: List<SimInfo>,
    val model: String = "",
    val deviceName: String = "",
    val androidVersion: String = "",
    val dataToday: Long? = null,
    val dataMonth: Long? = null,
    val clients: List<HotspotClient> = emptyList(),
    val smsApp: Boolean = false,
    val noAnswerSec: Int = 0,
    val voicemail: String = "",
) {
    companion object {
        fun parse(o: JSONObject) = GatewayStatus(
            at = System.currentTimeMillis(),
            battery = if (o.has("battery")) o.optInt("battery") else null,
            charging = o.optBoolean("charging"),
            batteryMin = if (o.has("batteryMin")) o.optInt("batteryMin") else null,
            chargeMin = if (o.has("chargeMin")) o.optInt("chargeMin") else null,
            chargeLimit = o.optString("chargeLimit").ifBlank { null },
            dailyReport = o.optBoolean("dailyReport", true),
            powerSaver = o.optBoolean("powerSaver"),
            dataOn = o.optBoolean("dataOn"),
            hotspot = o.optBoolean("hotspot"),
            uptimeMin = o.optLong("uptimeMin"),
            version = o.optString("version"),
            sims = (o.optJSONArray("sims") ?: JSONArray()).let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { s ->
                    SimInfo(s.optInt("slot", i + 1), s.optString("name"), s.optString("carrier"), s.optInt("level", -1),
                        s.optInt("dbm"), s.optString("network"), s.optBoolean("roaming"), s.optString("number"),
                        s.optBoolean("esim"), s.optString("mode"))
                } }
            },
            model = o.optString("model"),
            deviceName = o.optString("deviceName"),
            androidVersion = o.optString("android"),
            dataToday = if (o.has("dataToday")) o.optLong("dataToday") else null,
            dataMonth = if (o.has("dataMonth")) o.optLong("dataMonth") else null,
            clients = (o.optJSONArray("clients") ?: JSONArray()).let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { HotspotClient(it.optString("ip"), it.optString("mac"), it.optBoolean("simbridge")) } }
            },
            smsApp = o.optBoolean("smsApp"),
            noAnswerSec = o.optInt("noAnswerSec"),
            voicemail = o.optString("voicemail"),
        )
    }
}

/** A USSD answer; [session] = the network waits for a menu choice. */
data class UssdReply(val code: String, val ok: Boolean, val text: String, val session: Boolean = false, val at: Long = System.currentTimeMillis())

/**
 * The main phone's side of the SimBridge control channel: asks the SIM phone for its status,
 * runs USSD codes and remote switches, keeps its block list in sync, and records the calls it
 * reports (missed while this phone was unreachable, or blocked by rule).
 */
class PhoneControl(private val core: LinkCore) {
    private val ctx: Context get() = core.context
    private val _status = MutableStateFlow<GatewayStatus?>(null)
    val status: StateFlow<GatewayStatus?> = _status
    private val _ussd = MutableStateFlow<UssdReply?>(null)
    val ussd: StateFlow<UssdReply?> = _ussd
    private val _apns = MutableStateFlow<List<Apn>?>(null)
    val apns: StateFlow<List<Apn>?> = _apns
    private val _location = MutableStateFlow<GatewayLocation?>(null)
    val location: StateFlow<GatewayLocation?> = _location
    private val _notice = MutableStateFlow<String?>(null)
    /** One-off text for the UI (a failed remote switch). */
    val notice: StateFlow<String?> = _notice

    fun clearNotice() { _notice.value = null }

    fun handle(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("op")) {
            "status" -> _status.value = GatewayStatus.parse(o).also {
                settle(it)
                Telecom.syncSims(ctx, it.sims)
                Log.i("SIM phone: battery ${it.battery}% data=${it.dataOn} hotspot=${it.hotspot} saver=${it.powerSaver} " +
                    it.sims.joinToString { s -> "SIM${s.slot} ${s.network} level ${s.level}" })
            }
            "ussd-result" -> _ussd.value = UssdReply(o.optString("code"), o.optBoolean("ok"), o.optString("text"), o.optBoolean("session")).also {
                Log.i("USSD ${if (it.ok) "answered" else "failed"} (${it.text.length} chars)")
            }
            "control-result" -> {
                clearPending(o.optString("action"))
                if (!o.optBoolean("ok", true)) _notice.value = "${o.optString("action")}: ${o.optString("text")}"
            }
            "sms-status" -> smsStatus(o.optString("ref"), o.optString("status"))
            "missed", "blocked" -> callEvent(o)
            "mms" -> core.onIncomingMms(o)
            "data-detail" -> _dataDetail.value = DataDetail.parse(o)
            "screen-ready" -> _screen.value = ScreenOffer.parse(o)
            "apps" -> _apps.value = (o.optJSONArray("list") ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { e -> e.optString("pkg") to e.optString("name") } }
            } to (o.optJSONArray("chosen") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            "audit" -> _audit.value = (o.optJSONArray("list") ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getJSONObject(it).let { e -> AuditEntry(e.optLong("t"), e.optString("what"), e.optString("from")) } }
            }
            "speed-ready" -> _speed.value = o.optInt("port") to o.optString("token")
            "wifi-saved" -> _savedWifi.value = (o.optJSONArray("list") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } }
            "apns" -> _apns.value = (o.optJSONArray("list") ?: JSONArray()).let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let {
                    Apn(it.optLong("id"), it.optString("name"), it.optString("apn"), it.optString("type"), it.optBoolean("preferred"))
                } }
            }
            "location" -> _location.value = GatewayLocation(o.optBoolean("ok"), o.optDouble("lat"), o.optDouble("lon"),
                o.optDouble("acc"), o.optLong("at", System.currentTimeMillis()))
        }
    }

    private fun send(o: JSONObject): Boolean {
        val u = core.ua ?: return false
        if (!u.status.ready) return false
        u.sendControl(o.toString())
        return true
    }

    fun refresh() = send(JSONObject().put("op", "status"))

    /** Answers a USSD menu (the reply goes into the SIM phone's USSD dialog). */
    fun ussdReply(text: String): Boolean { _ussd.value = null; return send(JSONObject().put("op", "ussd-reply").put("text", text)) }
    fun ussdCancel() { _ussd.value = null; send(JSONObject().put("op", "ussd-cancel")) }

    fun ussd(code: String, sim: Int?): Boolean {
        _ussd.value = null
        return send(JSONObject().put("op", "ussd").put("code", code).apply { sim?.let { put("sim", it) } })
    }

    fun sendMms(to: List<String>, text: String, parts: JSONArray, ref: String, sim: Int): Boolean =
        send(JSONObject().put("op", "mms-send").put("to", JSONArray(to)).put("text", text).put("parts", parts).put("ref", ref)
            .apply { if (sim > 0) put("sim", sim) })

    fun networkMode(sim: Int, mode: String): Boolean {
        markPending("net:$sim", mode)
        return send(JSONObject().put("op", "network-mode").put("sim", sim).put("mode", mode))
    }

    // ---- pending actions: what the user asked for, shown at once, until the SIM phone confirms

    private val _pending = MutableStateFlow<Map<String, Any>>(emptyMap())
    /** action -> wanted value, while the SIM phone hasn't confirmed it yet. */
    val pending: StateFlow<Map<String, Any>> = _pending

    private fun markPending(key: String, value: Any) {
        _pending.value = _pending.value + (key to value)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ clearPending(key) }, 20_000)
    }

    fun clearPending(key: String) { _pending.value = _pending.value - key }

    /** Drops pending actions the new status confirms. */
    private fun settle(s: GatewayStatus) {
        val done = _pending.value.filter { (k, v) ->
            when (k) {
                "power-saver" -> s.powerSaver == v
                "mobile-data" -> s.dataOn == v
                "hotspot" -> s.hotspot == v
                else -> k.startsWith("net:") && s.sims.firstOrNull { "net:${it.slot}" == k }?.mode == v
            }
        }.keys
        if (done.isNotEmpty()) _pending.value = _pending.value - done
    }

    // ---- detailed data usage

    private val _dataDetail = MutableStateFlow<DataDetail?>(null)
    val dataDetail: StateFlow<DataDetail?> = _dataDetail
    fun loadDataDetail() = send(JSONObject().put("op", "data-detail"))

    // ---- remote screen

    /** Speed test server on the SIM phone: port and one-time token. */
    private val _speed = MutableStateFlow<Pair<Int, String>?>(null)
    val speed: StateFlow<Pair<Int, String>?> = _speed
    fun startSpeed(): Boolean { _speed.value = null; return send(JSONObject().put("op", "speed-start")) }

    private val _screen = MutableStateFlow<ScreenOffer?>(null)
    val screen: StateFlow<ScreenOffer?> = _screen
    /** [flags]: 1 = keep the SIM phone's panel dark, 2 = forward its sound. */
    fun startScreen(maxWidth: Int, bitRate: Int, flags: Int): Boolean {
        _screen.value = null
        return send(JSONObject().put("op", "screen-start").put("w", maxWidth).put("bitrate", bitRate).put("flags", flags))
    }
    fun stopScreen() { _screen.value = null; send(JSONObject().put("op", "screen-stop")) }

    /** Wi-Fi networks saved on the SIM phone (for picking a home/work network). */
    private val _savedWifi = MutableStateFlow<List<String>>(emptyList())
    val savedWifi: StateFlow<List<String>> = _savedWifi
    fun loadSavedWifi() = send(JSONObject().put("op", "wifi-saved"))

    // ---- places (home / work Wi-Fi)

    fun pushPlaces() = send(JSONObject().put("op", "places").put("auto", core.prefs.placesAuto)
        .put("list", org.json.JSONArray(core.prefs.places.map { JSONObject().put("name", it.name).put("ssid", it.ssid).put("pw", it.password) })))

    /** The main phone sees a saved place's Wi-Fi: ask the SIM phone to join it and drop the hotspot. */
    fun atPlace(p: Place) = send(JSONObject().put("op", "at-place").put("ssid", p.ssid).put("pw", p.password))
    fun loadApns() = send(JSONObject().put("op", "apn-list"))
    fun selectApn(id: Long) = send(JSONObject().put("op", "apn-select").put("id", id))
    fun addApn(name: String, apn: String, mcc: String, mnc: String, user: String, password: String) =
        send(JSONObject().put("op", "apn-add").put("name", name).put("apn", apn).put("mcc", mcc).put("mnc", mnc)
            .put("user", user).put("password", password))
    fun setNoAnswer(seconds: Int) = send(JSONObject().put("op", "voicemail").put("seconds", seconds))
    fun setChargeLimit(value: String) = send(JSONObject().put("op", "charge-limit").put("value", value))
    fun setDailyReport(on: Boolean) = send(JSONObject().put("op", "report").put("on", on)).also { refresh() }

    /** Changes made on the SIM phone (who and when), newest last. */
    private val _audit = MutableStateFlow<List<AuditEntry>>(emptyList())
    val audit: StateFlow<List<AuditEntry>> = _audit
    fun loadAudit() = send(JSONObject().put("op", "audit"))

    /** Apps on the SIM phone (package, name) and those whose notifications are forwarded. */
    private val _apps = MutableStateFlow<Pair<List<Pair<String, String>>, Set<String>>?>(null)
    val apps: StateFlow<Pair<List<Pair<String, String>>, Set<String>>?> = _apps
    fun loadApps() { _apps.value = null; send(JSONObject().put("op", "apps")) }
    fun setNotifApps(pkgs: Set<String>) = send(JSONObject().put("op", "notif-apps").put("list", JSONArray(pkgs.toList())))

    private val _audioTest = MutableStateFlow<String?>(null)
    /** Result of the last audio self-test, in plain words. */
    val audioTest: StateFlow<String?> = _audioTest

    /**
     * "Does it work on my phone?": calls [number] (the voicemail by default) through the SIM
     * phone, listens for 12 seconds, hangs up and reports whether network audio came through.
     */
    fun runAudioTest(number: String) {
        _audioTest.value = "…"
        AudioMeter.reset()
        val tm = ctx.getSystemService(android.telecom.TelecomManager::class.java)
        runCatching {
            tm.placeCall(android.net.Uri.fromParts("tel", number, null), android.os.Bundle().apply {
                putParcelable(android.telecom.TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, Telecom.handle(ctx))
            })
        }.onFailure { _audioTest.value = "Couldn't place the call: ${it.message}"; return }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            ActiveCall.current?.onDisconnect()
            val down = AudioMeter.downMax; val up = AudioMeter.upMax
            val g = status.value
            _audioTest.value = when {
                down > 1500 -> "✅ Works: network audio came through (level $down, ${AudioMeter.rate / 1000} kHz)."
                down > 0 -> "⚠️ Very quiet audio (level $down). Try another Cellular audio method on the SIM phone."
                else -> "❌ No audio from the network. On the SIM phone: ⚙ → Cellular audio → try another method."
            } + " Mic level $up." + (g?.let { "\n${it.model}, Android ${it.androidVersion}, Gateway ${it.version}" } ?: "")
        }, 15_000)
    }

    fun locate(): Boolean { _location.value = null; return send(JSONObject().put("op", "locate")) }

    /** Contact names for caller ID on the SIM phone; sent when they changed. */
    fun syncContacts() {
        if (!core.prefs.shareContacts) return
        Thread {
            val list = core.contacts.all()
            val hash = list.hashCode()
            if (hash == core.prefs.contactsHash) return@Thread
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("n", it.number).put("name", it.name)) }
            if (send(JSONObject().put("op", "contacts").put("list", a))) core.prefs.contactsHash = hash
        }.start()
    }

    fun control(action: String, on: Boolean): Boolean {
        markPending(action, on)
        return send(JSONObject().put("op", "control").put("action", action).put("on", on)).also { if (!it) clearPending(action) }
    }

    /** Sends this phone's block list (its own plus Android's blocked numbers, when readable). */
    fun pushBlockRules() {
        val nums = LinkedHashSet(core.prefs.blocked)
        nums += systemBlocked()
        send(JSONObject().put("op", "block-rules").put("numbers", JSONArray(nums.toList())).put("hidden", core.prefs.blockHidden))
    }

    /** Android's own blocked numbers (Phone app → Blocked numbers); readable as the default SMS app. */
    private fun systemBlocked(): List<String> = runCatching {
        if (!BlockedNumberContract.canCurrentUserBlockNumbers(ctx) || !isDefaultSmsApp(ctx)) return emptyList()
        ctx.contentResolver.query(BlockedNumberContract.BlockedNumbers.CONTENT_URI,
            arrayOf(BlockedNumberContract.BlockedNumbers.COLUMN_ORIGINAL_NUMBER), null, null, null)?.use { c ->
            ArrayList<String>().apply { while (c.moveToNext()) c.getString(0)?.let(::add) }
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /** Called after every (re)registration. */
    fun onConnected() {
        pushBlockRules()
        refresh()
        syncContacts()
    }

    private fun smsStatus(ref: String, status: String) {
        val id = ref.removePrefix("m").toLongOrNull() ?: return
        val st = when (status) { "delivered" -> Msg.DELIVERED; "sent" -> Msg.SENT; "failed" -> Msg.FAILED; else -> return }
        core.store.advanceStatus(id, st)
        core.store.get(id)?.let { SystemSms.setStatus(ctx, it) }
        if (st == Msg.FAILED) Log.w("text $ref not delivered")
    }

    private fun callEvent(o: JSONObject) {
        val blocked = o.optString("op") == "blocked"
        val number = o.optString("from")
        val at = o.optLong("at", System.currentTimeMillis())
        val sim = o.optInt("sim", 0)
        addToCallLog(number, at, blocked)
        if (!blocked) core.notifier.missedCall(number, core.contacts.lookup(number)?.name ?: o.optString("name").ifBlank { null }, at, sim)
        Log.i("${if (blocked) "blocked" else "missed"} call reported by the SIM phone")
    }

    /** Adds the call to the Phone app's history (needs the call log permission). */
    private fun addToCallLog(number: String, at: Long, blocked: Boolean) {
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) return
        val v = ContentValues().apply {
            put(CallLog.Calls.NUMBER, number.ifBlank { "" })
            put(CallLog.Calls.NUMBER_PRESENTATION,
                if (number.isBlank()) CallLog.Calls.PRESENTATION_RESTRICTED else CallLog.Calls.PRESENTATION_ALLOWED)
            put(CallLog.Calls.DATE, at)
            put(CallLog.Calls.DURATION, 0)
            put(CallLog.Calls.TYPE, if (blocked) CallLog.Calls.BLOCKED_TYPE else CallLog.Calls.MISSED_TYPE)
            put(CallLog.Calls.NEW, if (blocked) 0 else 1)
            put(CallLog.Calls.IS_READ, if (blocked) 1 else 0)
            Telecom.handle(ctx).let { h ->
                put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, h.componentName.flattenToString())
                put(CallLog.Calls.PHONE_ACCOUNT_ID, h.id)
            }
        }
        runCatching { ctx.contentResolver.insert(CallLog.Calls.CONTENT_URI, v) }.onFailure { Log.w("call log write failed", it) }
    }
}

/** Mobile data per day (last 30 days, oldest first) and the apps that used most this month. */
data class DataDetail(val days: List<Pair<Long, Long>>, val apps: List<Pair<String, Long>>) {
    companion object {
        fun parse(o: JSONObject) = DataDetail(
            (o.optJSONArray("days") ?: JSONArray()).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { it.optLong("day") to it.optLong("bytes") } } },
            (o.optJSONArray("apps") ?: JSONArray()).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).let { it.optString("name") to it.optLong("bytes") } } },
        )
    }
}

/** One screenshot of the SIM phone (JPEG) and the real screen size, for remote control. */
/** Where and with which one-time key to open the SIM phone's screen stream. */
class ScreenOffer(val port: Int, val key: String, val ivDown: String, val ivUp: String) {
    companion object {
        fun parse(o: JSONObject) = ScreenOffer(o.optInt("port"), o.optString("key"), o.optString("ivd"), o.optString("ivu"))
    }
}

/** A Wi-Fi network where both phones should use Wi-Fi instead of the hotspot. */
data class Place(val name: String, val ssid: String, val password: String)

/** One change on the SIM phone: when, what, and from which address. */
data class AuditEntry(val at: Long, val what: String, val from: String)
