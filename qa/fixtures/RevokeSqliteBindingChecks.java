package com.waenhancer.xposed.features.privacy;

import java.util.*;
import java.util.regex.Pattern;

/** Verbatim production callbacks with explicit SQLite, Xposed, history and UI doubles. */
public final class RevokeSqliteBindingChecks {
    @interface Nullable {}
    @interface NonNull {}
    static final String TAG = "[fixture]";
    static final int MAX_RECENT_REVOKES = 4096;
    static final Map<String, Long> REVOKED_MESSAGES = new LinkedHashMap<>();
    static final Set<String> PRESERVED_OWN_ROW_IDS = new LinkedHashSet<>();
    static final List<String> toasts = new ArrayList<>();
    static final List<String> realtime = new ArrayList<>();
    static int checks;
    final Object context = new Object();
    boolean messages, statuses, own;

    private boolean isAntiRevokeEnabled() { return messages; }
    private boolean isAntiRevokeStatusEnabled() { return statuses; }
    private boolean isPreserveOwnRevokeEnabled() { return own; }
    private void showDeletedMessageToast(String sender) { toasts.add(sender); }
    private void dispatchRealtimeRevokeUI(String key, long timestamp) { realtime.add(key); }
    private String resolveSenderJid(SQLiteDatabase db, ContentValues values) { return "sender-fixture"; }
    private String resolveJidRowId(SQLiteDatabase db, long id) { return "sender-fixture"; }
    private String resolveChatJid(SQLiteDatabase db, long id) { return "chat-fixture"; }

    /* PRODUCTION_METHODS */

