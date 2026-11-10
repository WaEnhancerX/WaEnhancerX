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


public class HideSeenStore extends SQLiteOpenHelper {

    private static final String DATABASE_NAME = "HideSeenMessages.db";
    private static final int DATABASE_VERSION = 1;
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
                "UNIQUE(jid, message_id) ON CONFLICT IGNORE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hide_seen_jid_read ON " + TABLE_NAME + " (jid, read)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_hide_seen_jid_played ON " + TABLE_NAME + " (jid, played)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public void insertHideSeenMessage(@Nullable String jid, @Nullable String messageId, int type, boolean viewed) {
        if (jid == null || messageId == null) return;
        int isViewedInt = viewed ? 1 : 0;
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put("jid", jid);
            values.put("message_id", messageId);
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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
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
        } catch (Throwable ignored) {}
        return list;
    }
}
