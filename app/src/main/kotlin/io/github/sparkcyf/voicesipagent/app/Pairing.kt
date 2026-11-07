package io.github.sparkcyf.voicesipagent.app

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.sparkcyf.voicesipagent.sip.TlsPinning
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * The gateway's identity for the encrypted phone link.
 *
 * An EC P-256 key lives in the Android Keystore (it never leaves the secure hardware) with a
 * self-signed certificate. The main phone pins its fingerprint when it scans the pairing QR, so
 * only this phone can ever be "the gateway" for it. [reset] makes a new key and password, which
 * unpairs every phone.
 */
class Pairing(private val context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Random, non-identifying id advertised on the network so phones find *their* gateway. */
    val gatewayId: String
        get() = prefs.getString("id", null) ?: newId().also { prefs.edit().putString("id", it).apply() }

    var tlsPort: Int
        get() = prefs.getInt("tls_port", DEFAULT_TLS_PORT)
        set(v) = prefs.edit().putInt("tls_port", v).apply()

    /** Also accept unencrypted SIP/UDP from other SIP apps (baresip etc.). Off by default. */
    var legacyUdp: Boolean
        get() = prefs.getBoolean("legacy_udp", false)
        set(v) = prefs.edit().putBoolean("legacy_udp", v).apply()

    /** Offer 16 kHz wideband audio (L16) to SimBridge phones. */
    var hdVoice: Boolean
        get() = prefs.getBoolean("hd_voice", true)
        set(v) = prefs.edit().putBoolean("hd_voice", v).apply()

    @Synchronized fun ensureKey() {
        if (ks.containsAlias(ALIAS)) return
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=SimBridge Gateway ${gatewayId.take(6)}"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()))
            .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
            .setCertificateNotAfter(Date(System.currentTimeMillis() + 30L * 365 * 86_400_000L))
            .build())
        gen.generateKeyPair()
        AppLog.i("pairing: new gateway key ${fingerprint().take(8)}…")
    }

    fun certificate(): X509Certificate { ensureKey(); return ks.getCertificate(ALIAS) as X509Certificate }

    fun fingerprint(): String = TlsPinning.fingerprint(certificate())

    fun serverContext(): SSLContext {
        ensureKey()
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val chain = arrayOf(certificate())
        return TlsPinning.serverContext(arrayOf<KeyManager>(SingleKeyManager(key, chain)))
    }

    /** New key + new random password: every paired phone must scan again. */
    @Synchronized fun reset(): String {
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        prefs.edit().putString("id", newId()).apply()
        ensureKey()
        return newPassword()
    }

    /** The link the QR code carries. Contains the password: show it only on request. */
    fun pairingUri(password: String): String = Uri.Builder().scheme("simbridge").authority("pair")
        .appendQueryParameter("v", "2")
        .appendQueryParameter("id", gatewayId)
        .appendQueryParameter("fp", fingerprint())
        .appendQueryParameter("pw", password)
        .appendQueryParameter("port", tlsPort.toString())
        .build().toString()

    private class SingleKeyManager(private val key: PrivateKey, private val chain: Array<X509Certificate>) : X509ExtendedKeyManager() {
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = ALIAS
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getCertificateChain(alias: String?) = chain
        override fun getPrivateKey(alias: String?) = key
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    }

    companion object {
        private const val ALIAS = "simbridge-tls"
        const val DEFAULT_TLS_PORT = 5063
        private val rnd = SecureRandom()
        private fun newId() = ByteArray(6).also(rnd::nextBytes).joinToString("") { "%02x".format(it) }

        /** 20 characters from an unambiguous alphabet (~117 bits). */
        fun newPassword(): String {
            val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
            return (1..20).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
