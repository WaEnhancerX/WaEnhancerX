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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.licensing.LicenseManager;
import com.waenhancer.licensing.ProFeatureGate;
import com.waenhancer.licensing.features.BootloaderSpooferFeature;
import com.waenhancer.xposed.core.db.PreservedMessageStore;
import com.waenhancer.update.UpdateCheckCoordinator;
import com.waenhancer.utils.WhatsAppPackageDetector;

/**
 * HookProvider provides preference IPC bridge between WaEnhancerX UI and target processes (WhatsApp / WA Business).
 */
public class HookProvider extends ContentProvider {

    public static final String AUTHORITY = "com.waenhancer.hookprovider";
    public static final String KEY_VALUES = "values";
    public static final String KEY_SETS = "sets";
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
        int callerPid = Binder.getCallingPid();
        Context context = getContext();
        if (context == null) return null;
        if (!isTrustedCaller(context, callerUid)) {
            throw new SecurityException("WAEX provider caller is outside the configured hook scope");
        }
        long token = Binder.clearCallingIdentity();
        try {
            SharedPreferences prefs = getPrefs();
            if (prefs == null) return null;

            if ("request_update_check".equals(method)) {
                UpdateCheckCoordinator.request(context, UpdateCheckCoordinator.TRIGGER_WHATSAPP, false);
                Bundle result = new Bundle();
                result.putBoolean("accepted", true);
                return result;
            }

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
                    editor.putInt("runtime_boot_" + pkg,
                            extras != null ? extras.getInt("boot_count", -1) : -1);
                    editor.putInt("runtime_pid_" + pkg, callerPid);
                    editor.putString("runtime_status_" + pkg, "INJECTED");
                    boolean committed = editor.commit();
                    if (committed) {
                        context.getContentResolver().notifyChange(
                                Uri.parse("content://" + AUTHORITY + "/runtime_health"), null);
                    }
                    Bundle result = new Bundle();
                    result.putBoolean("success", committed);
                    return result;
                }
            }

            if ("report_runtime_health".equals(method) && extras != null) {
                String pkg = extras.getString("package", arg);
                String runtimeStatus = allowedRuntimeStatus(extras.getString("runtime_status"));
                String bridgeStatus = allowedBridgeStatus(extras.getString("bridge_status"));
                if (pkg != null && callerOwnsPackage(context, callerUid, pkg)
                        && runtimeStatus != null && bridgeStatus != null) {
                    long timestamp = extras.getLong("timestamp", System.currentTimeMillis());
                    String error = sanitizeDiagnosticError(extras.getString("error"));
                    var currentSet = new HashSet<>(prefs.getStringSet(
                            "hooked_whatsapp_packages", new HashSet<>()));
                    currentSet.add(pkg);
                    var editor = prefs.edit()
                            .putStringSet("hooked_whatsapp_packages", currentSet)
                            .putLong("last_active_" + pkg, timestamp)
                            .putInt("runtime_boot_" + pkg, extras.getInt("boot_count", -1))
                            .putInt("runtime_pid_" + pkg, callerPid)
                            .putString("runtime_status_" + pkg, runtimeStatus)
                            .putString("bridge_status_" + pkg, bridgeStatus);
                    if (error == null) editor.remove("runtime_error_" + pkg);
                    else editor.putString("runtime_error_" + pkg, error);
                    boolean committed = editor.commit();
                    if (committed) {
                        context.getContentResolver().notifyChange(
                                Uri.parse("content://" + AUTHORITY + "/runtime_health"), null);
                    }
                    Bundle result = new Bundle();
                    result.putBoolean("success", committed);
                    return result;
                }
            }

            if ("register_injected_hooks".equals(method) && extras != null) {
                String pkg = extras.getString("package", arg);
                String hooksJson = extras.getString("hooks_json");
                long timestamp = extras.getLong("timestamp", System.currentTimeMillis());
                if (pkg != null && callerOwnsPackage(context, callerUid, pkg) && hooksJson != null) {
                    var currentSet = new HashSet<>(prefs.getStringSet("hooked_whatsapp_packages", new HashSet<>()));
                    currentSet.add(pkg);
                    var editor = prefs.edit();
                    editor.putStringSet("hooked_whatsapp_packages", currentSet);
                    editor.putString("injected_hooks_" + pkg, hooksJson);
                    editor.putLong("injected_hooks_time_" + pkg, timestamp);
                    editor.putLong("last_active_" + pkg, timestamp);
                    editor.putInt("runtime_pid_" + pkg, callerPid);
                    boolean committed = editor.commit();
                    if (committed) {
                        context.getContentResolver().notifyChange(
                                Uri.parse("content://" + AUTHORITY + "/injected_hooks"), null);
                        context.getContentResolver().notifyChange(
                                Uri.parse("content://" + AUTHORITY + "/runtime_health"), null);
                    }
                    Bundle result = new Bundle();
                    result.putBoolean("success", committed);
                    return result;
                }
            }

            if ("get_all_preferences".equals(method)) {
                var all = new HashMap<String, Object>(prefs.getAll());
                all.put("waex_pro_active", "ACTIVE".equalsIgnoreCase(
                        LicenseManager.getProStatus(context)));
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
                all.put(ProFeatureGate.AUDIO_TO_VOICE_STATUS_ENTITLEMENT, audioToVoiceEntitled);
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
                boolean filterItemsEntitled = ProFeatureGate.isEntitled(
                        context, ProFeatureGate.FILTER_ITEMS);
                all.put(ProFeatureGate.FILTER_ITEMS_ENTITLEMENT, filterItemsEntitled);
                boolean unlockWaPlusEntitled = ProFeatureGate.isEntitled(
                        context, ProFeatureGate.UNLOCK_WA_PLUS);
                all.put(ProFeatureGate.UNLOCK_WA_PLUS_ENTITLEMENT, unlockWaPlusEntitled);
                if (!unlockWaPlusEntitled) all.put(ProFeatureGate.UNLOCK_WA_PLUS_PREF, false);
                return encodePreferences(all);
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

            if ("put_preference".equals(method) && extras != null) {
                ArrayList<Bundle> operations = new ArrayList<>();
                operations.add(extras);
                return applyPreferenceChanges(context, prefs, callerUid, false, operations);
            }

            if ("apply_preferences".equals(method) && extras != null) {
                ArrayList<Bundle> operations = extras.getParcelableArrayList("operations");
                return applyPreferenceChanges(context, prefs, callerUid,
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

                PreservedMessageStore.getInstance(context)
                        .insertPreservedMessage(jid, contactName, senderName, msgId, text, timestamp, fromMe, isGroup);
                return Bundle.EMPTY;
            }

            return null;
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Nullable
    private static String allowedRuntimeStatus(@Nullable String value) {
        if (value == null) return null;
        return Arrays.asList("INJECTED", "INITIALIZING", "READY", "READY_WITH_ERRORS", "UNSUPPORTED_VERSION", "COMPATIBILITY_UNAVAILABLE",
                "INITIALIZATION_FAILED").contains(value) ? value : null;
    }

    @Nullable
    private static String allowedBridgeStatus(@Nullable String value) {
        if (value == null) return null;
        return Arrays.asList("PENDING", "PROVIDER", "DISK_FALLBACK", "FAILED").contains(value)
                ? value : null;
    }

    @Nullable
    private static String sanitizeDiagnosticError(@Nullable String value) {
        if (value == null) return null;
        String sanitized = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (sanitized.isEmpty()) return null;
        return sanitized.length() <= 240 ? sanitized : sanitized.substring(0, 240);
    }

    private boolean isTrustedCaller(@NonNull Context context, int uid) {
        // Keep the Binder caller check before clearCallingIdentity().
        return CallerScopePolicy.isTrusted(uid, context.getApplicationInfo().uid,
                context.getPackageManager().getPackagesForUid(uid),
                WhatsAppPackageDetector.getSupportedPackages(context));
    }

    private boolean callerOwnsPackage(@NonNull Context context, int uid, @NonNull String pkg) {
        return CallerScopePolicy.ownsPackage(uid, context.getApplicationInfo().uid,
                context.getPackageManager().getPackagesForUid(uid), pkg);
    }

    private Bundle encodePreferences(Map<String, Object> source) {
        Bundle values = new Bundle();
        Bundle sets = new Bundle();
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Boolean) values.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) values.putInt(key, (Integer) value);
            else if (value instanceof Long) values.putLong(key, (Long) value);
            else if (value instanceof Float) values.putFloat(key, (Float) value);
            else if (value instanceof String) values.putString(key, (String) value);
            else if (value instanceof Set<?>) {
                ArrayList<String> strings = new ArrayList<>();
                for (Object item : (Set<?>) value) if (item instanceof String) strings.add((String) item);
                sets.putStringArrayList(key, strings);
            }
        }
        Bundle result = new Bundle();
        result.putBundle(KEY_VALUES, values);
        result.putBundle(KEY_SETS, sets);
        return result;
    }

    private static Bundle writeResult(boolean success) {
        Bundle result = new Bundle();
        result.putBoolean("success", success);
        return result;
    }

    private Bundle applyPreferenceChanges(Context context, SharedPreferences prefs,
                                          int callerUid, boolean clear, @Nullable ArrayList<Bundle> operations) {
        // Bulk clear must be restricted to the module itself. Scoped host UIDs
        // may update allowed settings but must not erase the module's state.
        if (clear && !ProviderDerivedKeyPolicy.mayClearPreferences(
                callerUid, context.getApplicationInfo().uid)) return writeResult(false);
        if (operations == null || operations.size() > 128) return writeResult(false);
        for (Bundle op : operations) {
            if (op == null) return writeResult(false);
            String key = op.getString("key");
            String type = op.getString("type");
            if (key == null || key.isEmpty() || key.length() > 200 || type == null) return writeResult(false);
            if (!ProviderDerivedKeyPolicy.allowsGenericWrite(key)) return writeResult(false);
            PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
            if (entry != null && entry.store == PreferenceSchema.Store.PRIVATE) return writeResult(false);
            // Known public keys must retain their declared schema types. Otherwise a
            // string-encoded boolean could sidestep the following entitlement guard.
            if (entry != null && !PreferenceWriteTypePolicy.accepts(entry.type.name(), type))
                return writeResult(false);
            if (!("remove".equals(type) || "string".equals(type) || "string_set".equals(type)
                    || "boolean".equals(type) || "int".equals(type) || "long".equals(type)
                    || "float".equals(type))) return writeResult(false);
            if ("boolean".equals(type) && op.getBoolean("value", false)
                    && !isEntitledBoolean(context, key)) return writeResult(false);
        }

        SharedPreferences.Editor editor = prefs.edit();
        if (clear) editor.clear();
        for (Bundle op : operations) {
            String key = op.getString("key");
            switch (op.getString("type")) {
                case "remove": editor.remove(key); break;
                case "string": editor.putString(key, op.getString("value")); break;
                case "string_set":
                    ArrayList<String> list = op.getStringArrayList("value");
                    editor.putStringSet(key, list == null ? null : new HashSet<>(list));
                    break;
                case "boolean": editor.putBoolean(key, op.getBoolean("value")); break;
                case "int": editor.putInt(key, op.getInt("value")); break;
                case "long": editor.putLong(key, op.getLong("value")); break;
                case "float": editor.putFloat(key, op.getFloat("value")); break;
            }
        }
        boolean committed = editor.commit();
        if (committed) {
            context.getContentResolver().notifyChange(
                    Uri.parse("content://" + AUTHORITY + "/preferences"), null);
        }
        return writeResult(committed);
    }

    private boolean isEntitledBoolean(Context context, String key) {
        if (ProFeatureGate.AUDIO_TO_VOICE_STATUS_PREF.equals(key))
            return ProFeatureGate.isEntitled(context, ProFeatureGate.AUDIO_TO_VOICE_STATUS);
        if (ProFeatureGate.MESSAGE_BOMBER_PREF.equals(key))
            return ProFeatureGate.isEntitled(context, ProFeatureGate.MESSAGE_BOMBER);
        if (ProFeatureGate.STATUS_SPLITTER_PREF.equals(key))
            return ProFeatureGate.isEntitled(context, ProFeatureGate.STATUS_SPLITTER);
        if (ProFeatureGate.FILE_SIZE_SPOOFER_PREF.equals(key))
            return ProFeatureGate.isEntitled(context, ProFeatureGate.FILE_SIZE_SPOOFER);
        if (ProFeatureGate.UNLOCK_WA_PLUS_PREF.equals(key))
            return ProFeatureGate.isEntitled(context, ProFeatureGate.UNLOCK_WA_PLUS);
        return true;
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

}
