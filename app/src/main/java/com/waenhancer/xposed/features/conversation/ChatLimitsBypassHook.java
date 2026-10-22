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
                    "wpp_pinned_in_chat_limit_getter_v2",
                    (bridge, loader) -> {
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

                        MethodData legacy = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingNumber(3732)
                                        .returnType(int.class)
                                )
                        ).firstOrNull();
                        return legacy != null ? legacy.getMethodInstance(loader) : null;
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
                    "wpp_chat_settings_set_pin",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("ChatSettingsStore/setPin", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
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
                    "wpp_pinned_hash_set_getter",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("getPinnedJids/QUERY_CHAT_SETTINGS", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
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
                                for (java.lang.reflect.Field field : currentClass.getDeclaredFields()) {
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
                    "wpp_pinned_selection_filter",
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
            java.lang.reflect.Constructor<?> setConstructor = java.util.LinkedHashSet.class.getConstructor(int.class);
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
            java.lang.reflect.Constructor<?> listConstructor = java.util.ArrayList.class.getConstructor(int.class);
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
                    "wpp_multi_selection_limit_info_class",
                    (bridge, loader) -> {
                        org.luckypray.dexkit.result.ClassData cd = bridge.findClass(
                                org.luckypray.dexkit.query.FindClass.create().matcher(
                                        org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                                                .addUsingString("MultiSelectionLimitInfo")
                                )
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            if (multiSelectionLimitInfoClass != null) {
                for (java.lang.reflect.Constructor<?> constructor : multiSelectionLimitInfoClass.getDeclaredConstructors()) {
                    XposedBridge.hookMethod(constructor, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isForwardLimitBypassEnabled()) {
                                return;
                            }
                            if (param.args != null && param.args.length > 0) {
                                // If first argument is Integer/int limit (e.g. older versions or constructor(int limit, ...)):
                                if (param.args[0] instanceof Integer) {
                                    param.args[0] = Integer.MAX_VALUE;
                                }
                                // In modern versions: constructor(Integer num, int limit, int stringRes, boolean z, ...)
                                if (param.args.length >= 2 && param.args[1] instanceof Integer) {
                                    param.args[1] = Integer.MAX_VALUE;
                                }
                            }
                        }
                    });
                }
            }

            // Strategy 2 (Legacy / Secondary Fallback):
            // Hook any method containing "forward_limit" returning int (if present in specific variants).
            Method forwardLimitMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_forward_limit_getter",
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
                    "wpp_revoke_time_window_check",
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
