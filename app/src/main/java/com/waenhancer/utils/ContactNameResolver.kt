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
 * 4. Android System Contacts Provider (ContactsContract PhoneLookup & suffix queries).
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
        if (rawJid.isBlank() && phoneHint.isNullOrBlank()) return fallbackName ?: "Unknown"

        val isGroup = isGroupJid(rawJid)
        val userPart = cleanUserPart(rawJid)

        // If fallbackName is already a valid human-readable contact name, return it directly
        if (isValidDisplayName(fallbackName)) {
            return fallbackName!!
        }

        // 1. Check PreservedMessageStore (where WhatsApp Delete-For-Me engine records exact resolved names)
        try {
            val recordedName = PreservedMessageStore.getInstance(context).getContactNameByJid(rawJid)
            if (isValidDisplayName(recordedName)) {
                return recordedName!!
            }
            if (!phoneHint.isNullOrBlank()) {
                val phoneRecorded = PreservedMessageStore.getInstance(context).getContactNameByJid(phoneHint)
                if (isValidDisplayName(phoneRecorded)) {
                    return phoneRecorded!!
                }
            }
        } catch (_: Throwable) {}

        // 2. Group Subject Resolution (from msgstore.db if available)
        if (isGroup) {
            try {
                val dbFile = findDatabase(context, "msgstore.db")
                if (dbFile != null && dbFile.exists()) {
                    SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                        db.rawQuery(
                            "SELECT subject FROM chat WHERE jid_row_id = (SELECT _id FROM jid WHERE raw_string = ? OR user = ?) LIMIT 1",
                            arrayOf(rawJid, userPart)
                        ).use { c ->
                            if (c != null && c.moveToFirst()) {
                                val subject = c.getString(0)
                                if (!subject.isNullOrBlank()) return subject
                            }
                        }
                    }
                }
            } catch (_: Throwable) {}
            return if (!fallbackName.isNullOrBlank() && fallbackName != "Group ($userPart)") fallbackName else "Group"
        }

        // 3. Personal Contact / LID Resolution
        var resolvedPhoneJid: String? = null
        var resolvedPhoneUser: String? = phoneHint
        val cleanJid = if (rawJid.contains("@")) rawJid else "$rawJid@lid"

        // Step A: Resolve LID to Phone JID in msgstore.db (if accessible)
        try {
            val dbFile = findDatabase(context, "msgstore.db")
            if (dbFile != null && dbFile.exists()) {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val resolvePhoneSql = """
                        SELECT j2.raw_string, j2.user 
                        FROM jid_map jm
                        JOIN jid j1 ON jm.lid_row_id = j1._id
                        JOIN jid j2 ON jm.jid_row_id = j2._id
                        WHERE j1.raw_string = ? OR j1.user = ?
                    """.trimIndent()
                    db.rawQuery(resolvePhoneSql, arrayOf(cleanJid, userPart)).use { c ->
                        if (c != null && c.moveToFirst()) {
                            resolvedPhoneJid = c.getString(0)
                            resolvedPhoneUser = c.getString(1)
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // Step B: Query Android System Contacts Provider (PhoneLookup + Trailing Digits Match)
        val candidatePhones = listOfNotNull(resolvedPhoneUser, phoneHint, userPart).filter { it.isNotBlank() }
        for (targetPhone in candidatePhones) {
            val systemName = getSystemContactName(context, targetPhone)
            if (isValidDisplayName(systemName)) {
                return systemName!!
            }
        }

        // Step C: Query wa.db (wa_contacts table)
        try {
            val waDbFile = findDatabase(context, "wa.db")
            if (waDbFile != null && waDbFile.exists()) {
                SQLiteDatabase.openDatabase(waDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { waDb ->
                    val querySql = "SELECT display_name, wa_name, sort_name, number FROM wa_contacts WHERE jid = ? OR jid = ? OR number = ? OR number = ? OR number LIKE ?"
                    val params = arrayOf(
                        rawJid,
                        resolvedPhoneJid ?: "",
                        userPart,
                        resolvedPhoneUser ?: "",
                        "%${resolvedPhoneUser ?: userPart}%"
                    )
                    waDb.rawQuery(querySql, params).use { c ->
                        if (c != null && c.moveToFirst()) {
                            val displayName = c.getString(0)
                            val waName = c.getString(1)
                            val sortName = c.getString(2)
                            val number = c.getString(3)

                            if (isValidDisplayName(displayName)) return displayName
                            if (isValidDisplayName(waName)) return waName
                            if (isValidDisplayName(sortName)) return sortName
                            if (!displayName.isNullOrBlank()) return displayName
                            if (!number.isNullOrBlank()) return "+$number"
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        // Step D: Check wa_vnames (Verified Business Names) in wa.db
        try {
            val waDbFile = findDatabase(context, "wa.db")
            if (waDbFile != null && waDbFile.exists()) {
                SQLiteDatabase.openDatabase(waDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { waDb ->
                    waDb.rawQuery("SELECT verified_name FROM wa_vnames WHERE jid = ? OR jid = ?", arrayOf(rawJid, resolvedPhoneJid ?: "")).use { c ->
                        if (c != null && c.moveToFirst()) {
                            val vname = c.getString(0)
                            if (isValidDisplayName(vname)) return vname
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        if (!resolvedPhoneUser.isNullOrBlank()) {
            return formatPhoneNumber(resolvedPhoneUser)
        }

        return if (userPart.all { it.isDigit() } && userPart.length in 10..15) {
            "+$userPart"
        } else {
            userPart
        }
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
                val mappedDbName = resolveFromWaDb(activity, mappedPhone)
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
        try {
            val waDbFile = findDatabase(context, "wa.db")
            if (waDbFile != null && waDbFile.exists()) {
                SQLiteDatabase.openDatabase(waDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { waDb ->
                    val user = cleanUserPart(rawJid)
                    val query = "SELECT display_name, wa_name, sort_name, number FROM wa_contacts WHERE jid LIKE ? OR jid = ? OR number = ? OR number LIKE ?"
                    waDb.rawQuery(query, arrayOf("%$user%", rawJid, user, "%$user%")).use { c ->
                        if (c != null && c.moveToFirst()) {
                            val displayName = c.getString(0)
                            val waName = c.getString(1)
                            val sortName = c.getString(2)
                            if (isValidDisplayName(displayName)) return displayName
                            if (isValidDisplayName(waName)) return waName
                            if (isValidDisplayName(sortName)) return sortName
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Maps a WhatsApp LID back to its phone number from msgstore.db jid_map table.
     */
    fun resolveLidToPhone(context: Context, rawJid: String): String? {
        try {
            val dbFile = findDatabase(context, "msgstore.db")
            if (dbFile != null && dbFile.exists()) {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    val user = cleanUserPart(rawJid)
                    val query = """
                        SELECT j2.user, j2.raw_string 
                        FROM jid_map jm
                        JOIN jid j1 ON jm.lid_row_id = j1._id
                        JOIN jid j2 ON jm.jid_row_id = j2._id
                        WHERE j1.raw_string LIKE ? OR j1.user = ?
                    """.trimIndent()
                    db.rawQuery(query, arrayOf("%$user%", user)).use { c ->
                        if (c != null && c.moveToFirst()) {
                            val phone = c.getString(0)
                            if (!phone.isNullOrBlank()) return phone
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    /**
     * Resolves contact display name from Android System Contacts using:
     * 1. Standard PhoneLookup
     * 2. PhoneLookup with '+'
     * 3. CommonDataKinds.Phone with trailing 7-9 digits
     */
    fun getSystemContactName(context: Context, phoneNumber: String): String? {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9]"), "")
        if (cleanNumber.length < 5) return null

        // 1. Try standard PhoneLookup
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(cleanNumber))
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (isValidDisplayName(name)) return name.trim()
                }
            }
        } catch (_: Throwable) {}

        // 2. Try with leading '+'
        if (!cleanNumber.startsWith("+")) {
            try {
                val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode("+$cleanNumber"))
                context.contentResolver.query(
                    uri,
                    arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (isValidDisplayName(name)) return name.trim()
                    }
                }
            } catch (_: Throwable) {}
        }

        // 3. Fallback: Query CommonDataKinds.Phone with suffix match (last 7 to 9 digits)
        if (cleanNumber.length >= 7) {
            val suffix = cleanNumber.substring(cleanNumber.length - 7)
            try {
                val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
                val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val selection = "${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
                val selectionArgs = arrayOf("%$suffix")

                context.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (isValidDisplayName(name)) return name.trim()
                    }
                }
            } catch (_: Throwable) {}
        }

        return null
    }

    fun isGroupJid(jid: String): Boolean = jid.contains("@g.us") || jid.contains("-")

    fun isLidJid(jid: String): Boolean = jid.contains("@lid") || (cleanUserPart(jid).length >= 14 && !isGroupJid(jid))

    fun cleanUserPart(jid: String): String = jid.substringBefore("@").substringBefore(":")

    fun formatPhoneNumber(number: String): String = if (number.startsWith("+")) number else "+$number"

    fun isValidDisplayName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        if (trimmed == "Unknown" || trimmed == "WhatsApp" || trimmed == "Contact info" || trimmed == "Group info" || trimmed == "Custom Privacy") return false
        if (trimmed.startsWith("Group (") || trimmed.startsWith("+15478") || trimmed.startsWith("15478")) return false
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
        val targetPkg = "com.whatsapp"
        val candidates = listOf(
            File(context.filesDir?.parentFile, "databases/$dbName"),
            File("/data/data/$targetPkg/databases/$dbName"),
            File("/data/user/0/$targetPkg/databases/$dbName")
        )
        return candidates.firstOrNull { it.exists() }
    }
}
