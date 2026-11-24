package com.waenhancer.utils

import android.app.Activity
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.ContactsContract
import android.view.ViewGroup
import android.widget.TextView
import com.waenhancer.xposed.core.db.PreservedMessageStore
import java.io.File

/**
 * Universal WhatsApp Contact & Chat Name Resolver.
 * Provides a unified, multi-tier resolution pipeline for:
 * 1. Live WhatsApp UI / Activity traversal (Headers, ActionBars, View Hierarchy).
 * 2. WhatsApp internal SQLite databases (wa.db, msgstore.db jid_map for LID -> Phone translation).
 * 3. Preserved message store (cross-process IPC cached names).
 * 4. Android System Contacts Provider (full-number verification of PhoneLookup results).
 */
object ContactNameResolver {

    /**
     * Resolves the best display name for a given WhatsApp JID or phone number.
     */
    fun resolveName(
        context: Context,
        rawJid: String,
        fallbackName: String? = null,
        phoneHint: String? = null
    ): String {
        // An unqualified numeric identifier must not silently become a LID or a phone.
        val jid = ContactIdentityPolicy.canonicalJid(rawJid) ?: if (rawJid.isBlank()) {
            ContactIdentityPolicy.phoneDigits(phoneHint)?.let { "$it@s.whatsapp.net" }
        } else null
        if (jid == null) return fallbackName?.takeIf(::isValidDisplayName) ?: "Unknown"

        if (isGroupJid(jid)) {
            try {
                findDatabase(context, "msgstore.db")?.let { file ->
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery(
                            "SELECT subject FROM chat WHERE jid_row_id IN (SELECT _id FROM jid WHERE raw_string = ?) LIMIT 33",
                            arrayOf(jid)
                        ).use { cursor -> uniqueDisplayName(cursor, 1)?.let { return it } }
                    }
                }
            } catch (_: Exception) { }
            return fallbackName?.takeIf(::isValidDisplayName) ?: "Group"
        }

        // Each lookup has an exact namespace-qualified key. Try the original identity first.
        resolveFromWaDb(context, jid)?.let { return it }
        val mappedPhone = if (isLidJid(jid)) resolveLidToPhone(context, jid) else null
        val mappedJid = mappedPhone?.let { "$it@s.whatsapp.net" }
        if (mappedJid != null) resolveFromWaDb(context, mappedJid)?.let { return it }
        val phone = ContactIdentityPolicy.phoneUser(jid) ?: mappedPhone
        if (phone != null) getSystemContactName(context, phone)?.let { return it }

