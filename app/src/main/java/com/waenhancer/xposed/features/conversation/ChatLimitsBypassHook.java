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
    /** Replacement for WhatsApp's pin cap (3 normally, 20 for premium). */
    private static final int PINNED_CHATS_EXPANDED_LIMIT = 100;

    /** True once the pin-cap getter is hooked; the per-flow workarounds are then unnecessary. */
    private volatile boolean pinLimitGetterHooked;

    public ChatLimitsBypassHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookPinnedLimit();
        hookForwardLimit();
        hookRevokeTimeLimit();
    }

    private void hookPinnedLimit() {
        // Install these hooks regardless of the startup preference snapshot. WAEX settings may be
        // refreshed after WhatsApp starts; every callback below gates its behavior dynamically.
        try {
            // Current WhatsApp has no standalone pin-limit getter. The limit is calculated inside
            // the pin/trim runnables, found below using durable log anchors.
            // ChatSettingsStore#setPin is only suppressed while the exact sync handler runs.
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
                    }
                });
            }

            final ThreadLocal<Set<Object>> activePinBatch = new ThreadLocal<>();
            final ThreadLocal<Boolean> trimmingPinnedChats = new ThreadLocal<>();
            final ThreadLocal<Boolean> applyingPinSync = new ThreadLocal<>();

            // This map is consulted by the exact pin/trim branches. Hide existing pins only
            // within those short scopes; never alter the normal conversation list result.
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
                    }
                });
            }

            hookPinLimitGetter();

            // Current WhatsApp applies the limit in three additional places. These hooks mirror
            // three, and hide the existing pins only while WhatsApp trims "excess" entries.
            hookModernPinnedWorkflow(activePinBatch, trimmingPinnedChats, applyingPinSync,
                    setPinMethod);

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error setting up pinned limit bypass: " + t.getMessage());
        }
    }

    /** Log strings emitted by the pin flows that read the cap; any one of them is enough. */
    private static final String[] PIN_FLOW_ANCHORS = {
            "ConversationPinHelper/pinSelectedJids max=",
            "ConversationPinHelper/pinSelectedJids channels max=",
            "ConversationPinHelper/trimExcessPinnedChats",
            "pinSelectedJids max=",
    };

    /**
     * Resolves the pin-cap getter by behaviour, never by obfuscated name. The getter is a tiny
     * int method ("premium ? 20 : 3") that the pin flows call. Regular WhatsApp exposes it as an
     * instance no-arg method, WA Business as a static method taking its own helper class, so
     * both shapes are accepted. Strategies, most to least specific:
     *   1. int method using literals 3 and 20, called by a pin flow
     *   2. int method using literal 3, called by a pin flow (premium cap changed)
     *   3. the only int method in the app using literals 3 and 20
     * Among several matches, the one called by the most pin-flow methods wins.
     */
    private void hookPinLimitGetter() {
        try {
            Method getter = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "waex_pin_cap_getter_v4", (bridge, loader) -> {
                        java.util.Map<Method, Integer> calledByPinFlows = new LinkedHashMap<>();
                        for (String anchor : PIN_FLOW_ANCHORS) {
                            for (MethodData caller : bridge.findMethod(FindMethod.create()
                                    .matcher(MethodMatcher.create()
                                            .addUsingString(anchor, StringMatchType.Contains)))) {
                                for (MethodData invoked : caller.getInvokes()) {
                                    if (!invoked.isMethod()) continue;
                                    try {
                                        Method m = invoked.getMethodInstance(loader);
                                        if (isCapGetterShape(m)) calledByPinFlows.merge(m, 1, Integer::sum);
                                    } catch (Throwable ignored) { }
                                }
                            }
                        }

                        Method match = pickCapGetter(bridge, loader, calledByPinFlows, true, true);
                        if (match == null) match = pickCapGetter(bridge, loader, calledByPinFlows, true, false);
                        if (match == null) match = pickCapGetter(bridge, loader, calledByPinFlows, false, true);
                        return match;
                    });
            if (getter == null) {
                return;
            }
            XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!isPinnedLimitBypassEnabled()) return;
                    Object original = param.getResult();
                    // Only raise genuine caps (3 / 20 style); never touch an unexpected value.
                    if (original instanceof Integer && (Integer) original >= 1
                            && (Integer) original < PINNED_CHATS_EXPANDED_LIMIT) {
                        param.setResult(PINNED_CHATS_EXPANDED_LIMIT);
                    }
                }
            });
            pinLimitGetterHooked = true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Pin cap getter hook unavailable: " + t.getMessage());
        }
    }

    /** int return; instance no-arg, or static taking exactly its own declaring class. */
    private static boolean isCapGetterShape(Method m) {
        if (m.getReturnType() != int.class) return false;
        Class<?>[] params = m.getParameterTypes();
        if (Modifier.isStatic(m.getModifiers())) {
            return params.length == 0 || (params.length == 1 && params[0] == m.getDeclaringClass());
        }
        return params.length == 0;
    }

    private static Method pickCapGetter(org.luckypray.dexkit.DexKitBridge bridge, ClassLoader loader,
                                        java.util.Map<Method, Integer> calledByPinFlows,
                                        boolean requireTwenty, boolean requirePinFlowCaller) {
        MethodMatcher matcher = MethodMatcher.create()
                .returnType(int.class).paramCount(0, 1).addUsingNumber(3);
        if (requireTwenty) matcher.addUsingNumber(20);
        Method best = null;
        int bestScore = -1;
        int shapedMatches = 0;
        for (MethodData data : bridge.findMethod(FindMethod.create().matcher(matcher))) {
            try {
                Method m = data.getMethodInstance(loader);
                if (!isCapGetterShape(m)) continue;
                shapedMatches++;
                int score = calledByPinFlows.getOrDefault(m, 0);
                if (requirePinFlowCaller && score == 0) continue;
                if (score > bestScore) { best = m; bestScore = score; }
            } catch (Throwable ignored) { }
        }
        // Without pin-flow evidence, accept only an unambiguous match.
        if (!requirePinFlowCaller && shapedMatches != 1) return null;
        return best;
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
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Pinned premium hook unavailable: " + t.getMessage());
        }

        try {
            Method trim = findPinnedMethod("waex_trim_excess_pins_v2",
                    "ConversationPinHelper/trimExcessPinnedChats");
            if (trim != null) XposedBridge.hookMethod(trim, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!pinLimitGetterHooked && isPinnedLimitBypassEnabled()
                            && isRunnableBranch(param.thisObject, 22)) {
                        trimmingPinnedChats.set(true);
                    }
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
            if (pinSelected != null) XposedBridge.hookMethod(pinSelected, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (pinLimitGetterHooked || !isPinnedLimitBypassEnabled()
                            || !isRunnableBranch(param.thisObject, 4)) return;
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

    /**
     * String anchors resolve a shared synthetic Runnable#run method. Its selector is the only
     * stable way to confine the hook to the anchored branch instead of every runnable action.
     */
    private static boolean isRunnableBranch(Object runnable, int branch) {
        if (runnable == null) return false;
        for (Class<?> current = runnable.getClass(); current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getType() != int.class) continue;
                try {
                    field.setAccessible(true);
                    if (field.getInt(runnable) == branch) return true;
                } catch (Throwable ignored) { }
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
        // Presence matters: the current key written by the UI must override a stale legacy
        // "pinnedlimit" value, otherwise turning the toggle off can never take effect.
        return isEnabled(prefs.contains("unlimited_pinned_chats")
                ? "unlimited_pinned_chats" : "pinnedlimit", false);
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
