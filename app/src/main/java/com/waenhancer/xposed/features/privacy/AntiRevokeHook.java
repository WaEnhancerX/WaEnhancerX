package com.waenhancer.xposed.features.privacy;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.waenhancer.app.R;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.db.DelMessageStore;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.utils.ActivityTracker;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Anti-Revoke & Deleted Indicator Engine.
 *
 * Strategy (aligned with WaEnhancer):
 *  1. Block SQLite DELETE on 'message' table and INSERT of message_type=15 placeholders.
 *  2. Block bytecode revocation calls.
 *  3. Persist revoked message key_ids in DelMessageStore for cross-session survival.
 *  4. Decorate revoked messages via ListView.setAdapter → getView hook,
 *     filtered to Conversation activity + android.R.id.list only.
 */
public class AntiRevokeHook extends BaseFeature {

    private static final String TAG = "[WAEX][AntiRevoke]";

    private static final Map<String, Long> REVOKED_MESSAGES = new ConcurrentHashMap<>();
    private static final DateFormat TIME_FORMAT = DateFormat.getTimeInstance(DateFormat.SHORT);

    /** Resolved FMessage Key class and its String field that holds the message ID. */
    private Class<?> messageKeyClass;
    private Field keyIdField;

    /** Track previous getView hook so we can unhook on adapter change. */
    private volatile XC_MethodHook.Unhook previousGetViewHook;
    /** Track the currently hooked adapter to guard against stale callbacks. */
    private volatile ListAdapter currentAdapter;

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
                // Find the String field that holds the message key_id.
                // WhatsApp Key class has 3 fields: String (remoteJid), String (messageId), boolean (fromMe).
                // The message ID is the longer one and does NOT contain '@'.
                // We resolve it by checking all String fields.
                Field candidate = null;
                for (Field f : messageKeyClass.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == String.class) {
                        // Pick the LAST String field — in obfuscated WhatsApp,
                        // field order is typically: remoteJid, messageId.
                        // But we can't be sure at init time, so we'll resolve dynamically at extraction.
                        if (candidate == null) {
                            candidate = f;
                        } else {
                            // Store first as keyIdField, but we'll do runtime resolution in extractKeyIdFromFMessage
                            keyIdField = f;
                        }
                    }
                }
                // If only one String field, use it
                if (keyIdField == null && candidate != null) {
                    keyIdField = candidate;
                }
                XposedBridge.log(TAG + " Key class: " + messageKeyClass.getName()
                        + ", keyIdField: " + (keyIdField != null ? keyIdField.getName() : "null"));
            } else {
                XposedBridge.log(TAG + " WARNING: Could not resolve messageKeyClass.");
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
            } else {
                XposedBridge.log(TAG + " WARNING: Bytecode revoke method not found.");
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
                                param.setResult(0);
                                XposedBridge.log(TAG + " DB DELETE blocked, keyId=" + keyId);
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
                            XposedBridge.log(TAG + " DB INSERT type=15 blocked, keyId=" + keyId);
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

    // ─── ListView adapter hook (main indicator path) ─────────────────────

    /**
     * Hooks ListView.setAdapter, filtered to the Conversation activity and android.R.id.list.
     * On each getView call, checks if the bound FMessage is revoked and decorates accordingly.
     * Follows WaEnhancer's ConversationItemListener strategy exactly.
     */
    private void hookListViewAdapter() {
        try {
            XposedHelpers.findAndHookMethod(ListView.class, "setAdapter", ListAdapter.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    // ── Guard 1: Only in Conversation activity ──
                    Activity currentAct = ActivityTracker.getCurrentActivity();
                    if (currentAct == null) return;
                    String actName = currentAct.getClass().getSimpleName();
                    if (!actName.equals("Conversation") && !actName.contains("Conversation")) return;

                    // ── Guard 2: Only the main message list (android.R.id.list) ──
                    if (((ListView) param.thisObject).getId() != android.R.id.list) return;

                    // ── Unwrap adapter ──
                    ListAdapter adapter = (ListAdapter) param.args[0];
                    if (adapter instanceof HeaderViewListAdapter) {
                        adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                    }
                    if (adapter == null) return;

                    // ── Unhook previous getView hook to prevent stale adapter refs ──
                    if (previousGetViewHook != null) {
                        previousGetViewHook.unhook();
                        previousGetViewHook = null;
                    }

                    currentAdapter = adapter;
                    final ListAdapter boundAdapter = adapter;

                    try {
                        Method getViewMethod = boundAdapter.getClass().getDeclaredMethod(
                                "getView", int.class, View.class, ViewGroup.class);

                        previousGetViewHook = XposedBridge.hookMethod(getViewMethod, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                // Guard: only process if this is still the current adapter
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
                                long deletedTs = getRevokedTimestamp(keyId);
                                if (deletedTs > 0) {
                                    decorateRevokedRow(row, deletedTs);
                                } else {
                                    resetRowDecoration(row);
                                }
                            }
                        });

                        XposedBridge.log(TAG + " getView hook installed on adapter: " + boundAdapter.getClass().getName());
                    } catch (NoSuchMethodException e) {
                        // getView not declared in this adapter class, try all methods
                        try {
                            for (Method m : boundAdapter.getClass().getDeclaredMethods()) {
                                if (m.getName().equals("getView")) {
                                    previousGetViewHook = XposedBridge.hookMethod(m, new XC_MethodHook() {
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
                                            long deletedTs = getRevokedTimestamp(keyId);
                                            if (deletedTs > 0) {
                                                decorateRevokedRow(row, deletedTs);
                                            } else {
                                                resetRowDecoration(row);
                                            }
                                        }
                                    });
                                    XposedBridge.log(TAG + " getView hook (fallback) installed on: " + boundAdapter.getClass().getName());
                                    break;
                                }
                            }
                        } catch (Throwable ignored) {}
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " Error hooking getView: " + t.getMessage());
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
        if (keyId == null) return 0L;
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

            if (dateTextView == null) return;

            // Preserve original colors before mutating
            if (XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color") == null) {
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_color", dateTextView.getCurrentTextColor());
            }
            if (messageTextView != null && XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color") == null) {
                XposedHelpers.setAdditionalInstanceField(messageTextView, "waex_original_color", messageTextView.getCurrentTextColor());
            }

            int indicatorType = getIndicatorType();

            // Set red color accent
            dateTextView.setTextColor(Color.parseColor("#EF4444"));
            if (messageTextView != null) {
                messageTextView.setTextColor(Color.parseColor("#EF4444"));
            }

            if (indicatorType == 1) { // Show Text
                String originalDate = (String) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_date");
                if (originalDate == null) {
                    originalDate = dateTextView.getText().toString();
                    XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_date", originalDate);
                }
                if (!originalDate.startsWith("Deleted")) {
                    dateTextView.setText("Deleted • " + originalDate);
                }
            } else if (indicatorType == 2) { // Show Icon
                // Clear any previous text indicator first
                dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                try {
                    Context moduleContext = rowContext.createPackageContext("com.waenhancer", Context.CONTEXT_IGNORE_SECURITY);
                    Drawable deleteDrawable = ContextCompat.getDrawable(moduleContext, R.drawable.ic_deleted);
                    if (deleteDrawable != null) {
                        int size = (int) (dateTextView.getTextSize() * 1.2f);
                        deleteDrawable.setBounds(0, 0, size, size);
                        dateTextView.setCompoundDrawables(null, null, deleteDrawable, null);
                        dateTextView.setCompoundDrawablePadding(5);
                    } else {
                        dateTextView.setText("🚫 " + dateTextView.getText());
                    }
                } catch (Throwable t) {
                    // Fallback: emoji indicator
                    dateTextView.setText("🚫 " + dateTextView.getText());
                }
            }

            // Clickable deletion timestamp toast
            if (deletedTimestamp > 0) {
                String formattedTime = TIME_FORMAT.format(new Date(deletedTimestamp));
                dateTextView.getPaint().setUnderlineText(true);
                dateTextView.setOnClickListener(v ->
                        Toast.makeText(rowContext, "Message deleted at: " + formattedTime, Toast.LENGTH_LONG).show()
                );
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error decorating revoked row: " + t.getMessage());
        }
    }

    private void resetRowDecoration(@NonNull ViewGroup rowView) {
        try {
            Context rowContext = rowView.getContext();
            int dateId = rowContext.getResources().getIdentifier("date", "id", rowContext.getPackageName());
            int messageTextId = rowContext.getResources().getIdentifier("message_text", "id", rowContext.getPackageName());

            TextView dateTextView = dateId != 0 ? rowView.findViewById(dateId) : null;
            TextView messageTextView = messageTextId != 0 ? rowView.findViewById(messageTextId) : null;
            if (dateTextView == null) return;

            Integer originalDateColor = (Integer) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color");
            if (originalDateColor != null) {
                dateTextView.setTextColor(originalDateColor);
            }
            if (messageTextView != null) {
                Integer originalMsgColor = (Integer) XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color");
                if (originalMsgColor != null) {
                    messageTextView.setTextColor(originalMsgColor);
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

    // ─── Key extraction from FMessage objects ────────────────────────────

    /**
     * Extracts the message key_id from an FMessage object.
     * Tries multiple strategies to handle obfuscated field names.
     * The key_id is a String that does NOT contain '@' (distinguishing it from a JID).
     */
    private String extractKeyIdFromFMessage(Object fMessageObj) {
        if (fMessageObj == null) return null;
        try {
            // Strategy 1: Direct resolution via known Key class
            if (messageKeyClass != null) {
                Object keyObject = findKeyObjectInInstance(fMessageObj);
                if (keyObject != null) {
                    return extractMessageIdFromKey(keyObject);
                }
            }

            // Strategy 2: Scan all fields for an object whose class name contains "Key"
            Class<?> curr = fMessageObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object val = field.get(fMessageObj);
                    if (val != null && val.getClass().getSimpleName().contains("Key")) {
                        String id = extractMessageIdFromKey(val);
                        if (id != null) return id;
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " extractKeyId error: " + t.getMessage());
        }
        return null;
    }

    /**
     * Finds the Key field within an FMessage instance using the resolved messageKeyClass.
     */
    @Nullable
    private Object findKeyObjectInInstance(Object fMessageObj) {
        try {
            Class<?> curr = fMessageObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (f.getType() == messageKeyClass) {
                        f.setAccessible(true);
                        return f.get(fMessageObj);
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Extracts the message ID from a Key object.
     * The message ID is a String field that does NOT contain '@' (which would indicate a JID).
     * It's typically >= 12 chars (WhatsApp message IDs are hex strings).
     */
    private String extractMessageIdFromKey(Object keyObj) {
        if (keyObj == null) return null;
        try {
            // Collect all String fields in the Key object
            String bestCandidate = null;
            for (Field f : keyObj.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                String val = (String) f.get(keyObj);
                if (val == null || val.isEmpty()) continue;

                // Skip JIDs (contain '@')
                if (val.contains("@")) continue;

                // A message ID is typically a long hex/alphanumeric string (>= 12 chars)
                if (val.length() >= 12) {
                    return val; // Best match: long non-JID string
                }

                // Keep shorter candidates as fallback
                if (val.length() >= 6 && bestCandidate == null) {
                    bestCandidate = val;
                }
            }
            return bestCandidate;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " extractMessageIdFromKey error: " + t.getMessage());
        }
        return null;
    }

    // ─── Preference helpers ──────────────────────────────────────────────

    private boolean isAntiRevokeEnabled() {
        return isEnabled("anti_revoke", false);
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

