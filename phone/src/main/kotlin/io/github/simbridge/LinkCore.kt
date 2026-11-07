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
        override fun warn(msg: String, t: Throwable?) = Log.w("sip: $msg", t)
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

    fun setPassword(pw: String) {
        prefs.password = pw
        dropUa()
        kick()
    }

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
            if (prefs.password.isBlank()) {
                dropUa()
                _status.value = LinkStatus(LinkState.NEEDS_PASSWORD, detail = "Enter the gateway password")
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
            for (c in discovery.candidates(prefs.lastHost, prefs.lastPort)) {
                val u = newUa(c.host, c.port)
                if (awaitReady(u, 7_000)) {
                    ua = u
                    prefs.lastHost = c.host
                    prefs.lastPort = c.port
                    Log.i("connected to SIM phone at ${c.host}:${c.port} (${c.via})")
                    publishConnected(u, c.host == gw)
                    break
                }
                Log.i("no SIM phone at ${c.host}:${c.port} (${c.via}): ${u.status.detail}")
                if (u.status.detail.contains("403") || u.status.detail.contains("401")) {
                    _status.value = LinkStatus(LinkState.NEEDS_PASSWORD, c.host, detail = "The SIM phone rejected the password")
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
            if (st.ready) d.complete(true) else if (st.detail.startsWith("registration failed")) d.complete(false)
        }
        u.addStatusListener(l)
        if (u.status.ready) d.complete(true)
        val ok = withTimeoutOrNull(ms) { d.await() } ?: false
        u.removeStatusListener(l)
        return ok
    }

    private fun newUa(host: String, port: Int): SipUserAgent = SipUserAgent(
        SipConfig(
            account = SipAccount(server = host, port = port, username = prefs.user, password = prefs.password,
                domain = host, displayName = android.os.Build.MODEL),
            localPort = 0,
            registerExpiresSec = 120,
            userAgent = "SimBridge/1.0",
            rtpTimeoutMs = 20_000,
            ringTimeoutMs = 90_000,
            acceptOnlyFromServer = true,
        ),
        sipLog,
    ).apply {
        setIncomingCallHandler { leg -> onIncomingCall(leg) }
        incomingMessageHandler = { number, name, text -> onIncomingText(number, name, text); true }
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
        runCatching { tm.addNewIncomingCall(Telecom.handle(context), extras) }.onFailure {
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
    fun onIncomingText(number: String?, name: String?, text: String) {
        val address = when {
            number.isNullOrBlank() || number == "sms" -> name ?: "Unknown"
            number == "gateway" -> "SimBridge Gateway"
            else -> number
        }
        val time = System.currentTimeMillis()
        val id = store.add(address, text, outgoing = false, status = Msg.RECEIVED, time = time)
        if (number != "gateway") mirror(id, address, text, time, outgoing = false)
        notifier.showMessages(MessageStore.keyOf(address), address)
    }

    private fun mirror(id: Long, address: String, text: String, time: Long, outgoing: Boolean) {
        if (!prefs.mirrorToSystem && !isDefaultSmsApp(context)) return
        SystemSms.insert(context, address, text, time, outgoing)?.let { store.setSystemId(id, it) }
    }

    /** Deletes a conversation here and its copies in the system Messages app. */
    fun deleteConversation(key: String) {
        SystemSms.delete(context, store.deleteConversation(key))
    }

    /** Marks a conversation read here and in the system Messages app. */
    fun markRead(key: String) {
        SystemSms.markRead(context, store.markRead(key))
    }

    /** Sends a text through the SIM phone. Returns the new message id. */
    fun sendText(address: String, text: String): Long {
        val id = store.add(address, text, outgoing = true, status = Msg.SENDING)
        deliver(id, address, text)
        return id
    }

    fun retry(msg: Msg) {
        store.setStatus(msg.id, Msg.SENDING)
        deliver(msg.id, msg.address, msg.body)
    }

    private fun deliver(id: Long, address: String, text: String) {
        val u = ua
        if (u == null || !u.status.ready) { store.setStatus(id, Msg.FAILED); kick(); return }
        u.sendMessageTo(address, text) { code ->
            val ok = code != null && code in 200..299
            store.setStatus(id, if (ok) Msg.SENT else Msg.FAILED)
            if (ok) mirror(id, address, text, System.currentTimeMillis(), outgoing = true)
            if (code == null || code !in 200..299) Log.w("text not accepted by the SIM phone (${code ?: "timeout"})")
        }
    }
}
