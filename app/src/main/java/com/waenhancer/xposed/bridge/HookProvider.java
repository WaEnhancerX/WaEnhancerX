package com.waenhancer.xposed.bridge;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.utils.WhatsAppPackageDetector;
import com.waenhancer.licensing.ProFeatureGate;
import com.waenhancer.licensing.features.BootloaderSpooferFeature;

/**
 * HookProvider provides preference IPC bridge between WaEnhancerX UI and target processes (WhatsApp / WA Business).
 */
public class HookProvider extends ContentProvider {

    public static final String AUTHORITY = "com.waenhancer.hookprovider";

    @Override
    public boolean onCreate() {

        return getContext() != null;
    }

    private SharedPreferences getPrefs() {
        Context context = getContext();
        if (context == null) return null;
        return context.getSharedPreferences("waex_prefs", Context.MODE_PRIVATE);
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        int callerUid = Binder.getCallingUid();
        // ContentProvider.call() is not protected by the manifest's read/write
        // permissions. Authenticate the *real Binder UID* before returning any
        // preferences (or permitting writes and stored message inserts).
        Context context = getContext();
        if (context == null) return null;
        if (!isTrustedCaller(context, callerUid)) {
            throw new SecurityException("WAEX provider: caller not in the configured hook scope");
        }
        long token = Binder.clearCallingIdentity();
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) return null;

            if ("create_call_recording".equals(method)) {
                if (extras == null) return null;
                String requestedName = extras.getString("name", "Call.m4a");
                String safeName = requestedName.replaceAll("[^A-Za-z0-9._-]", "_");
                if (safeName.isEmpty()) safeName = "Call.m4a";
                File base = context.getExternalFilesDir(null);
                if (base == null) base = context.getFilesDir();
                File directory = new File(base, "CallRecordings");
                if (!directory.exists() && !directory.mkdirs()) return null;
                File output = new File(directory, safeName);
                try {
                    ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(
                            output,
                            ParcelFileDescriptor.MODE_CREATE
                                    | ParcelFileDescriptor.MODE_TRUNCATE
                                    | ParcelFileDescriptor.MODE_READ_WRITE);
                    Bundle result = new Bundle();
                    result.putParcelable("descriptor", descriptor);
                    result.putString("path", output.getAbsolutePath());
                    return result;
                } catch (FileNotFoundException ignored) {
                    return null;
                }
            }

            if ("delete_call_recording".equals(method)) {
                if (extras == null) return null;
                String requestedName = extras.getString("name", "");
                String safeName = requestedName.replaceAll("[^A-Za-z0-9._-]", "_");
                File base = context.getExternalFilesDir(null);
                if (base == null) base = context.getFilesDir();
                boolean deleted = !safeName.isEmpty()
                        && new File(new File(base, "CallRecordings"), safeName).delete();
                Bundle result = new Bundle();
                result.putBoolean("deleted", deleted);
                return result;
            }

            if ("register_hooked_package".equals(method)) {
                String pkg = (arg != null) ? arg : (extras != null ? extras.getString("package") : null);
                if (pkg != null && callerOwnsPackage(context, callerUid, pkg)) {
                    var currentSet = new HashSet<>(prefs.getStringSet("hooked_whatsapp_packages", new HashSet<>()));
                    currentSet.add(pkg);
                    var editor = prefs.edit();
                    editor.putStringSet("hooked_whatsapp_packages", currentSet);
                    editor.putLong("last_active_" + pkg, System.currentTimeMillis());
                    editor.commit();
                    fixPermissions();
                    context.getContentResolver().notifyChange(Uri.parse("content://" + AUTHORITY + "/preferences"), null);
                    Bundle result = new Bundle();
                    result.putBoolean("success", true);
                    return result;
                }
            }

