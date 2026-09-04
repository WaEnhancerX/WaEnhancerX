package com.waenhancer.utils

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.ContactsContract
import com.waenhancer.xposed.core.db.PreservedMessageStore
import java.io.File

/**
 * Universal WhatsApp Contact & Chat Name Resolver.
 * Resolves LIDs, Phone JIDs, and Group JIDs to real contact display names,
 * matching the exact multi-tier resolution logic from the Delete For Me message section.
 */
object ContactNameResolver {

    fun resolveName(
        context: Context,
        rawJid: String,
        fallbackName: String? = null,
        phoneHint: String? = null
    ): String {
        if (rawJid.isBlank() && phoneHint.isNullOrBlank()) return fallbackName ?: "Unknown"

        val isGroup = rawJid.contains("@g.us") || rawJid.contains("-")
        val userPart = rawJid.substringBefore("@").substringBefore(":")

        // If fallbackName is already a real contact name (not a raw LID or numeric string), use it
        if (!fallbackName.isNullOrBlank() &&
            !fallbackName.startsWith("Group (") &&
            !fallbackName.startsWith("+15478") &&
            !fallbackName.startsWith("15478") &&
            !fallbackName.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
        ) {
            return fallbackName
        }

        // 1. Check PreservedMessageStore (where WhatsApp Delete-For-Me engine records exact resolved names)
        try {
            val recordedName = PreservedMessageStore.getInstance(context).getContactNameByJid(rawJid)
            if (!recordedName.isNullOrBlank() && !recordedName.all { it.isDigit() || it == '+' }) {
                return recordedName
            }
            if (!phoneHint.isNullOrBlank()) {
                val phoneRecorded = PreservedMessageStore.getInstance(context).getContactNameByJid(phoneHint)
                if (!phoneRecorded.isNullOrBlank() && !phoneRecorded.all { it.isDigit() || it == '+' }) {
                    return phoneRecorded
                }
            }
        } catch (_: Throwable) {}

        // 2. Group Subject Resolution
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
            if (!systemName.isNullOrBlank()) {
                return systemName
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

                            if (!displayName.isNullOrBlank() && !displayName.all { it.isDigit() }) return displayName
                            if (!waName.isNullOrBlank() && !waName.all { it.isDigit() }) return waName
                            if (!sortName.isNullOrBlank() && !sortName.all { it.isDigit() }) return sortName
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
                            if (!vname.isNullOrBlank()) return vname
                        }
                    }
                }
            }
        } catch (_: Throwable) {}

        if (!resolvedPhoneUser.isNullOrBlank()) {
            return "+$resolvedPhoneUser"
        }

        return if (userPart.all { it.isDigit() } && userPart.length in 10..15) {
            "+$userPart"
        } else {
            userPart
        }
    }

    /**
     * Resolves contact display name from Android System Contacts using:
     * 1. Standard PhoneLookup
     * 2. PhoneLookup with '+'
     * 3. CommonDataKinds.Phone with trailing 7-9 digits
     */
    private fun getSystemContactName(context: Context, phoneNumber: String): String? {
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
                    if (!name.isNullOrBlank()) return name.trim()
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
                        if (!name.isNullOrBlank()) return name.trim()
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
                        if (!name.isNullOrBlank()) return name.trim()
                    }
                }
            } catch (_: Throwable) {}
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
