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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    /**
     * Every revoked message id, loaded once in the background. Chat rows query this on the UI
     * thread for every bind, so after the load a lookup is a single hash probe and never touches
     * SQLite; the table only holds ids and timestamps, so it is small.
     */
    private final ConcurrentHashMap<String, Long> allRevoked = new ConcurrentHashMap<>();
    private final AtomicBoolean loadStarted = new AtomicBoolean(false);
    private volatile boolean loaded = false;
    private volatile boolean loadFailed = false;

    /** True once the full set is in memory, so "not found" is authoritative. */
    public boolean isLoaded() { return loaded; }

    /** Cheap hint for hot paths: true unless the loaded set is known to be empty. */
    public boolean mayHaveRevoked() { return !loaded || !allRevoked.isEmpty(); }

    /** Loads all revoked ids off the UI thread once; {@code onLoaded} runs on that thread. */
    public void preloadAsync(@Nullable Runnable onLoaded) {
        if (!loadStarted.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                SQLiteDatabase db = getReadableDatabase();
                try (Cursor cursor = db.query(TABLE_DEL_MESSAGES,
                        new String[]{COL_MSGID, COL_TIMESTAMP}, null, null, null, null, null)) {
                    while (cursor != null && cursor.moveToNext()) {
                        String id = cursor.getString(0);
                        long ts = cursor.getLong(1);
                        if (id != null && ts > 0) allRevoked.putIfAbsent(id, ts);
                    }
                }
                loaded = true;
            } catch (Throwable ignored) {
                loadFailed = true; // fall back to per-id queries below
            }
            if (onLoaded != null) {
                try { onLoaded.run(); } catch (Throwable ignored) {}
            }
        }, "WAEX-DelStore-Preload");
        t.setPriority(Thread.MIN_PRIORITY);
        t.setDaemon(true);
        t.start();
    }

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
        // The current schema is additive; never erase anti-revoke history on upgrade.
        onCreate(db);
    }

    public void insertMessage(@Nullable String jid, @NonNull String msgId, long timestamp) {
        if (msgId == null || msgId.isEmpty()) return;
        timestampCache.put(msgId, timestamp);
        if (timestamp > 0) allRevoked.put(msgId, timestamp);
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
        Long known = allRevoked.get(msgId);
        if (known != null) return known;
        // Full set loaded: a miss is authoritative. Still loading: never block the UI thread on
        // SQLite; the loader's callback refreshes the visible rows when it finishes.
        if (loaded) return 0L;
        if (!loadFailed) return 0L;
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
        } catch (Throwable ignored) {
            // A transient read failure is not proof the message was never revoked; do not
            // cache it as a miss, or the indicator stays hidden until the entry is evicted.
            return 0L;
        }

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