            if ("get_all_preferences".equals(method)) {
                var all = new HashMap<String, Object>(prefs.getAll());
                all.put("waex_pro_active", "ACTIVE".equalsIgnoreCase(
                        com.waenhancer.licensing.LicenseManager.getProStatus(context)));
                // Keybox material is private at rest and is exposed only in this in-memory IPC
                // response to the scoped hooked process when the free feature is enabled.
                if (prefs.getBoolean(BootloaderSpooferFeature.ENABLED, true)) {
                    SharedPreferences privatePrefs = context.getSharedPreferences("private_config", Context.MODE_PRIVATE);
                    boolean custom = prefs.getBoolean(BootloaderSpooferFeature.CUSTOM_ENABLED, false);
                    all.put(BootloaderSpooferFeature.CUSTOM_XML,
                            privatePrefs.getString(custom ? BootloaderSpooferFeature.CUSTOM_XML
                                    : BootloaderSpooferFeature.DEFAULT_XML, ""));
                    all.put(BootloaderSpooferFeature.DEFAULT_XML,
                            privatePrefs.getString(BootloaderSpooferFeature.DEFAULT_XML, ""));
                }
                boolean audioToVoiceEntitled = ProFeatureGate.isEntitled(
                        context, ProFeatureGate.AUDIO_TO_VOICE_STATUS);
                all.put(ProFeatureGate.AUDIO_TO_VOICE_STATUS_ENTITLEMENT,
                        audioToVoiceEntitled);
                // The hooked process never sees this Pro switch enabled without entitlement.
                if (!audioToVoiceEntitled) {
                    all.put(ProFeatureGate.AUDIO_TO_VOICE_STATUS_PREF, false);
                }
                boolean messageBomberEntitled = ProFeatureGate.isEntitled(
                        context, ProFeatureGate.MESSAGE_BOMBER);
                all.put(ProFeatureGate.MESSAGE_BOMBER_ENTITLEMENT, messageBomberEntitled);
                if (!messageBomberEntitled) {
                    all.put(ProFeatureGate.MESSAGE_BOMBER_PREF, false);
                }
                boolean statusSplitterEntitled = ProFeatureGate.isEntitled(
                        context, ProFeatureGate.STATUS_SPLITTER);
                all.put(ProFeatureGate.STATUS_SPLITTER_ENTITLEMENT, statusSplitterEntitled);
                if (!statusSplitterEntitled) all.put(ProFeatureGate.STATUS_SPLITTER_PREF, false);
                boolean fileSizeSpooferEntitled = ProFeatureGate.isEntitled(
                        context, ProFeatureGate.FILE_SIZE_SPOOFER);
                all.put(ProFeatureGate.FILE_SIZE_SPOOFER_ENTITLEMENT, fileSizeSpooferEntitled);
                if (!fileSizeSpooferEntitled) all.put(ProFeatureGate.FILE_SIZE_SPOOFER_PREF, false);
                Bundle result = new Bundle();
                result.putSerializable("prefs", all);
                return result;
            }

            if ("get_preference".equals(method) && extras != null) {
                String key = extras.getString("key");
                Bundle result = new Bundle();
                if (key != null) {
                    Object value = prefs.getAll().get(key);
                    if (value instanceof Boolean) result.putBoolean("value", (Boolean) value);
                    else if (value instanceof String) result.putString("value", (String) value);
                    else if (value instanceof Integer) result.putInt("value", (Integer) value);
                    else if (value instanceof Long) result.putLong("value", (Long) value);
                    else if (value instanceof Float) result.putFloat("value", (Float) value);
                }
                return result;
            }

            // Legacy single-key API and atomic batch API both use the same
            // validation, entitlement checks and persistence code.
            if ("put_preference".equals(method) && extras != null) {
                ArrayList<Bundle> operations = new ArrayList<>();
                operations.add(extras);
                return applyPreferenceChanges(context, prefs, false, operations);
            }
            if ("apply_preferences".equals(method) && extras != null) {
                ArrayList<Bundle> operations = extras.getParcelableArrayList("operations");
                return applyPreferenceChanges(context, prefs,
                        extras.getBoolean("clear", false), operations);
            }

