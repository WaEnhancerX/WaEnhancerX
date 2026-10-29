package com.waenhancer.calls

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import org.json.JSONObject

data class CallRecordingRecord(
    val callId: String,
    val contactJid: String?,
    val contactName: String,
    val direction: String,
    val targetPackage: String,
    val startedAt: Long,
    val endedAt: Long?,
    val recordingPath: String,
    val status: String
)

/** Durable one-row-per-call index; audio files are never the source of call identity. */
class CallRecordingStore private constructor(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, VERSION
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE call_recordings (
                call_id TEXT PRIMARY KEY NOT NULL,
                contact_jid TEXT,
                contact_name TEXT NOT NULL,
                direction TEXT NOT NULL CHECK(direction IN ('incoming','outgoing','unknown')),
                target_package TEXT NOT NULL,
                started_at INTEGER NOT NULL,
                ended_at INTEGER,
                recording_path TEXT NOT NULL UNIQUE,
                status TEXT NOT NULL CHECK(status IN ('recording','completed','failed'))
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX call_recordings_started_at ON call_recordings(started_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun begin(record: CallRecordingRecord) {
        writableDatabase.insertWithOnConflict(
            "call_recordings", null, record.values(), SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun finish(callId: String, endedAt: Long, successful: Boolean) {
        val values = ContentValues().apply {
            put("ended_at", endedAt)
            put("status", if (successful) "completed" else "failed")
        }
        writableDatabase.update("call_recordings", values, "call_id = ?", arrayOf(callId))
    }

    fun deleteByPath(path: String) {
        writableDatabase.delete("call_recordings", "recording_path = ?", arrayOf(path))
    }

    fun all(): List<CallRecordingRecord> {
        val result = mutableListOf<CallRecordingRecord>()
        readableDatabase.query(
            "call_recordings", null, null, null, null, null, "started_at DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += CallRecordingRecord(
                    callId = cursor.getString(cursor.getColumnIndexOrThrow("call_id")),
                    contactJid = cursor.getString(cursor.getColumnIndexOrThrow("contact_jid")),
                    contactName = cursor.getString(cursor.getColumnIndexOrThrow("contact_name")),
                    direction = cursor.getString(cursor.getColumnIndexOrThrow("direction")),
                    targetPackage = cursor.getString(cursor.getColumnIndexOrThrow("target_package")),
                    startedAt = cursor.getLong(cursor.getColumnIndexOrThrow("started_at")),
                    endedAt = cursor.getColumnIndexOrThrow("ended_at").let { if (cursor.isNull(it)) null else cursor.getLong(it) },
                    recordingPath = cursor.getString(cursor.getColumnIndexOrThrow("recording_path")),
                    status = cursor.getString(cursor.getColumnIndexOrThrow("status"))
                )
            }
        }
        return result
    }

    /** Imports metadata delivered alongside an audio file (backup/restore and test seeding). */
    fun importSidecars(directory: File) {
        directory.listFiles { file -> file.isFile && file.name.endsWith(".call.json") }
            ?.forEach { sidecar ->
                runCatching {
                    val json = JSONObject(sidecar.readText())
                    val audio = File(directory, json.getString("audio_file"))
                    if (!audio.isFile) return@runCatching
                    begin(CallRecordingRecord(
                        callId = json.getString("call_id"),
                        contactJid = json.optString("contact_jid").takeIf { it.isNotBlank() },
                        contactName = json.optString("contact_name", "Unknown contact"),
                        direction = json.optString("direction", "unknown"),
                        targetPackage = json.optString("target_package", "unknown"),
                        startedAt = json.optLong("started_at", audio.lastModified()),
                        endedAt = json.optLong("ended_at", audio.lastModified()),
                        recordingPath = audio.absolutePath,
                        status = "completed"
                    ))
                }
            }
    }

    private fun CallRecordingRecord.values() = ContentValues().apply {
        put("call_id", callId)
        put("contact_jid", contactJid)
        put("contact_name", contactName)
        put("direction", direction)
        put("target_package", targetPackage)
        put("started_at", startedAt)
        if (endedAt == null) putNull("ended_at") else put("ended_at", endedAt)
        put("recording_path", recordingPath)
        put("status", status)
    }

    companion object {
        private const val DATABASE_NAME = "call_recordings.db"
        private const val VERSION = 1
        @Volatile private var instance: CallRecordingStore? = null

        @JvmStatic fun get(context: Context): CallRecordingStore = instance ?: synchronized(this) {
            instance ?: CallRecordingStore(context).also { instance = it }
        }
    }
}
