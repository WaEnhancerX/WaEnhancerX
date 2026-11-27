package com.waenhancer.xposed.core.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.waenhancer.xposed.utils.HookErrors;


public class HideSeenStore extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "HideSeenMessages.db";
    private static final int DATABASE_VERSION = 2;
    private static final String TABLE_NAME = "hide_seen_messages";

    public static final int TYPE_READ = 0;
    public static final int TYPE_PLAYED = 1;

    private static volatile HideSeenStore instance;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static class SeenMessageItem {
        public final String jid;
        public final String messageId;
        public final boolean viewed;

        public SeenMessageItem(String jid, String messageId, boolean viewed) {
            this.jid = jid;
            this.messageId = messageId;
            this.viewed = viewed;
        }
    }

    private HideSeenStore(Context context) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
    }

    public static HideSeenStore getInstance(Context context) {
        if (instance == null) {
            synchronized (HideSeenStore.class) {
                if (instance == null) {
                    instance = new HideSeenStore(context);
                }
            }
        }
        return instance;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "jid TEXT NOT NULL, " +
                "message_id TEXT NOT NULL, " +
                "read INTEGER DEFAULT 0, " +
                "played INTEGER DEFAULT 0, " +
                "participant TEXT, " +
                "created_at INTEGER NOT NULL DEFAULT 0, " +
                "UNIQUE(jid, message_id) ON CONFLICT IGNORE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hide_seen_jid_read ON " + TABLE_NAME + " (jid, read)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hide_seen_jid_played ON " + TABLE_NAME + " (jid, played)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v2: unsent read-receipt ledger needs the group sender and an age for pruning.
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN participant TEXT");
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0");
            // Existing rows start their retention period now instead of being pruned at once.
            db.execSQL("UPDATE " + TABLE_NAME + " SET created_at = " + System.currentTimeMillis());
        }
    }

    // ─── Unsent read-receipt ledger ──────────────────────────────────────
    // A row with read = 0 is a message whose read receipt WhatsApp has not sent (hidden by
    // Ghost Mode, Hide blue ticks or Hide delivered). read = 1 once a receipt went out, either
    // from WhatsApp itself or from a manual / reply-triggered send.

    /** One message whose read receipt is still owed. */
    public static final class PendingRead {
        public final String chat;
        @Nullable public final String participant;
        public final String messageId;
        public final long createdAt;

        PendingRead(String chat, @Nullable String participant, String messageId, long createdAt) {
            this.chat = chat;
            this.participant = participant;
            this.messageId = messageId;
            this.createdAt = createdAt;
        }
    }

    /**
     * Records messages whose read receipt was withheld. Never downgrades a message that was
     * already sent, and fills in the group sender when a later observation knows it.
     */
    public void trackUnsentReadsAsync(@Nullable String chat, @Nullable String participant,
                                      @Nullable Iterable<String> messageIds) {
        if (chat == null || messageIds == null) return;
        final long now = System.currentTimeMillis();
        executor.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                db.beginTransaction();
                try {
                    for (String id : messageIds) {
                        if (id == null || id.isEmpty()) continue;
                        ContentValues values = new ContentValues();
                        values.put("jid", chat);
                        values.put("message_id", id);
                        values.put("read", 0);
                        values.put("created_at", now);
                        if (participant != null) values.put("participant", participant);
                        db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
                        if (participant != null) {
                            ContentValues sender = new ContentValues();
                            sender.put("participant", participant);
                            db.update(TABLE_NAME, sender, "jid=? AND message_id=? AND participant IS NULL",
                                    new String[]{chat, id});
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } catch (Throwable ignored) { HookErrors.swallowed("HideSeenStore", ignored); }
        });
    }

    /** Marks read receipts as sent (true) or owed again (false). */
    public void setReadsSentAsync(@Nullable String chat, @Nullable Iterable<String> messageIds, boolean sent) {
        if (chat == null || messageIds == null) return;
        executor.execute(() -> {
            try {
                SQLiteDatabase db = getWritableDatabase();
                ContentValues values = new ContentValues();
                values.put("read", sent ? 1 : 0);
                for (String id : messageIds) {
                    if (id == null || id.isEmpty()) continue;
                    db.update(TABLE_NAME, values, "jid=? AND message_id=?", new String[]{chat, id});
                }
            } catch (Throwable ignored) { HookErrors.swallowed("HideSeenStore", ignored); }
        });
    }

    /** Owed read receipts for these chat identities, oldest first. Blocks on pending writes. */
    public List<PendingRead> pendingReads(@Nullable Iterable<String> chats, int limit) {
        List<PendingRead> result = new ArrayList<>();
        if (chats == null || limit <= 0) return result;
        awaitPendingWrites();
        try {
            SQLiteDatabase db = getReadableDatabase();
            for (String chat : chats) {
                if (chat == null || result.size() >= limit) continue;
                try (Cursor cursor = db.query(TABLE_NAME,
                        new String[]{"message_id", "participant", "created_at"},
                        "jid=? AND read=0", new String[]{chat}, null, null,
                        "created_at ASC, _id ASC", String.valueOf(limit - result.size()))) {
                    while (cursor != null && cursor.moveToNext()) {
                        result.add(new PendingRead(chat, cursor.isNull(1) ? null : cursor.getString(1),
                                cursor.getString(0), cursor.getLong(2)));
                    }
                }
            }
        } catch (Throwable ignored) { HookErrors.swallowed("HideSeenStore", ignored); }
        return result;
    }

    /** Drops sent rows and owed rows older than their retention period. */
    public void pruneReadsAsync(long owedMaxAgeMs, long sentMaxAgeMs) {
        final long now = System.currentTimeMillis();
        executor.execute(() -> {
            try {
                getWritableDatabase().delete(TABLE_NAME,
                        "(read=0 AND played=0 AND created_at < ?) OR (read=1 AND created_at < ?)",
                        new String[]{String.valueOf(now - owedMaxAgeMs), String.valueOf(now - sentMaxAgeMs)});
            } catch (Throwable ignored) { HookErrors.swallowed("HideSeenStore", ignored); }
        });
    }

    /** Waits for queued writes so a send never misses a message recorded just before it. */
    private void awaitPendingWrites() {
        try {
            executor.submit(() -> { }).get(2, TimeUnit.SECONDS);
        } catch (Throwable ignored) { HookErrors.swallowed("HideSeenStore", ignored); }
    }

    public void insertHideSeenMessage(@Nullable String jid, @Nullable String messageId, int type, boolean viewed) {
        if (jid == null || messageId == null) return;
        int isViewedInt = viewed ? 1 : 0;
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("jid", jid);
            values.put("message_id", messageId);
            values.put("created_at", System.currentTimeMillis());
            if (type == TYPE_PLAYED) {
                values.put("played", isViewedInt);
            } else {
                values.put("read", isViewedInt);
            }
            db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues updateValues = new ContentValues();
            if (type == TYPE_PLAYED) {
                updateValues.put("played", isViewedInt);
            } else {
                updateValues.put("read", isViewedInt);
            }
            db.update(TABLE_NAME, updateValues, "jid=? AND message_id=?", new String[]{jid, messageId});
        } catch (Throwable ignored) { HookErrors.swallowed("HideSeenStore", ignored); }
    }

    public void insertHideSeenMessageAsync(String jid, String messageId, int type, boolean viewed) {
        executor.execute(() -> insertHideSeenMessage(jid, messageId, type, viewed));
    }

    public void insertHideSeenMessagesAsync(String jid, Iterable<String> messageIds, int type, boolean viewed) {
        executor.execute(() -> {
            for (String id : messageIds) {
                insertHideSeenMessage(jid, id, type, viewed);
            }
        });
    }

    public boolean updateViewedMessage(@Nullable String jid, @Nullable String messageId, int type, boolean viewed) {
        if (jid == null || messageId == null) return false;
        try {
            SQLiteDatabase db = getWritableDatabase();
            int isViewedInt = viewed ? 1 : 0;
            ContentValues updateValues = new ContentValues();
            if (type == TYPE_PLAYED) {
                updateValues.put("played", isViewedInt);
            } else {
                updateValues.put("read", isViewedInt);
            }
            int rows = db.update(TABLE_NAME, updateValues, "jid=? AND message_id=?", new String[]{jid, messageId});
            return rows > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public void updateViewedMessageAsync(String jid, String messageId, int type, boolean viewed) {
        executor.execute(() -> updateViewedMessage(jid, messageId, type, viewed));
    }

    public boolean isAlreadyViewed(@Nullable String jid, @Nullable String messageId, int type) {
        if (jid == null || messageId == null) return false;
        String column = type == TYPE_PLAYED ? "played" : "read";
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_NAME,
                new String[]{column},
                "jid=? AND message_id=?",
                new String[]{jid, messageId},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getInt(0) == 1;
            }
        } catch (Throwable ignored) { HookErrors.swallowed("HideSeenStore", ignored); }
        return false;
    }

    public List<SeenMessageItem> getHideSeenMessages(@Nullable String jid, int type, boolean viewed) {
        List<SeenMessageItem> list = new ArrayList<>();
        if (jid == null) return list;
        int isViewedInt = viewed ? 1 : 0;
        String column = type == TYPE_PLAYED ? "played" : "read";
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_NAME,
                new String[]{"message_id", column},
                "jid=? AND " + column + "=?",
                new String[]{jid, String.valueOf(isViewedInt)},
                null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String msgId = cursor.getString(0);
                    list.add(new SeenMessageItem(jid, msgId, viewed));
                }
            }
        } catch (Throwable ignored) { HookErrors.swallowed("HideSeenStore", ignored); }
        return list;
    }
}
