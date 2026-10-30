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
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

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
                "waex_locked_chats_notification_query",
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
        }

        Method lockedChatsQueryMethod = engine.findMethodWithCache(
                context,
                classLoader,
                "waex_locked_chats_query_list",
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
        }
    }

    private void hookContactPickerSuppression() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        Class<?> chatCacheClass = engine.findClassWithCache(
                context,
                classLoader,
                "waex_chat_cache_class",
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

            final Field lockedChatsField = selectLockedChatsField(chatCacheClass);
            Method loadedContactsMethod = engine.findMethodWithCache(
                    context,
                    classLoader,
                    "waex_loaded_contacts_method",
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
                        if (!isFeatureEnabled() || chatCacheInstance == null || lockedChatsField == null) return;
                        try {
                            if (param.args.length > 0 && param.args[0] != null) {
                                Object argObj = param.args[0];
                                Field listField = findListField(argObj.getClass());
                                if (listField == null) return;
                                Object listObj = listField.get(argObj);
                                if (listObj instanceof List) {
                                    Object setObj = lockedChatsField.get(chatCacheInstance);
                                    if (!(setObj instanceof Collection) || ((Collection<?>) setObj).isEmpty()) return;
                                    Set<String> lockedIds = LockedContactFilterPolicy.collectIds(
                                            (Collection<?>) setObj, LockedChatsEnhancerHook::identityStrings);
                                    List<?> filtered = LockedContactFilterPolicy.withoutLockedContacts(
                                            (List<?>) listObj, lockedIds, LockedChatsEnhancerHook::identityStrings);
                                    listField.set(argObj, filtered);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            }
        }
    }

    private static Field selectLockedChatsField(Class<?> type) {
        List<Field> candidates = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (HashSet.class.isAssignableFrom(field.getType()) || Set.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    candidates.add(field);
                }
            }
        }
        if (candidates.isEmpty()) return null;
        return candidates.size() > 1 ? candidates.get(1) : candidates.get(0);
    }

    private static Field findListField(Class<?> type) {
        Field fallback = null;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                if ("A01".equals(field.getName())) return field;
                if (fallback == null) fallback = field;
            }
        }
        return fallback;
    }

    private static Collection<String> identityStrings(Object value) {
        if (value == null) return Collections.emptyList();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        addIdentity(ids, value);
        // Contact wrappers generally contain one typed JID. Inspect shallow fields only.
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType().isPrimitive() || field.getType() == String.class) continue;
                try {
                    field.setAccessible(true);
                    addIdentity(ids, field.get(value));
                } catch (Throwable ignored) {}
            }
        }
        return ids;
    }

    private static void addIdentity(Set<String> ids, Object value) {
        if (value == null) return;
        try {
            Object raw = XposedHelpers.callMethod(value, "getRawString");
            if (raw != null) ids.add(raw.toString());
        } catch (Throwable ignored) {}
        String text = String.valueOf(value);
        if (text.contains("@")) ids.add(text);
        for (String method : new String[]{"getPhoneNumber", "getUser", "getUserRawString", "getPhoneRawString"}) {
            try {
                Object result = XposedHelpers.callMethod(value, method);
                if (result != null) ids.add(result.toString());
            } catch (Throwable ignored) {}
        }
    }
}
