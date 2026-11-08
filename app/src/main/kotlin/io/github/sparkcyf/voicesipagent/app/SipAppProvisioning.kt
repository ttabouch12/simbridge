package io.github.sparkcyf.voicesipagent.app

import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom

/**
 * "Use another SIP app" (Linphone on iPhone, Mac, Windows, Linux; also Acrobits, Zoiper by hand).
 * Linphone scans a QR code holding a one-time provisioning link; this tiny HTTP server on the
 * LAN answers that link once with a config: TLS to this phone, mandatory SRTP, the pairing
 * password. The link works for 10 minutes and only from private addresses.
 */
object SipAppProvisioning {
    const val PORT = 5064
    const val USER = "sipapp"
    private const val TTL_MS = 10 * 60_000L
    @Volatile private var token: String? = null
    @Volatile private var expires = 0L
    @Volatile private var server: ServerSocket? = null

    data class Account(val host: String, val port: Int, val user: String, val password: String, val url: String)

    /** Starts (or restarts) the one-time link; returns the account details and the link for the QR. */
    fun start(rt: GatewayRuntime, host: String): Account {
        val pw = rt.settings.registrarPassword
        val t = ByteArray(12).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        token = t; expires = System.currentTimeMillis() + TTL_MS
        if (server == null) Thread({ serve(rt, host) }, "sip-app-provisioning").apply { isDaemon = true }.start()
        return Account(host, rt.pairing.tlsPort, USER, pw, "http://$host:$PORT/p/$t.xml")
    }

    private fun serve(rt: GatewayRuntime, host: String) {
        val ss = runCatching { ServerSocket(PORT, 4) }.getOrElse { AppLog.w("provisioning: port $PORT busy", it); return }
        server = ss
        ss.soTimeout = 60_000
        try {
            while (System.currentTimeMillis() < expires) {
                val s = runCatching { ss.accept() }.getOrNull() ?: continue
                s.use { c ->
                    c.soTimeout = 5000
                    if (!isPrivate(c.inetAddress)) return@use
                    val line = c.getInputStream().bufferedReader().readLine().orEmpty()
                    val ok = token != null && System.currentTimeMillis() < expires && line.startsWith("GET /p/$token.xml ")
                    val body = if (ok) config(rt, host) else "not found"
                    val head = if (ok) "200 OK" else "404 Not Found"
                    c.getOutputStream().write(("HTTP/1.1 $head\r\nContent-Type: application/xml\r\nContent-Length: ${body.toByteArray().size}\r\n" +
                        "Cache-Control: no-store\r\nConnection: close\r\n\r\n$body").toByteArray())
                    if (ok) { token = null; AppLog.i("provisioning: a SIP app fetched its account") }
                }
            }
        } finally {
            runCatching { ss.close() }; server = null
        }
    }

    private fun isPrivate(a: InetAddress) = a.isSiteLocalAddress || a.isLinkLocalAddress || a.isLoopbackAddress ||
        (a.address.size == 4 && (a.address[0].toInt() and 0xFF) == 100 && (a.address[1].toInt() and 0xC0) == 64)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Linphone remote-provisioning XML (lpconfig). */
    private fun config(rt: GatewayRuntime, host: String): String {
        val port = rt.pairing.tlsPort
        val pw = esc(rt.settings.registrarPassword)
        return """<?xml version="1.0" encoding="UTF-8"?>
<config xmlns="http://www.linphone.org/xsds/lpconfig.xsd">
  <section name="sip">
    <entry name="verify_server_certs">0</entry>
    <entry name="verify_server_cn">0</entry>
    <entry name="media_encryption">srtp</entry>
    <entry name="media_encryption_mandatory">1</entry>
  </section>
  <section name="proxy_0">
    <entry name="reg_proxy">${esc("<sip:$host:$port;transport=tls>")}</entry>
    <entry name="reg_identity">${esc("\"SimBridge\" <sip:$USER@$host>")}</entry>
    <entry name="reg_expires">600</entry>
    <entry name="reg_sendregister">1</entry>
  </section>
  <section name="auth_info_0">
    <entry name="username">$USER</entry>
    <entry name="passwd">$pw</entry>
    <entry name="domain">$host</entry>
  </section>
</config>
"""
    }
}
