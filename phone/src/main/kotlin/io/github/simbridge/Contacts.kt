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

    /** Names saved on the SIM phone (its accounts and SIM card), for numbers this phone doesn't know. */
    @Volatile private var simPhone: Map<String, ContactEntry>? = null
    private val simFile get() = java.io.File(context.filesDir, "sim-contacts.json")

    private fun simPhoneContacts(): Map<String, ContactEntry> = simPhone ?: runCatching {
        val a = org.json.JSONArray(simFile.readText())
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.associate { o ->
            MessageStore.keyOf(o.optString("n")) to ContactEntry(o.optString("name"), o.optString("n"), "SIM phone", null)
        }
    }.getOrDefault(emptyMap()).also { simPhone = it }

    /** Replaces the SIM phone's contact list (sent by the gateway). */
    fun setSimPhoneContacts(list: org.json.JSONArray) {
        runCatching { simFile.writeText(list.toString()) }
        simPhone = null
        cache.clear()
        Log.i("contacts: ${list.length()} from the SIM phone")
    }

    fun lookup(number: String?): ContactInfo? {
        if (number != null && Group.isGroup(number)) {
            // Group: "Ali, Ayşe, +90555…"
            return ContactInfo(Group.members(number).joinToString(", ") { m -> lookup(m)?.name?.substringBefore(' ') ?: m }, null)
        }
        if (number.isNullOrBlank()) return null
        if (number.none { it.isDigit() }) return null
        val k = MessageStore.keyOf(number)
        cache[k]?.let { return it as? ContactInfo }
        if (!allowed()) return simPhoneContacts()[k]?.let { ContactInfo(it.name, null) }
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val info = runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI), null, null, null)?.use {
                if (it.moveToFirst()) ContactInfo(it.getString(0), it.getString(1)?.let(Uri::parse)) else null
            }
        }.getOrNull()
        val found = info ?: simPhoneContacts()[k]?.let { ContactInfo(it.name, null) }
        cache[k] = found ?: NONE
        return found
    }

    fun all(): List<ContactEntry> {
        val out = ArrayList<ContactEntry>()
        val seen = HashSet<String>()
        if (allowed()) own(out, seen)
        // Numbers only the SIM phone knows.
        val known = out.mapTo(HashSet()) { MessageStore.keyOf(it.number) }
        simPhoneContacts().forEach { (k, e) -> if (k !in known) out += e }
        val c = java.text.Collator.getInstance()
        return out.sortedWith { a, b -> c.compare(a.name, b.name) }
    }

    private fun own(out: ArrayList<ContactEntry>, seen: HashSet<String>) {
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
    }

    fun photo(uri: Uri?): Bitmap? = uri?.let {
        runCatching { context.contentResolver.openInputStream(it)?.use(BitmapFactory::decodeStream) }.getOrNull()
    }
}
