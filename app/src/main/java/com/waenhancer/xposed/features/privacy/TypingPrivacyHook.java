package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexCacheManager;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.json.JSONObject;
import java.lang.reflect.Method;

/**
 * Typing Privacy: intercepts outgoing typing and voice recording presence notifications
 * ("composing" and "recording" presence packets sent to WhatsApp servers).
 */
public class TypingPrivacyHook extends BaseFeature {

    public TypingPrivacyHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        DexCacheManager dexCache = DexCacheManager.getInstance(context);
        DexSearchEngine engine = DexSearchEngine.getInstance();

        Method composingMethod = dexCache.getMethod(classLoader, "waex_handle_me_composing_method", () -> {
            var bridge = engine.getBridge();
            if (bridge == null) {
                engine.initialize(context);
                bridge = engine.getBridge();
            }
            if (bridge == null) return null;

            MethodData data = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .addUsingString("HandleMeComposing/sendComposing", StringMatchType.Contains)
                    )
            ).firstOrNull();

            if (data != null) {
                return data.getMethodInstance(classLoader);
            }
            return null;
        });

        if (composingMethod != null) {
            XposedBridge.hookMethod(composingMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (param.args == null || param.args.length == 0) return;

                    boolean isGhostMode = isEnabled("ghostmode_active", false);

                    boolean hideTypingGlobal = isGhostMode
                            || isEnabled("typing_privacy", false)
                            || isEnabled("hide_typing", false)
                            || isEnabled("ghostmode_t", false);

                    boolean hideRecordingGlobal = isGhostMode
                            || isEnabled("typing_privacy", false)
                            || isEnabled("hide_recording", false)
                            || isEnabled("ghostmode_r", false);

                    // Extract typing state: 0 = typing/composing, 1 = recording audio, 2 = clear
                    int state = -1;
                    Object jidObj = null;

                    for (Object arg : param.args) {
                        if (arg == null) continue;
                        if (arg instanceof Integer) {
                            state = (Integer) arg;
                        } else if (!(arg instanceof Boolean)) {
                            String className = arg.getClass().getName();
                            if (className.contains("Jid") || className.contains("jid") || !arg.getClass().isPrimitive()) {
                                jidObj = arg;
                            }
                        }
                    }

                    // Check per-contact privacy override if JID available
                    boolean blockThisPacket = false;
                    if (state == 0) {
                        blockThisPacket = hideTypingGlobal;
                    } else if (state == 1) {
                        blockThisPacket = hideRecordingGlobal;
                    }

                    if (!blockThisPacket && jidObj != null) {
                        String jidStr = jidObj.toString();
                        // Query per-contact rules from prefs
                        JSONObject contactJson = CustomPrivacyHook.getContactPrivacy(prefs, jidStr);
                        if (contactJson != null) {
                            if (state == 0 && (contactJson.optBoolean("hide_typing", false) || contactJson.optBoolean("HideTyping", false))) {
                                blockThisPacket = true;
                            } else if (state == 1 && (contactJson.optBoolean("hide_recording", false) || contactJson.optBoolean("HideRecording", false))) {
                                blockThisPacket = true;
                            }
                        }
                    }

                    // Block typing (state 0) and voice recording (state 1) indicators
                    if (blockThisPacket) {
                        param.setResult(null); // Suppress outgoing presence packet
                    }
                }
            });
        } else {
            XposedBridge.log("[WAEX] Could not locate 'HandleMeComposing/sendComposing' method via DexKit.");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Typing Privacy";
    }
}
