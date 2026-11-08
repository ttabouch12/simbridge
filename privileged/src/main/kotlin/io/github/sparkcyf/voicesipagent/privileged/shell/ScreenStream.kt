package io.github.sparkcyf.voicesipagent.privileged.shell

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Live screen and touch for remote control, the way scrcpy does it: the screen is encoded as
 * H.264 by the system encoder (screenrecord) and streamed over one LAN socket; touches and keys
 * come back on the same socket and are injected straight into the input system (no `input`
 * process per tap).
 *
 * The socket only accepts the main phone that knows the one-time key sent over the authenticated
 * SIP control channel, and everything on it is encrypted with that key (AES-CTR, one IV per
 * direction).
 */
internal object ScreenStream {
    private const val TAG = "VsaScreen"
    @Volatile private var server: ServerSocket? = null
    @Volatile private var proc: Process? = null

    const val FLAG_SCREEN_OFF = 1
    const val FLAG_AUDIO = 2
    private const val VIDEO: Byte = 0
    private const val AUDIO: Byte = 1

    /** [arg] = "port keyHex ivDownHex ivUpHex maxWidth bitRate flags". Returns "listening" or throws. */
    fun start(arg: String): String {
        val p = arg.split(' ')
        require(p.size >= 5) { "bad arguments" }
        val port = p[0].toInt().also { require(it in 1024..65535) }
        val key = hex(p[1]).also { require(it.size == 16) }
        val ivDown = hex(p[2]).also { require(it.size == 16) }
        val ivUp = hex(p[3]).also { require(it.size == 16) }
        val opts = Opts(p[4].toInt().coerceIn(360, 2160), p.getOrNull(5)?.toIntOrNull()?.coerceIn(500_000, 20_000_000) ?: 6_000_000,
            p.getOrNull(6)?.toIntOrNull() ?: 0)
        stop()
        val ss = ServerSocket().apply { reuseAddress = true; soTimeout = 15_000; bind(InetSocketAddress(port)) }
        server = ss
        Thread({ serve(ss, key, ivDown, ivUp, opts) }, "screen-stream").apply { isDaemon = true; start() }
        return "listening"
    }

    private class Opts(val maxW: Int, val bitRate: Int, val flags: Int)

    fun stop(): String {
        runCatching { server?.close() }
        runCatching { proc?.destroy() }
        server = null; proc = null
        return "stopped"
    }

    private fun serve(ss: ServerSocket, key: ByteArray, ivDown: ByteArray, ivUp: ByteArray, o: Opts) {
        val screenOff = o.flags and FLAG_SCREEN_OFF != 0
        try {
            val s = ss.accept()
            ss.close()
            s.tcpNoDelay = true
            // Proof of the key: SHA-256(key) prefix, sent in clear by the client.
            val proof = ByteArray(16).also { DataInputStream(s.getInputStream()).readFully(it) }
            if (!MessageDigest.isEqual(proof, MessageDigest.getInstance("SHA-256").digest(key).copyOf(16))) {
                Log.w(TAG, "rejected a client without the key"); s.close(); return
            }
            val out = DataOutputStream(java.io.BufferedOutputStream(s.getOutputStream(), 64 * 1024))
            val inp = DataInputStream(java.io.BufferedInputStream(s.getInputStream()))
            down = GcmFrames(key, ivDown)
            val up = GcmFrames(key, ivUp)
            val (w, h) = screenSize()
            val vw = minOf(o.maxW, w) / 16 * 16
            val vh = (h.toLong() * vw / w).toInt() / 16 * 16
            down.write(out, java.nio.ByteBuffer.allocate(16).putInt(w).putInt(h).putInt(vw).putInt(vh).array())
            Thread({ readInput(inp, up, s) }, "screen-input").apply { isDaemon = true; start() }
            // Keep the phone awake while controlled (and its panel dark, if asked).
            Thread({
                while (!s.isClosed) {
                    runCatching { Thread.sleep(15_000) }
                    if (s.isClosed) break
                    key(KeyEvent.KEYCODE_WAKEUP, long = false)
                    if (screenOff) DisplayPower.set(false)
                }
            }, "screen-awake").apply { isDaemon = true; start() }
            if (o.flags and FLAG_AUDIO != 0) Thread({ audio(out, s) }, "screen-audio").apply { isDaemon = true; start() }
            pump(out, s, vw, vh, o.bitRate, screenOff)
        } catch (t: Throwable) {
            Log.i(TAG, "stream ended: ${t.message}")
        } finally {
            runCatching { proc?.destroy() }
            if (screenOff) DisplayPower.set(true)
        }
    }

    private lateinit var down: GcmFrames

    /** One frame: [type][payload]. */
    private fun packet(out: DataOutputStream, type: Byte, data: ByteArray, len: Int = data.size) {
        val b = ByteArray(len + 1); b[0] = type; System.arraycopy(data, 0, b, 1, len)
        down.write(out, b)
    }

