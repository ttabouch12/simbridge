package io.github.simbridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import java.util.concurrent.ConcurrentHashMap

data class ContactInfo(val name: String, val photo: Uri?)
data class ContactEntry(val name: String, val number: String, val label: String, val photo: Uri?)

/** Reads this phone's own contacts (synced from the Google account) for names and photos. */
class Contacts(private val context: Context) {
    private val cache = ConcurrentHashMap<String, Any>()   // key -> ContactInfo or NONE
    private object NONE

    fun allowed() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun clearCache() = cache.clear()

    fun lookup(number: String?): ContactInfo? {
        if (number != null && Group.isGroup(number)) {
            // Group: "Ali, Ayşe, +90555…"
            return ContactInfo(Group.members(number).joinToString(", ") { m -> lookup(m)?.name?.substringBefore(' ') ?: m }, null)
        }
        if (number.isNullOrBlank() || !allowed()) return null
        if (number.none { it.isDigit() }) return null
        val k = MessageStore.keyOf(number)
        cache[k]?.let { return it as? ContactInfo }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val info = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use {
                if (it.moveToFirst()) ContactInfo(it.getString(0), it.getString(1)?.let(Uri::parse)) else null
            }
        }.getOrNull()
        cache[k] = info ?: NONE
        return info
    }

    fun all(): List<ContactEntry> {
        if (!allowed()) return emptyList()
        val out = ArrayList<ContactEntry>()
        val seen = HashSet<String>()
        runCatching {
            context.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE,
                    ContactsContract.CommonDataKinds.Phone.LABEL,
                    ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC")?.use {
                while (it.moveToNext()) {
                    val name = it.getString(0) ?: continue
                    val num = it.getString(1) ?: continue
                    if (!seen.add(name + "|" + MessageStore.keyOf(num))) continue
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(context.resources, it.getInt(2), it.getString(3)).toString()
                    out += ContactEntry(name, num, label, it.getString(4)?.let(Uri::parse))
                }
            }
        }
        return out
    }

    fun photo(uri: Uri?): Bitmap? = uri?.let {
        runCatching { context.contentResolver.openInputStream(it)?.use(BitmapFactory::decodeStream) }.getOrNull()
    }
}
