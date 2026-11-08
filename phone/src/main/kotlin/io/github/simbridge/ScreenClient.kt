package io.github.simbridge

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Receives the SIM phone's live screen (H.264, decoded by the hardware decoder straight onto
 * [surface]) and sends touches and keys back on the same encrypted socket. See the gateway's
 * ScreenStream for the protocol.
 */
class ScreenClient(
    private val host: String,
    private val offer: ScreenOffer,
    private val surface: Surface,
    private val onSize: (realW: Int, realH: Int) -> Unit,
    private val onEnd: (String?) -> Unit,
) {
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var out: java.io.DataOutputStream? = null
    @Volatile private var up: GcmFrames? = null
    @Volatile private var closed = false

    fun start() = Thread({ run() }, "screen-client").apply { isDaemon = true; start() }

    /** One command line: "d x y", "m x y", "u x y", "k CODE", "l CODE", "s text". */
    fun send(line: String) {
        if (closed) return
        writer.execute { runCatching { out?.let { o -> up?.write(o, line.replace('\n', ' ').toByteArray()) } } }
    }

    fun close() {
        closed = true
        runCatching { socket?.close() }
        writer.shutdown()
    }

    private fun run() {
        var codec: MediaCodec? = null
        try {
            val key = hex(offer.key)
            // Retry briefly: the gateway opens the port right before it answers.
            val s = (1..5).firstNotNullOfOrNull { i ->
                runCatching { Socket().apply { tcpNoDelay = true; connect(InetSocketAddress(host, offer.port), 3000) } }
                    .getOrElse { Thread.sleep(200L * i); null }
            } ?: throw IllegalStateException("could not reach the SIM phone")
            socket = s
            s.getOutputStream().apply { write(MessageDigest.getInstance("SHA-256").digest(key).copyOf(16)); flush() }
            out = java.io.DataOutputStream(java.io.BufferedOutputStream(s.getOutputStream()))
            up = GcmFrames(key, hex(offer.ivUp))
            val down = GcmFrames(key, hex(offer.ivDown))
            val inp = DataInputStream(java.io.BufferedInputStream(s.getInputStream(), 64 * 1024))
            val head = java.nio.ByteBuffer.wrap(down.read(inp))
            val realW = head.int; val realH = head.int; val vw = head.int; val vh = head.int
            onSize(realW, realH)
            val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = c
            c.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, vw, vh).apply {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }, surface, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            while (!closed) {
                val frame = down.read(inp)
                val nal = frame.copyOfRange(1, frame.size)
                val len = nal.size
                if (frame[0].toInt() == 1) { play(nal); continue }
                val type = if (len > 4) nal[4].toInt() and 0x1f else 0
                val flags = if (type == 7 || type == 8) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                val i = c.dequeueInputBuffer(50_000)
                if (i >= 0) {
                    c.getInputBuffer(i)!!.apply { clear(); put(nal) }
                    c.queueInputBuffer(i, 0, len, System.nanoTime() / 1000, flags)
                }
                // Show every decoded frame at once: lowest latency.
                while (true) {
                    val o = c.dequeueOutputBuffer(info, 0)
                    if (o >= 0) c.releaseOutputBuffer(o, true) else break
                }
            }
            onEnd(null)
        } catch (t: Throwable) {
            onEnd(if (closed) null else t.message ?: "stream ended")
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { track?.release() }
            runCatching { socket?.close() }
        }
    }

    private var track: android.media.AudioTrack? = null

    /** Phone sound: 48 kHz stereo PCM, played as it arrives. */
    private fun play(pcm: ByteArray) {
        val t = track ?: android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(48_000)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(48_000 * 4 / 5)   // 200 ms: absorbs Wi-Fi jitter
            .setPerformanceMode(android.media.AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build().also { it.play(); track = it }
        t.write(pcm, 0, pcm.size, android.media.AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/**
 * Authenticated frames for the remote-screen socket: AES-GCM, one 12-byte nonce per frame
 * (4-byte direction prefix + 8-byte counter), so a frame that was changed, replayed or dropped
 * on the Wi-Fi fails to open and ends the session. Wire format: [int length][ciphertext + tag].
 * The same class exists in the gateway's ScreenStream; keep both in step.
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
