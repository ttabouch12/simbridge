package io.github.simbridge

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The gateway this phone is paired with: its random id, pinned TLS key, password and port. */
data class PairedGateway(val id: String, val fingerprint: String, val password: String, val tlsPort: Int) {
    companion object {
        /** Parses `simbridge://pair?v=2&id=…&fp=…&pw=…&port=…` from the gateway's QR code. */
        fun parse(link: String): PairedGateway? {
            val u = runCatching { Uri.parse(link.trim()) }.getOrNull() ?: return null
            if (u.scheme != "simbridge" || u.host != "pair") return null
            val id = u.getQueryParameter("id") ?: return null
            val fp = u.getQueryParameter("fp")?.takeIf { it.length == 43 } ?: return null
            val pw = u.getQueryParameter("pw")?.takeIf { it.length >= 8 } ?: return null
            val port = u.getQueryParameter("port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            return PairedGateway(id, fp, pw, port)
        }
    }
}

class Prefs(context: Context) {
    private val p = context.getSharedPreferences("link", Context.MODE_PRIVATE)

    /** Pairing secrets are encrypted with a key in the Android Keystore (never stored in clear). */
    var paired: PairedGateway?
        get() {
            val raw = p.getString("pairing_sealed", null)?.let(::unseal) ?: return null
            val f = raw.split('\n')
            return if (f.size == 4) PairedGateway(f[0], f[1], f[2], f[3].toIntOrNull() ?: return null) else null
        }
        set(v) {
            val e = p.edit().remove("password")   // 1.x stored the password in clear: drop it
            if (v == null) e.remove("pairing_sealed") else e.putString("pairing_sealed",
                seal(listOf(v.id, v.fingerprint, v.password, v.tlsPort.toString()).joinToString("\n")))
            e.apply()
        }

    /** True if an older (unencrypted, password-only) setup is waiting to be re-paired. */
    val needsRepair: Boolean get() = p.contains("password") && !p.contains("pairing_sealed")

    /** Last address the SIM phone answered on, tried first after a restart. */
    var lastHost: String?
        get() = p.getString("last_host", null)
        set(v) = p.edit().putString("last_host", v).apply()

    /** SIP user, unique per phone so the SIM phone can ring several main phones. */
    val user: String get() = p.getString("sip_user", null) ?: ("phone-" + java.util.UUID.randomUUID().toString().take(6))
        .also { p.edit().putString("sip_user", it).apply() }

    /** Also copy texts into the system SMS database (shows them in the default Messages app). */
    var mirrorToSystem: Boolean
        get() = p.getBoolean("mirror_system", true)
        set(v) = p.edit().putBoolean("mirror_system", v).apply()

    /** Calls: record every call automatically (the record button works either way). */
    var autoRecord: Boolean
        get() = p.getBoolean("auto_record", false)
        set(v) = p.edit().putBoolean("auto_record", v).apply()

    /** Calls: ring this phone only for SIM 1/2, or both (null). */
    var outgoingSim: Int
        get() = p.getInt("outgoing_sim", 0)   // 0 = SIM phone's default
        set(v) = p.edit().putInt("outgoing_sim", v).apply()

    /** Numbers whose calls and texts are refused at the SIM phone. */
    var blocked: Set<String>
        get() = p.getStringSet("blocked", emptySet()).orEmpty().toSortedSet()
        set(v) = p.edit().putStringSet("blocked", v.toSet()).apply()

    /** The SIM phone's VPN address for use away from home (Tailscale/WireGuard), or blank. */
    var remoteHost: String
        get() = p.getString("remote_host", "") ?: ""
        set(v) = p.edit().putString("remote_host", v.trim()).apply()

    /** Texts in a browser on the local network (TextsWebServer). */
    var webTexts: Boolean
        get() = p.getBoolean("web_texts", false)
        set(v) = p.edit().putBoolean("web_texts", v).apply()

    /** Check GitHub once a day for a newer release. */
    var updateCheck: Boolean
        get() = p.getBoolean("update_check", BuildConfig.UPDATE_CHECK_DEFAULT)
        set(v) = p.edit().putBoolean("update_check", v).apply()

    /** Send contact names to the SIM phone so it can show who's calling. */
    var shareContacts: Boolean
        get() = p.getBoolean("share_contacts", true)
        set(v) = p.edit().putBoolean("share_contacts", v).putInt("contacts_hash", 0).apply()

    var contactsHash: Int
        get() = p.getInt("contacts_hash", 0)
        set(v) = p.edit().putInt("contacts_hash", v).apply()

    /** Also refuse calls with no caller ID. */
    var blockHidden: Boolean
        get() = p.getBoolean("block_hidden", false)
        set(v) = p.edit().putBoolean("block_hidden", v).apply()

    fun isBlocked(number: String?): Boolean {
        return blocked.any { io.github.sparkcyf.voicesipagent.voice.PhoneNumbers.same(number, it) }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    private fun unseal(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        Log.w("pairing data unreadable; pair again", e); null
    }

    companion object { private const val ALIAS = "simbridge-pairing" }
}
