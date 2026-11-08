package io.github.simbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import io.github.sparkcyf.voicesipagent.sip.SipAccount
import io.github.sparkcyf.voicesipagent.sip.SipConfig
import io.github.sparkcyf.voicesipagent.sip.SipLog
import io.github.sparkcyf.voicesipagent.sip.SipUserAgent
import io.github.sparkcyf.voicesipagent.sip.SrtpMode
import io.github.sparkcyf.voicesipagent.sip.media.AudioCodec
import io.github.sparkcyf.voicesipagent.sip.TlsPinning
import io.github.sparkcyf.voicesipagent.sip.TlsSettings
import io.github.sparkcyf.voicesipagent.sip.TextMeta
import io.github.sparkcyf.voicesipagent.voice.CallLeg
import io.github.sparkcyf.voicesipagent.voice.HangupCause
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

enum class LinkState { NEEDS_PASSWORD, NO_WIFI, SEARCHING, CONNECTED }

data class LinkStatus(
    val state: LinkState,
    val host: String? = null,
    /** "hotspot" or "Wi-Fi". */
    val network: String? = null,
    val detail: String = "",
)

/** Everything this phone side does: find the SIM phone, stay registered, route calls and texts. */
class LinkCore(val context: Context) {
    val prefs = Prefs(context)
    val store = MessageStore(context)
    val contacts = Contacts(context)
    val notifier = Notifier(context, this)
    val control = PhoneControl(this)
    /** Texts in a browser on the LAN (off unless turned on in Settings). */
    val web by lazy { TextsWebServer(context, this) }

    private val _status = MutableStateFlow(LinkStatus(LinkState.SEARCHING))
    val status: StateFlow<LinkStatus> = _status

    @Volatile var ua: SipUserAgent? = null
        private set
    val legs = ConcurrentHashMap<String, CallLeg>()
    @Volatile var lastIncomingLeg: String? = null
    @Volatile private var activeCalls = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var networkChanged = false
    private val discovery = Discovery(context) { kick() }
    private var started = false

    private val sipLog = object : SipLog {
        override fun info(msg: String) = Log.i("sip: $msg")
        override fun warn(msg: String, t: Throwable?) {
            if (msg.contains("doesn't match the paired one") || t?.message?.contains("doesn't match the paired one") == true) keyMismatch = true
            Log.w("sip: $msg", t)
        }
    }

    @Synchronized fun start() {
        if (started) return
        started = true
        Telecom.register(context)
        discovery.start()
        watchNetwork()
        scope.launch { connectLoop() }
    }

    fun kick() { kicks.trySend(Unit) }

    /** Stores a pairing scanned from the SIM phone's QR code and connects to it. */
    fun pair(g: PairedGateway) {
        prefs.paired = g
        prefs.lastHost = null
        keyMismatch = false
        dropUa()
        discovery.restart()
        kick()
        Log.i("paired with gateway ${g.id} (key ${g.fingerprint.take(8)}…)")
    }

    fun unpair() {
        prefs.paired = null
        dropUa()
        kick()
    }

    @Volatile private var keyMismatch = false

    fun reconnect() {
        networkChanged = true
        discovery.restart()
        kick()
    }

    // ------------------------------------------------------------------ connection

    private suspend fun waitKick(ms: Long) { withTimeoutOrNull(ms) { kicks.receive() } }

