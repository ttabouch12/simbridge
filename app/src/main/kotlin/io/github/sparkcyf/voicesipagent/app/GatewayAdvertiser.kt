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
    private var port = 0

    @Synchronized fun start(sipPort: Int) {
        if (listener != null && port == sipPort) return
        stop()
        port = sipPort
        val info = NsdServiceInfo().apply {
            serviceName = "SimBridge " + Build.MODEL
            serviceType = SERVICE_TYPE
            setPort(sipPort)
            setAttribute("v", "1")
            setAttribute("model", Build.MODEL)
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = AppLog.i("mDNS: advertising ${info.serviceName} on port $sipPort")
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
