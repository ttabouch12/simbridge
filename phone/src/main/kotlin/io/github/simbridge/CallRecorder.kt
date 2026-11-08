package io.github.simbridge

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records one call (both sides mixed) to an .m4a file in Recordings/SimBridge on this phone.
 * The audio thread only queues frames; encoding runs on its own thread so the call never stutters.
 */
class CallRecorder(private val ctx: Context, private val rate: Int, label: String) {
    private val queue = LinkedBlockingQueue<ShortArray>(500)
    @Volatile private var running = true
    private val thread: Thread
    @Volatile var uri: Uri? = null
        private set
    val startedAt = System.currentTimeMillis()
    private val name = "Call ${label.filter { it.isLetterOrDigit() || it == '+' }.ifBlank { "unknown" }} " +
        SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date(startedAt)) + ".m4a"

    init {
        thread = Thread(::encode, "call-recorder").apply { start() }
    }

    /** From the audio thread: one frame from each side. Drops audio rather than block. */
    fun write(up: ShortArray, down: ShortArray) {
        if (!running) return
        val n = minOf(up.size, down.size)
        val mix = ShortArray(n) { i -> (up[i] + down[i]).coerceIn(-32768, 32767).toShort() }
        queue.offer(mix)
    }

    /** Finishes the file; returns its content URI (null if nothing could be saved). */
    fun stop(): Uri? {
        running = false
        thread.join(3000)
        return uri
    }

    private fun encode() {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, name)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_RECORDINGS}/SimBridge")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val item = runCatching { resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }
            .onFailure { Log.w("recording: can't create file", it) }.getOrNull() ?: run { drain(); return }
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var ok = false
        try {
            val pfd = resolver.openFileDescriptor(item, "rw") ?: error("no file descriptor")
            pfd.use {
                val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (rate >= 16000) 48_000 else 24_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
                }
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply { configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                ok = pump(codec!!, muxer!!)
            }
        } catch (t: Throwable) {
            Log.w("recording failed", t)
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
            runCatching { muxer?.stop() }; runCatching { muxer?.release() }
        }
        if (ok) {
            resolver.update(item, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            uri = item
            Log.i("recording saved: $name")
        } else {
            runCatching { resolver.delete(item, null, null) }
        }
    }

    /** Feeds queued PCM to the encoder until stopped; returns true if anything was written. */
    private fun pump(codec: MediaCodec, muxer: MediaMuxer): Boolean {
        val info = MediaCodec.BufferInfo()
        var track = -1
        var samples = 0L
        var eosSent = false
        var wrote = false
        var pending: ShortArray? = null
        while (true) {
            if (!eosSent) {
                if (pending == null) pending = queue.poll(20, TimeUnit.MILLISECONDS)
                val frame = pending
                if (frame != null) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        buf.clear()
                        for (s in frame) { buf.put((s.toInt() and 0xFF).toByte()); buf.put((s.toInt() shr 8).toByte()) }
                        codec.queueInputBuffer(inIdx, 0, frame.size * 2, samples * 1_000_000L / rate, 0)
                        samples += frame.size
                        pending = null
                    }
                } else if (!running && queue.isEmpty()) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, samples * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosSent = true
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                outIdx >= 0 -> {
                    val out = codec.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && track >= 0) { muxer.writeSampleData(track, out, info); wrote = true }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return wrote
                }
            }
        }
    }

    private fun drain() { while (running) { queue.poll(100, TimeUnit.MILLISECONDS) }; queue.clear() }
}
