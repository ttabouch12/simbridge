package io.github.simbridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/** A file in a picture message, stored under files/mms on this phone. */
data class Attachment(val type: String, val file: String, val name: String) {
    val isImage get() = type.startsWith("image/")
}

/**
 * The [Msg.att] JSON of a picture / group message: who sent it (in a group) and its files.
 * Group conversations use an address of several numbers joined with ';'.
 */
data class MmsInfo(val from: String?, val files: List<Attachment>) {
    fun toJson(): String = JSONObject().apply {
        from?.let { put("from", it) }
        put("files", JSONArray().apply { files.forEach { put(JSONObject().put("type", it.type).put("file", it.file).put("name", it.name)) } })
    }.toString()

    companion object {
        fun parse(att: String): MmsInfo? = if (att.isBlank()) null else runCatching {
            val o = JSONObject(att)
            val a = o.optJSONArray("files") ?: JSONArray()
            MmsInfo(o.optString("from").ifBlank { null },
                (0 until a.length()).map { i -> a.getJSONObject(i).let { Attachment(it.optString("type"), it.optString("file"), it.optString("name")) } })
        }.getOrNull()
    }
}

object Group {
    fun isGroup(address: String) = ';' in address
    fun members(address: String) = address.split(';').map { it.trim() }.filter { it.isNotEmpty() }
    /** One stable address for a set of people, whatever order they come in. */
    fun address(members: Collection<String>) = members.map { it.trim() }.filter { it.isNotEmpty() }
        .distinctBy { MessageStore.keyOf(it) }.sortedBy { MessageStore.keyOf(it) }.joinToString(";")
}

object Mms {
    /** Pictures are shrunk to fit what carriers accept (about 300 KB). */
    private const val TARGET_BYTES = 280 * 1024

    private fun dir(ctx: Context) = File(ctx.filesDir, "mms").apply { mkdirs() }

    fun fileOf(ctx: Context, a: Attachment) = File(dir(ctx), a.file)

    /** Saves the files of an incoming picture message; returns them as attachments. */
    fun saveIncoming(ctx: Context, parts: JSONArray?): List<Attachment> {
        parts ?: return emptyList()
        val out = ArrayList<Attachment>()
        val stamp = System.currentTimeMillis()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val type = p.optString("type", "application/octet-stream")
            val name = p.optString("name").ifBlank { "file$i" }.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
            val bytes = runCatching { Base64.decode(p.optString("data"), Base64.DEFAULT) }.getOrNull() ?: continue
            val f = File(dir(ctx), "${stamp}_${i}_$name")
            f.writeBytes(bytes)
            out += Attachment(type, f.name, name)
        }
        return out
    }

    /** Reads a picked file, shrinking pictures; returns the attachment saved on this phone. */
    fun prepareOutgoing(ctx: Context, uri: Uri): Attachment? {
        val type = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
        val bytes = if (type.startsWith("image/") && type != "image/gif") shrink(ctx, uri) else
            ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        bytes ?: return null
        if (bytes.size > 900 * 1024) return null
        val outType = if (type.startsWith("image/") && type != "image/gif") "image/jpeg" else type
        val ext = when (outType) { "image/jpeg" -> "jpg"; "image/gif" -> "gif"; "video/mp4" -> "mp4"; else -> "bin" }
        val name = "img_${System.currentTimeMillis()}.$ext"
        File(dir(ctx), name).writeBytes(bytes)
        return Attachment(outType, name, name)
    }

    private fun shrink(ctx: Context, uri: Uri): ByteArray? {
        val src = runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
                val longest = maxOf(info.size.width, info.size.height)
                if (longest > 1280) { val s = 1280f / longest; d.setTargetSize((info.size.width * s).toInt(), (info.size.height * s).toInt()) }
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        }.getOrNull() ?: return null
        var bmp = src
        var quality = 85
        while (true) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= TARGET_BYTES || (quality <= 40 && bmp.width <= 480)) return out.toByteArray()
            if (quality > 45) quality -= 15
            else bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * 0.75f).toInt(), (bmp.height * 0.75f).toInt(), true)
        }
    }

    /** The JSON parts for the gateway (base64 data). */
    fun partsJson(ctx: Context, files: List<Attachment>): JSONArray = JSONArray().apply {
        files.forEach { a ->
            val f = fileOf(ctx, a)
            if (f.exists()) put(JSONObject().put("type", a.type).put("name", a.name)
                .put("data", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)))
        }
    }

    /** Small preview bitmap for the chat. */
    fun thumbnail(ctx: Context, a: Attachment, maxPx: Int = 720): Bitmap? = runCatching {
        val f = fileOf(ctx, a)
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var sample = 1
        while (o.outWidth / sample > maxPx * 2 || o.outHeight / sample > maxPx * 2) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    fun deleteFiles(ctx: Context, att: String) { MmsInfo.parse(att)?.files?.forEach { fileOf(ctx, it).delete() } }
}