    static final class PreserveDeleteForMeHook {
        static boolean active;
        static boolean isDeleteForMeInProgress() { return active; }
    }
    static class XC_MethodHook {
        protected void beforeHookedMethod(MethodHookParam param) {}
        static class MethodHookParam {
            Object[] args;
            Object thisObject;
            boolean handled;
            Object result;
            void setResult(Object result) { this.result = result; handled = true; }
        }
    }
    static final class XposedBridge {
        static final Map<String, XC_MethodHook> hooks = new HashMap<>();
        static final List<String> logs = new ArrayList<>();
        static void hookAllMethods(Class<?> owner, String name, XC_MethodHook hook) {
            if (owner != SQLiteDatabase.class || hooks.put(name, hook) != null) {
                throw new AssertionError("unexpected hook registration");
            }
        }
        static void log(String message) { logs.add(message); }
    }
    static final class ContentValues extends LinkedHashMap<String, Object> {
        Integer getAsInteger(String key) {
            Object value = get(key);
            if (value == null) return null;
            return value instanceof Number ? ((Number) value).intValue() : Integer.valueOf(value.toString());
        }
        Long getAsLong(String key) {
            Object value = get(key);
            if (value == null) return null;
            return value instanceof Number ? ((Number) value).longValue() : Long.valueOf(value.toString());
        }
        String getAsString(String key) { Object value = get(key); return value == null ? null : value.toString(); }
    }
    static final class DelMessageStore {
        static final DelMessageStore instance = new DelMessageStore();
        static final Map<String, Long> rows = new LinkedHashMap<>();
        static final Map<String, String> senders = new LinkedHashMap<>();
        static boolean insertFailure;
        static DelMessageStore getInstance(Object context) { return instance; }
        long getTimestampByMessageId(String key) { return rows.getOrDefault(key, 0L); }
        void insertMessage(String sender, String key, long timestamp) {
            if (insertFailure) throw new IllegalStateException("fixture marker failure");
            rows.put(key, timestamp);
            senders.put(key, sender);
        }
    }
    static final class Cursor implements AutoCloseable {
        static final int FIELD_TYPE_NULL = 0;
        static final int FIELD_TYPE_INTEGER = 1;
        static final int FIELD_TYPE_FLOAT = 2;
        static final int FIELD_TYPE_STRING = 3;
        static final int FIELD_TYPE_BLOB = 4;
        final String[] columns;
        final Object[][] rows;
        int position = -1;
        int failAtRow = -1;
        boolean closeFailure, closed;
        Cursor(String[] columns, Object[][] rows) { this.columns = columns; this.rows = rows; }
        int getColumnCount() { return columns.length; }
        String getColumnName(int index) { return columns[index]; }
        int getCount() { return rows.length; }
        boolean moveToFirst() { position = -1; return moveToNext(); }
        boolean moveToNext() {
            int next = position + 1;
            if (next == failAtRow) throw new IllegalStateException("fixture cursor read failure");
            position = next;
            return position < rows.length;
        }
        boolean isNull(int column) { return value(column) == null; }
        Object value(int column) {
            if (closed || position < 0 || position >= rows.length) throw new IllegalStateException("invalid fixture cursor state");
            return rows[position][column];
        }
        int getType(int column) {
            Object value = value(column);
            if (value == null) return FIELD_TYPE_NULL;
            if (value instanceof String) return FIELD_TYPE_STRING;
            if (value instanceof byte[]) return FIELD_TYPE_BLOB;
            if (value instanceof Float || value instanceof Double) return FIELD_TYPE_FLOAT;
            if (value instanceof Number) return FIELD_TYPE_INTEGER;
            throw new IllegalArgumentException("unsupported fixture value");
        }
        String getString(int column) { Object value = value(column); return value == null ? null : value.toString(); }
        long getLong(int column) {
            Object value = value(column);
            if (value == null) return 0L;
            if (value instanceof Number) return ((Number) value).longValue();
            try { return Long.parseLong(value.toString()); } catch (NumberFormatException ignored) { return 0L; }
        }
        int getInt(int column) { return (int) getLong(column); }
        public void close() {
            closed = true;
            if (closeFailure) throw new IllegalStateException("fixture close failure");
        }
    }
    static final class SQLiteDatabase {
        Cursor result;
        boolean queryFailure;
        int originalCalls;
        final List<String> queries = new ArrayList<>();
        final List<List<String>> arguments = new ArrayList<>();
        Cursor rawQuery(String query, String[] args) {
            queries.add(query);
            arguments.add(args == null ? null : Arrays.asList(args.clone()));
            if (queryFailure) throw new IllegalStateException("fixture query failure");
            return result;
        }
    }
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    static RevokeSqliteBindingChecks setup(boolean messages, boolean statuses, boolean own) {
        XposedBridge.hooks.clear();
        XposedBridge.logs.clear();
        REVOKED_MESSAGES.clear();
        PRESERVED_OWN_ROW_IDS.clear();
        DelMessageStore.rows.clear();
        DelMessageStore.senders.clear();
        DelMessageStore.insertFailure = false;
        toasts.clear();
        realtime.clear();
        PreserveDeleteForMeHook.active = false;
        RevokeSqliteBindingChecks hook = new RevokeSqliteBindingChecks();
        hook.messages = messages;
        hook.statuses = statuses;
        hook.own = own;
        hook.hookDatabaseRevocation();
        check(XposedBridge.logs.isEmpty() && XposedBridge.hooks.size() == 3, "production hooks should install");
        return hook;
    }
    static SQLiteDatabase database(String[] columns, Object[]... rows) {
        SQLiteDatabase db = new SQLiteDatabase();
        db.result = new Cursor(columns, rows);
        return db;
    }
    static SQLiteDatabase status(Object[]... rows) {
        return database(new String[]{"key_id", "from_me", "sender_jid_row_id"}, rows);
    }
    static SQLiteDatabase message(Object owner, Object key) {
        return database(new String[]{"from_me", "message_type", "key_id", "chat_row_id", "sender_jid_row_id"},
                new Object[]{owner, 0, key, 9L, 10L});
    }
    static ContentValues placeholder(int owner, String key, long rowId) {
        ContentValues values = new ContentValues();
        values.put("message_type", 15);
        values.put("from_me", owner);
        values.put("key_id", key);
        values.put("_id", rowId);
        return values;
    }
    static XC_MethodHook.MethodHookParam invoke(String method, SQLiteDatabase db, Object... args) {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.thisObject = db;
        param.args = args;
        XposedBridge.hooks.get(method).beforeHookedMethod(param);
        if (!param.handled) {
            db.originalCalls++;
            if (method.equals("delete")) param.result = Integer.valueOf(1);
            else param.result = Long.valueOf(901L);
        }
        return param;
    }
    static XC_MethodHook.MethodHookParam delete(SQLiteDatabase db, String table, String where, String... args) {
        return invoke("delete", db, table, where, args);
    }
    static void untouched(XC_MethodHook.MethodHookParam param, SQLiteDatabase db, String reason) {
        check(!param.handled && db.originalCalls == 1, reason + ": host path must run");
        check(REVOKED_MESSAGES.isEmpty() && DelMessageStore.rows.isEmpty(), reason + ": no unverified marker");
        check(PRESERVED_OWN_ROW_IDS.isEmpty() && toasts.isEmpty() && realtime.isEmpty(), reason + ": no side effects");
    }
    static void fallbackKey() {
        setup(false, true, false);
        SQLiteDatabase db = database(new String[]{"_id", "from_me"}, new Object[]{71L, 0});
        untouched(delete(db, "status", "_id=?", "71"), db, "SQL argument is not a verified status key");
        check(db.result.closed, "unsupported cursor must close");
    }
    static void ownership() {
        setup(false, true, false);
        SQLiteDatabase missing = database(new String[]{"key_id"}, new Object[]{"story"});
        untouched(delete(missing, "status", "_id=?", "71"), missing, "ownership must precede marker effects");
        for (Object invalid : new Object[]{null, "0", 0.0d, 2, -1, 4_294_967_296L}) {
            setup(false, true, false);
            SQLiteDatabase db = status(new Object[]{"story", invalid, 10L});
            untouched(delete(db, "status_v3", "key_id=?", "story"), db, "unknown ownership " + invalid);
            check(db.result.closed, "unknown-ownership cursor must close");
        }
    }
    static void batch() {
        for (boolean ownFirst : new boolean[]{false, true}) {
            setup(false, true, false);
            Object[] remote = {"remote-story", 0, 10L};
            Object[] own = {"own-story", 1, 11L};
            SQLiteDatabase db = ownFirst ? status(own, remote) : status(remote, own);
            untouched(delete(db, "status", "_id IN (?,?)", "71", "72"), db, "mixed batch must not publish partial markers");
        }
        setup(false, true, false);
        SQLiteDatabase failed = status(new Object[]{"remote-story", 0, 10L}, new Object[]{"second-story", 0, 11L});
        failed.result.failAtRow = 1;
        untouched(delete(failed, "status", "_id IN (?,?)", "71", "72"), failed, "failed scan is not a committed preservation plan");
        check(failed.result.closed && XposedBridge.logs.size() == 1, "failed scan closes and reports only exception class");
        setup(false, true, false);
        SQLiteDatabase closeFailed = status(new Object[]{"remote-story", 0, 10L});
        closeFailed.result.closeFailure = true;
        untouched(delete(closeFailed, "status", "_id=?", "71"), closeFailed, "cursor close precedes effects");
        check(XposedBridge.logs.size() == 1, "close failure is bounded diagnostic");

        setup(false, true, false);
        SQLiteDatabase markerFailed = status(new Object[]{"remote-story", 0, 10L});
        DelMessageStore.insertFailure = true;
        XC_MethodHook.MethodHookParam preserved = delete(markerFailed, "status", "_id=?", "71");
        check(preserved.handled && preserved.result.equals(0) && markerFailed.originalCalls == 0,
                "marker failure after validation must not re-enable destructive SQL");
        check(markerFailed.result.closed && REVOKED_MESSAGES.containsKey("remote-story")
                && DelMessageStore.rows.isEmpty() && toasts.isEmpty(),
                "marker failure has no implied persistence rollback or completed notification");
        check(XposedBridge.logs.size() == 1, "marker failure reports a bounded diagnostic");
    }
    static void placeholderScope() {
        for (String table : new String[]{"message", "messages"}) {
            setup(false, true, false);
            SQLiteDatabase db = new SQLiteDatabase();
            ContentValues values = placeholder(0, "ordinary-message", 71L);
            untouched(invoke("insertWithOnConflict", db, table, null, values, 0), db,
                    "status-only option cannot intercept message placeholders");
            check(values.get("_id").equals(71L), "status-only option must leave content values unchanged");
        }
        setup(false, true, false);
        REVOKED_MESSAGES.put("collision", 123L);
        DelMessageStore.rows.put("collision", 123L);
        SQLiteDatabase db = new SQLiteDatabase();
        XC_MethodHook.MethodHookParam param = invoke("insertWithOnConflict", db, "message", null,
                placeholder(0, "collision", 71L), 0);
        check(!param.handled && db.originalCalls == 1, "old status marker cannot activate message interception");
        check(REVOKED_MESSAGES.equals(Map.of("collision", 123L)) && DelMessageStore.rows.equals(Map.of("collision", 123L))
                && toasts.isEmpty() && realtime.isEmpty(), "pre-existing marker must remain unchanged");
    }
    static void messagePredicate() {
        String[] unverified = {"key_id=?", "chat_row_id=?", "other_id=?", "chat_row_id=? AND _id=?",
                "_id=? OR _id=?", "_id=? AND from_me=?", "_id IN (?)", "_id=71", "\"_id\"=?"};
        for (String where : unverified) {
            setup(true, false, false);
            SQLiteDatabase db = message(0, "unrelated-numeric-row");
            untouched(delete(db, "message", where, "71"), db, "predicate does not prove first argument is the selected row ID");
            check(db.queries.isEmpty(), "unsupported predicate must not query a guessed numeric row");
        }
        for (String[] invalidArgs : new String[][]{null, {}, {"71", "72"}, {null}}) {
            setup(true, false, false);
            SQLiteDatabase db = message(0, "unrelated-numeric-row");
            untouched(invoke("delete", db, "message", "_id=?", invalidArgs), db, "exact predicate also requires one non-null binding");
            check(db.queries.isEmpty(), "unsupported bindings must not query");
        }
    }
    static void compatibility() {
        for (String table : new String[]{"status", "status_v3"}) {
            setup(false, true, false);
            SQLiteDatabase db = status(new Object[]{"actual-story", 0L, 900L});
            XC_MethodHook.MethodHookParam param = delete(db, table, "_id = ?", "71");
            check(param.handled && param.result.equals(0) && db.originalCalls == 0, "verified remote status delete is preserved");
            check(REVOKED_MESSAGES.keySet().equals(Set.of("actual-story")) && DelMessageStore.rows.keySet().equals(Set.of("actual-story")),
                    "marker identity comes from cursor, not the numeric SQL binding");
            check(db.queries.equals(List.of("SELECT * FROM " + table + " WHERE _id = ?"))
                    && db.arguments.equals(List.of(List.of("71"))), "status query preserves original predicate and all bindings");
            check(db.result.closed, "successful cursor closes before effects");
            check(DelMessageStore.senders.containsKey("actual-story") && DelMessageStore.senders.get("actual-story") == null
                    && toasts.equals(Arrays.asList((String) null)), "numeric sender row ID must not masquerade as a JID");
        }
        setup(false, true, false);
        SQLiteDatabase remoteBatch = status(new Object[]{"first", 0, 1L}, new Object[]{"second", 0, 2L});
        XC_MethodHook.MethodHookParam batch = delete(remoteBatch, "status", "_id IN (?,?)", "71", "72");
        check(batch.handled && DelMessageStore.rows.keySet().equals(Set.of("first", "second")), "fully verified remote batch is preserved");

        for (String table : new String[]{"message", "status", "status_v3"}) {
            setup(true, true, true);
            PreserveDeleteForMeHook.active = true;
            SQLiteDatabase db = status(new Object[]{"story", 0, 1L});
            untouched(delete(db, table, "_id=?", "71"), db, "explicit local Delete-for-me context");
            check(db.queries.isEmpty(), "local context must exit before any query");
        }
        setup(false, true, false);
        SQLiteDatabase self = status(new Object[]{"own-story", 1, 1L});
        untouched(delete(self, "status", "key_id=?", "own-story"), self, "self-owned status deletion");
        for (String table : new String[]{"status_downloads", "message_status", "status_receipt", "other"}) {
            setup(true, true, true);
            SQLiteDatabase db = status(new Object[]{"metadata", 0, 1L});
            untouched(delete(db, table, "_id=?", "71"), db, "unverified similarly named table");
            check(db.queries.isEmpty(), "unverified table must not be queried");
        }
        for (String where : new String[]{null, "", " \n\t"}) {
            setup(false, true, false);
            SQLiteDatabase db = status(new Object[]{"story", 0, 1L});
            untouched(invoke("delete", db, "status", where, null), db, "unscoped whole-table cleanup");
            check(db.queries.isEmpty(), "unscoped cleanup must not be queried");
        }
        for (Object invalidKey : new Object[]{null, "", "   ", 71L, new byte[]{1}}) {
            setup(false, true, false);
            SQLiteDatabase db = status(new Object[]{invalidKey, 0, 1L});
            untouched(delete(db, "status", "_id=?", "71"), db, "missing or unverified key value");
        }
        for (String[] columns : new String[][]{{"original_key_id", "from_me"}, {"key", "from_me"},
                {"msgid", "from_me"}, {"key_id", "status.from_me"}, {"key_id", "key_id", "from_me"},
                {"key_id", "from_me", "FROM_ME"}}) {
            setup(false, true, false);
            SQLiteDatabase db = database(columns, new Object[]{"story", 0, 0});
            untouched(delete(db, "status", "_id=?", "71"), db, "missing, similar or duplicate schema columns");
        }
        setup(false, true, false);
        SQLiteDatabase duplicate = status(new Object[]{"same", 0, 1L}, new Object[]{"same", 0, 2L});
        untouched(delete(duplicate, "status", "key_id=?", "same"), duplicate, "duplicate key cannot prove unique status identity");
        setup(false, true, false);
        Object[][] many = new Object[MAX_RECENT_REVOKES + 1][];
        for (int i = 0; i < many.length; i++) many[i] = new Object[]{"story-" + i, 0, 1L};
        SQLiteDatabase excessive = status(many);
        untouched(delete(excessive, "status", "_id>=?", "1"), excessive, "unbounded batch must fall through atomically");

        setup(false, true, false);
        SQLiteDatabase empty = status();
        untouched(delete(empty, "status", "_id=?", "71"), empty, "empty result");
        setup(false, true, false);
        SQLiteDatabase absent = new SQLiteDatabase();
        untouched(delete(absent, "status", "_id=?", "71"), absent, "null cursor");
        setup(false, true, false);
        SQLiteDatabase error = new SQLiteDatabase();
        error.queryFailure = true;
        untouched(delete(error, "status", "_id=?", "71"), error, "failed query");
        check(XposedBridge.logs.size() == 1, "query failure reports exception class");

        for (String where : new String[]{"_id=?", "  _id = ?\n"}) {
            setup(true, false, false);
            SQLiteDatabase db = message(0, "incoming");
            check(delete(db, "message", where, "71").handled && DelMessageStore.rows.containsKey("incoming"),
                    "explicit incoming message setting retains its verified row-ID path");
        }
        for (Object invalidOwner : new Object[]{null, "0", 0.0d, 4_294_967_296L}) {
            setup(true, false, false);
            SQLiteDatabase db = message(invalidOwner, "incoming");
            untouched(delete(db, "message", "_id=?", "71"), db, "unknown message ownership");
        }
        for (Object invalidKey : new Object[]{null, "", 71L}) {
            setup(true, false, false);
            SQLiteDatabase db = message(0, invalidKey);
            untouched(delete(db, "message", "_id=?", "71"), db, "unverified message key");
        }
        setup(true, false, false);
        SQLiteDatabase incomingInsert = new SQLiteDatabase();
        check(invoke("insertWithOnConflict", incomingInsert, "message", null, placeholder(0, "incoming", 71L), 0).handled,
                "explicit anti-message still preserves its incoming placeholder");

        setup(false, false, true);
        SQLiteDatabase ownDelete = message(1, "own-message");
        check(delete(ownDelete, "message", "_id=?", "71").handled && PRESERVED_OWN_ROW_IDS.contains("71"),
                "own revoke keeps its verified original row and reservation");
        ContentValues ownPlaceholder = placeholder(1, "own-message", 71L);
        SQLiteDatabase ownInsert = new SQLiteDatabase();
        XC_MethodHook.MethodHookParam inserted = invoke("insertWithOnConflict", ownInsert, "message", null, ownPlaceholder, 0);
        check(!inserted.handled && ownInsert.originalCalls == 1 && inserted.result.equals(901L),
                "own placeholder still executes and receives a real host result");
        check(!ownPlaceholder.containsKey("_id") && PRESERVED_OWN_ROW_IDS.isEmpty(), "own placeholder receives an unreserved row ID");
        setup(false, false, true);
        PRESERVED_OWN_ROW_IDS.add("71");
        ContentValues throwingInsert = placeholder(1, "own-message", 71L);
        SQLiteDatabase throwing = new SQLiteDatabase();
        XC_MethodHook.MethodHookParam throwingResult = invoke("insertOrThrow", throwing, "message", null, throwingInsert);
        check(!throwingResult.handled && !throwingInsert.containsKey("_id") && throwing.originalCalls == 1,
                "insertOrThrow retains existing outgoing reservation handling");
        setup(false, false, false);
        SQLiteDatabase disabled = status(new Object[]{"story", 0, 1L});
        untouched(delete(disabled, "status", "key_id=?", "story"), disabled, "disabled preservation");
        check(disabled.queries.isEmpty(), "disabled option avoids query work");
    }
    public static void main(String[] args) {
        String scenario = args.length == 0 ? "all" : args[0];
        if (scenario.equals("all") || scenario.equals("fallback_key")) fallbackKey();
        if (scenario.equals("all") || scenario.equals("ownership")) ownership();
        if (scenario.equals("all") || scenario.equals("batch")) batch();
        if (scenario.equals("all") || scenario.equals("placeholder_scope")) placeholderScope();
        if (scenario.equals("all") || scenario.equals("message_predicate")) messagePredicate();
        if (scenario.equals("all") || scenario.equals("compatibility")) compatibility();
        System.out.println(checks + " verbatim SQLite revoke callback checks passed (" + scenario + ")");
    }
}
