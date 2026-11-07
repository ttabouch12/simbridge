package io.github.sparkcyf.voicesipagent.sip

import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning for the phone-to-gateway TLS link. The gateway uses a self-signed key;
 * the phone learns its SHA-256 SPKI fingerprint once (QR pairing) and accepts nothing else,
 * so no CA is involved and a different device can't impersonate the gateway.
 */
object TlsPinning {
    /** SHA-256 of the certificate's SubjectPublicKeyInfo, base64url without padding. */
    fun fingerprint(cert: X509Certificate): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

    /** Client context that trusts only a server whose key matches [pin] (from [fingerprint]). */
    fun clientContext(pin: String): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(PinnedTrustManager(pin)), null)
    }

    /** Server context presenting the key from [keyManagers]. */
    fun serverContext(keyManagers: Array<KeyManager>): SSLContext = SSLContext.getInstance("TLS").apply {
        init(keyManagers, null, null)
    }

    class PinnedTrustManager(private val pin: String) : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val got = fingerprint(leaf)
            if (!MessageDigest.isEqual(got.toByteArray(), pin.toByteArray())) {
                throw CertificateException("gateway key doesn't match the paired one (got $got)")
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
