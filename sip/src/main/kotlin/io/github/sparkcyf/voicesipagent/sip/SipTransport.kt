package io.github.sparkcyf.voicesipagent.sip

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** Moves SIP messages; implementations decide how a destination address is reached. */
internal interface SipTransport {
    /** Port advertised in Via/Contact. */
    val localPort: Int
    fun start()
    fun send(msg: SipMessage, to: InetSocketAddress)
    /** Via transport token for messages sent to [to]: "UDP" or "TLS". */
    fun protoFor(to: InetSocketAddress): String
    /** True if [to] is reached over a reliable (stream) transport: no SIP retransmissions. */
    fun reliableFor(to: InetSocketAddress): Boolean
    fun close()
}

/** Decides whether a packet/connection from [source] is allowed in at all (e.g. LAN only). */
typealias SourceFilter = (InetAddress) -> Boolean

/** UDP socket plus a receive thread that hands parsed messages to [onMessage]. */
internal class UdpSipTransport(
    bindPort: Int,
    private val log: SipLog,
    private val filter: SourceFilter?,
    private val onMessage: (SipMessage, InetSocketAddress) -> Unit,
) : SipTransport {
    private val socket = DatagramSocket(null).apply {
        reuseAddress = true
        bind(InetSocketAddress(bindPort))
    }
    @Volatile private var closed = false
    private val thread = Thread(::loop, "sip-rx").apply { isDaemon = true }

    override val localPort: Int get() = socket.localPort

    override fun start() = thread.start()
    override fun protoFor(to: InetSocketAddress) = "UDP"
    override fun reliableFor(to: InetSocketAddress) = false

    override fun send(msg: SipMessage, to: InetSocketAddress) {
        if (closed) return
        val bytes = msg.encode()
        log.trace("--> $to\n${String(bytes)}")
        try {
            socket.send(DatagramPacket(bytes, bytes.size, to))
        } catch (e: Exception) {
            log.warn("send ${msg.startLine.take(40)} failed", e)
        }
    }

    private fun loop() {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        while (!closed) {
            try {
                pkt.setData(buf, 0, buf.size)
                socket.receive(pkt)
            } catch (e: SocketException) {
                if (closed) return
                continue
            } catch (e: Exception) {
                continue
            }
            if (filter != null && !filter.invoke(pkt.address)) continue
            val from = InetSocketAddress(pkt.address, pkt.port)
            val msg = try {
                SipParser.parse(buf, pkt.length)
            } catch (e: Exception) {
                log.warn("unparseable datagram from $from", e)
                null
            } ?: continue
            log.trace("<-- $from\n${String(buf, 0, pkt.length)}")
            onMessage(msg, from)
        }
    }

    override fun close() {
        closed = true
        socket.close()
    }
}

/**
 * Reads SIP messages from a byte stream (RFC 3261 §18.3 framing by Content-Length).
 * Leading CRLFs are keep-alives (RFC 5626 §4.4.1): [onPing] is told about a double CRLF.
 */
internal class SipStreamReader(input: InputStream) {
    private val inp = BufferedInputStream(input, 16 * 1024)
    private var crlfs = 0
    private var lastCrlfMs = 0L
    /** Small until the peer has proved the password; then large enough for picture messages. */
    @Volatile var maxBody = SMALL_BODY

    /** Next message's bytes, or null at end of stream. Keep-alive CRLFs are skipped (reported via [onKeepAlive]). */
    fun next(onKeepAlive: (pingWithDoubleCrlf: Boolean) -> Unit): ByteArray? {
        // Skip CRLF keep-alives, reporting each one as soon as it is complete: a ping is
        // "\r\n\r\n" (answered with a pong), a pong is a lone "\r\n". Nothing else may follow
        // for minutes, so we must not wait for the next message before reacting.
        var b: Int
        while (true) {
            inp.mark(1)
            b = inp.read()
            if (b < 0) return null
            if (b == '\r'.code) continue
            if (b == '\n'.code) {
                val now = System.currentTimeMillis()
                if (now - lastCrlfMs > 1000) crlfs = 0   // parts of one ping arrive together
                lastCrlfMs = now
                crlfs++
                if (crlfs >= 2) { crlfs = 0; onKeepAlive(true) }
                else if (inp.available() == 0) onKeepAlive(false)
                continue
            }
            inp.reset()
            break
        }
        crlfs = 0
        val head = ByteArrayOutputStream(1024)
        var state = 0   // matches \r\n\r\n
        while (state < 4) {
            b = inp.read()
            if (b < 0) return null
            head.write(b)
            state = when {
                (state == 0 || state == 2) && b == '\r'.code -> state + 1
                (state == 1 || state == 3) && b == '\n'.code -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (head.size() > MAX_HEADER) throw SocketException("SIP header too large")
        }
        val headText = head.toString(Charsets.UTF_8.name())
        val len = Regex("(?im)^(?:Content-Length|l)\\s*:\\s*(\\d+)").find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (len > maxBody) throw SocketException("SIP body too large")
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = inp.read(body, off, len - off)
            if (n < 0) return null
            off += n
        }
        return head.toByteArray() + body
    }

