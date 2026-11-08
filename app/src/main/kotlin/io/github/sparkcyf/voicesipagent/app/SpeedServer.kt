package io.github.sparkcyf.voicesipagent.app

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * Link speed test between the two phones: one TCP connection, opened by the main phone with a
 * one-time token sent over the authenticated control channel. Commands: 'p' echo one byte (ping),
 * 'd' send data for [SECONDS] (download), 'u' count what arrives for [SECONDS] and report it.
 */
object SpeedServer {
    const val PORT = 5067
    private const val SECONDS = 5
    @Volatile private var server: ServerSocket? = null

    fun start(token: String) {
        runCatching { server?.close() }
        val ss = ServerSocket().apply { reuseAddress = true; soTimeout = 15_000; bind(InetSocketAddress(PORT)) }
        server = ss
        Thread({
            try {
                ss.accept().use { s ->
                    ss.close()
                    s.soTimeout = 30_000; s.tcpNoDelay = true
                    val inp = DataInputStream(s.getInputStream().buffered())
                    val out = DataOutputStream(s.getOutputStream())
                    val got = ByteArray(token.length).also { inp.readFully(it) }
                    if (!java.security.MessageDigest.isEqual(got, token.toByteArray())) return@use
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        when (inp.read()) {
                            'p'.code -> { out.write('p'.code); out.flush() }
                            'd'.code -> {
                                val end = System.nanoTime() + SECONDS * 1_000_000_000L
                                while (System.nanoTime() < end) out.write(buf)
                                out.flush()
                            }
                            'u'.code -> {
                                // The client sends a length-prefixed burst; count it and answer.
                                var left = inp.readLong(); var total = 0L
                                while (left > 0) { val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; left -= n; total += n }
                                out.writeLong(total); out.flush()
                            }
                            else -> return@use
                        }
                    }
                }
            } catch (t: Throwable) {
                AppLog.i("speed test ended: ${t.message}")
            } finally { runCatching { ss.close() } }
        }, "speed-test").apply { isDaemon = true; start() }
    }
}
