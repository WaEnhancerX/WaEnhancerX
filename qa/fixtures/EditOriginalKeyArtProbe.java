package com.waenhancer.lab;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import com.waenhancer.xposed.core.db.EditMessageStore;
import com.waenhancer.xposed.core.db.MessageStore;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Runs only with caller-owned temporary databases; no installed app context. */
public final class EditOriginalKeyArtProbe {
    private static final List<String> failures = new ArrayList<>();
    private static int checks;
    private static int scenarios;
    private static File root;

    private static final class OwnedContext extends ContextWrapper {
        private final File folder;
        OwnedContext(File folder) { super(null); this.folder = folder; }
        @Override public Context getApplicationContext() { return this; }
        @Override public String getPackageName() { return "com.waenhancer.lab.fixture"; }
        @Override public ApplicationInfo getApplicationInfo() {
            ApplicationInfo info = new ApplicationInfo();
            info.targetSdkVersion = 30;
            return info;
        }
        @Override public File getDatabasePath(String name) {
            if (!"waex_history.db".equals(name)) throw new AssertionError("Unexpected database name");
            return new File(folder, name);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
        }
        @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
                                                             DatabaseErrorHandler errorHandler) {
            return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, errorHandler);
        }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) failures.add(label);
    }

    private static File hostFile(String name) throws Exception {
        File folder = new File(root, name);
        if (!folder.mkdir()) throw new AssertionError("Case directory is not new");
        File file = new File(folder, "host.db");
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null)) {
            db.execSQL("CREATE TABLE message(_id INTEGER PRIMARY KEY,chat_row_id INTEGER,key_id TEXT)");
            db.execSQL("CREATE INDEX message_key_idx ON message(key_id)");
            db.execSQL("CREATE TABLE message_edit_info(message_row_id INTEGER,original_key_id TEXT)");
        }
        return file;
    }

    private static void message(SQLiteDatabase db, long id, long chat, String key) {
        db.execSQL("INSERT INTO message VALUES(?,?,?)", new Object[] {id, chat, key});
    }

    private static void edit(SQLiteDatabase db, long id, String original) {
        db.execSQL("INSERT INTO message_edit_info VALUES(?,?)", new Object[] {id, original});
    }

    private static EditMessageStore store(File file) throws Exception {
        MessageStore.hostFile = file;
        Constructor<EditMessageStore> constructor = EditMessageStore.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new OwnedContext(file.getParentFile()));
    }

    private static byte[] sha(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[16384];
        try (FileInputStream input = new FileInputStream(file)) {
            for (int n; (n = input.read(buffer)) != -1;) digest.update(buffer, 0, n);
        }
        return digest.digest();
    }

    private static void unique() throws Exception {
        scenarios++;
        File file = hostFile("unique");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            message(db, 1, 10, "alias");
            edit(db, 1, "original");
        }
        byte[] before = sha(file);
        EditMessageStore store = store(file);
        check("original".equals(store.resolveOriginalKey("alias")), "unique resolves");
        check("original".equals(store.cachedOriginalKey("alias")), "unique caches");
        MessageStore.hostFile = new File(file.getParentFile(), "nonexistent.db");
        check("original".equals(store.resolveOriginalKey("alias")), "confirmed cache does not open a different host DB");
        check(Arrays.equals(before, sha(file)), "unique leaves host file unchanged");
        check(!new File(file.getParentFile(), "waex_history.db").exists(), "lookup creates no history DB");
    }

    private static void duplicates() throws Exception {
        for (String kind : new String[] {"different", "same", "one-unlinked", "both-unlinked", "duplicate-info"}) {
            for (boolean reverse : new boolean[] {false, true}) {
                scenarios++;
                String name = kind + "-" + reverse;
                File file = hostFile(name);
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                    if (reverse && !"duplicate-info".equals(kind)) message(db, 2, 20, "alias");
                    message(db, 1, 10, "alias");
                    if (!reverse && !"duplicate-info".equals(kind)) message(db, 2, 20, "alias");
                    if (!"both-unlinked".equals(kind)) edit(db, 1, "A");
                    if ("different".equals(kind)) edit(db, 2, "B");
                    if ("same".equals(kind)) edit(db, 2, "A");
                    if ("duplicate-info".equals(kind)) edit(db, 1, "B");
                }
                byte[] before = sha(file);
                EditMessageStore store = store(file);
                check("alias".equals(store.resolveOriginalKey("alias")), name + ": no first-match alias");
                check(store.cachedOriginalKey("alias") == null, name + ": no ambiguity cached");
                check(Arrays.equals(before, sha(file)), name + ": host file unchanged");
                check(!new File(file.getParentFile(), "waex_history.db").exists(), name + ": no history write");
            }
        }
    }

    private static void retry() throws Exception {
        for (String kind : new String[] {"no-message", "no-info", "null", "empty", "blank"}) {
            scenarios++;
            File file = hostFile("retry-" + kind);
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                if (!"no-message".equals(kind)) message(db, 1, 10, "alias");
                if ("null".equals(kind)) edit(db, 1, null);
                if ("empty".equals(kind)) edit(db, 1, "");
                if ("blank".equals(kind)) edit(db, 1, "   ");
            }
            byte[] before = sha(file);
            EditMessageStore store = store(file);
            check("alias".equals(store.resolveOriginalKey("alias")), kind + ": unresolved first lookup");
            check(store.cachedOriginalKey("alias") == null, kind + ": miss remains retryable");
            check(Arrays.equals(before, sha(file)), kind + ": first read leaves host file unchanged");
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
                if ("no-message".equals(kind)) message(db, 1, 10, "alias");
                db.delete("message_edit_info", null, null);
                edit(db, 1, "committed");
            }
            byte[] committed = sha(file);
            check("committed".equals(store.resolveOriginalKey("alias")), kind + ": later edit is discovered");
            check("committed".equals(store.cachedOriginalKey("alias")), kind + ": later alias confirmed");
            check(Arrays.equals(committed, sha(file)), kind + ": retry leaves committed host file unchanged");
        }
    }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length != 2) throw new IllegalArgumentException("folder and source label required");
            root = new File(args[0]).getCanonicalFile();
            if (!root.getPath().matches("/data/local/tmp/waex-edit-alias-[a-f0-9]+/(baseline|candidate)") ||
                    !root.isDirectory() || root.list().length != 0)
                throw new AssertionError("Probe requires a new owned empty directory");
            unique();
            duplicates();
            retry();
            JSONObject result = new JSONObject();
            result.put("source", args[1]);
            result.put("api", android.os.Build.VERSION.SDK_INT);
            result.put("uid", android.os.Process.myUid());
            result.put("scenarios", scenarios);
            result.put("checks", checks);
            result.put("failures", new JSONArray(failures));
            result.put("scope", "whole production EditMessageStore; real Android SQLite; synthetic context and host schema; no WhatsApp");
            System.out.println(result.toString());
            System.exit(failures.isEmpty() ? 0 : 1);
        } catch (Throwable failure) {
            failure.printStackTrace(System.err);
            System.exit(2);
        }
    }
}