    /** Runs screenrecord (restarting it at its time limit) and sends one NAL unit per packet. */
    private fun pump(out: DataOutputStream, s: Socket, vw: Int, vh: Int, bitRate: Int, screenOff: Boolean) {
        var noLimit = true
        var failures = 0
        while (!s.isClosed) {
            // screenrecord fails while the display is off: wake it first.
            key(KeyEvent.KEYCODE_WAKEUP, long = false)
            Thread.sleep(300)
            val cmd = mutableListOf("screenrecord", "--output-format=h264", "--size", "${vw}x$vh", "--bit-rate", bitRate.toString())
            if (noLimit) cmd += "--time-limit=0"
            cmd += "-"
            val p = ProcessBuilder(cmd).redirectErrorStream(false).start().also { proc = it }
            // Panel off only once recording runs: the picture keeps flowing, the glass stays dark.
            if (screenOff) Thread { Thread.sleep(800); DisplayPower.set(false) }.start()
            var sent = 0
            splitNals(p.inputStream) { nal -> packet(out, VIDEO, nal); sent++ }
            p.destroy()
            if (sent > 0) { failures = 0; continue }
            // Older Android has no unlimited recording: retry with the 3-minute default.
            if (++failures == 2) noLimit = false
            if (failures > 4) throw IllegalStateException("screenrecord gave no video")
        }
    }

