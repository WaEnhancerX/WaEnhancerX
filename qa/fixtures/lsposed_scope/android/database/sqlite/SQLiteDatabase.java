package android.database.sqlite;

import android.database.Cursor;
import java.io.Closeable;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Strict in-memory SQL double: unexpected SQL/arguments throw, never return all rows. */
public final class SQLiteDatabase implements Closeable {
    public static final int OPEN_READONLY = 1;
    public static boolean unsupportedSchema = false;
    public static final List<ScopeRow> rows = new ArrayList<>();
    public static int queries = 0;

    public record ScopeRow(int mid, String modulePackage, boolean enabled,
                           String targetPackage, int userId) {}

    private SQLiteDatabase() {}

    public static SQLiteDatabase openDatabase(String path, Object cursorFactory, int flags) {
        if (!new File(path).isFile() || flags != OPEN_READONLY || cursorFactory != null) {
            throw new IllegalStateException("Unexpected database open");
        }
        return new SQLiteDatabase();
    }

    public Cursor rawQuery(String sql, String[] args) {
        queries++;
        if (unsupportedSchema) throw new IllegalStateException("Unsupported LSPosed schema");
        String normalized = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("select name from sqlite_master where type='table'")) {
            // Legacy fallback sees no vendor tables in this strict fixture.
            return new RowCursor(List.of());
        }
        if (!normalized.startsWith("select s.app_pkg_name from scope s join modules m on m.mid = s.mid ") ||
            !normalized.contains("m.module_pkg_name = ?") ||
            !normalized.contains("m.enabled = 1")) {
            throw new IllegalArgumentException("Unexpected scope SQL: " + normalized);
        }
        boolean bindsUser = normalized.contains("s.user_id = ?");
        if ((bindsUser && (args == null || args.length != 2)) ||
            (!bindsUser && (args == null || args.length != 1))) {
            throw new IllegalArgumentException("Unexpected SQL bind count: " + Arrays.toString(args));
        }
        int userId = bindsUser ? Integer.parseInt(args[1]) : -1;
        List<String> result = new ArrayList<>();
        for (ScopeRow row : rows) {
            if (row.enabled && row.modulePackage.equals(args[0]) &&
                (!bindsUser || row.userId == userId)) result.add(row.targetPackage);
        }
        return new RowCursor(result);
    }

    @Override public void close() {}

    private static final class RowCursor implements Cursor {
        private final List<String> values;
        private int position = -1;
        private boolean closed = false;

        private RowCursor(List<String> values) { this.values = values; }

        @Override public boolean moveToNext() {
            if (closed) throw new IllegalStateException("Cursor closed");
            return ++position < values.size();
        }

        @Override public String getString(int column) {
            if (closed || column != 0 || position < 0 || position >= values.size()) {
                throw new IllegalStateException("Invalid cursor access");
            }
            return values.get(position);
        }

        @Override public void close() { closed = true; }
    }
}