        for (candidate in listOfNotNull(jid, mappedJid).distinct()) {
            try {
                findDatabase(context, "wa.db")?.let { file ->
                    SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery("SELECT verified_name FROM wa_vnames WHERE jid = ? LIMIT 33",
                            arrayOf(candidate)).use { cursor -> uniqueDisplayName(cursor, 1)?.let { return it } }
                    }
                }
            } catch (_: Exception) { }
        }
        // Historical display names are a fallback, not more authoritative than exact live rows.
        try {
            PreservedMessageStore.getInstance(context).getContactNameByJid(jid)
                ?.takeIf(::isValidDisplayName)?.let { return it }
        } catch (_: Exception) { }
        return fallbackName?.takeIf(::isValidDisplayName) ?: phone?.let(::formatPhoneNumber) ?: "Unknown"
    }

    /** Refuse conflicting names and bound cursor work, even for a corrupt database/provider. */
    private fun uniqueDisplayName(cursor: Cursor?, columns: Int): String? {
        if (cursor == null) return null
        var name: String? = null
        var rows = 0
        while (cursor.moveToNext()) {
            if (++rows > 32) return null
            val candidate = (0 until columns).asSequence().map { cursor.getString(it) }
                .firstOrNull(::isValidDisplayName)?.trim() ?: continue
            if (name != null && name != candidate) return null
            name = candidate
        }
        return name
    }

    /**
     * Resolves contact or chat display name from within a live WhatsApp Activity.
     * Searches databases, view hierarchy, action bars, intent extras, and system contacts.
     */
    fun resolveFromActivity(
        activity: Activity,
        rawJid: String? = null,
        fallbackNumber: String? = null
    ): String {
        val jid = rawJid ?: extractJidFromActivity(activity)
        val cleanNumber = fallbackNumber ?: cleanUserPart(jid)

        try {
            // 1. Direct WA database lookup (wa.db)
            val dbName = resolveFromWaDb(activity, jid)
            if (isValidDisplayName(dbName)) {
                return dbName!!
            }

            // 2. Resolve LID to Phone in msgstore.db, then check wa.db
            val mappedPhone = resolveLidToPhone(activity, jid)
            if (!mappedPhone.isNullOrBlank()) {
                val mappedDbName = resolveFromWaDb(activity, "$mappedPhone@s.whatsapp.net")
                if (isValidDisplayName(mappedDbName)) {
                    return mappedDbName!!
                }
            }

            // 3. Scan Activity View Hierarchy (Toolbar / Header TextViews)
            val decorView = activity.window?.decorView
            if (decorView is ViewGroup) {
                val foundName = findContactNameInDecorView(decorView)
                if (isValidDisplayName(foundName)) {
                    return foundName!!
                }
            }

            // 4. Check direct Activity title
            val title = activity.title?.toString()?.trim()
            if (isValidDisplayName(title) && title != "WhatsApp" && title != "Contact info" && title != "Group info") {
                return resolveName(activity, jid, title, mappedPhone)
            }

            // 5. Check Action bar title
            val abTitle = activity.actionBar?.title?.toString()?.trim()
            if (isValidDisplayName(abTitle) && abTitle != "WhatsApp" && abTitle != "Contact info" && abTitle != "Group info") {
                return resolveName(activity, jid, abTitle, mappedPhone)
            }

            // 6. Check known WhatsApp contact TextViews
            val res = activity.resources
            val pkg = activity.packageName
            val viewIds = listOf("conversation_contact_name", "conversation_title", "name", "title_toolbar", "toolbar_title", "contact_name", "conversation_header")
            for (vId in viewIds) {
                val id = res.getIdentifier(vId, "id", pkg)
                if (id != 0) {
                    val tv = activity.findViewById<TextView>(id)
                    val text = tv?.text?.toString()?.trim()
                    if (isValidDisplayName(text) && text != "WhatsApp" && text != "Contact info" && text != "Group info" && !text!!.startsWith("online") && !text.startsWith("typing")) {
                        return resolveName(activity, jid, text, mappedPhone)
                    }
                }
            }

            // 7. Check Intent Extras
            val intent = activity.intent
            val extraName = intent?.getStringExtra("display_name") ?: intent?.getStringExtra("name")
            if (isValidDisplayName(extraName)) {
                return resolveName(activity, jid, extraName, mappedPhone)
            }
        } catch (_: Throwable) {}

        return resolveName(activity, jid, if (cleanNumber.length > 5) "+$cleanNumber" else cleanNumber)
    }

    /**
     * Extracts WhatsApp JID from Activity intent extras or reflection.
     */
    fun extractJidFromActivity(activity: Activity): String {
        val intent = activity.intent
        if (intent != null) {
            val candidateKeys = listOf("jid", "chat_jid", "extra_jid", "user_jid", "address")
            for (key in candidateKeys) {
                val str = intent.getStringExtra(key)
                if (!str.isNullOrEmpty()) return str
            }
            val extras = intent.extras
            if (extras != null) {
                for (key in extras.keySet()) {
                    val obj = extras.get(key)
                    if (obj != null) {
                        val str = obj.toString()
                        if (str.contains("@s.whatsapp.net") || str.contains("@g.us") || str.contains("@lid")) {
                            return str
                        }
                    }
                }
            }
        }

        // Reflection fallback: inspect Activity fields for Jid object
        try {
            for (f in activity.javaClass.declaredFields) {
                if (f.type.name.contains("Jid", ignoreCase = true)) {
                    f.isAccessible = true
                    val jidObj = f.get(activity)
                    if (jidObj != null) return jidObj.toString()
                }
            }
        } catch (_: Throwable) {}

        return "contact"
    }

    /**
     * Resolves contact name from WhatsApp's internal wa.db (when running inside com.whatsapp).
     */
    fun resolveFromWaDb(context: Context, rawJid: String): String? {
        val jid = ContactIdentityPolicy.canonicalJid(rawJid) ?: return null
        try {
            findDatabase(context, "wa.db")?.let { file ->
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery("SELECT display_name, wa_name, sort_name FROM wa_contacts WHERE jid = ? LIMIT 33",
                        arrayOf(jid)).use { cursor -> return uniqueDisplayName(cursor, 3) }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** Only a unique explicit jid_map edge may associate a LID with a phone JID. */
    fun resolveLidToPhone(context: Context, rawJid: String): String? {
        if (!ContactIdentityPolicy.isLid(rawJid)) return null
        try {
            findDatabase(context, "msgstore.db")?.let { file ->
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val query = """
                        SELECT DISTINCT j2.raw_string
                        FROM jid_map jm
                        JOIN jid j1 ON jm.lid_row_id = j1._id
                        JOIN jid j2 ON jm.jid_row_id = j2._id
                        WHERE j1.raw_string = ? LIMIT 2
                    """.trimIndent()
                    db.rawQuery(query, arrayOf(rawJid)).use { cursor ->
                        if (cursor == null || !cursor.moveToFirst()) return null
                        val phone = ContactIdentityPolicy.phoneUser(cursor.getString(0)) ?: return null
                        if (cursor.moveToNext()) return null
                        return phone
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** Reverse of [resolveLidToPhone]: only a unique explicit jid_map edge counts. */
    fun resolvePhoneToLid(context: Context, rawJid: String): String? {
        if (ContactIdentityPolicy.phoneUser(rawJid) == null) return null
        try {
            findDatabase(context, "msgstore.db")?.let { file ->
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val query = """
                        SELECT DISTINCT j1.raw_string
                        FROM jid_map jm
                        JOIN jid j1 ON jm.lid_row_id = j1._id
                        JOIN jid j2 ON jm.jid_row_id = j2._id
                        WHERE j2.raw_string = ? LIMIT 2
                    """.trimIndent()
                    db.rawQuery(query, arrayOf(rawJid)).use { cursor ->
                        if (cursor == null || !cursor.moveToFirst()) return null
                        val lid = cursor.getString(0)?.takeIf { ContactIdentityPolicy.isLid(it) } ?: return null
                        if (cursor.moveToNext()) return null
                        return lid
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** PhoneLookup may be fuzzy: independently compare its complete number before using a name. */
    fun getSystemContactName(context: Context, phoneNumber: String): String? {
        val digits = ContactIdentityPolicy.phoneDigits(phoneNumber) ?: return null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode("+$digits"))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.NORMALIZED_NUMBER, ContactsContract.PhoneLookup.NUMBER),
                null, null, null)?.use { cursor ->
                var name: String? = null
                var rows = 0
                while (cursor.moveToNext()) {
                    if (++rows > 32) return null
                    val number = ContactIdentityPolicy.phoneDigits(cursor.getString(1))
                        ?: ContactIdentityPolicy.phoneDigits(cursor.getString(2))
                    if (number != digits) continue
                    val candidate = cursor.getString(0)?.trim()?.takeIf(::isValidDisplayName) ?: continue
                    if (name != null && name != candidate) return null
                    name = candidate
                }
                return name
            }
        } catch (_: Exception) { }
        return null
    }

    fun isGroupJid(jid: String): Boolean = ContactIdentityPolicy.isGroup(jid)

    fun isLidJid(jid: String): Boolean = ContactIdentityPolicy.isLid(jid)

    fun cleanUserPart(jid: String): String = jid.substringBefore("@").substringBefore(":").substringBefore(".")

    fun formatPhoneNumber(number: String): String = if (number.startsWith("+")) number else "+$number"

    fun isValidDisplayName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        if (trimmed == "Unknown" || trimmed == "WhatsApp" || trimmed == "Contact info" || trimmed == "Group info" || trimmed == "Custom Privacy") return false
        if (trimmed.startsWith("Group (") || trimmed.startsWith("+15478") || trimmed.startsWith("15478")) return false
        // If string is pure digits and length >= 14, it's a raw LID ID, not a valid contact name
        val digitsOnly = trimmed.filter { it.isDigit() }
        if (digitsOnly.length >= 14 && (trimmed.startsWith("+") || trimmed.all { it.isDigit() || it == '.' || it == ':' || it == ' ' })) return false
        if (trimmed.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) return false
        return true
    }

    private fun findContactNameInDecorView(group: ViewGroup): String? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child is TextView) {
                val text = child.text?.toString()?.trim() ?: ""
                if (isValidDisplayName(text) &&
                    !text.startsWith("online") &&
                    !text.startsWith("offline") &&
                    !text.startsWith("last seen") &&
                    !text.startsWith("typing")
                ) {
                    return text
                }
            } else if (child is ViewGroup) {
                val found = findContactNameInDecorView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findDatabase(context: Context, dbName: String): File? {
        if (dbName != "wa.db" && dbName != "msgstore.db") return null
        // Never fall back to another package's or user 0's databases.
        return context.getDatabasePath(dbName)?.takeIf { it.isFile && it.canRead() }
    }
}
