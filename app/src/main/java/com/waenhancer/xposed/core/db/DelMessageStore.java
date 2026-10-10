package com.waenhancer.xposed.core.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent SQLite Database for anti-revoked message storage and deletion timestamps.
 * Ensures deleted indicators remain visible across chat reopenings, searches, and app restarts.
 */
public final class DelMessageStore extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "waex_delmessages.db";
    private static final int DATABASE_VERSION = 1;

    public static final String TABLE_DEL_MESSAGES = "delmessages";
    public static final String COL_ID = "_id";
    public static final String COL_JID = "jid";
    public static final String COL_MSGID = "msgid";
    public static final String COL_TIMESTAMP = "timestamp";

    private static volatile DelMessageStore sInstance;

    private final Map<String, Long> timestampCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > 2000;
                }
            }
    );

    public static class PreservedRecord {
        public final String jid;
        public final String msgId;
        public final long timestamp;

        public PreservedRecord(String jid, String msgId, long timestamp) {
            this.jid = jid != null ? jid : "";
            this.msgId = msgId != null ? msgId : "";
            this.timestamp = timestamp;
        }
    }

    private DelMessageStore(@NonNull Context context) {
        super(context.getApplicationContext() != null ? context.getApplicationContext() : context,
                DATABASE_NAME, null, DATABASE_VERSION);
    }

    public static DelMessageStore getInstance(@NonNull Context context) {
        if (sInstance == null) {
            synchronized (DelMessageStore.class) {
                if (sInstance == null) {
                    sInstance = new DelMessageStore(context);
                }
            }
        }
        return sInstance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_DEL_MESSAGES + " (" +
                COL_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                COL_JID + " TEXT, " +
                COL_MSGID + " TEXT UNIQUE, " +
                COL_TIMESTAMP + " INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Retain anti-revoke history when the schema version changes.
        onCreate(db);
    }

    public void insertMessage(@Nullable String jid, @NonNull String msgId, long timestamp) {
        if (msgId == null || msgId.isEmpty()) return;
        timestampCache.put(msgId, timestamp);
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues cv = new ContentValues();
            cv.put(COL_JID, jid != null ? jid : "");
            cv.put(COL_MSGID, msgId);
            cv.put(COL_TIMESTAMP, timestamp);
            db.insertWithOnConflict(TABLE_DEL_MESSAGES, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (Throwable ignored) {}
    }

    public long getTimestampByMessageId(@Nullable String msgId) {
        if (msgId == null || msgId.isEmpty()) return 0L;
        Long cached = timestampCache.get(msgId);
        if (cached != null) {
            return cached > 0 ? cached : 0L;
        }

        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor cursor = db.query(
                    TABLE_DEL_MESSAGES,
                    new String[]{COL_TIMESTAMP},
                    COL_MSGID + "=?",
                    new String[]{msgId},
                    null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    long ts = cursor.getLong(0);
                    timestampCache.put(msgId, ts);
                    return ts;
                }
            }
        } catch (Throwable ignored) {}

        timestampCache.put(msgId, -1L);
        return 0L;
    }

    public List<PreservedRecord> getAllPreservedRecords() {
        List<PreservedRecord> list = new ArrayList<>();
        try {
            SQLiteDatabase db = getReadableDatabase();
            try (Cursor cursor = db.query(
                    TABLE_DEL_MESSAGES,
                    new String[]{COL_JID, COL_MSGID, COL_TIMESTAMP},
                    null, null, null, null,
                    COL_TIMESTAMP + " DESC")) {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        list.add(new PreservedRecord(
                                cursor.getString(0),
                                cursor.getString(1),
                                cursor.getLong(2)
                        ));
                    } while (cursor.moveToNext());
                }
            }
        } catch (Throwable ignored) {}
        return list;
    }

    public boolean isRevoked(@Nullable String msgId) {
        return getTimestampByMessageId(msgId) > 0;
    }
}
