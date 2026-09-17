package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
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
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Locked Chats Enhancer Hook:
 * Enhances WhatsApp's native locked chats vault by:
 * 1. Suppressing notification previews/alerts for locked chats.
 * 2. Hiding locked contacts from appearing in contact pickers.
 */
public class LockedChatsEnhancerHook extends BaseFeature {

    private static final String TAG = "[WAEX][LockedChats]";
    private static final String PREF_KEY_1 = "locked_chats_enhancer";
    private static final String PREF_KEY_2 = "lockedchats_enhancer";

    private final ThreadLocal<Boolean> suppressNotificationQuery = ThreadLocal.withInitial(() -> false);
    private Object chatCacheInstance = null;

    public LockedChatsEnhancerHook(@NonNull Context context,
                                   @NonNull ClassLoader classLoader,
                                   @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Locked Chats Enhancer";
    }

    private boolean isFeatureEnabled() {
        return isEnabled(PREF_KEY_1, false) || isEnabled(PREF_KEY_2, false);
    }

    @Override
    public void hook() throws Throwable {
        try {
            hookNotificationSuppression();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking notification suppression: " + t.getMessage());
        }

        try {
            hookContactPickerSuppression();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking contact picker suppression: " + t.getMessage());
        }
    }

    private void hookNotificationSuppression() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        Method notificationMethod = engine.findMethodWithCache(
                context,
                classLoader,
                "wpp_locked_chats_notification_query",
                (bridge, loader) -> {
                    MethodData md = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .addUsingString("LastMessageStore/getLastMessagesForNotificationAfterReply", StringMatchType.Contains)
                            )
                    ).firstOrNull();
                    return md != null ? md.getMethodInstance(loader) : null;
                }
        );

        if (notificationMethod != null) {
            XposedBridge.hookMethod(notificationMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isFeatureEnabled()) {
                        suppressNotificationQuery.set(true);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    suppressNotificationQuery.remove();
                }
            });
            XposedBridge.log(TAG + " Hooked notification method: " + notificationMethod.getName());
        }

        Method lockedChatsQueryMethod = engine.findMethodWithCache(
                context,
                classLoader,
                "wpp_locked_chats_query_list",
                (bridge, loader) -> {
                    ClassData convMgrClass = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .addUsingString("conversationsmgr/replacecontact", StringMatchType.Contains)
                            )
                    ).firstOrNull();

                    if (convMgrClass != null && notificationMethod != null) {
                        MethodData invokedMethod = bridge.getMethodData(notificationMethod);
                        if (invokedMethod != null) {
                            for (MethodData invoke : invokedMethod.getInvokes()) {
                                if (invoke.isMethod() && convMgrClass.getName().equals(invoke.getClassName())
                                        && ArrayList.class.getName().equals(invoke.getReturnType())) {
                                    return invoke.getMethodInstance(loader);
                                }
                            }
                        }
                    }
                    return null;
                }
        );

        if (lockedChatsQueryMethod != null) {
            XposedBridge.hookMethod(lockedChatsQueryMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isFeatureEnabled() && Boolean.TRUE.equals(suppressNotificationQuery.get())) {
                        param.setResult(new ArrayList<>());
                    }
                }
            });
            XposedBridge.log(TAG + " Hooked locked chats query method: " + lockedChatsQueryMethod.getName());
        }
    }

    private void hookContactPickerSuppression() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        Class<?> chatCacheClass = engine.findClassWithCache(
                context,
                classLoader,
                "wpp_chat_cache_class",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .addUsingString("Chatscache/", StringMatchType.StartsWith)
                            )
                    ).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                }
        );

        if (chatCacheClass != null) {
            XposedBridge.hookAllConstructors(chatCacheClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    chatCacheInstance = param.thisObject;
                }
            });

            Method loadedContactsMethod = engine.findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_loaded_contacts_method",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingNumber(8726)
                                        .paramCount(1)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (loadedContactsMethod != null) {
                XposedBridge.hookMethod(loadedContactsMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isFeatureEnabled() || chatCacheInstance == null) return;
                        try {
                            if (param.args.length > 0 && param.args[0] != null) {
                                Object argObj = param.args[0];
                                Object listObj = XposedHelpers.getObjectField(argObj, "A01");
                                if (listObj instanceof List) {
                                    List<?> list = (List<?>) listObj;
                                    // Safely filter out locked chats if HashSet is available
                                    Field[] fields = chatCacheClass.getDeclaredFields();
                                    for (Field f : fields) {
                                        if (f.getType().equals(HashSet.class)) {
                                            f.setAccessible(true);
                                            Object setObj = f.get(chatCacheInstance);
                                            if (setObj instanceof HashSet) {
                                                HashSet<?> lockedSet = (HashSet<?>) setObj;
                                                if (!lockedSet.isEmpty()) {
                                                    ((List<?>) list).removeIf(lockedSet::contains);
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
                XposedBridge.log(TAG + " Hooked loaded contacts method: " + loadedContactsMethod.getName());
            }
        }
    }
}