    private suspend fun connectLoop() {
        var notReadySince = 0L
        while (true) {
            val pg = prefs.paired
            if (pg == null) {
                dropUa()
                _status.value = LinkStatus(LinkState.NEEDS_PASSWORD, detail =
                    if (prefs.needsRepair) "Update: scan your SIM phone's code once to turn on encryption" else "Pair with your SIM phone")
                waitKick(60_000); continue
            }
            val cur = ua
            if (networkChanged && activeCalls == 0) {
                networkChanged = false
                if (cur != null) { Log.i("network changed: reconnecting"); dropUa() }
                continue
            }
            if (cur != null) {
                if (cur.status.ready) {
                    notReadySince = 0
                    publishConnected(cur)
                    waitKick(30_000); continue
                }
                // Lost registration (SIM phone rebooted, moved address...). Give it a moment, then rediscover.
                if (notReadySince == 0L) notReadySince = System.currentTimeMillis()
                if (System.currentTimeMillis() - notReadySince < 25_000 || activeCalls > 0) { waitKick(5_000); continue }
                Log.i("registration lost: searching again")
                dropUa()
                notReadySince = 0
            }
            if (!discovery.onWifi()) {
                _status.value = LinkStatus(LinkState.NO_WIFI, detail = "Connect to home Wi-Fi or the SIM phone's hotspot")
                waitKick(30_000); continue
            }
            _status.value = LinkStatus(LinkState.SEARCHING, detail = "Looking for your SIM phone…")
            val gw = discovery.wifiGateway()
            for (c in discovery.candidates(pg.id, pg.tlsPort, prefs.lastHost, prefs.remoteHost)) {
                val u = newUa(c.host, c.port, pg)
                if (awaitReady(u, 7_000)) {
                    ua = u
                    prefs.lastHost = c.host
                    control.onConnected()
                    Log.i("connected to SIM phone at ${c.host}:${c.port} (${c.via})")
                    publishConnected(u, c.host == gw)
                    break
                }
                Log.i("no SIM phone at ${c.host}:${c.port} (${c.via}): ${u.status.detail}")
                if (u.status.detail.contains("403") || u.status.detail.contains("401")) {
                    _status.value = LinkStatus(LinkState.NEEDS_PASSWORD, c.host, detail = "The SIM phone rejected this phone. Scan its code again")
                } else if (keyMismatch) {
                    _status.value = LinkStatus(LinkState.NEEDS_PASSWORD, c.host, detail = "The SIM phone's key changed. Scan its code again")
                }
                Thread { u.stop(500) }.start()
            }
            if (ua == null) waitKick(15_000)
        }
    }

    private fun publishConnected(u: SipUserAgent, hotspot: Boolean? = null) {
        val host = prefs.lastHost
        val onHotspot = hotspot ?: (host != null && host == discovery.wifiGateway())
        val s = LinkStatus(LinkState.CONNECTED, host, if (onHotspot) "hotspot" else "Wi-Fi")
        if (_status.value != s) _status.value = s
    }

    private suspend fun awaitReady(u: SipUserAgent, ms: Long): Boolean {
        if (u.status.ready) return true
        val d = CompletableDeferred<Boolean>()
        val l = io.github.sparkcyf.voicesipagent.voice.EndpointStatusListener { _, st ->
            if (st.ready) d.complete(true)
            else if (st.detail.startsWith("registration failed") || st.detail == "unreachable") d.complete(false)
        }
        u.addStatusListener(l)
        if (u.status.ready) d.complete(true)
        val ok = withTimeoutOrNull(ms) { d.await() } ?: false
        u.removeStatusListener(l)
        return ok
    }

    private fun newUa(host: String, port: Int, pg: PairedGateway): SipUserAgent = SipUserAgent(
        SipConfig(
            account = SipAccount(server = host, port = port, username = prefs.user, password = pg.password,
                domain = host, displayName = "Phone"),
            localPort = 0,
            // Over TLS the CRLF keep-alive notices a dead link, so registration can be lazy (battery).
            registerExpiresSec = 600,
            keepAliveSec = 120,
            userAgent = "SimBridge/2.0",
            rtpTimeoutMs = 20_000,
            ringTimeoutMs = 90_000,
            acceptOnlyFromServer = true,
            tls = TlsSettings(TlsPinning.clientContext(pg.fingerprint), 0),
            srtp = SrtpMode.REQUIRED,
            // HD voice first (16 kHz linear PCM); G.711 if the gateway has it turned off.
            codecs = listOf(AudioCodec.L16_16K, AudioCodec.PCMU),
        ),
        sipLog,
    ).apply {
        setIncomingCallHandler { leg -> onIncomingCall(leg) }
        incomingMessageHandler = { number, name, text, meta -> onIncomingText(number, name, text, meta.sim ?: 0); true }
        controlHandler = { json, _ -> control.handle(json) }
        addStatusListener { _, _ -> kick() }
        start()
    }

