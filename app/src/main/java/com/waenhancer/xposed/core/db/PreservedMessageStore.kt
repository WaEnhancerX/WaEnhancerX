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
        private const val DATABASE_VERSION = 2
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

    data class StoredMessage(
        val id: String,
        val jid: String,
        val chatName: String,
        val senderName: String,
        val text: String,
        val timestamp: Long,
        val isFromMe: Boolean,
        val isGroup: Boolean
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
            try {
                db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $COL_SENDER_NAME TEXT")
            } catch (ignored: Throwable) {
                db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
                onCreate(db)
            }
        }
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
        } catch (ignored: Throwable) {}
    }

    fun getAllPreservedMessages(): List<StoredMessage> {
        val list = mutableListOf<StoredMessage>()
        try {
            val db = readableDatabase
            val cursor: Cursor? = db.query(
                TABLE_NAME,
                arrayOf(COL_MSG_ID, COL_JID, COL_NAME, COL_SENDER_NAME, COL_TEXT, COL_TIMESTAMP, COL_FROM_ME, COL_IS_GROUP),
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
                            isGroup = isGroup
                        )
                    )
                }
            }
        } catch (ignored: Throwable) {}
        return list
    }

    fun getContactNameByJid(jid: String): String? {
        try {
            val db = readableDatabase
            val user = jid.substringBefore("@").substringBefore(":")
            val cleanJid = if (jid.contains("@")) jid else "$jid@s.whatsapp.net"
            db.rawQuery(
                "SELECT $COL_NAME FROM $TABLE_NAME WHERE ($COL_JID = ? OR $COL_JID LIKE ? OR $COL_JID LIKE ?) AND $COL_NAME IS NOT NULL AND $COL_NAME != '' LIMIT 1",
                arrayOf(cleanJid, "%$user%", "%$user@%")
            ).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val name = cursor.getString(0)
                    if (!name.isNullOrBlank() && !name.all { it.isDigit() || it == '+' }) {
                        return name
                    }
                }
            }
        } catch (ignored: Throwable) {}
        return null
    }
}
