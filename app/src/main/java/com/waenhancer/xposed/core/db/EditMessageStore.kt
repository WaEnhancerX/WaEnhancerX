package com.waenhancer.xposed.core.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Persistent SQLite Store for recording and querying edited message history.
 * Preserves pre-edited content and edit timestamps across sessions and app restarts.
 */
class EditMessageStore private constructor(context: Context) : SQLiteOpenHelper(
    if (context.applicationContext != null) context.applicationContext else context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    data class EditHistoryItem(
        val msgId: String,
        val jid: String,
        val originalText: String,
        val editedText: String,
        val timestamp: Long
    )

    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, MutableList<EditHistoryItem>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<EditHistoryItem>>?): Boolean {
                return size > 500
            }
        }
    )

    companion object {
        private const val DATABASE_NAME = "waex_edithistory.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_NAME = "edit_history"
        const val COL_ID = "_id"
        const val COL_MSGID = "msgid"
        const val COL_JID = "jid"
        const val COL_ORIGINAL_TEXT = "original_text"
        const val COL_EDITED_TEXT = "edited_text"
        const val COL_TIMESTAMP = "timestamp"

        @Volatile
        private var instance: EditMessageStore? = null

        @JvmStatic
        fun getInstance(context: Context): EditMessageStore {
            return instance ?: synchronized(this) {
                instance ?: EditMessageStore(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_MSGID TEXT,
                $COL_JID TEXT,
                $COL_ORIGINAL_TEXT TEXT,
                $COL_EDITED_TEXT TEXT,
                $COL_TIMESTAMP INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edit_msgid ON $TABLE_NAME ($COL_MSGID)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun recordEdit(msgId: String?, jid: String?, originalText: String?, editedText: String?, timestamp: Long) {
        if (msgId.isNullOrEmpty()) return
        val orig = originalText ?: ""
        val edited = editedText ?: ""
        val ts = if (timestamp > 0) timestamp else System.currentTimeMillis()

        val item = EditHistoryItem(msgId, jid ?: "", orig, edited, ts)
        val list = cache.getOrPut(msgId) { mutableListOf() }
        synchronized(list) {
            if (!list.any { it.originalText == orig && it.editedText == edited }) {
                list.add(item)
            }
        }

        try {
            writableDatabase.use { db ->
                val cv = ContentValues().apply {
                    put(COL_MSGID, msgId)
                    put(COL_JID, jid ?: "")
                    put(COL_ORIGINAL_TEXT, orig)
                    put(COL_EDITED_TEXT, edited)
                    put(COL_TIMESTAMP, ts)
                }
                db.insert(TABLE_NAME, null, cv)
            }
        } catch (ignored: Throwable) {}
    }

    fun getHistoryByMessageId(msgId: String?): List<EditHistoryItem> {
        if (msgId.isNullOrEmpty()) return emptyList()

        val cached = cache[msgId]
        if (!cached.isNullOrEmpty()) {
            return synchronized(cached) { ArrayList(cached) }
        }

        val result = mutableListOf<EditHistoryItem>()
        try {
            readableDatabase.use { db ->
                val cursor: Cursor? = db.query(
                    TABLE_NAME,
                    arrayOf(COL_MSGID, COL_JID, COL_ORIGINAL_TEXT, COL_EDITED_TEXT, COL_TIMESTAMP),
                    "$COL_MSGID=?",
                    arrayOf(msgId),
                    null,
                    null,
                    "$COL_TIMESTAMP ASC"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        result.add(
                            EditHistoryItem(
                                msgId = it.getString(0),
                                jid = it.getString(1),
                                originalText = it.getString(2),
                                editedText = it.getString(3),
                                timestamp = it.getLong(4)
                            )
                        )
                    }
                }
            }
            if (result.isNotEmpty()) {
                cache[msgId] = result
            }
        } catch (ignored: Throwable) {}

        return result
    }
}