    private fun dropUa() {
        val u = ua ?: return
        ua = null
        Thread { u.stop(1000) }.start()
    }

    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            private var lastKey: String? = null
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val key = network.toString() + lp.linkAddresses.joinToString { it.address.hostAddress ?: "" }
                if (key != lastKey) {
                    if (lastKey != null) { networkChanged = true; discovery.restart() }
                    lastKey = key
                    kick()
                }
            }
            override fun onLost(network: Network) {
                lastKey = null
                networkChanged = true
                kick()
            }
        })
    }

    // ------------------------------------------------------------------ calls

    private fun onIncomingCall(leg: CallLeg) {
        legs[leg.id] = leg
        lastIncomingLeg = leg.id
        leg.ring()
        val number = leg.remote.number ?: ""
        Log.i("incoming call relayed by the SIM phone")
        val tm = context.getSystemService(TelecomManager::class.java)
        val inner = Bundle().apply { putString(Telecom.EXTRA_LEG, leg.id) }
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", number, null))
            putBundle(TelecomManager.EXTRA_INCOMING_CALL_EXTRAS, inner)
        }
        // Ring on that SIM's account when it's enabled, otherwise on the plain SimBridge account.
        val simHandle = Telecom.handle(context, leg.remote.sim)
        val target = if (leg.remote.sim != null && Telecom.isEnabled(context, simHandle)) simHandle else Telecom.handle(context)
        runCatching { tm.addNewIncomingCall(target, extras) }.onFailure {
            Log.w("telecom: can't ring (is the SIM phone calling account turned on?)", it)
            legs.remove(leg.id)
            leg.hangup(HangupCause.UNAVAILABLE)
            notifier.callAccountOff()
        }
    }

    fun onCallActive() { activeCalls = legs.size }
    fun onCallEnded() { activeCalls = legs.size }

    // ------------------------------------------------------------------ texts

    /** A text that reached this phone's own SIM (only when it has one and SimBridge is the SMS app). */
    fun onLocalSms(from: String, body: String) {
        val time = System.currentTimeMillis()
        val id = store.add(from, body, outgoing = false, status = Msg.RECEIVED, time = time)
        SystemSms.insert(context, from, body, time, outgoing = false)?.let { store.setSystemId(id, it) }
        notifier.showMessages(MessageStore.keyOf(from), from)
    }

    /** Also used by adb tests (ControlReceiver op "test-in"). */
    fun onIncomingText(number: String?, name: String?, text: String, sim: Int = 0) {
        if (number != "gateway" && prefs.isBlocked(number)) { Log.i("text from a blocked number dropped"); return }
        val address = when {
            number.isNullOrBlank() || number == "sms" -> name ?: "Unknown"
            number == "gateway" -> "SimBridge Gateway"
            else -> number
        }
        // The SIM phone resends a text when it got no confirmation, even if the first copy arrived.
        if (store.isRecentDuplicate(address, text)) { Log.i("duplicate text dropped"); return }
        val time = System.currentTimeMillis()
        val id = store.add(address, text, outgoing = false, status = Msg.RECEIVED, time = time, sim = sim)
        if (number != "gateway") mirror(id, address, text, time, outgoing = false)
        if (prefs.otpCopy) Otp.find(text)?.let { Otp.copy(context, it) }
        notifier.showMessages(MessageStore.keyOf(address), address)
    }

    private fun mirror(id: Long, address: String, text: String, time: Long, outgoing: Boolean) {
        if (!prefs.mirrorToSystem && !isDefaultSmsApp(context)) return
        SystemSms.insert(context, address, text, time, outgoing)?.let { store.setSystemId(id, it) }
    }

    /** A picture or group message relayed by the SIM phone. */
    fun onIncomingMms(o: org.json.JSONObject) {
        val from = o.optString("from").ifBlank { null }
        if (prefs.isBlocked(from)) { Log.i("picture message from a blocked number dropped"); return }
        val people = o.optJSONArray("participants")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val address = if (people.size > 1) Group.address(people) else people.firstOrNull() ?: from ?: "Unknown"
        val files = Mms.saveIncoming(context, o.optJSONArray("parts"))
        val text = listOfNotNull(o.optString("subject").ifBlank { null }, o.optString("text").ifBlank { null }).joinToString("\n")
        val id = store.add(address, text, outgoing = false, status = Msg.RECEIVED, time = o.optLong("at", System.currentTimeMillis()),
            sim = o.optInt("sim", 0), att = MmsInfo(if (Group.isGroup(address)) from else null, files).toJson())
        Log.i("picture message: ${files.size} file(s)${if (Group.isGroup(address)) ", group" else ""} (#$id)")
        notifier.showMessages(MessageStore.keyOf(address), address)
    }

    /** Sends a picture and/or group message through the SIM phone (MMS). */
    fun sendMms(address: String, text: String, files: List<Attachment>, sim: Int = simFor(address)): Long {
        val id = store.add(address, text, outgoing = true, status = Msg.SENDING, sim = sim, att = MmsInfo(null, files).toJson())
        deliverMms(id, address, text, files, sim)
        return id
    }

    private fun deliverMms(id: Long, address: String, text: String, files: List<Attachment>, sim: Int) {
        val to = if (Group.isGroup(address)) Group.members(address) else listOf(address)
        val ok = control.sendMms(to, text, Mms.partsJson(context, files), "m$id", sim)
        if (!ok) { store.setStatus(id, Msg.FAILED); kick() }
    }

    /** Deletes a conversation here and its copies in the system Messages app. */
    fun deleteConversation(key: String) {
        store.attachmentsIn(key).forEach { Mms.deleteFiles(context, it) }
        SystemSms.delete(context, store.deleteConversation(key))
    }

    /** Marks a conversation read here and in the system Messages app. */
    fun markRead(key: String) {
        SystemSms.markRead(context, store.markRead(key))
    }

    /** Sends a text through the SIM phone. Returns the new message id. */
    /** SIM to send a text on: the line the conversation last came in on, else the chosen default. */
    fun simFor(address: String): Int {
        if ((control.status.value?.sims?.size ?: 0) < 2) return 0
        return store.lastIncomingSim(MessageStore.keyOf(address)) ?: prefs.outgoingSim
    }

    fun sendText(address: String, text: String, sim: Int = simFor(address)): Long {
        if (Group.isGroup(address)) return sendMms(address, text, emptyList(), sim)   // group texts are MMS
        val id = store.add(address, text, outgoing = true, status = Msg.SENDING, sim = sim)
        deliver(id, address, text, sim)
        return id
    }

    fun retry(msg: Msg) {
        store.setStatus(msg.id, Msg.SENDING)
        val files = MmsInfo.parse(msg.att)?.files.orEmpty()
        if (files.isNotEmpty() || Group.isGroup(msg.address)) deliverMms(msg.id, msg.address, msg.body, files, msg.sim)
        else deliver(msg.id, msg.address, msg.body, msg.sim)
    }

    /** Block rules changed in the UI. */
    fun blockRulesChanged() = control.pushBlockRules()

    private fun deliver(id: Long, address: String, text: String, sim: Int) {
        val u = ua
        if (u == null || !u.status.ready) { store.setStatus(id, Msg.FAILED); kick(); return }
        // The ref brings back "sent" and "delivered" reports for this message.
        u.sendMessageTo(address, text, TextMeta(ref = "m$id", sim = sim.takeIf { it > 0 })) { code ->
            val ok = code != null && code in 200..299
            if (ok) store.advanceStatus(id, Msg.SENT) else store.setStatus(id, Msg.FAILED)
            if (ok) mirror(id, address, text, System.currentTimeMillis(), outgoing = true)
            if (code == null || code !in 200..299) Log.w("text not accepted by the SIM phone (${code ?: "timeout"})")
        }
    }
}
