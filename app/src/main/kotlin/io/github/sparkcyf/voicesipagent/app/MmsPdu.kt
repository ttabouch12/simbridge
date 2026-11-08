package io.github.sparkcyf.voicesipagent.app

import java.io.ByteArrayOutputStream

/**
 * Minimal MMS PDU codec (OMA MMS Encapsulation 1.2, WAP WSP encoding), enough to receive
 * picture/group messages and send them: M-Notification.ind and M-Retrieve.conf are decoded,
 * M-Send.req and M-NotifyResp.ind are encoded. Pure Kotlin so it can be unit-tested anywhere.
 */
object MmsPdu {
    // Message types (X-Mms-Message-Type values)
    const val SEND_REQ = 0x80
    const val SEND_CONF = 0x81
    const val NOTIFICATION_IND = 0x82
    const val NOTIFYRESP_IND = 0x83
    const val RETRIEVE_CONF = 0x84
    const val ACKNOWLEDGE_IND = 0x85
    const val DELIVERY_IND = 0x86

    // Header field codes (already OR-ed with 0x80)
    private const val H_BCC = 0x81
    private const val H_CC = 0x82
    private const val H_CONTENT_LOCATION = 0x83
    private const val H_CONTENT_TYPE = 0x84
    private const val H_DATE = 0x85
    private const val H_DELIVERY_REPORT = 0x86
    private const val H_FROM = 0x89
    private const val H_MESSAGE_CLASS = 0x8A
    private const val H_MESSAGE_ID = 0x8B
    private const val H_MESSAGE_TYPE = 0x8C
    private const val H_MMS_VERSION = 0x8D
    private const val H_MESSAGE_SIZE = 0x8E
    private const val H_READ_REPORT = 0x90
    private const val H_RESPONSE_STATUS = 0x92
    private const val H_STATUS = 0x95
    private const val H_SUBJECT = 0x96
    private const val H_TO = 0x97
    private const val H_TRANSACTION_ID = 0x98

    private const val MMS_VERSION_1_2 = 0x92

    /** WSP well-known content types (WAP-230 table 40), index = code. */
    private val CONTENT_TYPES = listOf(
        "*/*", "text/*", "text/html", "text/plain", "text/x-hdml", "text/x-ttml", "text/x-vCalendar", "text/x-vCard",
        "text/vnd.wap.wml", "text/vnd.wap.wmlscript", "text/vnd.wap.wta-event", "multipart/*", "multipart/mixed",
        "multipart/form-data", "multipart/byterantes", "multipart/alternative", "application/*", "application/java-vm",
        "application/x-www-form-urlencoded", "application/x-hdmlc", "application/vnd.wap.wmlc", "application/vnd.wap.wmlscriptc",
        "application/vnd.wap.wta-eventc", "application/vnd.wap.uaprof", "application/vnd.wap.wtls-ca-certificate",
        "application/vnd.wap.wtls-user-certificate", "application/x-x509-ca-cert", "application/x-x509-user-cert", "image/*",
        "image/gif", "image/jpeg", "image/tiff", "image/png", "image/vnd.wap.wbmp", "application/vnd.wap.multipart.*",
        "application/vnd.wap.multipart.mixed", "application/vnd.wap.multipart.form-data", "application/vnd.wap.multipart.byteranges",
        "application/vnd.wap.multipart.alternative", "application/xml", "text/xml", "application/vnd.wap.wbxml",
        "application/x-x968-cross-cert", "application/x-x968-ca-cert", "application/x-x968-user-cert", "text/vnd.wap.si",
        "application/vnd.wap.sic", "text/vnd.wap.sl", "application/vnd.wap.slc", "text/vnd.wap.co", "application/vnd.wap.coc",
        "application/vnd.wap.multipart.related", "application/vnd.wap.sia", "text/vnd.wap.connectivity-xml",
        "application/vnd.wap.connectivity-wbxml", "application/pkcs7-mime", "application/vnd.wap.hashed-certificate",
        "application/vnd.wap.signed-certificate", "application/vnd.wap.cert-response", "application/xhtml+xml",
        "application/wml+xml", "text/css", "application/vnd.wap.mms-message", "application/vnd.wap.rollover-certificate",
    )

    /** IANA MIBenum -> charset name, for the ones phones actually send. */
    private val CHARSETS = mapOf(106 to "UTF-8", 3 to "US-ASCII", 4 to "ISO-8859-1", 1000 to "UTF-16", 1015 to "UTF-16",
        2026 to "Big5", 17 to "Shift_JIS", 38 to "EUC-KR", 2084 to "KOI8-R", 2252 to "windows-1252", 113 to "GBK")