    companion object {
        const val MAX_HEADER = 32 * 1024
        /** Picture messages travel as control messages over TLS: allow a few MB. */
        const val MAX_BODY = 4 * 1024 * 1024
        const val SMALL_BODY = 64 * 1024
    }
}

/** One TLS connection: a reader thread and a synchronized writer. */
internal class SipConnection(
    val socket: SSLSocket,
    private val log: SipLog,
    private val onMessage: (SipMessage, InetSocketAddress) -> Unit,
    private val onClosed: (SipConnection) -> Unit,
    /** Server side: run the handshake on the reader thread (bounded by a timeout). */
    private val handshakeHere: Boolean = false,
) {
    val remote: InetSocketAddress = socket.remoteSocketAddress as InetSocketAddress
    private val out: OutputStream = socket.outputStream
    @Volatile var lastRxMs = System.currentTimeMillis()
        private set
    @Volatile private var closed = false
    /** Set when we sent a ping and are waiting for the CRLF pong. */
    @Volatile var pingSentAtMs = 0L
    val openedAtMs = System.currentTimeMillis()
    /** Set once a request on this connection passed digest auth. */
    @Volatile var trusted = false
        private set
    @Volatile private var reader: SipStreamReader? = null

    fun trust() { trusted = true; reader?.maxBody = SipStreamReader.MAX_BODY }

    fun start() = Thread(::loop, "sip-tls-${remote.port}").apply { isDaemon = true }.start()

    @Synchronized fun write(bytes: ByteArray): Boolean = try {
        out.write(bytes); out.flush(); true
    } catch (e: Exception) {
        log.warn("TLS write to $remote failed: ${e.message}")
        close(); false
    }

    private fun loop() {
        try {
            if (handshakeHere) {
                socket.soTimeout = 10_000          // don't let a silent client pin a thread
                socket.startHandshake()
                socket.soTimeout = 0
            }
            val reader = SipStreamReader(socket.inputStream).also {
                this.reader = it
                if (trusted || !handshakeHere) it.maxBody = SipStreamReader.MAX_BODY   // client side trusts its pinned server
            }
            while (!closed) {
                val bytes = reader.next { doubleCrlf ->
                    lastRxMs = System.currentTimeMillis()
                    pingSentAtMs = 0
                    if (doubleCrlf) write(CRLF)   // RFC 5626 pong
                } ?: break
                lastRxMs = System.currentTimeMillis()
                pingSentAtMs = 0
                val msg = try { SipParser.parse(bytes, bytes.size) } catch (e: Exception) { null } ?: continue
                log.trace("<-- tls $remote\n${String(bytes)}")
                onMessage(msg, remote)
            }
        } catch (e: Exception) {
            if (!closed) log.info("TLS connection $remote closed: ${e.message}")
        } finally {
            close()
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket.close() }
        onClosed(this)
    }

    val isClosed get() = closed

    companion object {
        val CRLF = "\r\n".toByteArray()
        val PING = "\r\n\r\n".toByteArray()
    }
}

/**
 * Server side of SIP over TLS (the gateway). The phone connects in and keeps the connection
 * open; requests to the phone go back over that same connection (RFC 5626 style).
 */
