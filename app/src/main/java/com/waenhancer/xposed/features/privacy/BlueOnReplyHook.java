package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blue on Reply Hook:
 * Defers sending blue read ticks until the user actually sends a reply message to the contact/group.
 */
public class BlueOnReplyHook extends BaseFeature {

    private static final String TAG = "[WAEX][BlueOnReply]";
    private static final String PREF_KEY = "blueonreply";

    // Tracks JIDs that have been replied to during current session
    private final Set<String> repliedJids = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public BlueOnReplyHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookMessageSend();
    }

    private void hookMessageSend() {
        try {
            Method sendMessageMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_send_message_action_reply",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("app/sendmessage/message_sent", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (sendMessageMethod != null) {
                XposedBridge.hookMethod(sendMessageMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBlueOnReplyEnabled()) return;

                        if (param.args.length > 0 && param.args[0] != null) {
                            Object fMessage = param.args[0];
                            String remoteJid = extractRemoteJid(fMessage);
                            if (remoteJid != null) {
                                repliedJids.add(remoteJid);
                                XposedBridge.log(TAG + " Message sent to " + remoteJid + ", blue tick unlocked on reply.");
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked sendMessageMethod for Blue on Reply: " + sendMessageMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking Blue on Reply: " + t.getMessage());
        }
    }

    public boolean shouldAllowReadReceipt(String jid) {
        if (!isBlueOnReplyEnabled()) return true;
        if (jid == null) return false;
        return repliedJids.contains(jid);
    }

    public boolean isBlueOnReplyEnabled() {
        return isEnabled(PREF_KEY, false);
    }

    private String extractRemoteJid(Object fMessage) {
        try {
            Object key = XposedHelpers.getObjectField(fMessage, "key");
            if (key != null) {
                Object remoteJidObj = XposedHelpers.getObjectField(key, "remoteJid");
                if (remoteJidObj != null) {
                    try {
                        Object raw = XposedHelpers.callMethod(remoteJidObj, "getRawString");
                        if (raw != null) return raw.toString();
                    } catch (Throwable ignored) {}
                    return remoteJidObj.toString();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @NonNull
    @Override
    public String getName() {
        return "Blue on Reply";
    }
}