    /** Phone sound as raw 48 kHz stereo PCM (plenty of room on a LAN; no encoder delay). */
    @Suppress("MissingPermission")
    private fun audio(out: DataOutputStream, s: Socket) {
        val rate = 48_000
        val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            android.media.AudioRecord(android.media.MediaRecorder.AudioSource.REMOTE_SUBMIX, rate,
                android.media.AudioFormat.CHANNEL_IN_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 19_200) * 2)
        }.getOrNull()
        if (rec == null || rec.state != android.media.AudioRecord.STATE_INITIALIZED) { Log.w(TAG, "no audio capture"); return }
        val buf = ByteArray(3840)   // 20 ms
        try {
            rec.startRecording()
            while (!s.isClosed) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) packet(out, AUDIO, buf, n)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { rec.stop() }; rec.release()
        }
    }

    /** Splits an Annex-B H.264 byte stream at its start codes; each NAL is emitted with a 4-byte start code. */
    internal fun splitNals(inp: InputStream, emit: (ByteArray) -> Unit) {
        val buf = java.io.ByteArrayOutputStream(64 * 1024)
        val start = byteArrayOf(0, 0, 0, 1)
        var zeros = 0
        var started = false
        val chunk = ByteArray(64 * 1024)
        fun flush(drop: Int) {
            val bytes = buf.toByteArray()
            if (started && bytes.size > drop) emit(start + bytes.copyOf(bytes.size - drop))
            buf.reset()
        }
        while (true) {
            val n = inp.read(chunk)
            if (n < 0) break
            for (i in 0 until n) {
                val b = chunk[i].toInt() and 0xff
                if (b == 1 && zeros >= 2) {
                    // The zeros of this start code were already buffered: cut them off.
                    flush(if (started) zeros else 0)
                    started = true; zeros = 0
                    continue
                }
                zeros = if (b == 0) zeros + 1 else 0
                if (started) buf.write(b)
            }
        }
        flush(0)
    }

    // ------------------------------------------------------------ input

    /** Commands, one per line: "d x y" / "m x y" / "u x y" touch, "k CODE" key, "l CODE" long press, "s text". */
    private fun readInput(inp: DataInputStream, up: GcmFrames, s: Socket) {
        var downAt = 0L
        try {
            while (true) {
                val line = up.read(inp).toString(Charsets.UTF_8)
                val c = line.firstOrNull() ?: continue
                val a = line.drop(2)
                when (c) {
                    'd', 'm', 'u' -> {
                        val (x, y) = a.split(' ').map { it.toFloat() }
                        val now = SystemClock.uptimeMillis()
                        if (c == 'd') downAt = now
                        val action = when (c) { 'd' -> MotionEvent.ACTION_DOWN; 'm' -> MotionEvent.ACTION_MOVE; else -> MotionEvent.ACTION_UP }
                        val e = MotionEvent.obtain(downAt, now, action, x, y, 0)
                        e.source = InputDevice.SOURCE_TOUCHSCREEN
                        inject(e); e.recycle()
                    }
                    'k', 'l' -> key(a.toInt(), long = c == 'l')
                    's' -> KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(a.toCharArray())?.forEach { inject(it) }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { s.close() }
            runCatching { proc?.destroy() }
        }
    }

    private fun key(code: Int, long: Boolean) {
        val now = SystemClock.uptimeMillis()
        inject(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0))
        if (long) {
            Thread.sleep(700)
            inject(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, code, 1, 0, -1, 0, KeyEvent.FLAG_LONG_PRESS))
        }
        inject(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0))
    }

    private val injector: (android.view.InputEvent) -> Unit by lazy {
        // Android 14+: InputManagerGlobal; older: InputManager.getInstance(). Mode 0 = async.
        val (target, cls) = runCatching {
            val c = Class.forName("android.hardware.input.InputManagerGlobal")
            c.getMethod("getInstance").invoke(null) to c
        }.getOrElse {
            val c = Class.forName("android.hardware.input.InputManager")
            c.getMethod("getInstance").invoke(null) to c
        }
        val m = cls.getMethod("injectInputEvent", android.view.InputEvent::class.java, Int::class.javaPrimitiveType)
        val f: (android.view.InputEvent) -> Unit = { e -> m.invoke(target, e, 0) }
        f
    }

    private fun inject(e: android.view.InputEvent) = runCatching { injector(e) }.onFailure { Log.w(TAG, "inject failed: ${it.message}") }

    /** Turns the panel off or on without changing the display state (what scrcpy's --turn-screen-off does). */
    private object DisplayPower {
        private val sc by lazy { Class.forName("android.view.SurfaceControl") }

        private fun token(): android.os.IBinder? = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // Android 14+: the display tokens moved to DisplayControl in services.jar.
                val factory = Class.forName("com.android.internal.os.ClassLoaderFactory")
                val create = factory.getDeclaredMethod("createClassLoader", String::class.java, String::class.java, String::class.java,
                    ClassLoader::class.java, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, String::class.java)
                val loader = create.invoke(null, "/system/framework/services.jar", null, null, ClassLoader.getSystemClassLoader(), 0, true, null) as ClassLoader
                val dc = loader.loadClass("com.android.server.display.DisplayControl")
                Runtime::class.java.getDeclaredMethod("loadLibrary0", Class::class.java, String::class.java)
                    .apply { isAccessible = true }.invoke(Runtime.getRuntime(), dc, "android_servers")
                val ids = dc.getMethod("getPhysicalDisplayIds").invoke(null) as LongArray
                dc.getMethod("getPhysicalDisplayToken", Long::class.javaPrimitiveType).invoke(null, ids.first()) as android.os.IBinder
            } else sc.getMethod("getInternalDisplayToken").invoke(null) as android.os.IBinder
        }.onFailure { Log.w(TAG, "no display token: ${it.message}") }.getOrNull()

        private val display by lazy { token() }

        fun set(on: Boolean) {
            val t = display ?: return
            runCatching { sc.getMethod("setDisplayPowerMode", android.os.IBinder::class.java, Int::class.javaPrimitiveType).invoke(null, t, if (on) 2 else 0) }
                .onFailure { Log.w(TAG, "display power: ${it.message}") }
        }
    }

    // ------------------------------------------------------------ helpers

    private fun screenSize(): Pair<Int, Int> {
        val out = ShellEnv.run("wm", "size").out
        val m = Regex("(?:Override|Physical) size: (\\d+)x(\\d+)").findAll(out).lastOrNull() ?: return 1080 to 2340
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Authenticated frames for the remote-screen socket: AES-GCM, one 12-byte nonce per frame
 * (4-byte direction prefix + 8-byte counter), so a frame that was changed, replayed or dropped
 * on the Wi-Fi fails to open and ends the session. Wire format: [int length][ciphertext + tag].
 * The same class exists on the gateway side; keep both in step.
 */
internal class GcmFrames(key: ByteArray, prefix: ByteArray) {
    private val k = javax.crypto.spec.SecretKeySpec(key, "AES")
    private val pre = prefix.copyOf(4)
    private var sent = 0L
    private var got = 0L

    private fun nonce(n: Long) = java.nio.ByteBuffer.allocate(12).put(pre).putLong(n).array()
    private fun gcm(mode: Int, n: Long) = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        .apply { init(mode, k, javax.crypto.spec.GCMParameterSpec(128, nonce(n))) }

    @Synchronized fun write(out: java.io.DataOutputStream, data: ByteArray, off: Int = 0, len: Int = data.size) {
        val c = gcm(javax.crypto.Cipher.ENCRYPT_MODE, sent++).doFinal(data, off, len)
        out.writeInt(c.size); out.write(c); out.flush()
    }

    fun read(inp: java.io.DataInputStream): ByteArray {
        val len = inp.readInt()
        require(len in 16..(8 shl 20)) { "bad frame" }
        val c = ByteArray(len).also { inp.readFully(it) }
        return gcm(javax.crypto.Cipher.DECRYPT_MODE, got++).doFinal(c)   // throws AEADBadTagException if tampered
    }
}
