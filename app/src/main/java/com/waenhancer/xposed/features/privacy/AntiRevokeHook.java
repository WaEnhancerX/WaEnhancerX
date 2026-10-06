package com.waenhancer.xposed.features.privacy;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.db.DelMessageStore;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.homescreen.MenuIconLoader;
import com.waenhancer.xposed.utils.ActivityTracker;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-Revoke & Deleted Indicator Engine for Messages and Statuses.
 *
 * Strategy:
 *  1. Block SQLite DELETE on 'message' and 'status' / 'status_v3' tables.
 *  2. Block SQLite INSERT of message_type=15 placeholders.
 *  3. Block bytecode revocation calls for both chat messages and status stories.
 *  4. Persist revoked message/status key_ids in DelMessageStore for cross-session survival.
 *  5. Decorate revoked messages in Conversation via ListView adapter getView hook.
 *  6. Decorate revoked statuses in Status Playback viewer header.
 */
public class AntiRevokeHook extends BaseFeature {

    private static final String TAG = "[WAEX][AntiRevoke]";

    private static final Map<String, Long> REVOKED_MESSAGES = new ConcurrentHashMap<>();
    private static final DateFormat TIME_FORMAT = DateFormat.getTimeInstance(DateFormat.SHORT);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Resolved WhatsApp FMessage class — used to guard getView items. */
    private Class<?> fMessageClass;

    /** Resolved FMessage Key class and reflection cache. */
    private Class<?> messageKeyClass;
    /** WhatsApp's status-store key type, resolved independently from message keys. */
    private Class<?> statusKeyClass;
    /** Maps WhatsApp's status model to its backing FMessage for playback decoration. */
    private Method statusToMessageMethod;
    private Class<?> statusModelClass;
    private volatile Object statusToMessageMapper;
    private volatile Field cachedFMessageKeyField;
    private volatile Field cachedKeyIdField;

    /** Track previous getView hook so we can unhook on adapter change. */
    private volatile XC_MethodHook.Unhook previousGetViewHook;
    /** Track the currently hooked adapter and ListView. */
    private volatile ListAdapter currentAdapter;
    private static WeakReference<ListView> activeListView = new WeakReference<>(null);

