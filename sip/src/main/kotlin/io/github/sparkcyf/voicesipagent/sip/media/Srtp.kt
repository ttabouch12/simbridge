package io.github.sparkcyf.voicesipagent.sip.media

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SRTP for one direction of one stream: RFC 3711, suite AES_CM_128_HMAC_SHA1_80, keyed with
 * SDES (RFC 4568 `a=crypto` lines). Only RTP is protected; this stack doesn't send or read RTCP.
 *
 * Senders call [protect] on a plain RTP packet; receivers call [unprotect], which verifies the
 * 80-bit tag, rejects replays (64-packet window) and decrypts in place.
 */
class SrtpContext private constructor(masterKey: ByteArray, masterSalt: ByteArray) {
    private val encKey = SecretKeySpec(derive(masterKey, masterSalt, 0x00, 16), "AES")
    private val authKey = derive(masterKey, masterSalt, 0x01, 20)
    private val salt = derive(masterKey, masterSalt, 0x02, 14)
    private val aes: Cipher = Cipher.getInstance("AES/CTR/NoPadding")
    private val mac: Mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(authKey, "HmacSHA1")) }
    private val iv = ByteArray(16)

    // Sender state
    private var txRoc = 0L
    private var txLastSeq = -1

    // Receiver state (RFC 3711 §3.3.1 index estimation + replay window)
    private var rxRoc = 0L
    private var rxHighSeq = -1
    private var rxSsrc = 0
    private var replayWindow = 0L

    /** Encrypts and authenticates [buf] (RTP header + payload, [len] bytes) in place. Returns the new length. */
    @Synchronized
    fun protect(buf: ByteArray, len: Int): Int {
        if (len < 12 || buf.size < len + TAG_LEN) return -1
        val seq = u16(buf, 2)
        if (txLastSeq >= 0 && seq < txLastSeq && txLastSeq - seq > 0x8000) txRoc = (txRoc + 1) and 0xffffffffL
        txLastSeq = seq
        val index = (txRoc shl 16) or seq.toLong()
        val off = headerLen(buf, len)
        if (off < 0) return -1
        crypt(buf, off, len - off, ssrc(buf), index)
        tag(buf, len, txRoc, buf, len)
        return len + TAG_LEN
    }

    /** Verifies and decrypts [buf] in place. Returns the plain RTP length, or -1 to drop the packet. */
    @Synchronized
    fun unprotect(buf: ByteArray, len: Int): Int {
        if (len < 12 + TAG_LEN) return -1
        val plainLen = len - TAG_LEN
        val seq = u16(buf, 2)
        val ssrc = ssrc(buf)
        if (ssrc != rxSsrc || rxHighSeq < 0) { rxSsrc = ssrc; rxHighSeq = -1; rxRoc = 0; replayWindow = 0 }
        val roc = guessRoc(seq)
        val index = (roc shl 16) or seq.toLong()
        val highIndex = if (rxHighSeq < 0) -1L else (rxRoc shl 16) or rxHighSeq.toLong()
        // Replay check
        if (highIndex >= 0) {
            val delta = index - highIndex
            if (delta <= 0) {
                if (-delta >= 64 || (replayWindow shr (-delta).toInt()) and 1L == 1L) return -1
            }
        }
        val expected = ByteArray(TAG_LEN)
        tag(buf, plainLen, roc, expected, 0)
        if (!constantTimeEquals(expected, buf, plainLen)) return -1
        val off = headerLen(buf, plainLen)
        if (off < 0) return -1
        crypt(buf, off, plainLen - off, ssrc, index)
        // Commit receiver state only after authentication.
        if (highIndex < 0 || index > highIndex) {
            val shift = if (highIndex < 0) 64 else (index - highIndex).toInt()
            replayWindow = if (shift >= 64) 1L else (replayWindow shl shift) or 1L
            rxRoc = roc; rxHighSeq = seq
        } else {
            replayWindow = replayWindow or (1L shl (highIndex - index).toInt())
        }
        return plainLen
    }

    private fun guessRoc(seq: Int): Long {
        if (rxHighSeq < 0) return rxRoc
        return if (rxHighSeq < 0x8000) {
            if (seq - rxHighSeq > 0x8000) (rxRoc - 1) and 0xffffffffL else rxRoc
        } else {
            if (rxHighSeq - 0x8000 > seq) (rxRoc + 1) and 0xffffffffL else rxRoc
        }
    }

    private fun crypt(buf: ByteArray, off: Int, n: Int, ssrc: Int, index: Long) {
        if (n <= 0) return
        System.arraycopy(salt, 0, iv, 0, 14)
        iv[14] = 0; iv[15] = 0
        iv[4] = (iv[4].toInt() xor (ssrc ushr 24)).toByte()
        iv[5] = (iv[5].toInt() xor (ssrc ushr 16)).toByte()
        iv[6] = (iv[6].toInt() xor (ssrc ushr 8)).toByte()
        iv[7] = (iv[7].toInt() xor ssrc).toByte()
        for (i in 0 until 6) iv[8 + i] = (iv[8 + i].toLong() xor (index ushr (8 * (5 - i)))).toInt().toByte()
        aes.init(Cipher.ENCRYPT_MODE, encKey, IvParameterSpec(iv))
        aes.doFinal(buf, off, n, buf, off)
    }

    private fun tag(buf: ByteArray, len: Int, roc: Long, out: ByteArray, outOff: Int) {
        mac.update(buf, 0, len)
        mac.update(byteArrayOf((roc ushr 24).toByte(), (roc ushr 16).toByte(), (roc ushr 8).toByte(), roc.toByte()))
        System.arraycopy(mac.doFinal(), 0, out, outOff, TAG_LEN)
    }

    companion object {
        const val SUITE = "AES_CM_128_HMAC_SHA1_80"
        const val TAG_LEN = 10
        private val random = SecureRandom()

        /** New random master key+salt, as the base64 `inline:` value of an a=crypto line. */
        fun newInlineKey(): String = Base64.getEncoder().encodeToString(ByteArray(30).also(random::nextBytes))

        /** Builds a context from an `inline:` key (base64 of 16-byte key + 14-byte salt). */
        fun fromInline(inline: String): SrtpContext? {
            val raw = runCatching { Base64.getDecoder().decode(inline.substringBefore('|').trim()) }.getOrNull() ?: return null
            if (raw.size != 30) return null
            return SrtpContext(raw.copyOfRange(0, 16), raw.copyOfRange(16, 30))
        }

        /** RFC 3711 §4.3 key derivation (key_derivation_rate 0): AES-CM PRF over salt XOR label. */
        internal fun derive(key: ByteArray, salt: ByteArray, label: Int, n: Int): ByteArray {
            val x = ByteArray(16)
            System.arraycopy(salt, 0, x, 0, 14)
            x[7] = (x[7].toInt() xor label).toByte()   // label sits 48 bits above the (zero) index
            val c = Cipher.getInstance("AES/CTR/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(x))
            return c.doFinal(ByteArray(n))
        }

        private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)
        private fun ssrc(b: ByteArray) = ((b[8].toInt() and 0xff) shl 24) or ((b[9].toInt() and 0xff) shl 16) or
            ((b[10].toInt() and 0xff) shl 8) or (b[11].toInt() and 0xff)

        private fun headerLen(b: ByteArray, len: Int): Int {
            var off = 12 + (b[0].toInt() and 0x0f) * 4
            if ((b[0].toInt() and 0x10) != 0) {
                if (off + 4 > len) return -1
                off += 4 + 4 * u16(b, off + 2)
            }
            return if (off > len) -1 else off
        }

        private fun constantTimeEquals(a: ByteArray, b: ByteArray, bOff: Int): Boolean {
            var d = 0
            for (i in a.indices) d = d or (a[i].toInt() xor b[bOff + i].toInt())
            return d == 0
        }
    }
}
