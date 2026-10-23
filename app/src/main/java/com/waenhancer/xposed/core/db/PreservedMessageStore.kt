package com.waenhancer.xposed.core.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * SQLite Store for Preserved "Delete For Me" messages.
 * Stores full text, contact/chat JID, chat display name, sender display name, timestamp, and sender direction (fromMe).
 */
class PreservedMessageStore private constructor(context: Context) : SQLiteOpenHelper(
    if (context.applicationContext != null) context.applicationContext else context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    companion object {
        private const val DATABASE_NAME = "waex_preserved_messages.db"
        // Version 3 has existed with two UNIQUE-key layouts. Version 4 was also
        // published in the compatibility candidate. Both remain readable without
        // rebuilding the table; keep the version monotonic across those builds.
        private const val DATABASE_VERSION = 4
        const val TABLE_NAME = "preserved_messages"

        const val COL_ID = "_id"
        const val COL_JID = "jid"
        const val COL_NAME = "contact_name"
        const val COL_SENDER_NAME = "sender_name"
        const val COL_MSG_ID = "msg_id"
        const val COL_TEXT = "text_data"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_FROM_ME = "from_me"
        const val COL_IS_GROUP = "is_group"

        @Volatile
        private var instance: PreservedMessageStore? = null

        @JvmStatic
        fun getInstance(context: Context): PreservedMessageStore {
            return instance ?: synchronized(this) {
                instance ?: PreservedMessageStore(context).also { instance = it }
            }
        }
    }

    data class StoredMessage @JvmOverloads constructor(
        val id: String,
        val jid: String,
        val chatName: String,
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val isFromMe: Boolean,
        val isGroup: Boolean,
        val storageId: Long = 0L
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_JID TEXT NOT NULL,
                $COL_NAME TEXT,
                $COL_SENDER_NAME TEXT,
                $COL_MSG_ID TEXT UNIQUE,
                $COL_TEXT TEXT,
                $COL_TIMESTAMP INTEGER DEFAULT 0,
                $COL_FROM_ME INTEGER DEFAULT 0,
                $COL_IS_GROUP INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val columns = mutableSetOf<String>()
            db.rawQuery("PRAGMA table_info($TABLE_NAME)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) if (nameIndex >= 0) columns.add(cursor.getString(nameIndex))
            }
            if (columns.isEmpty()) {
                onCreate(db)
            } else if (COL_SENDER_NAME !in columns) {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SENDER_NAME TEXT")
            }
        }
        // The readable columns of v2, both historical v3 layouts, and v4 are
        // compatible. Preserve their row IDs, indexes, constraints and sequence.
        // Let SQLiteOpenHelper reject an unknown future version by default.
    }

    fun insertPreservedMessage(
        jid: String,
        chatName: String?,
        senderName: String?,
        msgId: String,
        text: String,
        timestamp: Long,
        fromMe: Boolean,
        isGroup: Boolean
    ) {
        if (msgId.isBlank()) return
        try {
            val db = writableDatabase
            val resolvedChatName = if (!chatName.isNullOrBlank() && !chatName.all { it.isDigit() }) {
                chatName
            } else {
                jid.substringBefore("@")
            }
            val resolvedSenderName = if (!senderName.isNullOrBlank() && !senderName.all { it.isDigit() }) {
                senderName
            } else if (fromMe) {
                "You"
            } else {
                resolvedChatName
            }

            val cv = ContentValues().apply {
                put(COL_JID, jid.ifBlank { "Unknown" })
                put(COL_NAME, resolvedChatName)
                put(COL_SENDER_NAME, resolvedSenderName)
                put(COL_MSG_ID, msgId)
                put(COL_TEXT, text)
                put(COL_TIMESTAMP, if (timestamp > 0) timestamp else System.currentTimeMillis())
                put(COL_FROM_ME, if (fromMe) 1 else 0)
                put(COL_IS_GROUP, if (isGroup) 1 else 0)
            }
            db.insertWithOnConflict(TABLE_NAME, null, cv, SQLiteDatabase.CONFLICT_REPLACE)

            if (!chatName.isNullOrBlank() && !chatName.all { it.isDigit() }) {
                val updateCv = ContentValues().apply {
                    put(COL_NAME, chatName)
                }
                db.update(TABLE_NAME, updateCv, "$COL_JID = ?", arrayOf(jid))
            }
        } catch (t: Throwable) {
            // SQLite exception messages and stacks can contain message values.
            android.util.Log.e("WAEX:PreservedStore", "insertPreservedMessage failed: ${t.javaClass.simpleName}")
        }
    }

    fun getAllPreservedMessages(): List<StoredMessage> {
        val list = mutableListOf<StoredMessage>()
        try {
            val db = readableDatabase
            val cursor: Cursor? = db.query(
                TABLE_NAME,
                arrayOf(COL_MSG_ID, COL_JID, COL_NAME, COL_SENDER_NAME, COL_TEXT, COL_TIMESTAMP, COL_FROM_ME, COL_IS_GROUP, COL_ID),
                null, null, null, null,
                "$COL_TIMESTAMP ASC"
            )
            cursor?.use {
                val hasSenderCol = it.columnCount >= 8
                while (it.moveToNext()) {
                    val msgId = it.getString(0) ?: ""
                    val jid = it.getString(1) ?: ""
                    val chatName = it.getString(2) ?: ""
                    val senderName = if (hasSenderCol) (it.getString(3) ?: "") else ""
                    val text = it.getString(4) ?: ""
                    val timestamp = it.getLong(5)
                    val isFromMe = it.getInt(6) == 1
                    val isGroup = it.getInt(7) == 1

                    list.add(
                        StoredMessage(
                            id = msgId,
                            jid = jid,
                            chatName = chatName,
                            senderName = senderName,
                            text = text,
                            timestamp = timestamp,
                            isFromMe = isFromMe,
                            isGroup = isGroup,
                            storageId = it.getLong(8)
                        )
                    )
                }
            }
        } catch (ignored: Throwable) {}
        return list
    }

    fun getContactNameByJid(jid: String): String? {
        val exact = com.waenhancer.utils.ContactIdentityPolicy.canonicalJid(jid) ?: return null
        try {
            readableDatabase.rawQuery(
                "SELECT DISTINCT $COL_NAME FROM $TABLE_NAME WHERE $COL_JID = ? AND $COL_NAME IS NOT NULL AND $COL_NAME != '' LIMIT 2",
                arrayOf(exact)
            ).use { cursor ->
                if (cursor == null || !cursor.moveToFirst()) return null
                val name = cursor.getString(0)?.trim()
                if (cursor.moveToNext()) return null
                if (!name.isNullOrBlank() && !name.all { it.isDigit() || it == '+' }) return name
            }
        } catch (_: Exception) { }
        return null
    }
}
