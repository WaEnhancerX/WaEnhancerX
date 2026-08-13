package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Typing Privacy: intercepts outgoing typing and voice recording presence notifications.
 */
public class TypingPrivacyHook extends BaseFeature {

    public TypingPrivacyHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull XSharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_typing_presence_sender",
                (bridge, loader) -> {
                    // Match presence composer packets method
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .modifiers(Modifier.PUBLIC)
                                    .returnType(void.class)
                                    .usingStrings("composing")
                            )
                    ).firstOrNull();
                    return data != null ? data.getMethodInstance(loader) : null;
                }
        );

        if (method != null) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled("typing_privacy", false)) {
                        // Block outgoing composing / recording presence state
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log("[WAEX] Hooked TypingPrivacy successfully.");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Typing Privacy";
    }
}
