package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * View Once Bypass: converts incoming view-once photos/videos into normal permanent media.
 */
public class AntiViewOnceHook extends BaseFeature {

    public AntiViewOnceHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }


    @Override
    public void hook() throws Throwable {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_anti_view_once_setter",
                (bridge, loader) -> {
                    // Match the setter method for view_once_state
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .modifiers(Modifier.PUBLIC)
                                    .paramTypes(int.class)
                                    .returnType(void.class)
                                    .usingStrings("view_once")
                            )
                    ).firstOrNull();
                    return data != null ? data.getMethodInstance(loader) : null;
                }
        );

        if (method != null) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled("anti_view_once", false)) {
                        int state = (int) param.args[0];
                        // If view once is set (1), override to standard media (0)
                        if (state == 1) {
                            param.args[0] = 0;
                        }
                    }
                }
            });
            XposedBridge.log("[WAEX] Hooked AntiViewOnce successfully.");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Anti-View Once";
    }
}
