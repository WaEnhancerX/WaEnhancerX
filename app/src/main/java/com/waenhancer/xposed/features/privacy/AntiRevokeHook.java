package com.waenhancer.xposed.features.privacy;

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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robust Anti-Revoke & Deleted Indicator Engine:
 * 1. Blocks SQLite message deletion and placeholder (type 15) insertion strictly when anti_revoke is enabled.
 * 2. Blocks core bytecode revocation events across all WhatsApp versions.
 * 3. Persists revoked messages in DelMessageStore so indicators persist across app reloads and chat reopenings.
 * 4. Decorates preserved deleted messages via ConversationRow / ListView binding:
 *    - Option 1: "Deleted" text indicator
 *    - Option 2: Deleted icon indicator
 *    - Clickable deletion timestamp Toast
 *    - Clean reset on recycled rows
 */
public class AntiRevokeHook extends BaseFeature {

    private static final Map<String, Long> REVOKED_MESSAGES = new ConcurrentHashMap<>();
    private static final DateFormat TIME_FORMAT = DateFormat.getTimeInstance(DateFormat.SHORT);

    private Class<?> fMessageClass;
    private Class<?> messageKeyClass;
    private Field keyIdField;
    private Field keyFromMeField;

    public AntiRevokeHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        initMessageClasses();
        hookBytecodeRevocation();
        hookDatabaseRevocation();
        hookConversationRowDirect();
        hookListViewAdapter();
    }

    private void initMessageClasses() {
        try {
            messageKeyClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_message_key_class",
                    (bridge, loader) -> {
                        ClassDataList list = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().fieldCount(3)
                                        .addMethod(MethodMatcher.create().addUsingString("Key", StringMatchType.Contains).name("toString"))));
                        for (ClassData cd : list) {
                            return cd.getInstance(loader);
                        }
                        return null;
                    }
            );

            if (messageKeyClass != null) {
                for (Field f : messageKeyClass.getDeclaredFields()) {
                    if (f.getType() == String.class && keyIdField == null) {
                        f.setAccessible(true);
                        keyIdField = f;
                    } else if (f.getType() == boolean.class && keyFromMeField == null) {
                        f.setAccessible(true);
                        keyFromMeField = f;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error initializing message key classes: " + t.getMessage());
        }
    }

    /**
     * Intercepts WhatsApp's core MessageStore / FMessage revocation methods.
     */
    private void hookBytecodeRevocation() {
        try {
            Method revokeMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_core_msgstore_revoke",
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
                                    if (m.getParameterCount() >= 1) {
                                        return m;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    }
            );

            if (revokeMethod != null) {
                XposedBridge.hookMethod(revokeMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isAntiRevokeEnabled()) {
                            try {
                                Object fMessage = param.args != null && param.args.length > 0 ? param.args[0] : null;
                                if (fMessage != null) {
                                    String keyId = extractKeyIdFromFMessage(fMessage);
                                    if (keyId != null) {
                                        long now = System.currentTimeMillis();
                                        REVOKED_MESSAGES.put(keyId, now);
                                        DelMessageStore.getInstance(context).insertMessage(null, keyId, now);
                                    }
                                }
                            } catch (Throwable ignored) {}

                            param.setResult(true);
                        }
                    }
                });
                XposedBridge.log("[WAEX] Hooked Bytecode AntiRevoke successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error hooking Bytecode AntiRevoke: " + t.getMessage());
        }
    }

    /**
     * Intercepts SQLite database operations to block WhatsApp from deleting incoming messages.
     */
    private void hookDatabaseRevocation() {
        try {
            // 1. Intercept DELETE calls on 'message' table
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "delete", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isAntiRevokeEnabled()) return;

                    String table = (String) param.args[0];
                    if (!"message".equals(table)) return;

                    String where = (String) param.args[1];
                    String[] whereArgs = param.args.length > 2 ? (String[]) param.args[2] : null;

                    if (where != null && where.contains("_id=?") && whereArgs != null && whereArgs.length > 0) {
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

                                if (fromMe == 0 && msgType != 15) {
                                    if (keyId != null) {
                                        long now = System.currentTimeMillis();
                                        REVOKED_MESSAGES.put(keyId, now);
                                        DelMessageStore.getInstance(context).insertMessage(String.valueOf(chatRowId), keyId, now);
                                    }
                                    // Block database delete
                                    param.setResult(0);
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[WAEX] Error intercepting message delete: " + t.getMessage());
                        }
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
                        }
                        // Drop insertion of placeholder
                        param.setResult(-1L);
                    }
                }
            });

            XposedBridge.log("[WAEX] Hooked AntiRevoke database interceptors successfully.");
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Failed to hook SQLiteDatabase for AntiRevoke: " + t.getMessage());
        }
    }

    /**
     * Hooks ConversationRow classes directly to decorate message bubbles.
     */
    private void hookConversationRowDirect() {
        try {
            int participantHeaderId = context.getResources().getIdentifier("conversation_row_participant_header_view_stub", "id", context.getPackageName());
            int dateId = context.getResources().getIdentifier("date", "id", context.getPackageName());

            Class<?> conversationRowClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_conversation_row_class",
                    (bridge, loader) -> {
                        List<Integer> ids = new ArrayList<>();
                        if (participantHeaderId > 0) ids.add(participantHeaderId);
                        if (dateId > 0) ids.add(dateId);

                        if (!ids.isEmpty()) {
                            ClassDataList candidates = bridge.findClass(
                                    FindClass.create().matcher(
                                            ClassMatcher.create().addMethod(
                                                    MethodMatcher.create().usingNumbers(ids)
                                            )
                                    )
                            );
                            for (var cData : candidates) {
                                Class<?> cls = cData.getInstance(loader);
                                if (ViewGroup.class.isAssignableFrom(cls) && !cls.isInterface()) {
                                    return cls;
                                }
                            }
                        }
                        return null;
                    }
            );

            if (conversationRowClass != null) {
                for (Method method : conversationRowClass.getDeclaredMethods()) {
                    Class<?>[] pTypes = method.getParameterTypes();
                    if (pTypes.length >= 1 && !pTypes[0].isPrimitive()) {
                        XposedBridge.hookMethod(method, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!isAntiRevokeEnabled()) return;
                                Object fMsg = param.args[0];
                                String keyId = extractKeyIdFromFMessage(fMsg);
                                if (param.thisObject instanceof ViewGroup) {
                                    ViewGroup row = (ViewGroup) param.thisObject;
                                    long deletedTs = getRevokedTimestamp(keyId);
                                    if (deletedTs > 0) {
                                        decorateRevokedRow(row, deletedTs);
                                    } else {
                                        resetRowDecoration(row);
                                    }
                                }
                            }
                        });
                    }
                }
                XposedBridge.log("[WAEX] Hooked ConversationRow directly for AntiRevoke indicator.");
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error hooking ConversationRow directly: " + t.getMessage());
        }
    }

    /**
     * Fallback: Hooks ListView adapter to decorate revoked messages in chat.
     */
    private void hookListViewAdapter() {
        try {
            XposedHelpers.findAndHookMethod(ListView.class, "setAdapter", ListAdapter.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ListAdapter adapter = (ListAdapter) param.args[0];
                    if (adapter instanceof HeaderViewListAdapter) {
                        adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                    }
                    if (adapter == null) return;

                    final ListAdapter finalAdapter = adapter;
                    try {
                        Method[] methods = finalAdapter.getClass().getDeclaredMethods();
                        for (Method m : methods) {
                            if (m.getName().equals("getView")) {
                                XposedBridge.hookMethod(m, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam p) {
                                        if (!isAntiRevokeEnabled()) return;
                                        int pos = (int) p.args[0];
                                        ViewGroup row = (ViewGroup) p.getResult();
                                        if (row == null) return;

                                        Object item = finalAdapter.getItem(pos);
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
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error setting up ListView adapter hook: " + t.getMessage());
        }
    }

    private long getRevokedTimestamp(@Nullable String keyId) {
        if (keyId == null) return 0L;
        Long ts = REVOKED_MESSAGES.get(keyId);
        if (ts != null && ts > 0) return ts;
        return DelMessageStore.getInstance(context).getTimestampByMessageId(keyId);
    }

    private void decorateRevokedRow(@NonNull ViewGroup rowView, long deletedTimestamp) {
        try {
            Context rowContext = rowView.getContext();
            int dateId = rowContext.getResources().getIdentifier("date", "id", rowContext.getPackageName());
            int messageTextId = rowContext.getResources().getIdentifier("message_text", "id", rowContext.getPackageName());

            TextView dateTextView = dateId != 0 ? rowView.findViewById(dateId) : null;
            TextView messageTextView = messageTextId != 0 ? rowView.findViewById(messageTextId) : null;

            if (dateTextView == null) return;

            // Preserve original colors and text before mutating
            if (XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color") == null) {
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_color", dateTextView.getCurrentTextColor());
            }
            if (messageTextView != null && XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color") == null) {
                XposedHelpers.setAdditionalInstanceField(messageTextView, "waex_original_color", messageTextView.getCurrentTextColor());
            }

            int indicatorType = getIndicatorType();

            // Set red color accent for deleted message
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
                try {
                    Context moduleContext = rowContext.createPackageContext("com.waenhancer", Context.CONTEXT_IGNORE_SECURITY);
                    Drawable deleteDrawable = ContextCompat.getDrawable(moduleContext, R.drawable.ic_deleted);
                    if (deleteDrawable != null) {
                        dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, deleteDrawable, null);
                        dateTextView.setCompoundDrawablePadding(8);
                    } else {
                        dateTextView.setText("🚫 " + dateTextView.getText());
                    }
                } catch (Throwable t) {
                    dateTextView.setText("🚫 " + dateTextView.getText());
                }
            }

            // Clickable timestamp toast
            if (deletedTimestamp > 0) {
                String formattedTime = TIME_FORMAT.format(new Date(deletedTimestamp));
                dateTextView.setOnClickListener(v -> {
                    Toast.makeText(rowContext, "Message deleted at: " + formattedTime, Toast.LENGTH_LONG).show();
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error decorating revoked row: " + t.getMessage());
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

            dateTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            dateTextView.setOnClickListener(null);
        } catch (Throwable ignored) {}
    }

    private String extractKeyIdFromFMessage(Object fMessageObj) {
        if (fMessageObj == null) return null;
        try {
            // Direct reflection using resolved fields
            if (messageKeyClass != null && keyIdField != null) {
                for (Field f : fMessageObj.getClass().getDeclaredFields()) {
                    if (f.getType() == messageKeyClass) {
                        f.setAccessible(true);
                        Object key = f.get(fMessageObj);
                        if (key != null) {
                            return (String) keyIdField.get(key);
                        }
                    }
                }
            }

            // Fallback scan fields on instance and superclasses
            Class<?> curr = fMessageObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object val = field.get(fMessageObj);
                    if (val != null && val.getClass().getSimpleName().contains("Key")) {
                        String id = extractIdFromKeyObject(val);
                        if (id != null) return id;
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractIdFromKeyObject(Object keyObj) {
        if (keyObj == null) return null;
        try {
            if (keyIdField != null) {
                return (String) keyIdField.get(keyObj);
            }
            for (Field f : keyObj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == String.class) {
                    String name = f.getName();
                    if (name.equals("id") || name.equals("keyId") || name.equals("A01") || name.equals("A00")) {
                        String strVal = (String) f.get(keyObj);
                        if (strVal != null && strVal.length() >= 8) {
                            return strVal;
                        }
                    }
                }
            }
            for (Field f : keyObj.getClass().getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == String.class) {
                    String strVal = (String) f.get(keyObj);
                    if (strVal != null && strVal.length() >= 12 && !strVal.contains("@")) {
                        return strVal;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

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
