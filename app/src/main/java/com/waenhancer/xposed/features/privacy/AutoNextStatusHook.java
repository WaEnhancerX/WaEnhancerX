package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
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

/**
 * Hook to disable automatic progression to the next status item/contact in WhatsApp.
 * Keeps status playback paused on current status until user manually swipes or taps.
 */
public class AutoNextStatusHook extends BaseFeature {

    private static final String TAG = "[WAEX][AutoNextStatus]";

    public AutoNextStatusHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "AutoNextStatusHook";
    }

    @Override
    public void hook() throws Throwable {
        try {
            hookStatusPlaybackProgression();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking auto next status: " + t.getMessage());
        }
    }

    private void hookStatusPlaybackProgression() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        // 1. Resolve StatusPlaybackContactFragment or related status playback fragment class
        Class<?> playbackContactFragmentClass = engine.findClassWithCache(
                context, classLoader, "wpp_status_playback_contact_fragment",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .className("StatusPlaybackContactFragment", StringMatchType.EndsWith))
                    ).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                }
        );

        // 2. Resolve next status playback transition runner method
        Method nextStatusRunMethod = engine.findMethodWithCache(
                context, classLoader, "wpp_status_playback_run_next",
                (bridge, loader) -> {
                    MethodData md = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .name("run")
                                    .addUsingString("playMiddleTone"))
                    ).firstOrNull();
                    return md != null ? md.getMethodInstance(loader) : null;
                }
        );

        if (nextStatusRunMethod != null) {
            XposedBridge.hookMethod(nextStatusRunMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isEnabled("autonext_status", false)) return;

                    if (playbackContactFragmentClass != null && param.thisObject != null) {
                        try {
                            // Find any field referencing the StatusPlaybackContactFragment on this runner
                            Field[] fields = param.thisObject.getClass().getDeclaredFields();
                            for (Field f : fields) {
                                f.setAccessible(true);
                                Object val = f.get(param.thisObject);
                                if (val != null && playbackContactFragmentClass.isInstance(val)) {
                                    param.setResult(null);
                                    return;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            });
            XposedBridge.log(TAG + " Successfully hooked nextStatusRunMethod");
        }

        // 3. Resolve onPlaybackFinished callback method to prevent auto-advancing when story/status ends
        Method onPlaybackFinishedMethod = engine.findMethodWithCache(
                context, classLoader, "wpp_status_on_playback_finished",
                (bridge, loader) -> {
                    MethodData md = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .addUsingString("playbackPage/onPlaybackContentFinished"))
                    ).firstOrNull();
                    return md != null ? md.getMethodInstance(loader) : null;
                }
        );

        if (onPlaybackFinishedMethod != null) {
            XposedBridge.hookMethod(onPlaybackFinishedMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled("autonext_status", false)) {
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log(TAG + " Successfully hooked onPlaybackFinishedMethod");
        }
    }
}
