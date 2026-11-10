package com.waenhancer.xposed.core.db

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * MessageStore helper for querying WhatsApp's internal msgstore.db database.
 */
class MessageStore private constructor(private val context: Context) {

    private var sqLiteDatabase: SQLiteDatabase? = null

    init {
        openDatabase()
    }

    private fun openDatabase() {
        val dbFile = findMsgstoreDbFile(context) ?: return
        try {
            if (sqLiteDatabase?.isOpen == true) {
                try { sqLiteDatabase?.close() } catch (_: Throwable) {}
            }
            sqLiteDatabase = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                null,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            )
        } catch (e: Exception) {
            try {
                sqLiteDatabase = SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
                )
            } catch (ex: Exception) {
                XposedBridge.log("[WAEX][MessageStore] Failed to open msgstore.db: ${ex.javaClass.simpleName}")
            }
        }
    }

    val database: SQLiteDatabase?
        get() {
            val dbFile = findMsgstoreDbFile(context)
            if (sqLiteDatabase == null || !sqLiteDatabase!!.isOpen ||
                (dbFile != null && sqLiteDatabase!!.path != dbFile.absolutePath)) {
                openDatabase()
            }
            return sqLiteDatabase
        }

    fun getIdFromKey(messageKey: String): Long {
        val db = database ?: return -1L
        val columns = arrayOf("_id")
        val selection = "key_id=?"
        val selectionArgs = arrayOf(messageKey)
        try {
            db.query("message", columns, selection, selectionArgs, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getLong(0)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[WAEX][MessageStore] getIdFromKey error: ${e.javaClass.simpleName}")
        }
        return -1L
    }

    fun getMediaFromId(id: Long): String? {
        val db = database ?: return null
        val columns = arrayOf("file_path")
        val selection = "message_row_id=?"
        val selectionArgs = arrayOf(id.toString())
        try {
            db.query("message_media", columns, selection, selectionArgs, null, null, null).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    return cursor.getString(0)
                }
            }
        } catch (e: Exception) {
            XposedBridge.log("[WAEX][MessageStore] getMediaFromId error: ${e.javaClass.simpleName}")
        }
        return null
    }

    companion object {
        @Volatile
        private var instance: MessageStore? = null

        @JvmStatic
        fun getInstance(context: Context): MessageStore {
            return instance ?: synchronized(this) {
                instance ?: MessageStore(context.applicationContext ?: context).also { instance = it }
            }
        }

        @JvmStatic
        fun findAccountDataDir(context: Context): File {
            try {
                val filesDir = context.filesDir
                val rootData = filesDir?.parentFile
                if (rootData != null) {
                    val activeAccountFile = File(rootData, "app_account_switching/active_account")
                    if (activeAccountFile.exists() && activeAccountFile.canRead()) {
                        val bytes = Files.readAllBytes(activeAccountFile.toPath())
                        val accountId = String(bytes, StandardCharsets.UTF_8).trim()
                        if (accountId.isNotEmpty()) {
                            val accountDir = File(rootData, "accounts/$accountId")
                            if (accountDir.exists() && accountDir.isDirectory) {
                                return accountDir
                            }
                        }
                    }
                    return rootData
                }
            } catch (t: Throwable) {
                XposedBridge.log("[WAEX][MessageStore] findAccountDataDir error: ${t.javaClass.simpleName}")
            }
            return context.filesDir?.parentFile ?: File("/data/data/com.whatsapp")
        }

        @JvmStatic
        fun findMsgstoreDbFile(context: Context): File? {
            val accountDir = findAccountDataDir(context)
            val candidates = listOf(
                File(accountDir, "databases/msgstore.db"),
                File(context.filesDir?.parentFile, "databases/msgstore.db"),
                context.getDatabasePath("msgstore.db"),
                File("/data/data/${context.packageName}/databases/msgstore.db"),
                File(context.filesDir, "msgstore.db")
            )
            return candidates.firstOrNull { it.exists() && it.canRead() }
        }
    }
}
