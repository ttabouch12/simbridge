package io.github.simbridge

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** Where the SIM phone might be, best guess first. */
data class Candidate(val host: String, val port: Int, val via: String)

/**
 * Finds the SIM phone gateway without any typed IP address:
 *  1. mDNS/DNS-SD: the gateway advertises "_simbridge._udp" on every network it is on.
 *  2. On the SIM phone's hotspot the SIM phone *is* the Wi-Fi default gateway.
 *  3. The last address that worked.
 */
class Discovery(private val context: Context, private val onChange: () -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val found = ConcurrentHashMap<String, Pair<Int, Long>>()   // host -> (port, seen at)
    private val infoCallbacks = ConcurrentHashMap<String, NsdManager.ServiceInfoCallback>()
    private var discovery: NsdManager.DiscoveryListener? = null

    @Synchronized fun start() {
        if (discovery != null) return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Log.i("mDNS: looking for the SIM phone")
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, code: Int) {
                Log.w("mDNS: discovery failed ($code)")
                synchronized(this@Discovery) { discovery = null }
            }
            override fun onStopDiscoveryFailed(type: String, code: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) = watch(info)
            override fun onServiceLost(info: NsdServiceInfo) {
                infoCallbacks.remove(info.serviceName)?.let { runCatching { nsd.unregisterServiceInfoCallback(it) } }
            }
        }
        discovery = l
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w("mDNS: cannot start discovery", it); discovery = null }
    }

    @Synchronized fun restart() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        infoCallbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        infoCallbacks.clear()
        found.clear()   // addresses from the previous network are stale
        start()
    }

    private fun watch(info: NsdServiceInfo) {
        if (infoCallbacks.containsKey(info.serviceName)) return
        val cb = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(code: Int) { infoCallbacks.remove(info.serviceName) }
            override fun onServiceUpdated(s: NsdServiceInfo) {
                var changed = false
                s.hostAddresses.filterIsInstance<Inet4Address>().forEach { a ->
                    val host = a.hostAddress ?: return@forEach
                    if (found.put(host, s.port to System.currentTimeMillis())?.first != s.port) changed = true
                }
                if (changed) { Log.i("mDNS: SIM phone found at ${found.keys}"); onChange() }
            }
            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        infoCallbacks[info.serviceName] = cb
        runCatching { nsd.registerServiceInfoCallback(info, executor, cb) }
            .onFailure { infoCallbacks.remove(info.serviceName); Log.w("mDNS: resolve failed", it) }
    }

    /** IPv4 default gateway of the current Wi-Fi network (the SIM phone itself when on its hotspot). */
    fun wifiGateway(): String? {
        val net = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val lp = cm.getLinkProperties(net) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
    }

    fun onWifi(): Boolean {
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun candidates(lastHost: String?, lastPort: Int): List<Candidate> {
        val out = LinkedHashMap<String, Candidate>()
        found.entries.sortedByDescending { it.value.second }
            .forEach { out.putIfAbsent(it.key, Candidate(it.key, it.value.first, "mdns")) }
        wifiGateway()?.let { out.putIfAbsent(it, Candidate(it, DEFAULT_PORT, "gateway")) }
        lastHost?.let { out.putIfAbsent(it, Candidate(it, lastPort, "last")) }
        return out.values.toList()
    }

    companion object {
        const val SERVICE_TYPE = "_simbridge._udp"
        const val DEFAULT_PORT = 5062
    }
}
