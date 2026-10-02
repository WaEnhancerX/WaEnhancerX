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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-Revoke & Deleted Indicator Engine.
 *
 * Strategy:
 *  1. Block SQLite DELETE on 'message' table and INSERT of message_type=15 placeholders.
 *  2. Block bytecode revocation calls.
 *  3. Persist revoked message key_ids in DelMessageStore for cross-session survival.
 *  4. Decorate revoked messages via ListView.setAdapter -> getView hook on android.R.id.list.
 *  5. Render visual indicator (custom deleted vector icon or "Deleted" text) and colored text.
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
        hookDatabaseRevocation();
        hookListViewAdapter();
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
                        if (!isAntiRevokeEnabled()) return;
                        try {
                            Object fMessage = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                            if (fMessage != null) {
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

    // ─── Database revocation hooks ───────────────────────────────────────

    private void hookDatabaseRevocation() {
        try {
            // 1. Intercept DELETE on 'message' table
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "delete", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isAntiRevokeEnabled()) return;
                    String table = (String) param.args[0];
                    if (!"message".equals(table)) return;

                    String where = (String) param.args[1];
                    String[] whereArgs = param.args.length > 2 ? (String[]) param.args[2] : null;
                    if (where == null || !where.contains("_id=?") || whereArgs == null || whereArgs.length == 0) return;

                    String msgId = whereArgs[0];
                    SQLiteDatabase db = (SQLiteDatabase) param.thisObject;

                    try (Cursor cursor = db.rawQuery(
                            "SELECT from_me, message_type, key_id, chat_row_id FROM message WHERE _id=?",
                            new String[]{msgId})) {
                        if (cursor != null && cursor.moveToFirst()) {
                            int fromMe = cursor.getInt(0);
                            int msgType = cursor.getInt(1);
                            String keyId = cursor.getString(2);
                            long chatRowId = cursor.getLong(3);

                            if (fromMe == 0 && msgType != 15 && keyId != null) {
                                long now = System.currentTimeMillis();
                                REVOKED_MESSAGES.put(keyId, now);
                                DelMessageStore.getInstance(context).insertMessage(String.valueOf(chatRowId), keyId, now);
                                dispatchRealtimeRevokeUI(keyId, now);
                                com.waenhancer.xposed.features.automation.PresenceToastsHook.showDeletedMessageToast(context, null, uiHandler);
                                param.setResult(0);
                                XposedBridge.log(TAG + " DB DELETE blocked & UI updated, keyId=" + keyId);
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " Error intercepting message delete: " + t.getMessage());
                    }
                }
            });

            // 2. Intercept INSERT of type=15 (revocation placeholder)
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "insertWithOnConflict", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isAntiRevokeEnabled()) return;
                    String table = (String) param.args[0];
                    if (!"message".equals(table)) return;

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
                    ListView listView = (ListView) param.thisObject;
                    if (listView == null || listView.getId() != android.R.id.list) return;

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

                                // Guard: skip non-FMessage items if fMessageClass is known
                                if (fMessageClass != null && !fMessageClass.isInstance(item)) {
                                    return;
                                }

                                String keyId = extractKeyIdFromFMessage(item);
                                if (keyId != null) {
                                    XposedHelpers.setAdditionalInstanceField(row, "waex_key_id", keyId);
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
            XposedBridge.log(TAG + " decorateRevokedRow error: " + t.getMessage());
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

    // ─── Key extraction from FMessage objects ────────────────────────────

    /**
     * Robust message key_id extractor.
     * Scans for the Key object within FMessage (which has remoteJid, messageId String, isFromMe boolean).
     */
    @Nullable
    private String extractKeyIdFromFMessage(@Nullable Object fMessageObj) {
        if (fMessageObj == null) return null;
        try {
            // 1. Fast Path: Use cached fields if already discovered
            if (cachedFMessageKeyField != null && cachedKeyIdField != null) {
                Object keyObj = cachedFMessageKeyField.get(fMessageObj);
                if (keyObj != null) {
                    String id = (String) cachedKeyIdField.get(keyObj);
                    if (id != null && !id.isEmpty()) return id;
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
                            if (str != null && str.length() >= 6 && !str.contains("@")) {
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
                        if (strVal != null && strVal.length() >= 12 && !strVal.contains("@")) {
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