            if ("record_preserved_message".equals(method) && extras != null) {
                String jid = extras.getString("jid", "");
                String contactName = extras.getString("name", "");
                String senderName = extras.getString("senderName", "");
                String msgId = extras.getString("msgId", "");
                String text = extras.getString("text", "");
                long timestamp = extras.getLong("timestamp", System.currentTimeMillis());
                boolean fromMe = extras.getBoolean("fromMe", false);
                boolean isGroup = extras.getBoolean("isGroup", false);

                com.waenhancer.xposed.core.db.PreservedMessageStore.getInstance(context)
                        .insertPreservedMessage(jid, contactName, senderName, msgId, text, timestamp, fromMe, isGroup);
                return Bundle.EMPTY;
            }

            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /** A caller must own an explicitly listed scoped target package, not merely
     * put an arbitrary WhatsApp-looking string in an IPC argument. The module's
     * own UID is permitted for its settings UI. Custom clones outside the scope
     * are deliberately not trusted by the provider. */
    private boolean isTrustedCaller(@NonNull Context context, int uid) {
        if (uid == context.getApplicationInfo().uid) return true;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages == null) return false;
        Set<String> scopes = WhatsAppPackageDetector.getSupportedPackages(context);
        for (String name : packages) {
            if (scopes.contains(name)) return true;
        }
        return false;
    }

    private boolean callerOwnsPackage(@NonNull Context context, int uid, @NonNull String pkg) {
        if (uid == context.getApplicationInfo().uid) return true;
        String[] owned = context.getPackageManager().getPackagesForUid(uid);
        return owned != null && Arrays.asList(owned).contains(pkg);
    }

    private static Bundle writeResult(boolean success) {
        Bundle result = new Bundle();
        result.putBoolean("success", success);
        return result;
    }

    /** Validate the complete batch before making any changes, avoiding partial
     * writes and inconsistent quick-toggle states when an operation fails. */
    private Bundle applyPreferenceChanges(Context context, SharedPreferences prefs,
                                          boolean clear, @Nullable ArrayList<Bundle> operations) {
        if (operations == null || operations.size() > 128) return writeResult(false);
        for (Bundle op : operations) {
            if (op == null) return writeResult(false);
            String key = op.getString("key");
            String type = op.getString("type");
            if (key == null || key.isEmpty() || key.length() > 200 || type == null) {
                return writeResult(false);
            }
            PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
            if (entry != null && entry.store == PreferenceSchema.Store.PRIVATE) {
                return writeResult(false);
            }
            if (!("remove".equals(type) || "string".equals(type)
                    || "string_set".equals(type) || "boolean".equals(type)
                    || "int".equals(type) || "long".equals(type)
                    || "float".equals(type))) return writeResult(false);
            if ("boolean".equals(type) && op.getBoolean("value", false)) {
                if (ProFeatureGate.AUDIO_TO_VOICE_STATUS_PREF.equals(key)
                        && !ProFeatureGate.isEntitled(context, ProFeatureGate.AUDIO_TO_VOICE_STATUS)) return writeResult(false);
                if (ProFeatureGate.MESSAGE_BOMBER_PREF.equals(key)
                        && !ProFeatureGate.isEntitled(context, ProFeatureGate.MESSAGE_BOMBER)) return writeResult(false);
                if (ProFeatureGate.STATUS_SPLITTER_PREF.equals(key)
                        && !ProFeatureGate.isEntitled(context, ProFeatureGate.STATUS_SPLITTER)) return writeResult(false);
                if (ProFeatureGate.FILE_SIZE_SPOOFER_PREF.equals(key)
                        && !ProFeatureGate.isEntitled(context, ProFeatureGate.FILE_SIZE_SPOOFER)) return writeResult(false);
            }
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (clear) editor.clear();
        for (Bundle op : operations) {
            String key = op.getString("key");
            switch (op.getString("type")) {
                case "remove": editor.remove(key); break;
                case "string": editor.putString(key, op.getString("value")); break;
                case "string_set":
                    ArrayList<String> values = op.getStringArrayList("value");
                    editor.putStringSet(key, values == null ? null : new HashSet<>(values));
                    break;
                case "boolean": editor.putBoolean(key, op.getBoolean("value")); break;
                case "int": editor.putInt(key, op.getInt("value")); break;
                case "long": editor.putLong(key, op.getLong("value")); break;
                case "float": editor.putFloat(key, op.getFloat("value")); break;
            }
        }
        boolean committed = editor.commit();
        if (committed) {
            fixPermissions();
            context.getContentResolver().notifyChange(
                    Uri.parse("content://" + AUTHORITY + "/preferences"), null);
        }
        return writeResult(committed);
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection, @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    private void fixPermissions() {
        try {
            Context context = getContext();
            if (context == null) return;
            File dataDir = new File(context.getApplicationInfo().dataDir);
            File prefsDir = new File(dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, "waex_prefs.xml");

            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);

            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);

            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) {}
    }
}
