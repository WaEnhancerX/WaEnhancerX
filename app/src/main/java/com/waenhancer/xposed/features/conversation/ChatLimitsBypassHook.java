package com.waenhancer.xposed.features.conversation;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.core.devkit.HookReturnTypeGuard;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

/**
 * Chat Limits Bypass Hook:
 * 1. Unlimited Pinned Chats (`unlimited_pinned_chats` / `pinnedlimit`): Raises pinned limit from 3 to 60.
 * 2. Remove Forward Limits (`removeforwardlimit`): Raises 5-chat forwarding limit to unlimited.
 * 3. Revoke Time Limit Bypass (`revokeallmessages`): Allows "Delete for Everyone" for messages past the default time threshold.
 */
public class ChatLimitsBypassHook extends BaseFeature {

    private static final String TAG = "[WAEX][ChatLimits]";

    public ChatLimitsBypassHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookPinnedLimit();
        hookForwardLimit();
        hookRevokeTimeLimit();
    }

    private static final int PINNED_CHATS_EXPANDED_LIMIT = 60;
    private static final String SYNC_HANDLER_TAG = "SyncResponseHandler";

    private void hookPinnedLimit() {
        // Install these hooks regardless of the startup preference snapshot. WAEX settings may be
        // refreshed after WhatsApp starts; every callback below gates its behavior dynamically.
        try {
            // 1. Hook collection constructors with capacity guards (LinkedHashSet and ArrayList)
            hookCollectionConstructors();

            
            Method chatPinLimitMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_pinned_in_chat_limit_getter_v2",
                    (bridge, loader) -> {
                        // Strategy A: WA Business uses full log string with class/method path.
                        MethodData pinSelectedCaller = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString(
                                                "ConversationPinHelper/pinSelectedJids max=",
                                                StringMatchType.Contains
                                        )
                                )
                        ).firstOrNull();
                        Method structuralMatch = findSelfTypedStaticIntInvoke(pinSelectedCaller, loader);
                        if (structuralMatch != null) {
                            return structuralMatch;
                        }

                        // Strategy B: Regular WA may use a shorter log string without the class prefix.
                        MethodData pinSelectedCallerShort = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("pinSelectedJids max=", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        Method structuralMatchShort = findSelfTypedStaticIntInvoke(pinSelectedCallerShort, loader);
                        if (structuralMatchShort != null) {
                            return structuralMatchShort;
                        }

                        // Strategy C: Look for any method that logs about pinning and calls a static int getter.
                        MethodData pinCallerAny = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("pinSelectedJids", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        Method structuralMatchAny = findSelfTypedStaticIntInvoke(pinCallerAny, loader);
                        if (structuralMatchAny != null) {
                            return structuralMatchAny;
                        }

                        // Strategy D (legacy WA Business fallback): R.string resource ID 3732.
                        MethodData legacy = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingNumber(3732)
                                        .returnType(int.class)
                                )
                        ).firstOrNull();
                        if (legacy != null) return legacy.getMethodInstance(loader);

                        // Strategy E: Search for a no-arg static int method whose name contains
                        // "max" or "limit" invoked from a method that contains "pinned" string.
                        MethodData pinnedStringCaller = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("pinned", StringMatchType.Contains)
                                        .returnType(void.class)
                                )
                        ).firstOrNull();
                        if (pinnedStringCaller != null) {
                            for (MethodData invoked : pinnedStringCaller.getInvokes()) {
                                if (!invoked.isMethod()) continue;
                                try {
                                    Method candidate = invoked.getMethodInstance(loader);
                                    if (candidate.getReturnType() == int.class
                                            && Modifier.isStatic(candidate.getModifiers())
                                            && candidate.getParameterTypes().length == 0) {
                                        String methodName = candidate.getName().toLowerCase();
                                        if (methodName.contains("max") || methodName.contains("limit")) {
                                            return candidate;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }

                        return null;
                    }
            );

            if (chatPinLimitMethod != null) {
                XposedBridge.hookMethod(chatPinLimitMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isPinnedLimitBypassEnabled()) {
                            param.setResult(PINNED_CHATS_EXPANDED_LIMIT);
                        }
                    }
                });
            }

            // 3. ChatSettingsStore setPin: Prevent server sync from resetting or unpinning excess chats
            Method setPinMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_chat_settings_set_pin",
                    (bridge, loader) -> {
                        // Primary (WA Business has the full qualified log string).
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("ChatSettingsStore/setPin", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        if (md != null) return md.getMethodInstance(loader);

                        // Fallback: Regular WA uses "setPin" without the class prefix in its log tag.
                        MethodData fallback = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("setPin", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return fallback != null ? fallback.getMethodInstance(loader) : null;
                    }
            );

            if (setPinMethod != null) {
                XposedBridge.hookMethod(setPinMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isPinnedLimitBypassEnabled() && isCalledFrom(SYNC_HANDLER_TAG)) {
                            param.setResult(null);
                        }
                    }
                });
            }

            // 4. Hook getPinnedJids / QUERY_CHAT_SETTINGS to provide wrapped PinnedChatMap
            Method pinnedHashSetMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_pinned_hash_set_getter",
                    (bridge, loader) -> {
                        // Primary: WA Business uses both strings together.
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("getPinnedJids/QUERY_CHAT_SETTINGS", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        if (md != null) return md.getMethodInstance(loader);

                        // Fallback: Regular WA may only log "getPinnedJids" without the query suffix.
                        MethodData fallback = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("getPinnedJids", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return fallback != null ? fallback.getMethodInstance(loader) : null;
                    }
            );

            if (pinnedHashSetMethod != null) {
                XposedBridge.hookMethod(pinnedHashSetMethod, new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isPinnedLimitBypassEnabled()) {
                            return;
                        }
                        Object result = param.getResult();
                        if (!(result instanceof Map)) {
                            return;
                        }

                        Map<Object, Object> originalMap = (Map<Object, Object>) result;
                        PinnedChatMap<Object, Object> pinnedMap;
                        if (originalMap instanceof PinnedChatMap) {
                            pinnedMap = (PinnedChatMap<Object, Object>) originalMap;
                        } else {
                            pinnedMap = new PinnedChatMap<>(originalMap);
                            param.setResult(pinnedMap);
                        }

                        pinnedMap.setMaxLimit(PINNED_CHATS_EXPANDED_LIMIT);

                        Object hostInstance = param.thisObject != null ? param.thisObject
                                : (param.args.length > 0 ? param.args[0] : null);

                        if (hostInstance != null) {
                            Set<Object> originalKeys = originalMap.keySet();
                            Class<?> currentClass = hostInstance.getClass();
                            while (currentClass != null && currentClass != Object.class) {
                                for (Field field : currentClass.getDeclaredFields()) {
                                    if (Set.class.isAssignableFrom(field.getType())) {
                                        try {
                                            field.setAccessible(true);
                                            Object fieldValue = field.get(hostInstance);
                                            if (fieldValue == originalKeys) {
                                                PinnedChatMap.PinnedKeySet<Object> newKeySet =
                                                        (PinnedChatMap.PinnedKeySet<Object>) pinnedMap.keySet();
                                                newKeySet.setIteratorSuppressed(false);
                                                field.set(hostInstance, newKeySet);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }
                                currentClass = currentClass.getSuperclass();
                            }
                        }
                    }
                });
            }

            // 5. Hook ConversationPinHelper / pinSelectedJids filter method
            Method pinFilterMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_pinned_selection_filter",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("pinSelectedJids", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        if (md == null) return null;

                        for (MethodData invoke : md.getInvokes()) {
                            if (!invoke.isMethod()) continue;
                            try {
                                Method instance = invoke.getMethodInstance(loader);
                                if (Set.class.isAssignableFrom(instance.getReturnType())
                                        && instance.getParameterTypes().length == 2
                                        && Iterable.class.isAssignableFrom(instance.getParameterTypes()[0])
                                        && Set.class.isAssignableFrom(instance.getParameterTypes()[1])) {
                                    return instance;
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    }
            );

            if (pinFilterMethod != null) {
                XposedBridge.hookMethod(pinFilterMethod, new XC_MethodHook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isPinnedLimitBypassEnabled()) {
                            return;
                        }
                        if (param.args.length > 0 && !(param.args[0] instanceof PinnedChatMap.PinnedKeySet)) {
                            return;
                        }

                        Object res = param.getResult();
                        if (!(res instanceof Set)) {
                            return;
                        }

                        Set<Object> originalSet = (Set<Object>) res;
                        PinnedChatMap<Object, Object> pinnedMap = new PinnedChatMap<>();
                        pinnedMap.setMaxLimit(PINNED_CHATS_EXPANDED_LIMIT);
                        for (Object item : originalSet) {
                            pinnedMap.put(item, item);
                        }

                        PinnedChatMap.PinnedKeySet<Object> newKeySet =
                                (PinnedChatMap.PinnedKeySet<Object>) pinnedMap.keySet();
                        newKeySet.setIteratorSuppressed(false);
                        param.setResult(newKeySet);
                    }
                });
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error setting up pinned limit bypass: " + t.getMessage());
        }
    }

    private static Method findSelfTypedStaticIntInvoke(MethodData caller, ClassLoader loader) {
        if (caller == null) {
            return null;
        }
        for (MethodData invoked : caller.getInvokes()) {
            if (!invoked.isMethod()) {
                continue;
            }
            try {
                Method candidate = invoked.getMethodInstance(loader);
                Class<?>[] parameters = candidate.getParameterTypes();
                if (candidate.getReturnType() == int.class
                        && Modifier.isStatic(candidate.getModifiers())
                        && parameters.length == 1
                        && parameters[0] == candidate.getDeclaringClass()) {
                    return candidate;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void hookCollectionConstructors() {
        try {
            Constructor<?> setConstructor = LinkedHashSet.class.getConstructor(int.class);
            XposedBridge.hookMethod(setConstructor, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isPinnedLimitBypassEnabled()) {
                        return;
                    }
                    if (param.args.length > 0 && param.args[0] instanceof Integer) {
                        int capacity = (Integer) param.args[0];
                        if (capacity < 0) {
                            param.args[0] = Math.abs(capacity);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Warning: LinkedHashSet(int) hook: " + t.getMessage());
        }

        try {
            Constructor<?> listConstructor = ArrayList.class.getConstructor(int.class);
            XposedBridge.hookMethod(listConstructor, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isPinnedLimitBypassEnabled()) {
                        return;
                    }
                    if (param.args.length > 0 && param.args[0] instanceof Integer) {
                        int capacity = (Integer) param.args[0];
                        if (capacity < 0) {
                            param.args[0] = Math.abs(capacity);
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Warning: ArrayList(int) hook: " + t.getMessage());
        }
    }

    private static boolean isCalledFrom(String targetClassName) {
        StackTraceElement[] trace = Thread.currentThread().getStackTrace();
        int maxFrames = Math.min(trace.length, 25);
        for (int i = 0; i < maxFrames; i++) {
            StackTraceElement frame = trace[i];
            if (frame.getClassName().contains(targetClassName)) {
                return true;
            }
        }
        return false;
    }

    private void hookForwardLimit() {
        try {
            Class<?> multiSelectionLimitInfoClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "waex_multi_selection_limit_info_class",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(
                                FindClass.create().matcher(
                                        ClassMatcher.create()
                                                .addUsingString("MultiSelectionLimitInfo")
                                )
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            if (multiSelectionLimitInfoClass != null) {
                for (Constructor<?> constructor : multiSelectionLimitInfoClass.getDeclaredConstructors()) {
                    final Class<?>[] parameterTypes = constructor.getParameterTypes();
                    XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isForwardLimitBypassEnabled()) {
                                return;
                            }
                            ForwardLimitPolicy.removeLimits(parameterTypes, param.args);
                        }
                    });
                }
            }

            // Strategy 2 (Legacy / Secondary Fallback):
            // Hook any method containing "forward_limit" returning int (if present in specific variants).
            Method forwardLimitMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_forward_limit_getter",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("forward_limit", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (forwardLimitMethod != null && HookReturnTypeGuard.returnsInteger(forwardLimitMethod)) {
                XposedBridge.hookMethod(forwardLimitMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isForwardLimitBypassEnabled()) {
                            param.setResult(Integer.MAX_VALUE);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking forward limit: " + t.getMessage());
        }
    }

    private void hookRevokeTimeLimit() {
        try {
            Method revokeWindowMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_revoke_time_window_check",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("RevokeMessageManager/canRevokeMessage", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (revokeWindowMethod != null && HookReturnTypeGuard.returnsBoolean(revokeWindowMethod)) {
                XposedBridge.hookMethod(revokeWindowMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isRevokeAllMessagesEnabled()) {
                            param.setResult(true); // Always permit "Delete for Everyone"
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking revoke time window: " + t.getMessage());
        }
    }

    private boolean isPinnedLimitBypassEnabled() {
        return isEnabled("unlimited_pinned_chats", false) || isEnabled("pinnedlimit", false);
    }

    private boolean isForwardLimitBypassEnabled() {
        return isEnabled("chat_limits_bypass", false) || isEnabled("removeforwardlimit", false);
    }

    private boolean isRevokeAllMessagesEnabled() {
        return isEnabled("revokeallmessages", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Chat Limits Bypass";
    }
}
