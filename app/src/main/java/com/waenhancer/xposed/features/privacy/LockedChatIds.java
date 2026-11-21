package com.waenhancer.xposed.features.privacy;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.waenhancer.xposed.utils.HookErrors;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The identities of WhatsApp's locked chats, read from WhatsApp's own database.
 *
 * Locked state is persisted in {@code chat.chat_lock}. That column, the {@code jid} table and the
 * {@code jid_map} LID-to-phone table are schema, not code: they survive every obfuscation pass
 * and WhatsApp's own SQL uses them. The mapping adds the other identity of each locked person,
 * so a phone-number row hides together with its {@code @lid} twin.
 *
 * Results are cached for a short time so a burst of picker callbacks costs one query, and the
 * same {@link Set} instance is returned until the locked chats really change.
 */
final class LockedChatIds {
    static final String SQL_LOCKED = "SELECT j.raw_string FROM chat c "
            + "JOIN jid j ON j._id = c.jid_row_id WHERE c.chat_lock > 0";
    static final String SQL_LID_TO_PHONE = "SELECT p.raw_string FROM chat c "
            + "JOIN jid_map m ON m.lid_row_id = c.jid_row_id "
            + "JOIN jid p ON p._id = m.jid_row_id WHERE c.chat_lock > 0";
    static final String SQL_PHONE_TO_LID = "SELECT l.raw_string FROM chat c "
            + "JOIN jid_map m ON m.jid_row_id = c.jid_row_id "
            + "JOIN jid l ON l._id = m.lid_row_id WHERE c.chat_lock > 0";

    private static final long FRESH_MS = 1_500L;
    private static final long RETRY_AFTER_FAILURE_MS = 60_000L;

    /** Runs one query that selects a single text column. */
    interface RowReader {
        List<String> readStrings(String sql) throws Exception;
    }

    private final Supplier<RowReader> reader;
    private final LongSupplier clock;
    private Set<String> cached;
    private long loadedAt = Long.MIN_VALUE;
    private boolean failed;

    LockedChatIds(Supplier<RowReader> reader, LongSupplier clock) {
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * The current locked identities (lower case), possibly empty, or {@code null} when the
     * database cannot be read (the caller then uses its fallback). After a failure the database
     * is not asked again for a minute.
     */
    synchronized Set<String> current() {
        long now = clock.getAsLong();
        long age = now - loadedAt;
        if (loadedAt != Long.MIN_VALUE && age >= 0 && age < (failed ? RETRY_AFTER_FAILURE_MS : FRESH_MS)) {
            return cached;
        }
        loadedAt = now;
        Set<String> loaded = load();
        failed = loaded == null;
        // Keep the previous instance while nothing changed, so callers can compare by identity.
        if (loaded != null && loaded.equals(cached)) return cached;
        cached = loaded;
        return cached;
    }

    private Set<String> load() {
        RowReader source = reader.get();
        if (source == null) return null;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        try {
            addAll(ids, source.readStrings(SQL_LOCKED));
        } catch (Exception schemaOrIo) {
            HookErrors.swallowed("LockedChatIds", schemaOrIo);
            return null;
        }
        if (ids.isEmpty()) return Collections.emptySet();
        // The mapping table is optional: its absence must not lose the primary result.
        for (String sql : new String[]{SQL_LID_TO_PHONE, SQL_PHONE_TO_LID}) {
            try {
                addAll(ids, source.readStrings(sql));
            } catch (Exception noMapping) {
                HookErrors.swallowed("LockedChatIds", noMapping);
            }
        }
        return Collections.unmodifiableSet(ids);
    }

    private static void addAll(Set<String> into, List<String> rows) {
        for (String row : rows) {
            if (row == null) continue;
            String clean = row.trim().toLowerCase(Locale.ROOT);
            if (!clean.isEmpty()) into.add(clean);
        }
    }

    /** A reader over the host's database, or {@code null} while it cannot be opened. */
    static RowReader overDatabase(Supplier<SQLiteDatabase> database) {
        SQLiteDatabase db = database.get();
        if (db == null || !db.isOpen()) return null;
        return sql -> {
            List<String> rows = new ArrayList<>();
            try (Cursor cursor = db.rawQuery(sql, null)) {
                while (cursor.moveToNext()) rows.add(cursor.getString(0));
            }
            return rows;
        };
    }
}
