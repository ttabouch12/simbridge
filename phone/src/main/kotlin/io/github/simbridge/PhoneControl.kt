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
data class SimInfo(val slot: Int, val name: String, val carrier: String, val level: Int, val dbm: Int, val network: String, val roaming: Boolean)

data class GatewayStatus(
    val at: Long,
    val battery: Int?,
    val charging: Boolean,
    val powerSaver: Boolean,
    val dataOn: Boolean,
    val hotspot: Boolean,
    val uptimeMin: Long,
    val version: String,
    val sims: List<SimInfo>,
) {
    companion object {
        fun parse(o: JSONObject) = GatewayStatus(
            at = System.currentTimeMillis(),
            battery = if (o.has("battery")) o.optInt("battery") else null,
            charging = o.optBoolean("charging"),
            powerSaver = o.optBoolean("powerSaver"),
            dataOn = o.optBoolean("dataOn"),
            hotspot = o.optBoolean("hotspot"),
            uptimeMin = o.optLong("uptimeMin"),
            version = o.optString("version"),
            sims = (o.optJSONArray("sims") ?: JSONArray()).let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { s ->
                    SimInfo(s.optInt("slot", i + 1), s.optString("name"), s.optString("carrier"), s.optInt("level", -1),
                        s.optInt("dbm"), s.optString("network"), s.optBoolean("roaming"))
                } }
            },
        )
    }
}

data class UssdReply(val code: String, val ok: Boolean, val text: String, val at: Long = System.currentTimeMillis())

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
    private val _notice = MutableStateFlow<String?>(null)
    /** One-off text for the UI (a failed remote switch). */
    val notice: StateFlow<String?> = _notice

    fun clearNotice() { _notice.value = null }

    fun handle(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("op")) {
            "status" -> _status.value = GatewayStatus.parse(o).also {
                Telecom.syncSims(ctx, it.sims)
                Log.i("SIM phone: battery ${it.battery}% data=${it.dataOn} hotspot=${it.hotspot} saver=${it.powerSaver} " +
                    it.sims.joinToString { s -> "SIM${s.slot} ${s.network} level ${s.level}" })
            }
            "ussd-result" -> _ussd.value = UssdReply(o.optString("code"), o.optBoolean("ok"), o.optString("text")).also {
                Log.i("USSD ${if (it.ok) "answered" else "failed"} (${it.text.length} chars)")
            }
            "control-result" -> if (!o.optBoolean("ok", true)) _notice.value = "${o.optString("action")}: ${o.optString("text")}"
            "sms-status" -> smsStatus(o.optString("ref"), o.optString("status"))
            "missed", "blocked" -> callEvent(o)
            "mms" -> core.onIncomingMms(o)
        }
    }

    private fun send(o: JSONObject): Boolean {
        val u = core.ua ?: return false
        if (!u.status.ready) return false
        u.sendControl(o.toString())
        return true
    }

    fun refresh() = send(JSONObject().put("op", "status"))

    fun ussd(code: String, sim: Int?): Boolean {
        _ussd.value = null
        return send(JSONObject().put("op", "ussd").put("code", code).apply { sim?.let { put("sim", it) } })
    }

    fun sendMms(to: List<String>, text: String, parts: JSONArray, ref: String, sim: Int): Boolean =
        send(JSONObject().put("op", "mms-send").put("to", JSONArray(to)).put("text", text).put("parts", parts).put("ref", ref)
            .apply { if (sim > 0) put("sim", sim) })

    fun control(action: String, on: Boolean) = send(JSONObject().put("op", "control").put("action", action).put("on", on))

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
