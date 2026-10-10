package com.waenhancer.xposed.core.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Message history store for recording and querying all versions of edited messages.
 */
class EditMessageStore private constructor(private val context: Context) : SQLiteOpenHelper(
    if (context.applicationContext != null) context.applicationContext else context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    data class MessageItem(
        val rowId: Long,
        val keyId: String,
        val textData: String,
        val timestamp: Long,
        val versionNumber: Int
    )

    private val messagesCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, MutableList<MessageItem>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MutableList<MessageItem>>?): Boolean {
                return size > 500
            }
        }
    )

    companion object {
        private const val TAG = "[WAEX:Store]"
        private const val DATABASE_NAME = "waex_history.db"
        private const val DATABASE_VERSION = 4

        const val TABLE_NAME = "message_history"
        const val COL_ID = "_id"
        const val COL_ROW_ID = "row_id"
        const val COL_KEY_ID = "key_id"
        const val COL_TEXT = "text_data"
        const val COL_TIMESTAMP = "timestamp"
        const val COL_VERSION = "version"

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
                $COL_ROW_ID INTEGER DEFAULT 0,
                $COL_KEY_ID TEXT,
                $COL_TEXT TEXT,
                $COL_TIMESTAMP INTEGER DEFAULT 0,
                $COL_VERSION INTEGER DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hist_rowid ON $TABLE_NAME ($COL_ROW_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hist_keyid ON $TABLE_NAME ($COL_KEY_ID)")
        XposedBridge.log("$TAG Created SQLite table $TABLE_NAME")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Preserve saved message history. Older versions may be missing columns
        // or indexes; perform an additive, transactional migration instead.
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($TABLE_NAME)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) if (nameIndex >= 0) {
                columns.add(cursor.getString(nameIndex))
            }
        }
        if (columns.isEmpty()) {
            onCreate(db)
            return
        }
        val missing = mapOf(
            COL_ROW_ID to "INTEGER DEFAULT 0",
            COL_KEY_ID to "TEXT",
            COL_TEXT to "TEXT",
            COL_TIMESTAMP to "INTEGER DEFAULT 0",
            COL_VERSION to "INTEGER DEFAULT 1"
        )
        for ((column, declaration) in missing) {
            if (column !in columns) db.execSQL("ALTER TABLE $TABLE_NAME ADD COLUMN $column $declaration")
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hist_rowid ON $TABLE_NAME ($COL_ROW_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hist_keyid ON $TABLE_NAME ($COL_KEY_ID)")
    }

    @Synchronized
    fun recordEdit(rowId: Long, keyId: String?, newMessage: String, timestamp: Long) {
        if (newMessage.isBlank()) return
        val ts = if (timestamp > 0) timestamp else System.currentTimeMillis()
        val validKeyId = keyId ?: ""

        val existing = getMessages(rowId, validKeyId).toMutableList()

        // 1. If first edit, fetch original pre-edit text from WhatsApp's msgstore.db
        if (existing.isEmpty()) {
            val originalText = getOriginalMessageFromDb(rowId, validKeyId)
            if (originalText.isNotBlank() && originalText.trim() != newMessage.trim()) {
                insertMessageDirect(rowId, validKeyId, originalText.trim(), ts - 1000, 1)
                existing.add(MessageItem(rowId, validKeyId, originalText.trim(), ts - 1000, 1))
            }
        }

        // 2. Insert the new edited message version if it differs from the last recorded version
        val lastText = existing.lastOrNull()?.textData ?: ""
        if (newMessage.trim() != lastText) {
            val nextVer = existing.size + 1
            insertMessageDirect(rowId, validKeyId, newMessage.trim(), ts, nextVer)
            existing.add(MessageItem(rowId, validKeyId, newMessage.trim(), ts, nextVer))
        }

        if (rowId > 0) messagesCache[rowId.toString()] = existing
        if (validKeyId.isNotEmpty()) messagesCache[validKeyId] = existing
    }

    private fun insertMessageDirect(rowId: Long, keyId: String, text: String, timestamp: Long, version: Int) {
        try {
            val db = writableDatabase // SQLiteOpenHelper owns this connection; do not close it.
            run {
                val cv = ContentValues().apply {
                    put(COL_ROW_ID, rowId)
                    put(COL_KEY_ID, keyId)
                    put(COL_TEXT, text)
                    put(COL_TIMESTAMP, timestamp)
                    put(COL_VERSION, version)
                }
                db.insert(TABLE_NAME, null, cv)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Insert DB error: ${t.message}")
        }
    }

    @Synchronized
    fun getMessages(rowId: Long, keyId: String?): List<MessageItem> {
        if (rowId > 0 && messagesCache.containsKey(rowId.toString())) {
            val cached = messagesCache[rowId.toString()]
            if (!cached.isNullOrEmpty()) return synchronized(cached) { ArrayList(cached) }
        }
        if (!keyId.isNullOrEmpty() && messagesCache.containsKey(keyId)) {
            val cached = messagesCache[keyId]
            if (!cached.isNullOrEmpty()) return synchronized(cached) { ArrayList(cached) }
        }

        val result = mutableListOf<MessageItem>()
        try {
            val db = readableDatabase // Only cursors should be closed by the caller.
            run {
                val selection = if (rowId > 0 && !keyId.isNullOrEmpty()) {
                    "$COL_ROW_ID=? OR $COL_KEY_ID=?"
                } else if (rowId > 0) {
                    "$COL_ROW_ID=?"
                } else if (!keyId.isNullOrEmpty()) {
                    "$COL_KEY_ID=?"
                } else {
                    return emptyList()
                }

                val args = if (rowId > 0 && !keyId.isNullOrEmpty()) {
                    arrayOf(rowId.toString(), keyId)
                } else if (rowId > 0) {
                    arrayOf(rowId.toString())
                } else {
                    arrayOf(keyId!!)
                }

                val cursor = db.query(
                    TABLE_NAME,
                    arrayOf(COL_ROW_ID, COL_KEY_ID, COL_TEXT, COL_TIMESTAMP, COL_VERSION),
                    selection,
                    args,
                    null,
                    null,
                    "$COL_VERSION ASC, $COL_ID ASC"
                )
                cursor?.use {
                    while (it.moveToNext()) {
                        result.add(
                            MessageItem(
                                rowId = it.getLong(0),
                                keyId = it.getString(1) ?: "",
                                textData = it.getString(2) ?: "",
                                timestamp = it.getLong(3),
                                versionNumber = it.getInt(4)
                            )
                        )
                    }
                }
            }
            if (result.isNotEmpty()) {
                if (rowId > 0) messagesCache[rowId.toString()] = result
                if (!keyId.isNullOrEmpty()) messagesCache[keyId] = result
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Query error: ${t.message}")
        }

        return result
    }

    private fun getOriginalMessageFromDb(rowId: Long, keyId: String): String {
        try {
            val dbFile = File(context.filesDir.parentFile, "databases/msgstore.db")
            if (!dbFile.exists()) return ""

            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // 1. Query message table
                try {
                    val selection = if (rowId > 0) "_id=?" else "key_id=?"
                    val args = if (rowId > 0) arrayOf(rowId.toString()) else arrayOf(keyId)
                    val cursor = db.query(
                        "message",
                        arrayOf("text_data"),
                        selection,
                        args,
                        null, null, null
                    )
                    cursor?.use {
                        if (it.moveToFirst()) {
                            val txt = it.getString(0)
                            if (!txt.isNullOrBlank()) return txt
                        }
                    }
                } catch (ignored: Throwable) {}

                // 2. Query message_ftsv2_content
                if (rowId > 0) {
                    try {
                        val cursor = db.query(
                            "message_ftsv2_content",
                            arrayOf("c0content"),
                            "docid=?",
                            arrayOf(rowId.toString()),
                            null, null, null
                        )
                        cursor?.use {
                            if (it.moveToFirst()) {
                                val content = it.getString(0)
                                if (!content.isNullOrBlank()) return content
                            }
                        }
                    } catch (ignored: Throwable) {}
                }
            }
        } catch (ignored: Throwable) {}
        return ""
    }
}
