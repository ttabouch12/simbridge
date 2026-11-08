package io.github.simbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.simbridge.LinkCore
import io.github.simbridge.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** Ping, download and upload results; null = not measured. */
private data class Speed(val pingMs: Double? = null, val downMbps: Double? = null, val upMbps: Double? = null, val error: String? = null)

/** Speed between the two phones (over the hotspot or home Wi-Fi) and from this phone to the internet. */
@Composable
fun SpeedTest(core: LinkCore, online: Boolean) {
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<Speed?>(null) }
    var net by remember { mutableStateOf<Speed?>(null) }
    var running by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section(Icons.Filled.SwapHoriz, stringResource(R.string.speed_link), Tint.Teal) {
            Muted(stringResource(R.string.speed_link_sub))
            Result(link, running == "link")
            Button({
                running = "link"; link = null
                scope.launch {
                    link = runCatching {
                        if (!core.control.startSpeed()) error("offline")
                        var offer: Pair<Int, String>? = null
                        repeat(50) { if (offer == null) { delay(100); offer = core.control.speed.value } }
                        val (port, token) = offer ?: error("no answer from the SIM phone")
                        withContext(Dispatchers.IO) { linkTest(core.prefs.lastHost.orEmpty(), port, token) }
                    }.getOrElse { Speed(error = it.message) }
                    running = null
                }
            }, enabled = online && running == null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.speed_run)) }
        }
        Section(Icons.Filled.Language, stringResource(R.string.speed_internet), Tint.Purple) {
            Muted(stringResource(R.string.speed_internet_sub))
            Result(net, running == "net")
            Button({
                running = "net"; net = null
                scope.launch { net = withContext(Dispatchers.IO) { runCatching { internetTest() }.getOrElse { Speed(error = it.message) } }; running = null }
            }, enabled = running == null, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.speed_run)) }
        }
    }
}

@Composable
private fun Result(s: Speed?, running: Boolean) {
    if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
    if (s?.error != null) Text(s.error, color = MaterialTheme.colorScheme.error)
    else if (s != null) Row(Modifier.padding(vertical = 8.dp)) {
        Big(stringResource(R.string.speed_ping), s.pingMs?.let { "%.0f ms".format(it) }, Modifier.weight(1f))
        Big(stringResource(R.string.speed_down), s.downMbps?.let { "%.1f Mb/s".format(it) }, Modifier.weight(1f))
        Big(stringResource(R.string.speed_up), s.upMbps?.let { "%.1f Mb/s".format(it) }, Modifier.weight(1f))
    }
}

@Composable
private fun Big(label: String, value: String?, m: Modifier) = Column(m) {
    Text(value ?: "—", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Muted(label)
}

private fun mbps(bytes: Long, nanos: Long) = bytes * 8.0 / (nanos / 1e9) / 1e6

private fun linkTest(host: String, port: Int, token: String): Speed {
    Socket().use { s ->
        s.tcpNoDelay = true
        s.connect(InetSocketAddress(host, port), 4000)
        s.soTimeout = 15_000
        val out = DataOutputStream(s.getOutputStream())
        val inp = DataInputStream(s.getInputStream())
        out.write(token.toByteArray()); out.flush()
        // Ping: median of 10 one-byte round trips.
        val pings = (1..10).map { val t = System.nanoTime(); out.write('p'.code); out.flush(); inp.read(); (System.nanoTime() - t) / 1e6 }.sorted()
        // Download: everything the SIM phone sends in 5 s.
        out.write('d'.code); out.flush()
        val buf = ByteArray(64 * 1024)
        var got = 0L
        val t0 = System.nanoTime()
        s.soTimeout = 1500
        try { while (true) { val n = inp.read(buf); if (n < 0) break; got += n } } catch (_: java.net.SocketTimeoutException) {}
        val down = mbps(got, System.nanoTime() - t0 - 1_500_000_000L)
        // Upload: a fixed 20 MB burst.
        s.soTimeout = 30_000
        val size = 20L shl 20
        out.write('u'.code); out.writeLong(size)
        val t1 = System.nanoTime()
        var left = size
        while (left > 0) { val n = minOf(left, buf.size.toLong()).toInt(); out.write(buf, 0, n); left -= n }
        out.flush()
        val counted = inp.readLong()
        val up = mbps(counted, System.nanoTime() - t1)
        return Speed(pings[pings.size / 2], down, up)
    }
}

/** Cloudflare's public speed test endpoints: about 25 MB down and 10 MB up of mobile data. */
private fun internetTest(): Speed {
    fun open(u: String) = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 15_000 }
    val pings = (1..5).map {
        val t = System.nanoTime()
        open("https://speed.cloudflare.com/__down?bytes=0").run { inputStream.use { it.readBytes() }; disconnect() }
        (System.nanoTime() - t) / 1e6
    }.sorted()
    val buf = ByteArray(64 * 1024)
    var got = 0L
    val t0 = System.nanoTime()
    open("https://speed.cloudflare.com/__down?bytes=25000000").run {
        inputStream.use { s -> while (System.nanoTime() - t0 < 8_000_000_000L) { val n = s.read(buf); if (n < 0) break; got += n } }
        disconnect()
    }
    val down = mbps(got, System.nanoTime() - t0)
    val size = 10_000_000
    val t1 = System.nanoTime()
    open("https://speed.cloudflare.com/__up").run {
        requestMethod = "POST"; doOutput = true; setFixedLengthStreamingMode(size)
        outputStream.use { o -> var left = size; while (left > 0) { val n = minOf(left, buf.size); o.write(buf, 0, n); left -= n } }
        responseCode; disconnect()
    }
    val up = mbps(size.toLong(), System.nanoTime() - t1)
    return Speed(pings[pings.size / 2], down, up)
}