internal class TlsServerTransport(
    port: Int,
    sslContext: SSLContext,
    private val log: SipLog,
    private val filter: SourceFilter?,
    private val onMessage: (SipMessage, InetSocketAddress) -> Unit,
    /** A phone's connection closed: it is gone until it connects again. */
    private val onConnectionClosed: (InetSocketAddress) -> Unit = {},
) {
    private val server: SSLServerSocket = (sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        reuseAddress = true
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.3" }.toTypedArray().ifEmpty { arrayOf("TLSv1.2") }
        bind(InetSocketAddress(port), 8)
    }
    private val conns = ConcurrentHashMap<InetSocketAddress, SipConnection>()
    @Volatile private var closed = false

    val localPort: Int get() = server.localPort

    fun start() = Thread(::acceptLoop, "sip-tls-accept").apply { isDaemon = true }.start()

    private fun acceptLoop() {
        while (!closed) {
            val s = try { server.accept() as SSLSocket } catch (e: Exception) { if (closed) return else continue }
            if (filter != null && !filter.invoke(s.inetAddress)) {
                runCatching { s.close() }
                continue
            }
            if (conns.size >= MAX_CONNECTIONS) {
                // Make room: an unauthenticated connection goes first, then the quietest one.
                (conns.values.filter { !it.trusted }.minByOrNull { it.openedAtMs } ?: conns.values.minByOrNull { it.lastRxMs })?.close()
            }
            runCatching { s.tcpNoDelay = true }
            val c = SipConnection(s, log, onMessage, { conns.remove(it.remote, it); if (it.trusted) onConnectionClosed(it.remote) }, handshakeHere = true)
            conns[c.remote] = c
            c.start()
        }
    }

    fun has(to: InetSocketAddress) = conns[to]?.isClosed == false

    /** Called after a request from [from] passed auth: lifts the pre-auth limits. */
    fun trust(from: InetSocketAddress) { conns[from]?.trust() }

    fun send(msg: SipMessage, to: InetSocketAddress): Boolean {
        val c = conns[to] ?: return false
        val bytes = msg.encode()
        log.trace("--> tls $to\n${String(bytes)}")
        return c.write(bytes)
    }

    /** Closes connections that have been silent for [idleMs] (the phone pings every few minutes). */
    fun reapIdle(idleMs: Long) {
        val now = System.currentTimeMillis()
        // Nobody needs more than half a minute to log in: free the slot (LAN denial of service).
        conns.values.filter { !it.trusted && now - it.openedAtMs > PREAUTH_MS }.forEach { it.close() }
        conns.values.filter { now - it.lastRxMs > idleMs }.forEach {
            log.info("closing idle TLS connection ${it.remote}")
            it.close()
        }
    }

    fun close() {
        closed = true
        runCatching { server.close() }
        conns.values.toList().forEach { it.close() }
    }

    companion object { const val MAX_CONNECTIONS = 8; const val PREAUTH_MS = 30_000L }
}

/** Gateway: optional UDP (other SIP apps) + TLS (SimBridge). Replies follow the way requests came in. */
internal class ServerTransport(
    private val udp: UdpSipTransport?,
    private val tls: TlsServerTransport?,
) : SipTransport {
    override val localPort: Int get() = tls?.localPort ?: udp!!.localPort
    val udpPort: Int? get() = udp?.localPort
    val tlsPort: Int? get() = tls?.localPort

    override fun start() { udp?.start(); tls?.start() }
    override fun protoFor(to: InetSocketAddress) = if (tls?.has(to) == true) "TLS" else "UDP"
    override fun reliableFor(to: InetSocketAddress) = tls?.has(to) == true

    override fun send(msg: SipMessage, to: InetSocketAddress) {
        if (tls?.send(msg, to) == true) return
        udp?.send(msg, to)
    }

    fun reapIdle(idleMs: Long) { tls?.reapIdle(idleMs) }
    fun trust(from: InetSocketAddress) { tls?.trust(from) }

    override fun close() { udp?.close(); tls?.close() }
}

/**
 * Client side of SIP over TLS (the phone): one connection to the gateway, opened on demand,
 * pinged every [pingMs] (RFC 5626 CRLF keep-alive) so a dead link is noticed quickly.
 */
