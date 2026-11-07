package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/**
 * Announces the gateway's SIP port over mDNS/DNS-SD as "_simbridge._udp" so the SimBridge app can find
 * it on any network (home Wi-Fi or this phone's hotspot) without typing an IP address.
 * The TXT record carries no secrets: the main phone still has to pass digest auth to register.
 */
class GatewayAdvertiser(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null
    private var current = ""

    /** [tlsPort]: encrypted SimBridge link; [udpPort]: legacy SIP apps (null when off); [id]: random gateway id. */
    @Synchronized fun start(tlsPort: Int, udpPort: Int?, id: String) {
        val sig = "$tlsPort/$udpPort/$id"
        if (listener != null && current == sig) return
        stop()
        current = sig
        val info = NsdServiceInfo().apply {
            // No device model or name: only a random id, so the network learns nothing about the phone.
            serviceName = "SimBridge-${id.take(6)}"
            serviceType = SERVICE_TYPE
            setPort(tlsPort)
            setAttribute("v", "2")
            setAttribute("id", id)
            setAttribute("tls", tlsPort.toString())
            udpPort?.let { setAttribute("udp", it.toString()) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("mDNS: advertising ${info.serviceName} (TLS $tlsPort)")
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = AppLog.w("mDNS: advertise failed ($code)")
            override fun onServiceUnregistered(info: NsdServiceInfo) = AppLog.i("mDNS: advertisement stopped")
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { AppLog.w("mDNS: advertise error", it); listener = null }
    }

    @Synchronized fun stop() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object { const val SERVICE_TYPE = "_simbridge._udp" }
}
