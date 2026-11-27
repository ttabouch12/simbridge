package io.github.sparkcyf.voicesipagent.voice

import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Internet speed test against Netflix's fast.com servers, measured the way fast.com does it:
 * several parallel streams to the nearest Netflix servers for a few seconds. Latency is a real
 * ping to 1.1.1.1 (then Google). Plain JVM code, shared by both apps.
 */
object FastSpeed {
    data class Result(
        val pingMs: Double? = null,
        val downMbps: Double? = null,
        val upMbps: Double? = null,
        /** Where the test servers are, e.g. "Istanbul, TR". */
        val server: String? = null,
        val error: String? = null,
    )

    private const val UA = "Mozilla/5.0 (Linux; Android) SimBridge"

    private fun open(u: String, timeout: Int = 8000) = (URL(u).openConnection() as HttpURLConnection).apply {
        connectTimeout = timeout; readTimeout = 15_000; setRequestProperty("User-Agent", UA)
    }

    private fun get(u: String): String = open(u).run { try { inputStream.bufferedReader().readText() } finally { disconnect() } }

    /** Median round trip to 1.1.1.1, then 8.8.8.8, by ICMP ping; a TCP connect to Google if ping isn't allowed. */
    fun ping(): Double? {
        for (host in listOf("1.1.1.1", "8.8.8.8")) runCatching {
            val p = ProcessBuilder("ping", "-c", "5", "-i", "0.2", "-W", "2", host).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(8, TimeUnit.SECONDS)
            val times = Regex("time=([0-9.]+)").findAll(out).map { it.groupValues[1].toDouble() }.sorted().toList()
            if (times.isNotEmpty()) return times[times.size / 2]
        }
        val tcp = (1..3).mapNotNull {
            runCatching {
                val t = System.nanoTime()
                Socket().use { s -> s.connect(InetSocketAddress("www.google.com", 443), 3000) }
                (System.nanoTime() - t) / 1e6
            }.getOrNull()
        }.sorted()
        return tcp.getOrNull(tcp.size / 2)
    }

    /** The token fast.com's own page uses, read from its script. */
    private fun token(): String {
        val page = get("https://fast.com/")
        val script = Regex("""src="(/app-[a-z0-9]+\.js)"""").find(page)?.groupValues?.get(1) ?: error("fast.com page changed")
        val js = get("https://fast.com$script")
        return Regex("""token:"([A-Za-z0-9]+)"""").find(js)?.groupValues?.get(1) ?: error("fast.com token not found")
    }

    private data class Targets(val urls: List<String>, val server: String?)

    private fun targets(): Targets {
        val json = get("https://api.fast.com/netflix/speedtest/v2?https=true&token=${token()}&urlCount=5")
        val urls = Regex(""""url":"([^"]+)"""").findAll(json).map { it.groupValues[1].replace("\\/", "/") }.toList()
        if (urls.isEmpty()) error("no fast.com servers")
        val loc = Regex(""""location":\{"city":"([^"]*)","country":"([^"]*)"""").find(json)
        return Targets(urls, loc?.let { "${it.groupValues[1]}, ${it.groupValues[2]}" })
    }

    /** fast.com asks for a byte range by putting /range/a-b in the path. */
    private fun ranged(url: String, bytes: Long) = url.replaceFirst("/speedtest", "/speedtest/range/0-${bytes - 1}")

    /**
     * Runs the test: ping, then [seconds] of download on 5 parallel streams, then (if [upload])
     * the same for upload. Uses roughly the data a fast.com run does (often 50-200 MB on fast lines).
     */
    fun run(upload: Boolean = true, seconds: Int = 8): Result {
        val ping = ping()
        val t = runCatching { targets() }.getOrElse { return Result(ping, error = it.message) }
        val down = parallel(t.urls, seconds) { url, until, count ->
            val buf = ByteArray(64 * 1024)
            while (System.nanoTime() < until) {
                val c = open(ranged(url, 25L shl 20))
                try { c.inputStream.use { s -> while (System.nanoTime() < until) { val n = s.read(buf); if (n < 0) break; count.addAndGet(n.toLong()) } } }
                finally { c.disconnect() }
            }
        }
        val up = if (!upload) null else parallel(t.urls.take(3), seconds) { url, until, count ->
            val buf = ByteArray(64 * 1024)
            while (System.nanoTime() < until) {
                val size = 8L shl 20
                val c = open(ranged(url, size)).apply { requestMethod = "POST"; doOutput = true; setFixedLengthStreamingMode(size) }
                try {
                    c.outputStream.use { o ->
                        var left = size
                        while (left > 0 && System.nanoTime() < until) { val n = minOf(left, buf.size.toLong()).toInt(); o.write(buf, 0, n); count.addAndGet(n.toLong()); left -= n }
                    }
                    if (System.nanoTime() < until) c.responseCode
                } catch (_: Exception) { } finally { c.disconnect() }
            }
        }
        return Result(ping, down, up, t.server)
    }

    /** Runs one worker per URL until the deadline; the first second is warm-up and isn't counted. */
    private fun parallel(urls: List<String>, seconds: Int, work: (String, Long, AtomicLong) -> Unit): Double? {
        val count = AtomicLong()
        val start = System.nanoTime()
        val until = start + seconds * 1_000_000_000L
        val pool = Executors.newFixedThreadPool(urls.size)
        urls.forEach { u -> pool.execute { runCatching { work(u, until, count) } } }
        Thread.sleep(1000)
        val warm = count.get()
        val warmAt = System.nanoTime()
        pool.shutdown()
        pool.awaitTermination(seconds + 10L, TimeUnit.SECONDS)
        pool.shutdownNow()
        val bytes = count.get() - warm
        val secs = (minOf(System.nanoTime(), until) - warmAt) / 1e9
        return if (bytes <= 0 || secs <= 0) null else bytes * 8 / secs / 1e6
    }
}
