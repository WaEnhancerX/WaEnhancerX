package com.waenhancer.xposed.core.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import de.robv.android.xposed.XposedBridge
import java.util.LinkedHashMap
import java.util.concurrent.Executors

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

    private data class CachedHistory(
        val items: List<MessageItem>, val characters: Long, val positiveRowId: Long
    )
    // Accessed only by synchronized store operations. Bound text and item count,
    // not just conversation count: one long history can otherwise retain MBs.
    private val messagesCache = LinkedHashMap<String, CachedHistory>(64, 0.75f, true)
    private var cachedCharacters = 0L
    private var cachedItems = 0
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WAEX-EditHistory").apply { isDaemon = true }
    }

    companion object {
        private const val TAG = "[WAEX:Store]"
        private const val DATABASE_NAME = "waex_history.db"
        private const val DATABASE_VERSION = 4
        private const val MAX_CACHED_HISTORIES = 500
        private const val MAX_CACHED_CHARACTERS = 1_048_576L
        private const val MAX_CACHED_ITEMS = 4_096

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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($TABLE_NAME)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) if (nameIndex >= 0) columns.add(cursor.getString(nameIndex))
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
        recordTransition(rowId, keyId, null, newMessage, timestamp)
    }

    fun recordTransitionAsync(
        rowId: Long,
        keyId: String?,
        previousMessage: String?,
        newMessage: String,
        timestamp: Long
    ) {
        if (newMessage.isBlank() || (rowId <= 0 && keyId.isNullOrBlank())) return
        writer.execute { recordTransition(rowId, keyId, previousMessage, newMessage, timestamp) }
    }

    @Synchronized
    fun recordTransition(
        rowId: Long,
        keyId: String?,
        previousMessage: String?,
        newMessage: String,
        timestamp: Long
    ) {
        if (newMessage.isBlank() || (rowId <= 0 && keyId.isNullOrBlank())) return
        val ts = if (timestamp > 0) timestamp else System.currentTimeMillis()
        val validKeyId = keyId.orEmpty()
        var db: SQLiteDatabase? = null
        var transactionStarted = false
        var committed: MutableList<MessageItem>? = null
        try {
            db = writableDatabase
            // Never hold our SQLite write transaction while opening/reading the
            // host database: another host callback can acquire them oppositely.
            val providedOriginal = previousMessage?.trim().orEmpty()
            val original = if (providedOriginal.isNotEmpty()) providedOriginal else {
                val before = queryMessages(db, rowId, validKeyId) ?: return
                if (before.isEmpty()) getOriginalMessageFromDb(rowId, validKeyId).trim() else ""
            }
            db.beginTransaction()
            transactionStarted = true
            // Read the authoritative rows within the write transaction. A failed
            // read is not an empty history, and a previous cache cannot prove
            // what was persisted by another writer.
            val existing = queryMessages(db, rowId, validKeyId)?.toMutableList() ?: return
            if (existing.isEmpty()) {
                if (original.isNotBlank() && original != newMessage.trim()) {
                    insertMessageDirect(db, rowId, validKeyId, original, ts - 1000, 1)
                    existing.add(MessageItem(rowId, validKeyId, original, ts - 1000, 1))
                }
            }
            if (newMessage.trim() != existing.lastOrNull()?.textData.orEmpty()) {
                val nextVersion = (existing.lastOrNull()?.versionNumber ?: 0) + 1
                insertMessageDirect(db, rowId, validKeyId, newMessage.trim(), ts, nextVersion)
                existing.add(MessageItem(rowId, validKeyId, newMessage.trim(), ts, nextVersion))
            }
            db.setTransactionSuccessful()
            committed = existing
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Record DB error: ${t.javaClass.simpleName}")
        } finally {
            if (transactionStarted) {
                try {
                    db!!.endTransaction()
                } catch (t: Throwable) {
                    // Commit outcome is unknown: the next reader must query the
                    // database, never display the candidate as confirmed history.
                    committed = null
                    XposedBridge.log("$TAG Commit DB error: ${t.javaClass.simpleName}")
                }
            }
            invalidateCache(rowId, validKeyId)
        }
        committed?.let { history -> cacheKey(rowId, validKeyId)?.let { cacheHistory(it, history) } }
    }

    private val originalKeyCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * An edited message's row carries the edit's own key; WhatsApp keeps the key of the
     * original in message_edit_info. History is stored under the original key, so map to it.
     * Returns [keyId] unchanged when the message was never edited or cannot be resolved.
     */
    fun resolveOriginalKey(keyId: String): String {
        originalKeyCache[keyId]?.let { return it.ifEmpty { keyId } }
        var resolved = ""
        var definitive = false
        try {
            val dbFile = MessageStore.findMsgstoreDbFile(context)
            if (dbFile != null) {
                SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                    db.rawQuery(
                        "SELECT e.original_key_id FROM message m JOIN message_edit_info e " +
                            "ON e.message_row_id = m._id WHERE m.key_id = ? LIMIT 1",
                        arrayOf(keyId)
                    ).use { c ->
                        if (c.moveToFirst()) resolved = c.getString(0).orEmpty()
                    }
                    definitive = true
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Resolve original key error: ${t.javaClass.simpleName}")
        }
        // An edit gives the row a new key, so a definitive miss for this key stays a miss.
        // Never cache a miss caused by a failed read.
        if (definitive) originalKeyCache[keyId] = resolved
        return resolved.ifEmpty { keyId }
    }

    fun getCurrentMessageFromWhatsApp(rowId: Long, keyId: String?): String {
        return getOriginalMessageFromDb(rowId, keyId.orEmpty())
    }

    private fun insertMessageDirect(
        db: SQLiteDatabase, rowId: Long, keyId: String, text: String, timestamp: Long, version: Int
    ) {
        val values = ContentValues().apply {
            put(COL_ROW_ID, rowId)
            put(COL_KEY_ID, keyId)
            put(COL_TEXT, text)
            put(COL_TIMESTAMP, timestamp)
            put(COL_VERSION, version)
        }
        if (db.insert(TABLE_NAME, null, values) < 0) {
            throw IllegalStateException("History insert was not persisted")
        }
    }

    @Synchronized
    fun getMessages(rowId: Long, keyId: String?): List<MessageItem> {
        val cacheKey = cacheKey(rowId, keyId) ?: return emptyList()
        messagesCache[cacheKey]?.let {
            // The key cache must not make a contradictory numeric request look
            // verified. Zero means unresolved; never infer it from the caller.
            if (rowId > 0 && it.positiveRowId > 0 && rowId != it.positiveRowId) return emptyList()
            return ArrayList(it.items)
        }
        val result = try {
            queryMessages(readableDatabase, rowId, keyId)
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Query DB error: ${t.javaClass.simpleName}")
            null
        } ?: return emptyList()
        // Do not retain empty reads: another host process may add its first edit.
        if (result.isNotEmpty()) cacheHistory(cacheKey, result)
        return result
    }

    private fun queryMessages(db: SQLiteDatabase, rowId: Long, keyId: String?): List<MessageItem>? {
        val selection: String
        val args: Array<String>
        when {
            !keyId.isNullOrEmpty() -> { selection = "$COL_KEY_ID=?"; args = arrayOf(keyId) }
            rowId > 0 -> { selection = "$COL_ROW_ID=?"; args = arrayOf(rowId.toString()) }
            else -> return emptyList()
        }
        return try {
            val result = mutableListOf<MessageItem>()
            var positiveRowId = rowId.coerceAtLeast(0L)
            val cursor = db.query(
                TABLE_NAME, arrayOf(COL_ROW_ID, COL_KEY_ID, COL_TEXT, COL_TIMESTAMP, COL_VERSION),
                selection, args, null, null, "$COL_VERSION ASC, $COL_ID ASC"
            ) ?: return null
            cursor.use {
                while (it.moveToNext()) {
                    val storedRowId = it.getLong(0)
                    if (storedRowId > 0) {
                        // The schema has no chat/account identity. Reject the
                        // numeric contradiction it can prove, without deleting
                        // legacy rows or treating ambiguity as an empty history.
                        if (positiveRowId > 0 && positiveRowId != storedRowId) return null
                        positiveRowId = storedRowId
                    }
                    result.add(MessageItem(storedRowId, it.getString(1).orEmpty(),
                        it.getString(2).orEmpty(), it.getLong(3), it.getInt(4)))
                }
            }
            result
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Query error: ${t.javaClass.simpleName}")
            null
        }
    }

    private fun invalidateCache(rowId: Long, keyId: String?) {
        cacheKey(rowId, keyId)?.let { removeCacheEntry(it) }
        // A caller may have cached the same persisted row through its numeric ID.
        if (rowId > 0) removeCacheEntry("row:$rowId")
    }

    private fun cacheHistory(key: String, history: List<MessageItem>) {
        removeCacheEntry(key)
        if (history.isEmpty() || history.size > MAX_CACHED_ITEMS) return
        var characters = key.length.toLong()
        var positiveRowId = 0L
        for (item in history) {
            if (item.rowId > 0) {
                if (positiveRowId > 0 && positiveRowId != item.rowId) return
                positiveRowId = item.rowId
            }
            characters += item.keyId.length.toLong() + item.textData.length.toLong()
            if (characters > MAX_CACHED_CHARACTERS) return
        }
        val entries = messagesCache.entries.iterator()
        while (entries.hasNext() && (messagesCache.size >= MAX_CACHED_HISTORIES ||
                cachedCharacters + characters > MAX_CACHED_CHARACTERS ||
                cachedItems + history.size > MAX_CACHED_ITEMS)) {
            val oldest = entries.next().value
            cachedCharacters -= oldest.characters
            cachedItems -= oldest.items.size
            entries.remove()
        }
        messagesCache[key] = CachedHistory(ArrayList(history), characters, positiveRowId)
        cachedCharacters += characters
        cachedItems += history.size
    }

    private fun removeCacheEntry(key: String) {
        messagesCache.remove(key)?.let { removed ->
            cachedCharacters -= removed.characters
            cachedItems -= removed.items.size
        }
    }

    private fun cacheKey(rowId: Long, keyId: String?): String? = when {
        !keyId.isNullOrEmpty() -> "key:$keyId"
        rowId > 0 -> "row:$rowId"
        else -> null
    }

    private fun getOriginalMessageFromDb(rowId: Long, keyId: String): String {
        try {
            val dbFile = MessageStore.findMsgstoreDbFile(context) ?: return ""

            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // The message key is stable across edit processing. Prefer it over reflected
                // numeric fields, which can be timestamps or other obfuscated values.
                var verifiedRowId = 0L
                if (keyId.isNotBlank()) {
                    try {
                        db.query(
                            "message", arrayOf("_id", "text_data"), "key_id=?", arrayOf(keyId),
                            null, null, null
                        )?.use {
                            // key_id alone is not guaranteed globally unique across chats.
                            // Do not choose an arbitrary first row when identity is ambiguous.
                            if (it.count != 1 || !it.moveToFirst()) return ""
                            verifiedRowId = it.getLong(0)
                            val text = it.getString(1)
                            if (!text.isNullOrBlank()) return text
                        }
                    } catch (ignored: Throwable) {
                        // Some schemas omit text_data. Resolve the key separately before FTS.
                        try {
                            db.query(
                                "message", arrayOf("_id"), "key_id=?", arrayOf(keyId),
                                null, null, null
                            )?.use {
                                if (it.count != 1 || !it.moveToFirst()) return ""
                                verifiedRowId = it.getLong(0)
                            }
                        } catch (ignored: Throwable) { return "" }
                    }
                    // A missing key must never fall back to an unrelated reflected numeric ID.
                    if (verifiedRowId <= 0) return ""
                } else if (rowId > 0) {
                    verifiedRowId = rowId
                    try {
                        db.query(
                            "message", arrayOf("text_data"), "_id=?", arrayOf(rowId.toString()),
                            null, null, null
                        )?.use {
                            if (it.moveToFirst()) {
                                val text = it.getString(0)
                                if (!text.isNullOrBlank()) return text
                            }
                        }
                    } catch (ignored: Throwable) {}
                }

                // FTS fallback for builds where message.text_data is unavailable.
                if (verifiedRowId > 0) {
                    try {
                        val cursor = db.query(
                            "message_ftsv2_content",
                            arrayOf("c0content"),
                            "docid=?",
                            arrayOf(verifiedRowId.toString()),
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
