package com.waenhancer.xposed.features.conversation;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;

/**
 * Chat Limits Bypass Hook:
 * 1. Unlimited Pinned Chats (`unlimited_pinned_chats` / `pinnedlimit`): Raises pinned limit from 3 to 1000.
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

    private void hookPinnedLimit() {
        try {
            Method pinLimitMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_pinned_limit_getter",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("pininfo/setpin/failed-already-max-pinned", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (pinLimitMethod != null) {
                XposedBridge.hookMethod(pinLimitMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isPinnedLimitBypassEnabled()) {
                            // If method takes limit parameter or sets count
                            if (param.args.length > 0 && param.args[0] instanceof Integer) {
                                param.args[0] = 1000;
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked pinLimitMethod: " + pinLimitMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking pinned limit: " + t.getMessage());
        }
    }

    private void hookForwardLimit() {
        try {
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

            if (forwardLimitMethod != null && (forwardLimitMethod.getReturnType() == int.class
                    || forwardLimitMethod.getReturnType() == Integer.class)) {
                XposedBridge.hookMethod(forwardLimitMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isForwardLimitBypassEnabled()) {
                            param.setResult(1000); // Allow forwarding up to 1000 contacts at once
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked forwardLimitMethod: " + forwardLimitMethod.getName());
            } else if (forwardLimitMethod != null) {
                XposedBridge.log(TAG + " Skipped incompatible forwarding-limit signature: " + forwardLimitMethod);
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

            if (revokeWindowMethod != null && (revokeWindowMethod.getReturnType() == boolean.class
                    || revokeWindowMethod.getReturnType() == Boolean.class)) {
                XposedBridge.hookMethod(revokeWindowMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isRevokeAllMessagesEnabled()) {
                            param.setResult(true); // Always permit "Delete for Everyone"
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked revokeWindowMethod: " + revokeWindowMethod.getName());
            } else if (revokeWindowMethod != null) {
                XposedBridge.log(TAG + " Skipped incompatible revoke-window signature: " + revokeWindowMethod);
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