    public AntiRevokeHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        initMessageClasses();
        hookBytecodeRevocation();
        hookStatusBytecodeRevocation();
        hookDatabaseRevocation();
        hookListViewAdapter();
        hookStatusPlaybackUI();
        XposedBridge.log(TAG + " All hooks installed.");
    }

    // ─── Key class resolution ────────────────────────────────────────────

    private void initMessageClasses() {
        try {
            // ── Resolve FMessage class ──
            fMessageClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_fmessage_class",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("FMessage/getSenderUserJid/key.id",
                                                StringMatchType.Contains)))
                                .firstOrNull();
                        if (data != null) {
                            return data.getMethodInstance(loader).getDeclaringClass();
                        }
                        return null;
                    });

            if (fMessageClass != null) {
                XposedBridge.log(TAG + " FMessage class resolved: " + fMessageClass.getName());
            }

            // ── Resolve Key class ──
            messageKeyClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_message_key_class",
                    (bridge, loader) -> {
                        ClassDataList list = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().fieldCount(3)
                                        .addMethod(MethodMatcher.create()
                                                .addUsingString("Key", StringMatchType.Contains)
                                                .name("toString"))));
                        for (ClassData cd : list) {
                            return cd.getInstance(loader);
                        }
                        return null;
                    });

            if (messageKeyClass != null) {
                XposedBridge.log(TAG + " Key class resolved: " + messageKeyClass.getName());
            }

            statusToMessageMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "wpp_status_to_message_mapper_v1",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create().addUsingString(
                                        "mapFStatusToFMessageForForwarding", StringMatchType.Contains)))
                                .firstOrNull();
                        return data != null ? data.getMethodInstance(loader) : null;
                    });
            if (statusToMessageMethod != null && statusToMessageMethod.getParameterCount() > 0) {
                statusToMessageMethod.setAccessible(true);
                statusModelClass = statusToMessageMethod.getParameterTypes()[0];
                XposedBridge.hookAllConstructors(statusToMessageMethod.getDeclaringClass(),
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                statusToMessageMapper = param.thisObject;
                            }
                        });
                XposedBridge.log(TAG + " Status model mapper resolved: "
                        + statusModelClass.getName() + " -> "
                        + (fMessageClass != null ? fMessageClass.getName() : "FMessage"));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error initializing message key classes: " + t.getMessage());
        }
    }

    // ─── Bytecode revocation hook ────────────────────────────────────────

    private void hookBytecodeRevocation() {
        try {
            Method revokeMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "wpp_core_msgstore_revoke",
                    (bridge, loader) -> {
                        String[] anchors = {
                                "msgstore/edit/revoke",
                                "msgstore/add/revoke",
                                "CoreMessageStore/revoke",
                                "RevokeMessageStore/revoke",
                                "revoke_message"
                        };
                        for (String anchor : anchors) {
                            try {
                                MethodData data = bridge.findMethod(FindMethod.create()
                                        .matcher(MethodMatcher.create().usingStrings(anchor))
                                ).firstOrNull();
                                if (data != null) {
                                    Method m = data.getMethodInstance(loader);
                                    if (m.getParameterCount() >= 1) return m;
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });

            if (revokeMethod != null) {
                XposedBridge.hookMethod(revokeMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean antiRevokeMsg = isAntiRevokeEnabled();
                        boolean antiRevokeStatus = isAntiRevokeStatusEnabled();
                        if (!antiRevokeMsg && !antiRevokeStatus) return;

                        try {
                            Object fMessage = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                            if (fMessage != null) {
                                if (Boolean.TRUE.equals(extractIsFromMe(fMessage))) {
                                    XposedBridge.log(TAG + " Allowing own message/status revoke");
                                    return;
                                }
                                String keyId = extractKeyIdFromFMessage(fMessage);
                                if (keyId != null) {
                                    long now = System.currentTimeMillis();
                                    REVOKED_MESSAGES.put(keyId, now);
                                    DelMessageStore.getInstance(context).insertMessage(null, keyId, now);
                                    dispatchRealtimeRevokeUI(keyId, now);
                                    com.waenhancer.xposed.features.automation.PresenceToastsHook.showDeletedMessageToast(context, null, uiHandler);
                                    XposedBridge.log(TAG + " Bytecode revoke blocked, keyId=" + keyId);
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " Error extracting key from bytecode revoke: " + t.getMessage());
                        }
                        param.setResult(true);
                    }
                });
                XposedBridge.log(TAG + " Hooked bytecode revoke: " + revokeMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking bytecode revoke: " + t.getMessage());
        }
    }

    private void hookStatusBytecodeRevocation() {
        try {
            statusKeyClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_status_key_class_v2",
                    (bridge, loader) -> {
                        ClassData data = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .addUsingString("Key(id=", StringMatchType.Contains)
                                        .addUsingString("senderJid", StringMatchType.Contains)))
                                .firstOrNull();
                        return data != null ? data.getInstance(loader) : null;
                    });

            Method revokeStatusMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "wpp_revoke_status_manager_v2",
                    (bridge, loader) -> {
                        if (statusKeyClass == null) return null;

                        ClassData managerData = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .addUsingString("RevokeStatusManager/failed", StringMatchType.Contains)))
                                .firstOrNull();
                        if (managerData == null) return null;

                        Class<?> managerClass = managerData.getInstance(loader);
                        Class<?> current = managerClass;
                        while (current != null && current != Object.class) {
                            for (Method method : current.getDeclaredMethods()) {
                                Class<?>[] parameterTypes = method.getParameterTypes();
                                if (parameterTypes.length > 0
                                        && statusKeyClass.isAssignableFrom(parameterTypes[0])) {
                                    return method;
                                }
                            }
                            current = current.getSuperclass();
                        }
                        return null;
                    });

            if (revokeStatusMethod != null) {
                XposedBridge.hookMethod(revokeStatusMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAntiRevokeStatusEnabled()) return;
                        try {
                            Object statusKey = findArgumentOfType(param.args, statusKeyClass);
                            if (statusKey != null) {
                                if (Boolean.TRUE.equals(extractIsFromMe(statusKey))) {
                                    XposedBridge.log(TAG + " Allowing own status revoke");
                                    return;
                                }
                                String keyId = extractKeyIdFromFMessage(statusKey);
                                if (keyId != null) {
                                    long now = System.currentTimeMillis();
                                    REVOKED_MESSAGES.put(keyId, now);
                                    DelMessageStore.getInstance(context).insertMessage(null, keyId, now);
                                    com.waenhancer.xposed.features.automation.PresenceToastsHook.showDeletedMessageToast(context, null, uiHandler);
                                    XposedBridge.log(TAG + " Status bytecode revoke blocked, keyId=" + keyId);
                                }
                            } else {
                                XposedBridge.log(TAG + " Status revoke invoked without resolved status key");
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " Error in status bytecode revoke hook: " + t.getMessage());
                        }
                        Class<?> retType = ((Method) param.method).getReturnType();
                        if (retType == boolean.class || retType == Boolean.class) {
                            param.setResult(true);
                        } else if (retType == int.class || retType == Integer.class) {
                            param.setResult(0);
                        } else {
                            param.setResult(null);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked status bytecode revoke: " + revokeStatusMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking status bytecode revoke: " + t.getMessage());
        }
    }

    @Nullable
    private Object findArgumentOfType(@Nullable Object[] args, @Nullable Class<?> expectedType) {
        if (args == null || expectedType == null) return null;
        for (Object arg : args) {
            if (arg != null && expectedType.isInstance(arg)) return arg;
        }
        return null;
    }

    // ─── Database revocation hooks ───────────────────────────────────────

    private void hookDatabaseRevocation() {
        try {
            // 1. Intercept DELETE on 'message' and 'status' / 'status_v3' tables
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "delete", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String table = (String) param.args[0];
                    if (table == null) return;

                    boolean isMsgTable = "message".equals(table) || "messages".equals(table);
                    boolean isStatusTable = "status".equals(table) || "status_v3".equals(table) || table.contains("status");

                    if (!isMsgTable && !isStatusTable) return;

                    String where = (String) param.args[1];
                    String[] whereArgs = param.args.length > 2 ? (String[]) param.args[2] : null;
                    SQLiteDatabase db = (SQLiteDatabase) param.thisObject;

                    // Handle Status table deletion
                    if (isStatusTable) {
                        if (!isAntiRevokeStatusEnabled()) return;
                        try {
                            String query = "SELECT * FROM " + table + (where != null ? " WHERE " + where : "");
                            boolean foundRemoteStatus = false;
                            boolean foundOwnStatus = false;
                            boolean hasOwnershipColumn = false;
                            try (Cursor cursor = db.rawQuery(query, whereArgs)) {
                                if (cursor != null && cursor.moveToFirst()) {
                                    do {
                                        String jid = null;
                                        String keyId = null;
                                        boolean fromMe = false;
                                        for (int i = 0; i < cursor.getColumnCount(); i++) {
                                            String col = cursor.getColumnName(i).toLowerCase();
                                            if (col.equals("from_me") || col.endsWith(".from_me")) {
                                                hasOwnershipColumn = true;
                                                fromMe = cursor.getInt(i) == 1;
                                            } else if (col.contains("jid") && jid == null) {
                                                jid = cursor.getString(i);
                                            } else if ((col.contains("key_id") || col.equals("key") || col.contains("msgid")) && keyId == null) {
                                                keyId = cursor.getString(i);
                                            }
                                        }
                                        if (keyId == null && whereArgs != null && whereArgs.length > 0) {
                                            keyId = whereArgs[0];
                                        }
                                        if (fromMe) {
                                            foundOwnStatus = true;
                                            continue;
                                        }
                                        if (keyId != null) {
                                            foundRemoteStatus = true;
                                            long now = System.currentTimeMillis();
                                            REVOKED_MESSAGES.put(keyId, now);
                                            DelMessageStore.getInstance(context).insertMessage(jid, keyId, now);
                                            com.waenhancer.xposed.features.automation.PresenceToastsHook.showDeletedMessageToast(context, null, uiHandler);
                                            XposedBridge.log(TAG + " Status DB DELETE blocked & preserved: keyId=" + keyId + " jid=" + jid);
                                        }
                                    } while (cursor.moveToNext());
                                }
                            }
                            // A delete can target multiple rows. Never suppress it if an own status is
                            // included; preventing the user from deleting their status is worse than
                            // allowing a rare mixed batch to proceed.
                            if (hasOwnershipColumn && foundRemoteStatus && !foundOwnStatus) {
                                param.setResult(0);
                            }
                        } catch (Throwable t) {
                            // Ownership could not be established, so let WhatsApp perform the delete.
                            XposedBridge.log(TAG + " Error querying status before delete: " + t.getMessage());
                        }
                        return;
                    }

                    // Handle Message table deletion
                    if (isMsgTable) {
                        if (where == null || !where.contains("_id=?") || whereArgs == null || whereArgs.length == 0) return;

                        String msgId = whereArgs[0];
                        try (Cursor cursor = db.rawQuery(
                                "SELECT from_me, message_type, key_id, chat_row_id FROM " + table + " WHERE _id=?",
                                new String[]{msgId})) {
                            if (cursor != null && cursor.moveToFirst()) {
                                int fromMe = cursor.getInt(0);
                                int msgType = cursor.getInt(1);
                                String keyId = cursor.getString(2);
                                long chatRowId = cursor.getLong(3);

                                if (fromMe == 0 && msgType != 15 && keyId != null) {
                                    boolean antiRevokeMsg = isAntiRevokeEnabled();
                                    boolean antiRevokeStatus = isAntiRevokeStatusEnabled();

                                    if (antiRevokeMsg || antiRevokeStatus) {
                                        long now = System.currentTimeMillis();
                                        REVOKED_MESSAGES.put(keyId, now);
                                        DelMessageStore.getInstance(context).insertMessage(String.valueOf(chatRowId), keyId, now);
                                        dispatchRealtimeRevokeUI(keyId, now);
                                        com.waenhancer.xposed.features.automation.PresenceToastsHook.showDeletedMessageToast(context, null, uiHandler);
                                        param.setResult(0);
                                        XposedBridge.log(TAG + " Message DB DELETE blocked & UI updated, keyId=" + keyId);
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " Error intercepting message delete: " + t.getMessage());
                        }
                    }
                }
            });

            // 2. Intercept INSERT of type=15 (revocation placeholder)
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "insertWithOnConflict", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isAntiRevokeEnabled() && !isAntiRevokeStatusEnabled()) return;
                    String table = (String) param.args[0];
                    if (!"message".equals(table) && !"messages".equals(table)) return;

                    ContentValues cv = (ContentValues) param.args[2];
                    if (cv == null) return;

                    Integer msgType = cv.getAsInteger("message_type");
                    Integer fromMe = cv.getAsInteger("from_me");

                    if (msgType != null && msgType == 15 && fromMe != null && fromMe == 0) {
                        String keyId = cv.getAsString("key_id");
                        if (keyId != null) {
                            long now = System.currentTimeMillis();
                            REVOKED_MESSAGES.put(keyId, now);
                            DelMessageStore.getInstance(context).insertMessage(null, keyId, now);
                            dispatchRealtimeRevokeUI(keyId, now);
                            com.waenhancer.xposed.features.automation.PresenceToastsHook.showDeletedMessageToast(context, null, uiHandler);
                            XposedBridge.log(TAG + " DB INSERT type=15 blocked & UI updated, keyId=" + keyId);
                        }
                        param.setResult(-1L);
                    }
                }
            });

            XposedBridge.log(TAG + " Database interceptors installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook SQLiteDatabase: " + t.getMessage());
        }
    }

    /**
     * Real-time UI dispatcher: instantly updates on-screen visible chat bubble when a revocation occurs.
     */
    private void dispatchRealtimeRevokeUI(@NonNull String targetKeyId, long timestamp) {
        uiHandler.post(() -> {
            try {
                // 1. Direct row update on active ListView
                ListView lv = activeListView.get();
                if (lv != null) {
                    int count = lv.getChildCount();
                    for (int i = 0; i < count; i++) {
                        View child = lv.getChildAt(i);
                        if (child instanceof ViewGroup) {
                            String childKey = (String) XposedHelpers.getAdditionalInstanceField(child, "waex_key_id");
                            if (targetKeyId.equals(childKey)) {
                                decorateRevokedRow((ViewGroup) child, timestamp);
                                child.invalidate();
                                XposedBridge.log(TAG + " Live on-screen chat bubble updated for key: " + targetKeyId);
                                break;
                            }
                        }
                    }
                }

                // 2. Notify adapter to rebind dataset
                if (currentAdapter instanceof BaseAdapter) {
                    ((BaseAdapter) currentAdapter).notifyDataSetChanged();
                } else if (currentAdapter instanceof HeaderViewListAdapter) {
                    ListAdapter wrapped = ((HeaderViewListAdapter) currentAdapter).getWrappedAdapter();
                    if (wrapped instanceof BaseAdapter) {
                        ((BaseAdapter) wrapped).notifyDataSetChanged();
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Error in realtime UI update runnable: " + t.getMessage());
            }
        });
    }

    // ─── ListView adapter hook (main indicator path) ─────────────────────

    /**
     * Hooks ListView.setAdapter on the conversation list view (android.R.id.list).
     * On each getView call, checks if the bound FMessage is revoked and decorates accordingly.
     */
    private void hookListViewAdapter() {
        try {
            XposedHelpers.findAndHookMethod(ListView.class, "setAdapter", ListAdapter.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity currentActivity = ActivityTracker.getCurrentActivity();
                    if (currentActivity == null
                            || !currentActivity.getClass().getSimpleName().contains("Conversation")) return;

                    ListView listView = (ListView) param.thisObject;
                    if (listView == null) return;

                    activeListView = new WeakReference<>(listView);

                    // Unwrap adapter
                    ListAdapter adapter = (ListAdapter) param.args[0];
                    if (adapter == null) return;
                    if (adapter instanceof HeaderViewListAdapter) {
                        adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                    }
                    if (adapter == null) return;

                    // Unhook previous getView hook to prevent stale adapter refs
                    if (previousGetViewHook != null) {
                        try {
                            previousGetViewHook.unhook();
                        } catch (Throwable ignored) {}
                        previousGetViewHook = null;
                    }

                    currentAdapter = adapter;
                    final ListAdapter boundAdapter = adapter;

                    try {
                        Method getViewMethod = XposedHelpers.findMethodBestMatch(
                                boundAdapter.getClass(),
                                "getView",
                                int.class,
                                View.class,
                                ViewGroup.class
                        );

                        if (getViewMethod == null) {
                            XposedBridge.log(TAG + " getView method not found on " + boundAdapter.getClass().getName());
                            return;
                        }

                        previousGetViewHook = XposedBridge.hookMethod(getViewMethod, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                if (p.thisObject != currentAdapter) return;
                                if (!isAntiRevokeEnabled()) return;

                                int pos = (int) p.args[0];
                                ViewGroup row = (ViewGroup) p.getResult();
                                if (row == null) return;

                                Object item;
                                try {
                                    item = boundAdapter.getItem(pos);
                                } catch (Throwable t) {
                                    return;
                                }
                                if (item == null) return;

                                String keyId = extractKeyIdFromFMessage(item);
                                if (keyId != null) {
                                    XposedHelpers.setAdditionalInstanceField(row, "waex_key_id", keyId);
                                } else {
                                    // Rows are recycled. Never leave the previous message key attached.
                                    XposedHelpers.removeAdditionalInstanceField(row, "waex_key_id");
                                }

                                long deletedTs = getRevokedTimestamp(keyId);
                                if (deletedTs > 0) {
                                    decorateRevokedRow(row, deletedTs);
                                } else {
                                    resetRowDecoration(row);
                                }
                            }
                        });

                        XposedBridge.log(TAG + " getView hook installed on adapter: " + boundAdapter.getClass().getName());
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " Error hooking getView on " + boundAdapter.getClass().getName() + ": " + t.getMessage());
                    }
                }
            });
            XposedBridge.log(TAG + " ListView.setAdapter hook installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error setting up ListView adapter hook: " + t.getMessage());
        }
    }

    // ─── Status Playback UI Hook ─────────────────────────────────────────

    /**
     * Thread-local map: records the most recently DB-read status keyId per thread.
     * This allows reliable keyId lookup even when fragment field scanning fails.
     */
    private static final Map<Long, String> sStatusKeyByThread = new ConcurrentHashMap<>();

    private void hookStatusPlaybackUI() {
        try {
            DexSearchEngine engine = DexSearchEngine.getInstance();

            // ── 1. Resolve StatusPlaybackContactFragment class ──
            Class<?> playbackFragmentClass;
            try {
                // This class name is stable and loading it directly avoids both a false inner-class
                // match and an expensive DexKit class scan during WhatsApp startup.
                playbackFragmentClass = classLoader.loadClass(
                        "com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment");
            } catch (Throwable directLoadFailure) {
                playbackFragmentClass = engine.findClassWithCache(
                    context, classLoader, "wpp_status_playback_fragment_class_v3",
                    (bridge, loader) -> {
                        String[] patterns = {
                                "com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment",
                                "StatusPlaybackContactFragment",
                                "StatusPlaybackFragment"
                        };
                        for (String pattern : patterns) {
                            try {
                                ClassData cd = bridge.findClass(FindClass.create()
                                        .matcher(ClassMatcher.create().className(pattern, StringMatchType.Contains))
                                ).firstOrNull();
                                if (cd != null) {
                                    return cd.getInstance(loader);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
            }

            if (playbackFragmentClass != null) {
                XposedBridge.log(TAG + " Status playback fragment class resolved: " + playbackFragmentClass.getName());
            }

            // ── 2. Find status playback page-bind method via DexKit invokes & anchors ──
            final Class<?> targetFragmentClass = playbackFragmentClass;
            Method refreshSubtitleMethod = engine.findMethodWithCache(
                    // v2 is tied to the corrected fragment resolver above.
                    context, classLoader, "wpp_status_playback_refresh_subtitle_v2",
                    (bridge, loader) -> {
                        try {
                            MethodData refreshCurrentPage = bridge.findMethod(FindMethod.create()
                                    .matcher(MethodMatcher.create().usingStrings("playbackFragment/refreshCurrentPageSubTitle message is empty"))
                            ).firstOrNull();
                            if (refreshCurrentPage != null) {
                                List<MethodData> invokes = refreshCurrentPage.getInvokes();
                                if (invokes != null) {
                                    for (MethodData invoke : invokes) {
                                        try {
                                            Method m = invoke.getMethodInstance(loader);
                                            if (targetFragmentClass != null
                                                    && Modifier.isStatic(m.getModifiers())
                                                    && m.getDeclaringClass() == targetFragmentClass
                                                    && m.getParameterCount() > 1) {
                                                for (Class<?> pType : m.getParameterTypes()) {
                                                    if (pType == targetFragmentClass) {
                                                        XposedBridge.log(TAG + " Found exact status subtitle binder: " + m.getName());
                                                        return m;
                                                    }
                                                }
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }
                                return refreshCurrentPage.getMethodInstance(loader);
                            }
                        } catch (Throwable ignored) {}

                        String[] anchors = {
                                "playbackFragment/refreshCurrentPageSubTitle message is empty",
                                "StatusPlaybackContactFragment/refreshCurrentPageSubTitle",
                                "status_playback_subtitle",
                                "story_playback_page"
                        };
                        for (String anchor : anchors) {
                            try {
                                MethodData md = bridge.findMethod(FindMethod.create()
                                        .matcher(MethodMatcher.create().usingStrings(anchor))
                                ).firstOrNull();
                                if (md != null) {
                                    return md.getMethodInstance(loader);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });

            // ── 3. Find StatusPlaybackViewClass (the view container holding date & menu) ──
            Class<?> statusPlaybackViewClass = engine.findClassWithCache(
                    context, classLoader, "wpp_status_playback_view_class",
                    (bridge, loader) -> {
                        try {
                            int statusHeaderId = context.getResources().getIdentifier("status_header", "id", context.getPackageName());
                            int menuId = context.getResources().getIdentifier("menu", "id", context.getPackageName());
                            if (statusHeaderId != 0 && menuId != 0) {
                                ClassData cd = bridge.findClass(FindClass.create()
                                        .matcher(ClassMatcher.create()
                                                .addMethod(MethodMatcher.create().usingNumbers(statusHeaderId, menuId)))
                                ).firstOrNull();
                                if (cd != null) {
                                    return cd.getInstance(loader);
                                }
                            }
                        } catch (Throwable ignored) {}
                        return null;
                    });

            if (statusPlaybackViewClass != null) {
                XposedBridge.log(TAG + " Status playback view container class resolved: " + statusPlaybackViewClass.getName());
            }

            // ── 4. Hook the status playback subtitle / bind method ──
            if (refreshSubtitleMethod != null) {
                final Method finalRefresh = refreshSubtitleMethod;
                XposedBridge.hookMethod(finalRefresh,
                        buildStatusPageHook("refreshSubtitle", playbackFragmentClass, statusPlaybackViewClass));
                XposedBridge.log(TAG + " Hooked status playback method: " + finalRefresh.getName());
            } else {
                XposedBridge.log(TAG + " Status refresh method not found; using fallback hooks.");
            }

            // ── 5. Hook TARGETED lifecycle methods on the fragment class ──
            if (playbackFragmentClass != null) {
                hookStatusFragmentTargeted(playbackFragmentClass, refreshSubtitleMethod);
            }

            // ── 5. AndroidX Fragment.onResume fallback (fast class-name filter) ──
            hookAndroidXFragmentResumeFallback();

            // ── 6. Track status DB reads to capture current keyId reliably ──
            hookStatusDbQueryReads();

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking status playback UI: " + t.getMessage());
        }
    }

    /**
     * Hook only specific, named lifecycle methods on the playback fragment.
     * NEVER loops all declared methods.
     */
    private void hookStatusFragmentTargeted(Class<?> fragmentClass, @Nullable Method excludeMethod) {
        // Lifecycle method names to try (not a loop over all methods)
        String[] targetNames = {"onResume", "onStart", "onHiddenChanged", "setUserVisibleHint"};
        for (String name : targetNames) {
            try {
                Class<?> curr = fragmentClass;
                while (curr != null && curr != Object.class) {
                    try {
                        Method m = curr.getDeclaredMethod(name);
                        if (excludeMethod == null || !m.equals(excludeMethod)) {
                            XposedBridge.hookMethod(m, buildStatusPageHook(name));
                            XposedBridge.log(TAG + " Hooked status fragment." + name + " on " + curr.getSimpleName());
                        }
                        break;
                    } catch (NoSuchMethodException ignored) {
                        curr = curr.getSuperclass();
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Hook at most 2 single-int-arg methods (page-position setters) on the fragment
        int intArgCount = 0;
        for (Method m : fragmentClass.getDeclaredMethods()) {
            if (intArgCount >= 2) break;
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (excludeMethod != null && m.equals(excludeMethod)) continue;
            if (m.getParameterCount() == 1 && m.getParameterTypes()[0] == int.class) {
                try {
                    XposedBridge.hookMethod(m, buildStatusPageHookWithArg());
                    intArgCount++;
                    XposedBridge.log(TAG + " Hooked status fragment int-arg: " + m.getName());
                } catch (Throwable ignored) {}
            }
        }
        XposedBridge.log(TAG + " Targeted status fragment hooks complete (" + intArgCount + " int-arg).");
    }

    /**
     * Safety-net: hook AndroidX Fragment.onResume with a fast heuristic class-name guard.
     * Only triggers decoration for fragments whose simple class name implies status/story context.
     */
    private void hookAndroidXFragmentResumeFallback() {
        try {
            Class<?> fragmentBase = classLoader.loadClass("androidx.fragment.app.Fragment");
            XposedHelpers.findAndHookMethod(fragmentBase, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isAntiRevokeStatusEnabled()) return;
                    Object fragment = param.thisObject;
                    String simple = fragment.getClass().getSimpleName().toLowerCase();
                    if (!simple.contains("status") && !simple.contains("story") && !simple.contains("playback")) return;
                    uiHandler.postDelayed(() -> tryDecorateStatusFragment(fragment), 250);
                }
            });
            XposedBridge.log(TAG + " AndroidX Fragment.onResume status fallback hook installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AndroidX Fragment.onResume hook failed: " + t.getMessage());
        }
    }

    /**
     * Hook SQLiteDatabase.rawQuery to record the keyId of status rows being read.
     * Called before any UI update, so sStatusKeyByThread will hold the right keyId.
     */
    private void hookStatusDbQueryReads() {
        try {
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "rawQuery", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isAntiRevokeStatusEnabled()) return;
                    try {
                        String sql = (String) param.args[0];
                        if (sql == null) return;
                        String sqlLower = sql.toLowerCase();
                        if (!sqlLower.contains("status")) return;

                        android.database.Cursor cursor = (android.database.Cursor) param.getResult();
                        if (cursor == null || cursor.getCount() == 0) return;

                        // Try to find a key_id column without moving the cursor
                        int keyIdCol = cursor.getColumnIndex("key_id");
                        if (keyIdCol < 0) {
                            for (int i = 0; i < cursor.getColumnCount(); i++) {
                                if (cursor.getColumnName(i).toLowerCase().contains("key_id")) {
                                    keyIdCol = i;
                                    break;
                                }
                            }
                        }
                        if (keyIdCol < 0) return;

                        // Only peek if cursor is already positioned (not before-first)
                        if (!cursor.isBeforeFirst() && !cursor.isAfterLast()) {
                            String keyId = cursor.getString(keyIdCol);
                            if (isLikelyStatusKeyId(keyId)) {
                                sStatusKeyByThread.put(Thread.currentThread().getId(), keyId);
                                XposedBridge.log(TAG + " Tracked status keyId from DB read: " + keyId);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
            XposedBridge.log(TAG + " Status DB rawQuery tracker installed.");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error installing status DB read tracker: " + t.getMessage());
        }
    }

    /** Hook callback for DexKit-found method and named lifecycle hooks. */
    /** Hook callback for DexKit-found method and named lifecycle hooks. */
    private XC_MethodHook buildStatusPageHook(String tag) {
        return buildStatusPageHook(tag, null, null);
    }

    private XC_MethodHook buildStatusPageHook(String tag, @Nullable Class<?> fragmentClass,
                                               @Nullable Class<?> playbackViewClass) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isAntiRevokeStatusEnabled()) return;
                try {
                    // Try to inspect arguments first (especially for static helper methods like refreshCurrentPageSubTitle)
                    String keyIdFromArgs = findRevokedStatusKey(param.args);
                    Object fragmentObj = param.thisObject;

                    if (param.args != null && param.args.length > 0) {
                        for (Object arg : param.args) {
                            if (arg == null) continue;
                            if (arg instanceof View || arg instanceof Number || arg instanceof Boolean) continue;

                            // If thisObject is null (static method), check if any argument is the fragment/controller
                            if (fragmentClass != null && fragmentClass.isInstance(arg)) {
                                fragmentObj = arg;
                            } else if (fragmentObj == null) {
                                String clsName = arg.getClass().getName().toLowerCase();
                                if (clsName.contains("fragment") || clsName.contains("playback") || clsName.contains("controller")) {
                                    fragmentObj = arg;
                                }
                            }

                            if (keyIdFromArgs == null) {
                                // Generic fallback for WhatsApp variants whose binder exposes FMessage directly.
                                String extracted = extractKeyIdFromFMessage(arg);
                                if (extracted == null) extracted = extractStatusKeyIdStrict(arg);
                                if (extracted != null && getRevokedTimestamp(extracted) > 0) {
                                    keyIdFromArgs = extracted;
                                }
                            }
                        }
                    }

                    if (keyIdFromArgs != null) {
                        long ts = getRevokedTimestamp(keyIdFromArgs);
                        if (ts > 0) {
                            final String finalKey = keyIdFromArgs;
                            final long finalTs = ts;
                            final Object targetObj = fragmentObj;
                            final Object[] finalArgs = param.args;
                            uiHandler.post(() -> {
                                TextView exactDate = findStatusDateTextView(targetObj, playbackViewClass);
                                if (exactDate != null) {
                                    decorateRevokedStatusTextView(exactDate, finalTs);
                                    XposedBridge.log(TAG + " Status playback decorated from exact binder: keyId=" + finalKey);
                                } else {
                                    applyStatusDecorationFromContext(targetObj, finalArgs, finalKey, finalTs);
                                }
                            });
                            return;
                        }
                    }

                    if (fragmentObj != null) {
                        tryDecorateStatusFragment(fragmentObj);
                    }
                } catch (Throwable ignored) {}
            }
        };
    }

    /** Finds the revoked key in the binder arguments without recursively walking the UI model. */
    @Nullable
    private String findRevokedStatusKey(@Nullable Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null || arg instanceof View || arg instanceof Context) continue;
            String direct = extractKeyIdFromFMessage(arg);
            if (direct != null && getRevokedTimestamp(direct) > 0) return direct;
            try {
                Class<?> current = arg.getClass();
                while (current != null && current != Object.class) {
                    for (Field field : current.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                        field.setAccessible(true);
                        Object nested = field.get(arg);
                        if (nested == null) continue;
                        if ((statusKeyClass != null && statusKeyClass.isInstance(nested))
                                || (fMessageClass != null && fMessageClass.isInstance(nested))) {
                            String candidate = extractKeyIdFromFMessage(nested);
                            if (candidate != null && getRevokedTimestamp(candidate) > 0) return candidate;
                        } else if (statusModelClass != null && statusModelClass.isInstance(nested)
                                && statusToMessageMethod != null) {
                            Object receiver = null;
                            if (!Modifier.isStatic(statusToMessageMethod.getModifiers())) {
                                receiver = statusToMessageMapper;
                                if (receiver == null) {
                                    try {
                                        java.lang.reflect.Constructor<?> constructor =
                                                statusToMessageMethod.getDeclaringClass().getDeclaredConstructor();
                                        constructor.setAccessible(true);
                                        receiver = constructor.newInstance();
                                        statusToMessageMapper = receiver;
                                    } catch (Throwable ignored) {
                                        continue;
                                    }
                                }
                            }
                            Object message = statusToMessageMethod.invoke(receiver, nested);
                            String candidate = extractKeyIdFromFMessage(message);
                            if (candidate != null && getRevokedTimestamp(candidate) > 0) return candidate;
                        }
                    }
                    current = current.getSuperclass();
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Finds the date TextView through the exact playback-view field, as WaEnhancer does. */
    @Nullable
    private TextView findStatusDateTextView(@Nullable Object fragment, @Nullable Class<?> playbackViewClass) {
        if (fragment == null || playbackViewClass == null) return null;
        try {
            Object playbackView = null;
            Class<?> curr = fragment.getClass();
            while (curr != null && curr != Object.class && playbackView == null) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !playbackViewClass.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    playbackView = field.get(fragment);
                    if (playbackView != null) break;
                }
                curr = curr.getSuperclass();
            }
            if (playbackView == null) return null;

            int dateId = context.getResources().getIdentifier("date", "id", context.getPackageName());
            curr = playbackViewClass;
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !TextView.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    TextView textView = (TextView) field.get(playbackView);
                    if (textView != null && (dateId == 0 || textView.getId() == dateId)) return textView;
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Exact status date lookup failed: " + t.getMessage());
        }
        return null;
    }

    /** Hook callback for single-int-arg methods (page index binders). */
    private XC_MethodHook buildStatusPageHookWithArg() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isAntiRevokeStatusEnabled()) return;
                try {
                    // If any non-int argument is a status-like object, try extracting keyId from it
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg != null && !(arg instanceof Integer) && !(arg instanceof int[])) {
                                String keyId = extractStatusKeyIdStrict(arg);
                                if (keyId == null) {
                                    keyId = extractKeyIdFromFMessage(arg);
                                }
                                if (keyId != null) {
                                    long ts = getRevokedTimestamp(keyId);
                                    if (ts > 0) {
                                        final long finalTs = ts;
                                        final String finalKeyId = keyId;
                                        final Object targetObj = param.thisObject;
                                        final Object[] finalArgs = param.args;
                                        uiHandler.post(() -> applyStatusDecorationFromContext(targetObj, finalArgs, finalKeyId, finalTs));
                                        return;
                                    }
                                }
                            }
                        }
                    }
                    if (param.thisObject != null) {
                        tryDecorateStatusFragment(param.thisObject);
                    }
                } catch (Throwable ignored) {}
            }
        };
    }

    /**
     * Main status decoration entry point.
     * Tries multiple keyId sources in priority order.
     */
    private void tryDecorateStatusFragment(Object fragment) {
        try {
            // Priority 1: extract from fragment fields (status-specific strict scan)
            String keyId = extractStatusKeyIdFromFragment(fragment);

            // Priority 2: last DB-read keyId for this thread
            if (keyId == null) {
                keyId = sStatusKeyByThread.get(Thread.currentThread().getId());
            }

            // Priority 3: fall back to general FMessage extraction
            if (keyId == null) {
                keyId = extractStatusKeyFromFragmentGeneral(fragment);
            }

            if (keyId == null) return;

            long ts = getRevokedTimestamp(keyId);
            if (ts <= 0) return;

            final String finalKeyId = keyId;
            final long finalTs = ts;
            uiHandler.post(() -> applyStatusDecoration(fragment, finalKeyId, finalTs));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tryDecorateStatusFragment error: " + t.getMessage());
        }
    }

    /**
     * Attempts to find the status date/time TextView from args, fragment/controller object, or root view hierarchy.
     */
    private void applyStatusDecorationFromContext(@Nullable Object fragmentOrController, @Nullable Object[] args, @NonNull String keyId, long ts) {
        try {
            // 1. Check if date TextView can be found directly from arguments
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof TextView) {
                        TextView tv = (TextView) arg;
                        decorateRevokedStatusTextView(tv, ts);
                        XposedBridge.log(TAG + " Status playback decorated (from direct arg): keyId=" + keyId);
                        return;
                    }
                    if (arg instanceof View) {
                        TextView tv = findStatusTimestampTextViewInViewTree((View) arg);
                        if (tv != null) {
                            decorateRevokedStatusTextView(tv, ts);
                            XposedBridge.log(TAG + " Status playback decorated (from view arg): keyId=" + keyId);
                            return;
                        }
                    }
                }
            }

            // 2. Check if fragmentOrController has any View or statusPlaybackView field
            if (fragmentOrController != null) {
                TextView tv = findDateTextViewFromObjectFields(fragmentOrController);
                if (tv != null) {
                    decorateRevokedStatusTextView(tv, ts);
                    XposedBridge.log(TAG + " Status playback decorated (from controller fields): keyId=" + keyId);
                    return;
                }
                applyStatusDecoration(fragmentOrController, keyId, ts);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " applyStatusDecorationFromContext error: " + t.getMessage());
        }
    }

    /**
     * Inspects fields of an object (e.g. fragment or status playback page controller) for date TextView or View container.
     */
    @Nullable
    private TextView findDateTextViewFromObjectFields(@NonNull Object obj) {
        try {
            Class<?> curr = obj.getClass();
            int depth = 0;
            while (curr != null && curr != Object.class && depth < 4) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;

                    if (val instanceof TextView) {
                        TextView tv = (TextView) val;
                        if (isLikelyDateTextView(tv)) return tv;
                    } else if (val instanceof View) {
                        TextView tv = findStatusTimestampTextViewInViewTree((View) val);
                        if (tv != null) return tv;
                    } else if (!val.getClass().isPrimitive() && !val.getClass().isArray() && val.getClass().getName().startsWith("com.whatsapp")) {
                        // Check one level deeper (e.g. statusPlaybackView container object)
                        for (Field subField : val.getClass().getDeclaredFields()) {
                            if (Modifier.isStatic(subField.getModifiers())) continue;
                            subField.setAccessible(true);
                            Object subVal = subField.get(val);
                            if (subVal instanceof TextView) {
                                TextView subTv = (TextView) subVal;
                                if (isLikelyDateTextView(subTv)) return subTv;
                            } else if (subVal instanceof View) {
                                TextView subTv = findStatusTimestampTextViewInViewTree((View) subVal);
                                if (subTv != null) return subTv;
                            }
                        }
                    }
                }
                curr = curr.getSuperclass();
                depth++;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isLikelyDateTextView(TextView tv) {
        if (tv.getId() != View.NO_ID) {
            try {
                String entry = tv.getResources().getResourceEntryName(tv.getId()).toLowerCase();
                if (entry.contains("date") || entry.contains("time") || entry.contains("subtitle")) return true;
            } catch (Throwable ignored) {}
        }
        CharSequence text = tv.getText();
        return text != null && looksLikeTimeString(text.toString());
    }

    @Nullable
    private TextView findStatusTimestampTextViewInViewTree(@NonNull View view) {
        if (view instanceof TextView && isLikelyDateTextView((TextView) view)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            return findStatusTimestampTextView((ViewGroup) view, view.getContext());
        }
        return null;
    }

    /**
     * Apply the "Deleted" visual indicator to the status playback header TextView.
     * If view is not ready yet, retries after 300 ms.
     */
    private void applyStatusDecoration(Object fragment, String keyId, long ts) {
        try {
            View root = extractFragmentRootView(fragment);
            if (root == null) {
                // Retry once after delay — view may not be attached yet
                uiHandler.postDelayed(() -> {
                    try {
                        View r = extractFragmentRootView(fragment);
                        if (r != null) doApplyStatusDecoration(r, keyId, ts);
                        else XposedBridge.log(TAG + " Status root view still null after delay, keyId=" + keyId);
                    } catch (Throwable ignored) {}
                }, 350);
                return;
            }
            doApplyStatusDecoration(root, keyId, ts);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " applyStatusDecoration error: " + t.getMessage());
        }
    }

    private void doApplyStatusDecoration(View root, String keyId, long ts) {
        if (!(root instanceof ViewGroup)) return;
        Context ctx = root.getContext();

        TextView target = findStatusTimestampTextView((ViewGroup) root, ctx);
        if (target == null) {
            XposedBridge.log(TAG + " No timestamp TextView in status view for key=" + keyId);
            return;
        }
        decorateRevokedStatusTextView(target, ts);
        XposedBridge.log(TAG + " Status playback decorated: keyId=" + keyId);
    }

    /**
     * Finds the timestamp/date TextView in the status header using resource names then text heuristic.
     */
    @Nullable
    private TextView findStatusTimestampTextView(ViewGroup root, Context ctx) {
        // Strategy 1: by resource name
        String[] idNames = {"date", "time", "timestamp", "subtitle", "status_time", "story_time", "caption_time"};
        for (String name : idNames) {
            try {
                int id = ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
                if (id != 0) {
                    View v = root.findViewById(id);
                    if (v instanceof TextView) return (TextView) v;
                }
            } catch (Throwable ignored) {}
        }
        // Strategy 2: recursive scan for time-like text
        return findTimeTextViewRecursive(root);
    }

    @Nullable
    private TextView findTimeTextViewRecursive(ViewGroup group) {
        try {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;

                if (child instanceof TextView) {
                    TextView tv = (TextView) child;
                    // Check resource name
                    if (child.getId() != View.NO_ID) {
                        try {
                            String entry = child.getResources().getResourceEntryName(child.getId()).toLowerCase();
                            if (entry.contains("date") || entry.contains("time") || entry.contains("subtitle")) {
                                return tv;
                            }
                        } catch (Throwable ignored) {}
                    }
                    // Check text content looks like a time
                    CharSequence text = tv.getText();
                    if (text != null && looksLikeTimeString(text.toString())) return tv;
                } else if (child instanceof ViewGroup) {
                    TextView found = findTimeTextViewRecursive((ViewGroup) child);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean looksLikeTimeString(String text) {
        if (text == null || text.length() < 4 || text.length() > 20) return false;
        return text.matches(".*\\d{1,2}:\\d{2}.*") // e.g. "10:30 AM", "22:15"
                || text.equalsIgnoreCase("just now")
                || text.equalsIgnoreCase("now");
    }

    // ─── Status keyId extraction helpers ────────────────────────────────

    /**
     * Strict status keyId extraction from a fragment.
     * Scans String fields with isLikelyStatusKeyId() filter.
     * Does NOT use shared chat-message cache (cachedFMessageKeyField).
     */
    @Nullable
    private String extractStatusKeyIdFromFragment(Object fragment) {
        try {
            // 1. Check fragment arguments Bundle
            try {
                Method getArguments = fragment.getClass().getMethod("getArguments");
                Object bundle = getArguments.invoke(fragment);
                if (bundle != null) {
                    String keyId = extractKeyIdFromBundle(bundle);
                    if (keyId != null) return keyId;
                }
            } catch (Throwable ignored) {}

            // 2. Scan fragment String fields directly
            Class<?> curr = fragment.getClass();
            int depth = 0;
            while (curr != null && curr != Object.class && depth < 6) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        if (f.getType() == String.class) {
                            f.setAccessible(true);
                            String val = (String) f.get(fragment);
                            if (isLikelyStatusKeyId(val)) return val;
                        } else if (!f.getType().isPrimitive() && !f.getType().isArray()
                                && f.getType() != Context.class
                                && !View.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object nested = f.get(fragment);
                            if (nested == null) continue;
                            String keyId = extractStatusKeyIdStrict(nested);
                            if (keyId != null) return keyId;
                        }
                    } catch (Throwable ignored) {}
                }
                curr = curr.getSuperclass();
                depth++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " extractStatusKeyIdFromFragment error: " + t.getMessage());
        }
        return null;
    }

    /** Extract keyId-like strings from an Android Bundle via reflection. */
    @Nullable
    private String extractKeyIdFromBundle(Object bundle) {
        try {
            java.util.Set<?> keys = (java.util.Set<?>) bundle.getClass().getMethod("keySet").invoke(bundle);
            if (keys == null) return null;
            Method getString = bundle.getClass().getMethod("getString", String.class);
            for (Object key : keys) {
                try {
                    String val = (String) getString.invoke(bundle, key);
                    if (isLikelyStatusKeyId(val)) return val;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Strict keyId scan of one arbitrary object (not a fragment).
     * Only checks its own String fields — does not recurse further.
     */
    @Nullable
    private String extractStatusKeyIdStrict(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) {
            return isLikelyStatusKeyId((String) obj) ? (String) obj : null;
        }
        try {
            Class<?> clazz = obj.getClass();
            if (clazz.isPrimitive() || clazz.isArray()) return null;
            Class<?> curr = clazz;
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != String.class) continue;
                    try {
                        f.setAccessible(true);
                        String val = (String) f.get(obj);
                        if (isLikelyStatusKeyId(val)) return val;
                    } catch (Throwable ignored) {}
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * General (fallback) extraction via existing extractKeyIdFromFMessage.
     * Uses the shared cache which is tuned for chat messages —
     * only used as last resort for status.
     */
    @Nullable
    private String extractStatusKeyFromFragmentGeneral(Object fragment) {
        try {
            Class<?> curr = fragment.getClass();
            int depth = 0;
            while (curr != null && curr != Object.class && depth < 5) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(fragment);
                        if (val != null) {
                            String key = extractKeyIdFromFMessage(val);
                            if (key != null) return key;
                        }
                    } catch (Throwable ignored) {}
                }
                curr = curr.getSuperclass();
                depth++;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Returns true if the string matches the WA keyId pattern:
     * 1–64 alphanumeric/base64 chars, no spaces, dots, slashes, '@', or ':'.
     */
    private boolean isLikelyStatusKeyId(@Nullable String str) {
        if (str == null) return false;
        int len = str.length();
        if (len < 1 || len > 64) return false;
        if (str.contains(".") || str.contains("/") || str.contains("@")
                || str.contains(" ") || str.contains(":") || str.contains("\\")) return false;
        if (str.startsWith("com.") || str.startsWith("android") || str.startsWith("java.")) return false;
        // Must be base64/hex/numeric-like: letters, digits, +, /, =, -, _
        return str.matches("[A-Za-z0-9+/=_-]+");
    }

    @Nullable
    private String extractStatusKeyFromFragment(@NonNull Object fragment) {
        // Kept for compatibility — delegates to the new strict variant, then general
        String key = extractStatusKeyIdFromFragment(fragment);
        return key != null ? key : extractStatusKeyFromFragmentGeneral(fragment);
    }

    @Nullable
    private View extractFragmentRootView(@NonNull Object fragment) {
        try {
            Method getViewMethod = fragment.getClass().getMethod("getView");
            return (View) getViewMethod.invoke(fragment);
        } catch (Throwable ignored) {}
        try {
            Class<?> curr = fragment.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (View.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        View v = (View) f.get(fragment);
                        if (v != null) return v;
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ─── Revoke timestamp lookup ─────────────────────────────────────────

    private long getRevokedTimestamp(@Nullable String keyId) {
        if (keyId == null || keyId.isEmpty()) return 0L;
        Long ts = REVOKED_MESSAGES.get(keyId);
        if (ts != null && ts > 0) return ts;
        return DelMessageStore.getInstance(context).getTimestampByMessageId(keyId);
    }

    // ─── Row decoration ──────────────────────────────────────────────────

    private void decorateRevokedRow(@NonNull ViewGroup rowView, long deletedTimestamp) {
        try {
            Context rowContext = rowView.getContext();
            int dateId = rowContext.getResources().getIdentifier("date", "id", rowContext.getPackageName());
            int messageTextId = rowContext.getResources().getIdentifier("message_text", "id", rowContext.getPackageName());

            TextView dateTextView = dateId != 0 ? rowView.findViewById(dateId) : null;
            TextView messageTextView = messageTextId != 0 ? rowView.findViewById(messageTextId) : null;

            // Fallback: if dateTextView is not found by ID, scan rowView recursively
            if (dateTextView == null) {
                dateTextView = findDateTextViewFallback(rowView);
            }

            if (dateTextView == null) {
                return;
            }

            decorateRevokedTextView(dateTextView, messageTextView, rowContext, deletedTimestamp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " decorateRevokedRow error: " + t.getMessage());
        }
    }

    public void decorateRevokedTextView(@NonNull TextView dateTextView, @Nullable TextView messageTextView, @NonNull Context rowContext, long deletedTimestamp) {
        try {
            // Preserve original colors before mutating
            if (XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color") == null) {
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_color", dateTextView.getCurrentTextColor());
            }
            if (messageTextView != null && XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color") == null) {
                XposedHelpers.setAdditionalInstanceField(messageTextView, "waex_original_color", messageTextView.getCurrentTextColor());
            }

            int indicatorType = getIndicatorType();
            boolean colorEnabled = isColorDeletedMessagesEnabled();
            int customColor = getDeletedMessageColor();

            if (colorEnabled) {
                dateTextView.setTextColor(customColor);
                if (messageTextView != null) {
                    messageTextView.setTextColor(customColor);
                }
            } else {
                Integer origDateColor = (Integer) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color");
                if (origDateColor != null) dateTextView.setTextColor(origDateColor);
                if (messageTextView != null) {
                    Integer origMsgColor = (Integer) XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color");
                    if (origMsgColor != null) messageTextView.setTextColor(origMsgColor);
                }
            }

            // Save original date text
            String originalDate = (String) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_date");
            if (originalDate == null) {
                originalDate = dateTextView.getText().toString();
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_date", originalDate);
            }

            if (indicatorType == 1) { // Show "Deleted" text
                dateTextView.setCompoundDrawables(null, null, null, null);
                String newText = "Deleted • " + originalDate;
                dateTextView.setText(newText);
            } else { // indicatorType == 2 (Default: Show deleted icon)
                Drawable deleteDrawable = getDeletedIconDrawable(rowContext);
                if (deleteDrawable != null) {
                    deleteDrawable = deleteDrawable.mutate();
                    deleteDrawable.setTint(customColor);
                    int size = (int) (dateTextView.getTextSize() * 1.35f);
                    deleteDrawable.setBounds(0, 0, size, size);
                    dateTextView.setCompoundDrawables(null, null, deleteDrawable, null);
                    dateTextView.setCompoundDrawablePadding(8);
                    dateTextView.setText(originalDate);
                } else {
                    // Fallback to emoji indicator if vector resource fails to load
                    dateTextView.setCompoundDrawables(null, null, null, null);
                    dateTextView.setText("🚫 " + originalDate);
                }
            }

            // Clickable deletion timestamp toast
            if (deletedTimestamp > 0) {
                String formattedTime = TIME_FORMAT.format(new Date(deletedTimestamp));
                dateTextView.getPaint().setUnderlineText(true);
                dateTextView.setOnClickListener(v ->
                        Toast.makeText(rowContext, "Deleted at: " + formattedTime, Toast.LENGTH_LONG).show()
                );
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " decorateRevokedTextView error: " + t.getMessage());
        }
    }

    /** Status playback uses text deliberately; injected module drawables are not reliable in WA views. */
    private void decorateRevokedStatusTextView(@NonNull TextView dateTextView, long deletedTimestamp) {
        decorateRevokedTextView(dateTextView, null, dateTextView.getContext(), deletedTimestamp);
        try {
            String originalDate = (String) XposedHelpers.getAdditionalInstanceField(
                    dateTextView, "waex_original_date");
            if (originalDate == null || originalDate.isEmpty()) {
                originalDate = dateTextView.getText().toString();
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_date", originalDate);
            }
            dateTextView.setCompoundDrawables(null, null, null, null);
            dateTextView.setCompoundDrawablePadding(0);
            dateTextView.setText("🚫 Deleted • " + originalDate);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Status emoji decoration failed: " + t.getMessage());
        }
    }

    private void resetRowDecoration(@NonNull ViewGroup rowView) {
        try {
            Context rowContext = rowView.getContext();
            int dateId = rowContext.getResources().getIdentifier("date", "id", rowContext.getPackageName());
            int messageTextId = rowContext.getResources().getIdentifier("message_text", "id", rowContext.getPackageName());

            TextView dateTextView = dateId != 0 ? rowView.findViewById(dateId) : null;
            TextView messageTextView = messageTextId != 0 ? rowView.findViewById(messageTextId) : null;
            if (dateTextView == null) {
                dateTextView = findDateTextViewFallback(rowView);
            }
            if (dateTextView == null) return;

            Integer originalDateColor = (Integer) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color");
            if (originalDateColor != null) {
                dateTextView.setTextColor(originalDateColor);
                XposedHelpers.removeAdditionalInstanceField(dateTextView, "waex_original_color");
            }
            if (messageTextView != null) {
                Integer originalMsgColor = (Integer) XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color");
                if (originalMsgColor != null) {
                    messageTextView.setTextColor(originalMsgColor);
                    XposedHelpers.removeAdditionalInstanceField(messageTextView, "waex_original_color");
                }
            }

            String originalDate = (String) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_date");
            if (originalDate != null) {
                dateTextView.setText(originalDate);
                XposedHelpers.removeAdditionalInstanceField(dateTextView, "waex_original_date");
            }

            dateTextView.setCompoundDrawables(null, null, null, null);
            dateTextView.getPaint().setUnderlineText(false);
            dateTextView.setOnClickListener(null);
        } catch (Throwable ignored) {}
    }

    @Nullable
    private TextView findDateTextViewFallback(ViewGroup group) {
        try {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    int id = child.getId();
                    if (id != View.NO_ID) {
                        try {
                            String entryName = child.getResources().getResourceEntryName(id);
                            if (entryName != null && entryName.toLowerCase().contains("date")) {
                                return (TextView) child;
                            }
                        } catch (Throwable ignored) {}
                    }
                } else if (child instanceof ViewGroup) {
                    TextView found = findDateTextViewFallback((ViewGroup) child);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Nullable
    private Drawable getDeletedIconDrawable(@NonNull Context rowContext) {
        Drawable d = MenuIconLoader.load(rowContext, "ic_deleted");
        if (d != null) return d;
        return MenuIconLoader.load(context, "ic_deleted");
    }

    // ─── Key extraction from FMessage / FStatus objects ──────────────────

    /**
     * Reads the direction bit from an FMessage key. A null result means that
     * ownership could not be established and callers may use their normal path.
     */
    @Nullable
    private Boolean extractIsFromMe(@Nullable Object message) {
        if (message == null) return null;
        try {
            Object key = null;
            if (cachedFMessageKeyField != null
                    && cachedFMessageKeyField.getDeclaringClass().isInstance(message)) {
                try {
                    key = cachedFMessageKeyField.get(message);
                } catch (Throwable ignored) {}
            }

            if (key == null) {
                Class<?> curr = message.getClass();
                while (curr != null && curr != Object.class && key == null) {
                    for (Field field : curr.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        field.setAccessible(true);
                        Object value = field.get(message);
                        if (value == null) continue;
                        Class<?> valueClass = value.getClass();
                        if ((messageKeyClass != null && messageKeyClass.isInstance(value))
                                || looksLikeMessageKey(valueClass)) {
                            key = value;
                            break;
                        }
                    }
                    curr = curr.getSuperclass();
                }
            }

            // Some revoke methods receive the key itself rather than FMessage.
            if (key == null && ((messageKeyClass != null && messageKeyClass.isInstance(message))
                    || looksLikeMessageKey(message.getClass()))) {
                key = message;
            }
            if (key == null) return null;

            Class<?> curr = key.getClass();
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    Class<?> type = field.getType();
                    if (type == boolean.class || type == Boolean.class) {
                        field.setAccessible(true);
                        Object value = field.get(key);
                        return value instanceof Boolean ? (Boolean) value : null;
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " extractIsFromMe error: " + t.getMessage());
        }
        return null;
    }

    private boolean looksLikeMessageKey(@NonNull Class<?> candidate) {
        boolean hasBoolean = false;
        boolean hasString = false;
        Class<?> curr = candidate;
        while (curr != null && curr != Object.class) {
            for (Field field : curr.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (type == boolean.class || type == Boolean.class) hasBoolean = true;
                if (type == String.class) hasString = true;
            }
            curr = curr.getSuperclass();
        }
        return hasBoolean && hasString;
    }

    /**
     * Robust message key_id extractor.
     * Scans for the Key object within FMessage or FStatus (which has remoteJid, messageId String, isFromMe boolean).
     */
    @Nullable
    private String extractKeyIdFromFMessage(@Nullable Object fMessageObj) {
        if (fMessageObj == null) return null;
        try {
            // 1. Fast Path: Use cached fields if already discovered
            if (cachedFMessageKeyField != null && cachedKeyIdField != null
                    && cachedFMessageKeyField.getDeclaringClass().isInstance(fMessageObj)) {
                try {
                    Object keyObj = cachedFMessageKeyField.get(fMessageObj);
                    if (keyObj != null && cachedKeyIdField.getDeclaringClass().isInstance(keyObj)) {
                        String id = (String) cachedKeyIdField.get(keyObj);
                        if (id != null && !id.isEmpty()) return id;
                    }
                } catch (Throwable ignored) {
                    // Adapter items can have multiple runtime shapes; continue with discovery.
                }
            }

            Class<?> objClass = fMessageObj.getClass();

            // 2. Discover Key object inside FMessage
            Class<?> curr = objClass;
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object val = field.get(fMessageObj);
                    if (val == null) continue;

                    Class<?> valClass = val.getClass();
                    if (valClass.isPrimitive() || valClass == String.class || valClass.isArray()) continue;

                    // Inspect fields of val to determine if it is a Key object
                    String keyIdCandidate = null;
                    boolean hasBoolean = false;
                    boolean hasJidOrObject = false;

                    for (Field kField : valClass.getDeclaredFields()) {
                        if (Modifier.isStatic(kField.getModifiers())) continue;
                        kField.setAccessible(true);
                        Class<?> kType = kField.getType();

                        if (kType == String.class) {
                            String str = (String) kField.get(val);
                            if (str != null && str.length() >= 1 && !str.contains("@") && !str.contains("/")) {
                                keyIdCandidate = str;
                                cachedKeyIdField = kField;
                            }
                        } else if (kType == boolean.class || kType == Boolean.class) {
                            hasBoolean = true;
                        } else if (!kType.isPrimitive()) {
                            hasJidOrObject = true;
                        }
                    }

                    if (keyIdCandidate != null && (hasBoolean || hasJidOrObject)) {
                        cachedFMessageKeyField = field;
                        return keyIdCandidate;
                    }
                }
                curr = curr.getSuperclass();
            }

            // 3. Direct String field scan fallback
            curr = objClass;
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (field.getType() == String.class) {
                        field.setAccessible(true);
                        String strVal = (String) field.get(fMessageObj);
                        if (strVal != null && strVal.length() >= 1 && !strVal.contains("@") && isLikelyStatusKeyId(strVal)) {
                            return strVal;
                        }
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " extractKeyIdFromFMessage error: " + t.getMessage());
        }
        return null;
    }

    // ─── Preference helpers ──────────────────────────────────────────────

    private boolean isAntiRevokeEnabled() {
        try {
            if (prefs.contains("anti_revoke")) {
                Object val = prefs.getAll().get("anti_revoke");
                if (val instanceof Boolean) return (Boolean) val;
                if (val instanceof String) return !"0".equals(val) && !"false".equalsIgnoreCase((String) val);
                if (val instanceof Number) return ((Number) val).intValue() != 0;
            }
        } catch (Throwable ignored) {}
        return isEnabled("anti_revoke", false);
    }

    private boolean isAntiRevokeStatusEnabled() {
        try {
            if (prefs.contains("antirevokestatus")) {
                Object val = prefs.getAll().get("antirevokestatus");
                if (val instanceof Boolean) return (Boolean) val;
                if (val instanceof String) return !"0".equals(val) && !"false".equalsIgnoreCase((String) val);
                if (val instanceof Number) return ((Number) val).intValue() != 0;
            }
        } catch (Throwable ignored) {}
        return isEnabled("antirevokestatus", false);
    }

    private boolean isColorDeletedMessagesEnabled() {
        try {
            if (prefs.contains("anti_revoke_color_enabled")) {
                Object val = prefs.getAll().get("anti_revoke_color_enabled");
                if (val instanceof Boolean) return (Boolean) val;
                if (val instanceof String) return !"0".equals(val) && !"false".equalsIgnoreCase((String) val);
                if (val instanceof Number) return ((Number) val).intValue() != 0;
            }
        } catch (Throwable ignored) {}
        return isEnabled("anti_revoke_color_enabled", true);
    }

    private int getDeletedMessageColor() {
        try {
            String colorStr = prefs.getString("deleted_message_color", "#EF4444");
            if (colorStr != null && !colorStr.isEmpty()) {
                return Color.parseColor(colorStr);
            }
        } catch (Throwable ignored) {
            try {
                return prefs.getInt("deleted_message_color", Color.parseColor("#EF4444"));
            } catch (Throwable ignored2) {}
        }
        return Color.parseColor("#EF4444");
    }

    private int getIndicatorType() {
        try {
            String strVal = prefs.getString("anti_revoke_indicator", "2");
            return Integer.parseInt(strVal);
        } catch (Throwable ignored) {
            return prefs.getInt("anti_revoke_indicator", 2);
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Anti-Revoke";
    }
}
