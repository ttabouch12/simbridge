package io.github.sparkcyf.voicesipagent.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.system.Os
import android.system.OsConstants
import io.github.sparkcyf.voicesipagent.privileged.ShizukuConnector
import io.github.sparkcyf.voicesipagent.privileged.IPrivilegedService
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Single-session, ADB-only PCM transport. No dialing, SIP, processing, or audio persistence. */
class DirectPcmService : Service() {
    private val stopping = AtomicBoolean(false)
    @Volatile private var server: LocalServerSocket? = null
    @Volatile private var client: LocalSocket? = null
    @Volatile private var tap: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private lateinit var connector: ShizukuConnector
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker != null) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("direct-pcm", "Direct call PCM", NotificationManager.IMPORTANCE_LOW))
        startForeground(2, Notification.Builder(this, "direct-pcm")
            .setSmallIcon(R.drawable.ic_gateway).setContentTitle("Direct telephony PCM")
            .setContentText("ADB audio test active; stop with direct-stop").setOngoing(true).build())
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voicesipagent:direct-pcm")
            .apply { acquire(190_000L) }
        connector = ShizukuConnector(this, "direct-pcm") { AppLog.i("direct PCM Shizuku: $it") }
        connector.start()
        worker = Thread({ serve() }, "direct-pcm-server").apply { start() }
        // Bound idle listening and a stuck client even when the host disappears.
        android.os.Handler(mainLooper).postDelayed({ stopSelf() }, 180_000L)
        return START_NOT_STICKY
    }

    private fun serve() {
        var opened = false
        val runtime = GatewayRuntime.get(this)
        var service: IPrivilegedService? = null
        try {
            check(!runtime.settings.takeover && !runtime.health.value.loopbackTest) {
                "Disable takeover and loopback before direct-start"
            }
            val until = android.os.SystemClock.elapsedRealtime() + 8000
            while (connector.service == null && !stopping.get() && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50)
            val connected = connector.service ?: error("Shizuku is not connected")
            service = connected
            server = LocalServerSocket("$packageName.pcm")
            if (stopping.get()) return
            AppLog.i("direct PCM listening: $packageName.pcm")
            val peer = server!!.accept().also { client = it }
            if (stopping.get()) return
            // adb forward is opened by adbd (shell/root), never arbitrary Android applications.
            check(peer.peerCredentials.uid in listOf(0, 2000)) { "Only ADB shell clients allowed" }
            peer.soTimeout = 5000
            tap = connected.openCallAudio(2, 16000)
            opened = true
            val fd = tap!!.fileDescriptor
            // Keep local speaker/earpiece out of the remote conversation. Restored on tap close.
            connected.setLocalOutputMuted(true)
            val output = DataOutputStream(peer.outputStream)
            output.writeBytes("DTP1")
            output.writeInt(16000)
            output.writeInt(640)
            output.flush()
            val closing = AtomicBoolean(false)
            fun closeSession() {
                if (!closing.compareAndSet(false, true)) return
                runCatching { peer.close() }
                runCatching { Os.shutdown(fd, OsConstants.SHUT_RDWR) }
            }
            val upstream = Thread({
                try {
                    val input = DataInputStream(peer.inputStream)
                    val frame = ByteArray(640)
                    while (!stopping.get()) {
                        input.readFully(frame)
                        check(Os.write(fd, frame, 0, frame.size) == frame.size) { "Short uplink packet" }
                    }
                } catch (e: Exception) {
                    if (!stopping.get()) AppLog.i("direct PCM uplink closed: ${e.javaClass.simpleName}")
                } finally { closeSession() }
            }, "direct-pcm-tx").apply { start() }
            try {
                val frame = ByteArray(641)
                while (!stopping.get()) {
                    val n = Os.read(fd, frame, 0, frame.size)
                    if (n <= 0) break
                    check(n == 640) { "Downlink packet size $n" }
                    output.write(frame, 0, n)
                }
            } finally {
                closeSession()
                upstream.join(2000)
            }
        } catch (e: Exception) {
            AppLog.w("direct PCM stopped: ${e.message}")
        } finally {
            if (opened) {
                runCatching { service?.closeCallAudio() }
                runCatching { AppLog.i("direct PCM stats: ${service?.audioStats()}") }
            }
            releaseSockets()
            stopSelf()
        }
    }

    private fun releaseSockets() {
        runCatching { client?.close() }
        runCatching { server?.close() }
        tap?.let { runCatching { Os.shutdown(it.fileDescriptor, OsConstants.SHUT_RDWR) } }
        runCatching { tap?.close() }
    }

    override fun onDestroy() {
        stopping.set(true)
        releaseSockets()
        worker?.join(2000)
        if (::connector.isInitialized) connector.stop()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