    data class Part(val contentType: String, val data: ByteArray, val name: String? = null, val charset: String? = null,
                    val contentId: String? = null) {
        val isText get() = contentType.startsWith("text/plain")
        val isSmil get() = contentType == "application/smil"
        fun text(): String = String(data, runCatching { charset(charset ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
    }

    data class Pdu(
        val type: Int,
        val transactionId: String? = null,
        val from: String? = null,
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val subject: String? = null,
        val date: Long? = null,
        val contentLocation: String? = null,
        val messageSize: Long? = null,
        val messageId: String? = null,
        val responseStatus: Int? = null,
        val status: Int? = null,
        val parts: List<Part> = emptyList(),
    ) {
        /** Text of the message (all text parts, SMIL left out). */
        val text: String get() = parts.filter { it.isText }.joinToString("\n") { it.text() }.trim()
        /** Picture, video, audio and other files. */
        val media: List<Part> get() = parts.filter { !it.isText && !it.isSmil }
    }

    // ----------------------------------------------------------------- decoding

    fun parse(pdu: ByteArray): Pdu {
        val r = Reader(pdu)
        var type = -1; var tid: String? = null; var from: String? = null; var subject: String? = null
        var date: Long? = null; var loc: String? = null; var size: Long? = null; var mid: String? = null
        var resp: Int? = null; var status: Int? = null
        val to = ArrayList<String>(); val cc = ArrayList<String>()
        var parts: List<Part> = emptyList()
        while (r.has()) {
            val field = r.u8()
            when (field) {
                H_MESSAGE_TYPE -> type = r.u8()
                H_TRANSACTION_ID -> tid = r.text()
                H_MMS_VERSION -> r.u8()
                H_FROM -> from = r.fromValue()
                H_TO -> to += cleanAddress(r.encodedString())
                H_CC -> cc += cleanAddress(r.encodedString())
                H_BCC -> r.encodedString()
                H_SUBJECT -> subject = r.encodedString()
                H_DATE -> date = r.longInteger() * 1000
                H_CONTENT_LOCATION -> loc = r.text()
                H_MESSAGE_SIZE -> size = r.longInteger()
                H_MESSAGE_ID -> mid = r.text()
                H_RESPONSE_STATUS -> resp = r.u8()
                H_STATUS -> status = r.u8()
                H_CONTENT_TYPE -> {
                    // Always the last header: the multipart body follows.
                    r.contentType()
                    parts = r.multipart()
                    break
                }
                else -> if (field >= 0x80) r.skipValue() else { r.back(); r.text(); r.skipValue() }
            }
        }
        return Pdu(type, tid, from?.let(::cleanAddress), to, cc, subject, date, loc, size, mid, resp, status, parts)
    }

    /** "+905551112233/TYPE=PLMN" -> "+905551112233". */
    fun cleanAddress(a: String) = a.substringBefore("/TYPE=").trim()

    private class Reader(val b: ByteArray, var p: Int = 0, val end: Int = b.size) {
        fun has() = p < end
        fun u8(): Int { if (p >= end) throw IllegalArgumentException("truncated MMS PDU"); return b[p++].toInt() and 0xFF }
        fun peek(): Int = if (p < end) b[p].toInt() and 0xFF else -1
        fun back() { p-- }

        fun uintvar(): Long {
            var v = 0L
            repeat(5) { val x = u8(); v = (v shl 7) or (x and 0x7F).toLong(); if (x and 0x80 == 0) return v }
            throw IllegalArgumentException("bad uintvar")
        }

        /** Value-length: short (0..30) or 31 + uintvar. */
        fun valueLength(): Int { val x = u8(); return if (x < 31) x else if (x == 31) uintvar().toInt() else throw IllegalArgumentException("not a length") }

        fun text(): String {
            if (peek() == 0x7F || peek() == 0x22) p++   // quote / quoted-string start
            val s = p
            while (p < end && b[p].toInt() != 0) p++
            val out = String(b, s, p - s, Charsets.UTF_8)
            if (p < end) p++
            return out
        }

        fun longInteger(): Long {
            val x = peek()
            if (x >= 0x80) return (u8() and 0x7F).toLong()
            val n = u8()
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }

        fun integer(): Long = longInteger()

        /** Encoded-string-value: Text-string | Value-length Char-set Text-string. */
        fun encodedString(): String {
            val x = peek()
            if (x in 0..31) {
                val len = valueLength()
                val stop = p + len
                val cs = integer().toInt()
                val raw = run {
                    if (peek() == 0x7F) p++
                    val s = p; while (p < stop && b[p].toInt() != 0) p++
                    b.copyOfRange(s, p)
                }
                p = stop
                return String(raw, runCatching { charset(CHARSETS[cs] ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
            }
            return text()
        }

        fun fromValue(): String? {
            val len = valueLength()
            val stop = p + len
            val token = u8()
            val v = if (token == 0x80) encodedString() else null   // 0x81 = insert-address (none given)
            p = stop
            return v
        }

        /** Skips one WSP header value of any shape. */
        fun skipValue() {
            val x = peek()
            when {
                x < 0 -> return
                x < 31 -> { val n = u8(); p += n }
                x == 31 -> { p++; val n = uintvar().toInt(); p += n }
                x < 128 -> text()
                else -> p++
            }
        }

        fun mediaType(stop: Int): String {
            val x = peek()
            return when {
                x >= 0x80 -> CONTENT_TYPES.getOrElse(u8() and 0x7F) { "application/octet-stream" }
                x in 0..31 -> { val code = integer().toInt(); CONTENT_TYPES.getOrElse(code) { "application/octet-stream" } }
                else -> text()
            }.also { if (p > stop) p = stop }
        }

        /** Content-type with parameters; returns (type, params). */
        fun contentType(): Pair<String, Map<String, String>> {
            val x = peek()
            if (x >= 0x80) return CONTENT_TYPES.getOrElse(u8() and 0x7F) { "application/octet-stream" } to emptyMap()
            if (x >= 32) return text() to emptyMap()
            val len = valueLength()
            val stop = p + len
            val type = mediaType(stop)
            val params = HashMap<String, String>()
            while (p < stop) {
                val k = peek()
                if (k >= 0x80) {
                    when (u8() and 0x7F) {
                        0x01 -> params["charset"] = CHARSETS[integer().toInt()] ?: "UTF-8"
                        0x05, 0x17 -> params["name"] = text()
                        0x06, 0x18 -> params["filename"] = text()
                        0x09 -> params["type"] = text()
                        0x0A, 0x19 -> params["start"] = text()
                        0x03 -> integer()
                        else -> skipValue()
                    }
                } else {
                    val name = text().lowercase(); params[name] = text()
                }
            }
            p = stop
            return type to params
        }

        fun multipart(): List<Part> {
            if (!has()) return emptyList()
            val n = uintvar().toInt()
            val out = ArrayList<Part>()
            repeat(n) {
                val headersLen = uintvar().toInt()
                val dataLen = uintvar().toInt()
                val hStart = p
                val (type, params) = contentType()
                val hEnd = hStart + headersLen
                var location: String? = null; var cid: String? = null
                while (p < hEnd) {
                    val f = u8()
                    when (f) {
                        0x8E -> location = text()           // Content-Location
                        0xC0 -> cid = text()                // Content-ID
                        else -> if (f >= 0x80) skipValue() else { back(); text(); text() }
                    }
                }
                p = hEnd
                val data = b.copyOfRange(p, minOf(p + dataLen, end))
                p += dataLen
                out += Part(type, data, params["name"] ?: params["filename"] ?: location, params["charset"], cid)
            }
            return out
        }
    }

    // ----------------------------------------------------------------- encoding

    /**
     * M-Send.req for [to] (one number = picture message, several = group text) with optional
     * text and files. A SMIL layout is added because some carriers drop MMS without one.
     */
    fun sendReq(to: List<String>, text: String?, files: List<Part>, transactionId: String, subject: String? = null): ByteArray {
        val w = Writer()
        w.u8(H_MESSAGE_TYPE); w.u8(SEND_REQ)
        w.u8(H_TRANSACTION_ID); w.text(transactionId)
        w.u8(H_MMS_VERSION); w.u8(MMS_VERSION_1_2)
        w.u8(H_FROM); w.u8(1); w.u8(0x81)                  // insert-address: the network fills in our number
        for (a in to) { w.u8(H_TO); w.text(address(a)) }
        subject?.takeIf { it.isNotBlank() }?.let { w.u8(H_SUBJECT); w.utf8EncodedString(it) }
        w.u8(H_MESSAGE_CLASS); w.u8(0x80)                  // personal
        w.u8(H_DELIVERY_REPORT); w.u8(0x81)                // no
        w.u8(H_READ_REPORT); w.u8(0x81)                    // no
        val parts = ArrayList<Part>()
        val named = files.mapIndexed { i, f -> f.copy(name = f.name ?: "file$i.${ext(f.contentType)}", contentId = "<f$i>") }
        val textPart = text?.takeIf { it.isNotBlank() }?.let { Part("text/plain", it.toByteArray(Charsets.UTF_8), "text.txt", "UTF-8", "<text>") }
        parts += Part("application/smil", smil(named, textPart != null).toByteArray(), "smil.xml", null, "<smil>")
        parts += named
        textPart?.let { parts += it }
        // Content-Type: application/vnd.wap.multipart.related; start=<smil>; type=application/smil
        w.u8(H_CONTENT_TYPE)
        val ct = Writer().apply {
            u8(0x80 or 0x33)
            u8(0x80 or 0x0A); text("<smil>")
            u8(0x80 or 0x09); text("application/smil")
        }.bytes()
        w.valueLength(ct.size); w.raw(ct)
        w.uintvar(parts.size.toLong())
        for (p in parts) {
            val h = Writer()
            val ctw = Writer().apply {
                val code = CONTENT_TYPES.indexOf(p.contentType)
                if (code in 0..127) u8(0x80 or code) else text(p.contentType)
                if (p.charset != null) { u8(0x81); u8(0x80 or 106) }
                p.name?.let { u8(0x85); text(it) }
            }.bytes()
            h.valueLength(ctw.size); h.raw(ctw)
            p.name?.let { h.u8(0x8E); h.text(it) }                      // Content-Location
            p.contentId?.let { h.u8(0xC0); h.u8(0x22); h.rawText(it) }   // Content-ID (quoted string)
            val hb = h.bytes()
            w.uintvar(hb.size.toLong()); w.uintvar(p.data.size.toLong())
            w.raw(hb); w.raw(p.data)
        }
        return w.bytes()
    }

    /** M-NotifyResp.ind telling the MMSC we fetched message [transactionId]. */
    fun notifyResp(transactionId: String, status: Int = 0x81 /* retrieved */): ByteArray = Writer().apply {
        u8(H_MESSAGE_TYPE); u8(NOTIFYRESP_IND)
        u8(H_TRANSACTION_ID); text(transactionId)
        u8(H_MMS_VERSION); u8(MMS_VERSION_1_2)
        u8(H_STATUS); u8(status)
    }.bytes()

    /** Numbers go out as "+905551112233/TYPE=PLMN"; e-mail addresses as they are. */
    private fun address(a: String): String {
        val t = a.trim()
        return if ('@' in t) t else t.filter { it.isDigit() || it == '+' } + "/TYPE=PLMN"
    }

    fun ext(type: String) = when (type) {
        "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/gif" -> "gif"; "image/webp" -> "webp"
        "video/mp4" -> "mp4"; "video/3gpp" -> "3gp"; "audio/amr" -> "amr"; "audio/mp4", "audio/aac" -> "m4a"
        "text/x-vCard", "text/vcard" -> "vcf"; else -> "bin"
    }

    private fun smil(files: List<Part>, hasText: Boolean): String {
        val body = StringBuilder()
        files.forEach { f ->
            val tag = when {
                f.contentType.startsWith("image/") -> "img"; f.contentType.startsWith("video/") -> "video"
                f.contentType.startsWith("audio/") -> "audio"; else -> "ref"
            }
            body.append("<par dur=\"5000ms\"><$tag src=\"${f.name}\" region=\"Image\"/></par>")
        }
        if (hasText) body.append("<par dur=\"5000ms\"><text src=\"text.txt\" region=\"Text\"/></par>")
        return "<smil><head><layout><root-layout width=\"100%\" height=\"100%\"/>" +
            "<region id=\"Image\" left=\"0\" top=\"0\" width=\"100%\" height=\"80%\" fit=\"meet\"/>" +
            "<region id=\"Text\" left=\"0\" top=\"80%\" width=\"100%\" height=\"20%\" fit=\"scroll\"/>" +
            "</layout></head><body>$body</body></smil>"
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun raw(b: ByteArray) = out.write(b)
        fun bytes(): ByteArray = out.toByteArray()
        fun rawText(s: String) { out.write(s.toByteArray(Charsets.UTF_8)); u8(0) }
        fun text(s: String) { val b = s.toByteArray(Charsets.UTF_8); if (b.isNotEmpty() && (b[0].toInt() and 0xFF) >= 0x80) u8(0x7F); raw(b); u8(0) }
        fun uintvar(v: Long) {
            var n = v
            val tmp = ArrayList<Int>()
            do { tmp += (n and 0x7F).toInt(); n = n shr 7 } while (n > 0)
            for (i in tmp.indices.reversed()) u8(tmp[i] or if (i > 0) 0x80 else 0)
        }
        fun valueLength(n: Int) { if (n < 31) u8(n) else { u8(31); uintvar(n.toLong()) } }
        fun utf8EncodedString(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            valueLength(1 + b.size + 1 + if (b.isNotEmpty() && (b[0].toInt() and 0xFF) >= 0x80) 1 else 0)
            u8(0x80 or 106); text(s)
        }
    }
}
