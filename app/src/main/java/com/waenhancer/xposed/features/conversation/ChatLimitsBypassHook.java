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
import java.util.LinkedHashMap;
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
            XposedBridge.log(TAG + " Installing pinned-chat compatibility hooks; enabled="
                    + isPinnedLimitBypassEnabled());
            // Do not hook boot-class collection constructors here. They run for practically every
            // WhatsApp collection allocation and previously caused main-thread stalls/ANRs once
            // this feature was active. Current pinning code is handled at its own workflow methods.
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
                logResolved("limit-getter", chatPinLimitMethod);
                XposedBridge.hookMethod(chatPinLimitMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isPinnedLimitBypassEnabled()) {
                            param.setResult(PINNED_CHATS_EXPANDED_LIMIT);
                        }
                    }
                });
            } else {
                XposedBridge.log(TAG + " Resolver miss: limit-getter (continuing with workflow hooks)");
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
                logResolved("set-pin", setPinMethod);
                XposedBridge.hookMethod(setPinMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isPinnedLimitBypassEnabled() && isCalledFrom(SYNC_HANDLER_TAG)) {
                            param.setResult(null);
                        }
                    }
                });
            } else {
                XposedBridge.log(TAG + " Resolver miss: set-pin");
            }

            final ThreadLocal<Set<Object>> activePinBatch = new ThreadLocal<>();
            final ThreadLocal<Boolean> trimmingPinnedChats = new ThreadLocal<>();
            final ThreadLocal<Boolean> applyingPinSync = new ThreadLocal<>();

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
                logResolved("pinned-map", pinnedHashSetMethod);
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
                        if (Boolean.TRUE.equals(trimmingPinnedChats.get())) {
                            param.setResult(new LinkedHashMap<>());
                            return;
                        }
                        Set<Object> batch = activePinBatch.get();
                        if (batch != null) {
                            LinkedHashMap<Object, Object> filtered = new LinkedHashMap<>();
                            for (Map.Entry<Object, Object> entry : originalMap.entrySet()) {
                                if (batch.contains(entry.getKey())) {
                                    filtered.put(entry.getKey(), entry.getValue());
                                }
                            }
                            param.setResult(filtered);
                            return;
                        }
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
            } else {
                XposedBridge.log(TAG + " Resolver miss: pinned-map");
            }

            // Current WhatsApp applies the limit in three additional places. These hooks mirror
            // three, and hide the existing pins only while WhatsApp trims "excess" entries.
            hookModernPinnedWorkflow(activePinBatch, trimmingPinnedChats, applyingPinSync,
                    setPinMethod);

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
                logResolved("selection-filter", pinFilterMethod);
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
            } else {
                XposedBridge.log(TAG + " Resolver miss: selection-filter (optional)");
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error setting up pinned limit bypass: " + t.getMessage());
        }
    }

    private void hookModernPinnedWorkflow(ThreadLocal<Set<Object>> activePinBatch,
                                          ThreadLocal<Boolean> trimmingPinnedChats,
                                          ThreadLocal<Boolean> applyingPinSync,
                                          Method setPinMethod) {
        try {
            Method upsell = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "waex_pinned_premium_upsell_v2", (bridge, loader) -> {
                        var matches = bridge.findMethod(FindMethod.create().matcher(
                                MethodMatcher.create().returnType(boolean.class)
                                        .paramCount(2).addUsingNumber(3)));
                        for (MethodData data : matches) {
                            Method method = data.getMethodInstance(loader);
                            Class<?>[] types = method.getParameterTypes();
                            if (Modifier.isStatic(method.getModifiers()) && types.length == 2
                                    && types[0] == method.getDeclaringClass()
                                    && Set.class.isAssignableFrom(types[1])) return method;
                        }
                        return null;
                    });
            if (upsell != null) XposedBridge.hookMethod(upsell, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isPinnedLimitBypassEnabled()) param.setResult(false);
                }
            });
            if (upsell != null) logResolved("premium-gate", upsell);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Pinned premium hook unavailable: " + t.getMessage());
        }

        try {
            Method trim = findPinnedMethod("waex_trim_excess_pins_v2",
                    "ConversationPinHelper/trimExcessPinnedChats");
            if (trim != null) logResolved("trim-excess", trim);
            if (trim != null) XposedBridge.hookMethod(trim, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isPinnedLimitBypassEnabled()) trimmingPinnedChats.set(true);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    trimmingPinnedChats.remove();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Pinned trim hook unavailable: " + t.getMessage());
        }

        try {
            Method pinSelected = findPinnedMethod("waex_pin_selected_chats_v2",
                    "ConversationPinHelper/pinSelectedJids max=");
            if (pinSelected != null) logResolved("pin-selected", pinSelected);
            if (pinSelected != null) XposedBridge.hookMethod(pinSelected, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isPinnedLimitBypassEnabled() || param.thisObject == null) return;
                    Field selectionField = findSelectedJidsField(param.thisObject);
                    if (selectionField == null) return;
                    @SuppressWarnings("unchecked") Set<Object> selection =
                            (Set<Object>) selectionField.get(param.thisObject);
                    if (selection == null || selection.isEmpty()) return;
                    if (selection.size() <= 3) {
                        activePinBatch.set(new LinkedHashSet<>(selection));
                        return;
                    }
                    try {
                        ArrayList<Object> all = new ArrayList<>(selection);
                        for (int start = 0; start < all.size(); start += 3) {
                            Set<Object> batch = new LinkedHashSet<>(
                                    all.subList(start, Math.min(start + 3, all.size())));
                            selectionField.set(param.thisObject, batch);
                            activePinBatch.set(batch);
                            de.robv.android.xposed.XposedBridge.invokeOriginalMethod(
                                    pinSelected, param.thisObject, param.args);
                        }
                    } finally {
                        selectionField.set(param.thisObject, selection);
                        activePinBatch.remove();
                    }
                    param.setResult(null);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    activePinBatch.remove();
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Pinned batching hook unavailable: " + t.getMessage());
        }

        try {
            Method handler = findPinnedMethod("waex_pin_chat_handler_v2", "PinChatHandler/setArchive");
            if (handler != null) logResolved("pin-sync-handler", handler);
            if (handler != null) XposedBridge.hookMethod(handler, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isPinnedLimitBypassEnabled()) applyingPinSync.set(true);
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    applyingPinSync.remove();
                }
            });
            if (setPinMethod != null) XposedBridge.hookMethod(setPinMethod, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isPinnedLimitBypassEnabled() || !Boolean.TRUE.equals(applyingPinSync.get())) return;
                    for (Object arg : param.args) {
                        if (arg instanceof Boolean && !((Boolean) arg)) {
                            param.setResult(null);
                            return;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Pinned sync hook unavailable: " + t.getMessage());
        }
    }

    private void logResolved(String role, Method method) {
        XposedBridge.log(TAG + " Resolved " + role + " -> "
                + method.getDeclaringClass().getName() + "#" + method.getName()
                + java.util.Arrays.toString(method.getParameterTypes())
                + ":" + method.getReturnType().getSimpleName());
    }

    private Method findPinnedMethod(String cacheKey, String marker) throws Throwable {
        return DexSearchEngine.getInstance().findMethodWithCache(
                context, classLoader, cacheKey, (bridge, loader) -> {
                    MethodData data = bridge.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(marker, StringMatchType.Contains)))
                            .firstOrNull();
                    return data != null ? data.getMethodInstance(loader) : null;
                });
    }

    private Field findSelectedJidsField(Object owner) {
        Class<?> userJid = null;
        try {
            userJid = classLoader.loadClass("com.whatsapp.infra.core.jid.UserJid");
        } catch (Throwable ignored) {}
        for (Class<?> current = owner.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!Set.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(owner);
                    if (!(value instanceof Set) || ((Set<?>) value).isEmpty()) continue;
                    boolean allJids = true;
                    for (Object item : (Set<?>) value) {
                        if (item == null) continue;
                        String name = item.getClass().getName();
                        if (userJid != null ? !userJid.isInstance(item)
                                : !(name.endsWith(".Jid") || name.endsWith(".UserJid"))) {
                            allJids = false;
                            break;
                        }
                    }
                    if (allJids) return field;
                } catch (Throwable ignored) {}
            }
        }
        return null;
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
