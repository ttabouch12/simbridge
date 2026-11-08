package io.github.simbridge

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.sparkcyf.voicesipagent.sip.TlsPinning
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal

/**
 * Texts in a browser on your computer, served by this phone over HTTPS on the local network
 * (like KDE Connect / Phone Link, for texts). Log in with the 6-digit code shown in Settings;
 * the browser then keeps a session cookie. Calls on a computer go through Linphone instead.
 */
class TextsWebServer(private val ctx: Context, private val core: LinkCore) {
    private val prefs = ctx.getSharedPreferences("web", Context.MODE_PRIVATE)
    @Volatile private var server: SSLServerSocket? = null
    private val fails = ConcurrentHashMap<String, Pair<Int, Long>>()

    /** The code to type in the browser. New one each time the feature is turned on. */
    val code: String get() = prefs.getString("code", null) ?: newCode()

    fun newCode(): String = (100000 + SecureRandom().nextInt(900000)).toString().also {
        prefs.edit().putString("code", it).putStringSet("sessions", emptySet()).apply()
    }

    val port = PORT

    @Synchronized fun start() {
        if (server != null) return
        val ss = runCatching {
            (sslContext().serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                reuseAddress = true; bind(InetSocketAddress(PORT), 8)
            }
        }.getOrElse { Log.w("web: can't listen on $PORT", it); return }
        server = ss
        Thread({
            while (server === ss) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                if (!lan(s.inetAddress)) { runCatching { s.close() }; continue }
                Thread({ runCatching { s.use { handle(it) } } }, "web-client").apply { isDaemon = true }.start()
            }
        }, "web-accept").apply { isDaemon = true }.start()
        Log.i("web: texts page on port $PORT")
    }

    @Synchronized fun stop() { runCatching { server?.close() }; server = null }

    private fun lan(a: InetAddress) = a.isSiteLocalAddress || a.isLinkLocalAddress ||
        (a.address.size == 4 && (a.address[0].toInt() and 0xFF) == 100 && (a.address[1].toInt() and 0xC0) == 64)

    // ------------------------------------------------------------------ HTTP

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: String)

    private fun read(s: Socket): Req? {
        s.soTimeout = 15_000
        val inp = BufferedInputStream(s.getInputStream())
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = inp.read(); if (b < 0) return null
            head.write(b); last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 16_384) return null
        }
        val lines = head.toString("UTF-8").split("\r\n")
        val (method, target) = lines[0].split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 65_536) return null
        val body = ByteArray(len).also { var o = 0; while (o < len) { val n = inp.read(it, o, len - o); if (n < 0) break; o += n } }
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { java.net.URLDecoder.decode(it.substringBefore('='), "UTF-8") to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        return Req(method, path, query, headers, String(body, Charsets.UTF_8))
    }

    private fun reply(out: OutputStream, code: Int, type: String, body: ByteArray, extra: String = "") {
        val status = mapOf(200 to "OK", 302 to "Found", 401 to "Unauthorized", 404 to "Not Found", 429 to "Too Many Requests", 400 to "Bad Request")[code] ?: "OK"
        out.write(("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n" +
            "X-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'unsafe-inline' 'self'; script-src 'unsafe-inline' 'self'\r\n" +
            "Referrer-Policy: no-referrer\r\nConnection: close\r\n$extra\r\n").toByteArray())
        out.write(body); out.flush()
    }

    private fun json(out: OutputStream, v: Any) = reply(out, 200, "application/json", v.toString().toByteArray())

    private fun session(r: Req): Boolean {
        val c = r.headers["cookie"]?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("sb=") }?.removePrefix("sb=") ?: return false
        return c.length == 64 && c in prefs.getStringSet("sessions", emptySet()).orEmpty()
    }

    private fun handle(s: Socket) {
        val r = read(s) ?: return
        val out = s.getOutputStream()
        val ip = s.inetAddress.hostAddress ?: ""
        if (r.path == "/login" && r.method == "POST") {
            val (n, until) = fails[ip] ?: (0 to 0L)
            if (System.currentTimeMillis() < until) { reply(out, 429, "text/plain", "Too many tries. Wait 10 minutes.".toByteArray()); return }
            val typed = runCatching { JSONObject(r.body).optString("code") }.getOrDefault("")
            if (typed.isNotEmpty() && typed == code) {
                fails.remove(ip)
                val tok = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
                val set = prefs.getStringSet("sessions", emptySet()).orEmpty().toMutableSet().apply { add(tok); while (size > 10) remove(first()) }
                prefs.edit().putStringSet("sessions", set).apply()
                reply(out, 200, "application/json", "{}".toByteArray(), "Set-Cookie: sb=$tok; Secure; HttpOnly; SameSite=Strict; Max-Age=31536000; Path=/\r\n")
                Log.i("web: a browser logged in")
            } else {
                fails[ip] = (n + 1) to if (n + 1 >= 5) System.currentTimeMillis() + 600_000 else 0L
                reply(out, 401, "text/plain", "Wrong code".toByteArray())
            }
            return
        }
        if (r.path == "/" || r.path == "/index.html") { reply(out, 200, "text/html; charset=utf-8", PAGE.toByteArray()); return }
        if (!session(r)) { reply(out, 401, "application/json", "{\"login\":true}".toByteArray()); return }
        // CSRF: state-changing calls must come from our own page (custom header can't be sent cross-site).
        if (r.method == "POST" && r.headers["x-simbridge"] != "1") { reply(out, 400, "text/plain", ByteArray(0)); return }
        when (r.path) {
            "/api/convs" -> json(out, JSONArray().apply {
                core.store.conversations().forEach { c ->
                    put(JSONObject().put("key", c.key).put("address", c.address)
                        .put("name", core.contacts.lookup(c.address)?.name ?: c.address)
                        .put("last", c.last.body.ifBlank { if (c.last.att.isNotBlank()) "📷 Photo" else "" })
                        .put("time", c.last.time).put("unread", c.unread))
                }
            })
            "/api/thread" -> {
                val key = r.query["key"] ?: return reply(out, 400, "text/plain", ByteArray(0))
                json(out, JSONArray().apply {
                    core.store.thread(key).takeLast(300).forEach { m ->
                        val files = MmsInfo.parse(m.att)?.files.orEmpty()
                        put(JSONObject().put("id", m.id).put("out", m.outgoing).put("body", m.body).put("time", m.time).put("status", m.status)
                            .put("files", JSONArray(files.map { JSONObject().put("type", it.type).put("f", it.file) })))
                    }
                })
            }
            "/api/file" -> {
                val f = r.query["f"]?.takeIf { '/' !in it && !it.startsWith(".") } ?: return reply(out, 404, "text/plain", ByteArray(0))
                val file = java.io.File(java.io.File(ctx.filesDir, "mms"), f)
                if (!file.exists()) return reply(out, 404, "text/plain", ByteArray(0))
                reply(out, 200, if (f.endsWith(".png")) "image/png" else if (f.endsWith(".gif")) "image/gif" else "image/jpeg", file.readBytes())
            }
            "/api/send" -> {
                val o = runCatching { JSONObject(r.body) }.getOrNull() ?: return reply(out, 400, "text/plain", ByteArray(0))
                val address = o.optString("address"); val text = o.optString("text").trim()
                if (address.isBlank() || text.isBlank()) return reply(out, 400, "text/plain", ByteArray(0))
                json(out, JSONObject().put("id", core.sendText(address, text)))
            }
            "/api/read" -> { r.query["key"]?.let { core.markRead(it) }; json(out, "{}") }
            "/logout" -> {
                val c = r.headers["cookie"]?.substringAfter("sb=")?.take(64)
                prefs.edit().putStringSet("sessions", prefs.getStringSet("sessions", emptySet()).orEmpty() - (c ?: "")).apply()
                json(out, "{}")
            }
            else -> reply(out, 404, "text/plain", ByteArray(0))
        }
    }

    // ------------------------------------------------------------------ TLS key

    private fun sslContext(): javax.net.ssl.SSLContext {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_NONE)
                    .setCertificateSubject(X500Principal("CN=SimBridge texts"))
                    .setCertificateSerialNumber(BigInteger(64, SecureRandom()))
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 86_400_000L))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L)).build())
            }.generateKeyPair()
        }
        val key = ks.getKey(ALIAS, null) as PrivateKey
        val chain = arrayOf(ks.getCertificate(ALIAS) as X509Certificate)
        return TlsPinning.serverContext(arrayOf<KeyManager>(object : X509ExtendedKeyManager() {
            override fun getClientAliases(t: String?, i: Array<out Principal>?) = null
            override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?) = null
            override fun getServerAliases(t: String?, i: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?) = ALIAS
            override fun chooseEngineServerAlias(t: String?, i: Array<out Principal>?, e: SSLEngine?) = ALIAS
            override fun getCertificateChain(a: String?) = chain
            override fun getPrivateKey(a: String?) = key
        }))
    }

    companion object {
        const val PORT = 8090
        private const val ALIAS = "simbridge-web"

        /** One small page: login, conversation list, chat. Polls every 3 s. */
        private val PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>SimBridge texts</title><style>
:root{color-scheme:light dark;--p:#1a73e8;--bg:#fff;--fg:#1f1f1f;--mut:#5f6368;--bub:#e9eef6;--line:#e0e0e0}
@media(prefers-color-scheme:dark){:root{--bg:#131314;--fg:#e3e3e3;--mut:#9aa0a6;--bub:#2a2f36;--line:#333}}
*{box-sizing:border-box}body{margin:0;font:15px system-ui,sans-serif;background:var(--bg);color:var(--fg);height:100vh;display:flex}
#side{width:320px;border-right:1px solid var(--line);overflow:auto}#main{flex:1;display:flex;flex-direction:column}
.c{padding:12px 16px;cursor:pointer;border-bottom:1px solid var(--line)}.c:hover,.c.on{background:var(--bub)}
.c b{display:block}.c span{color:var(--mut);font-size:13px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;display:block}
#msgs{flex:1;overflow:auto;padding:16px;display:flex;flex-direction:column;gap:6px}
.m{max-width:70%;padding:8px 12px;border-radius:18px;background:var(--bub);white-space:pre-wrap;word-wrap:break-word}
.m.o{align-self:flex-end;background:var(--p);color:#fff}.m img{max-width:260px;border-radius:12px;display:block}
.t{font-size:11px;color:var(--mut);align-self:flex-end}form{display:flex;gap:8px;padding:12px;border-top:1px solid var(--line)}
input,textarea{flex:1;font:inherit;padding:10px 14px;border-radius:24px;border:1px solid var(--line);background:var(--bg);color:var(--fg)}
button{font:inherit;border:0;border-radius:24px;padding:10px 18px;background:var(--p);color:#fff;cursor:pointer}
#login{margin:auto;text-align:center}#login input{width:200px;text-align:center;letter-spacing:4px;font-size:22px}
</style></head><body><div id="login" hidden><h2>SimBridge texts</h2><p>Type the code shown in SimBridge → Settings → Texts on your computer.</p>
<form id="lf"><input id="code" inputmode="numeric" maxlength="6" autofocus><button>Log in</button></form><p id="err"></p></div>
<div id="side" hidden></div><div id="main" hidden><div id="msgs"></div><form id="sf"><textarea id="txt" rows="1" placeholder="Text message"></textarea><button>Send</button></form></div>
<script>
let cur=null,addr=null;const H={'x-simbridge':'1','content-type':'application/json'};
const esc=s=>s.replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
async function api(p,o){const r=await fetch(p,o);if(r.status==401){show(false);throw 0}return r.json()}
function show(ok){login.hidden=ok;side.hidden=!ok;main.hidden=!ok}
async function convs(){const l=await api('/api/convs');show(true);side.innerHTML=l.map(c=>'<div class="c'+(c.key==cur?' on':'')+'" data-k="'+esc(c.key)+'" data-a="'+esc(c.address)+'"><b>'+esc(c.name)+(c.unread?' ('+c.unread+')':'')+'</b><span>'+esc(c.last)+'</span></div>').join('');
 side.querySelectorAll('.c').forEach(e=>e.onclick=()=>{cur=e.dataset.k;addr=e.dataset.a;thread(true);fetch('/api/read?key='+encodeURIComponent(cur),{method:'POST',headers:H})})}
async function thread(scroll){if(!cur)return;const l=await api('/api/thread?key='+encodeURIComponent(cur));
 msgs.innerHTML=l.map(m=>'<div class="m'+(m.out?' o':'')+'">'+m.files.filter(f=>f.type.startsWith('image/')).map(f=>'<img src="/api/file?f='+encodeURIComponent(f.f)+'">').join('')+esc(m.body)+'</div>'+(m.out&&m.status==3?'<div class="t">Not sent</div>':'')).join('');
 if(scroll)msgs.scrollTop=msgs.scrollHeight}
lf.onsubmit=async e=>{e.preventDefault();const r=await fetch('/login',{method:'POST',headers:H,body:JSON.stringify({code:code.value})});if(r.ok){convs()}else err.textContent=await r.text()}
sf.onsubmit=async e=>{e.preventDefault();const t=txt.value.trim();if(!t||!addr)return;txt.value='';await api('/api/send',{method:'POST',headers:H,body:JSON.stringify({address:addr,text:t})});thread(true)}
txt.onkeydown=e=>{if(e.key=='Enter'&&!e.shiftKey){e.preventDefault();sf.requestSubmit()}}
convs().catch(()=>{});setInterval(()=>{if(!side.hidden){convs().catch(()=>{});thread(false).catch(()=>{})}},3000);
</script></body></html>"""
    }
}