internal class TlsClientTransport(
    private val server: InetSocketAddress,
    private val sslContext: SSLContext,
    private val log: SipLog,
    private val onMessage: (SipMessage, InetSocketAddress) -> Unit,
    private val onDisconnected: () -> Unit,
    /** The server refused or didn't answer the TCP/TLS connect. */
    private val onConnectFailed: () -> Unit = {},
) : SipTransport {
    @Volatile private var conn: SipConnection? = null
    @Volatile private var closed = false
    @Volatile private var port = 0

    override val localPort: Int get() = port
    override fun start() { runCatching { connection() } }
    override fun protoFor(to: InetSocketAddress) = "TLS"
    override fun reliableFor(to: InetSocketAddress) = true

    /** The local address of the live connection (for Via/Contact), if any. */
    val localAddress: InetAddress? get() = conn?.socket?.localAddress

    @Synchronized private fun connection(): SipConnection? {
        conn?.takeUnless { it.isClosed }?.let { return it }
        if (closed) return null
        return try {
            val s = sslContext.socketFactory.createSocket() as SSLSocket
            s.enabledProtocols = s.supportedProtocols.filter { it == "TLSv1.3" }.toTypedArray().ifEmpty { arrayOf("TLSv1.2") }
            s.connect(server, 5000)
            s.tcpNoDelay = true
            s.soTimeout = 0
            s.startHandshake()
            port = s.localPort
            SipConnection(s, log, onMessage, onClosed = { c ->
                if (conn === c) { conn = null; if (!closed) onDisconnected() }
            }).also { conn = it; it.start() }
        } catch (e: Exception) {
            log.warn("TLS connect to $server failed: ${e.message}")
            if (!closed) onConnectFailed()
            null
        }
    }

    override fun send(msg: SipMessage, to: InetSocketAddress) {
        val bytes = msg.encode()
        log.trace("--> tls $server\n${String(bytes)}")
        connection()?.write(bytes)
    }

    /** Sends a keep-alive ping; closes the connection if the previous one went unanswered for [timeoutMs]. */
    fun keepAlive(timeoutMs: Long) {
        val c = conn ?: return
        if (c.pingSentAtMs != 0L && System.currentTimeMillis() - c.pingSentAtMs > timeoutMs) {
            log.info("TLS keep-alive unanswered; reconnecting")
            c.close()
            return
        }
        if (c.pingSentAtMs == 0L) { c.pingSentAtMs = System.currentTimeMillis(); c.write(SipConnection.PING) }
    }

    override fun close() {
        closed = true
        conn?.close()
    }
}

/**
 * Default source filter for a phone-to-phone LAN link: only private/link-local/ULA addresses
 * (RFC 1918, RFC 6598 CGNAT for VPNs like Tailscale, fe80::/10, fc00::/7), and only if the OS
 * would answer them over an interface accepted by [interfaceAllowed] (e.g. not mobile data).
 */
class LanSourceFilter(private val interfaceAllowed: (String) -> Boolean) : (InetAddress) -> Boolean {
    private val cache = ConcurrentHashMap<InetAddress, Pair<Boolean, Long>>()

    override fun invoke(a: InetAddress): Boolean {
        val now = System.currentTimeMillis()
        cache[a]?.let { (ok, at) -> if (now - at < 30_000) return ok }
        val ok = isPrivate(a) && routeInterface(a)?.let(interfaceAllowed) ?: a.isLoopbackAddress
        if (cache.size > 256) cache.clear()
        cache[a] = ok to now
        return ok
    }

    private fun routeInterface(dest: InetAddress): String? = try {
        DatagramSocket().use { s ->
            s.connect(dest, 9)
            java.net.NetworkInterface.getByInetAddress(s.localAddress)?.name
        }
    } catch (e: Exception) { null }

    companion object {
        fun isPrivate(a: InetAddress): Boolean {
            if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
            val b = a.address
            if (b.size == 4) return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64   // 100.64/10
            return (b[0].toInt() and 0xfe) == 0xfc   // fc00::/7
        }

        /** Interface names used for mobile data on common chipsets; everything else is LAN/Wi-Fi/hotspot/VPN. */
        private val MOBILE = Regex("^(rmnet|ccmni|seth|wwan|pdp|rev_rmnet|v4-rmnet|v4-ccmni|clat|r_rmnet|dummy|ifb).*")
        fun notMobile(name: String) = !MOBILE.matches(name)
    }
}
