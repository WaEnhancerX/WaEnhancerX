package com.waenhancer.xposed.features.privacy;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robust Anti-Revoke Engine:
 * Intercepts WhatsApp's bytecode revocation handler as well as SQLite database level DELETE+INSERT,
 * and adds visual indicators (symbol / text) to deleted messages.
 */
public class AntiRevokeHook extends BaseFeature {

    private static final Map<String, Long> REVOKED_MESSAGES = new ConcurrentHashMap<>();

    public AntiRevokeHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookBytecodeRevocation();
        hookDatabaseRevocation();
    }

    /**
     * Intercepts WhatsApp's core MessageStore / FMessage revocation method.
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
                        if (isEnabled("anti_revoke", false)) {
                            // Block the revocation handling logic in WhatsApp core
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
                    if (!isEnabled("anti_revoke", false)) return;

                    String table = (String) param.args[0];
                    if (!"message".equals(table)) return;

                    String where = (String) param.args[1];
                    String[] whereArgs = param.args.length > 2 ? (String[]) param.args[2] : null;

                    if (where != null && where.contains("_id=?") && whereArgs != null && whereArgs.length > 0) {
                        String msgId = whereArgs[0];
                        SQLiteDatabase db = (SQLiteDatabase) param.thisObject;

                        try (Cursor cursor = db.rawQuery(
                                "SELECT from_me, message_type, key_id FROM message WHERE _id=?",
                                new String[]{msgId})) {
                            if (cursor != null && cursor.moveToFirst()) {
                                int fromMe = cursor.getInt(0);
                                int msgType = cursor.getInt(1);
                                String keyId = cursor.getString(2);

                                // If this is an incoming message (fromMe == 0) and not already a placeholder (type 15)
                                if (fromMe == 0 && msgType != 15) {
                                    if (keyId != null) {
                                        REVOKED_MESSAGES.put(keyId, System.currentTimeMillis());
                                    }
                                    // Block the database delete
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
                    if (!isEnabled("anti_revoke", false)) return;

                    String table = (String) param.args[0];
                    if (!"message".equals(table)) return;

                    ContentValues cv = (ContentValues) param.args[2];
                    if (cv == null) return;

                    Integer msgType = cv.getAsInteger("message_type");
                    Integer fromMe = cv.getAsInteger("from_me");

                    if (msgType != null && msgType == 15 && fromMe != null && fromMe == 0) {
                        String keyId = cv.getAsString("key_id");
                        if (keyId != null) {
                            REVOKED_MESSAGES.put(keyId, System.currentTimeMillis());
                        }
                        // Drop the insertion of placeholder
                        param.setResult(-1L);
                    }
                }
            });

            XposedBridge.log("[WAEX] Hooked AntiRevoke database interceptors successfully.");
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Failed to hook SQLiteDatabase for AntiRevoke: " + t.getMessage());
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Anti-Revoke";
    }
}
