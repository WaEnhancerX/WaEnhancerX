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
 * Freeze Last Seen: intercepts heartbeat updates to freeze last seen timestamp.
 */
public class FreezeLastSeenHook extends BaseFeature {

    public FreezeLastSeenHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_freeze_last_seen_job",
                (bridge, loader) -> {
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .modifiers(Modifier.PUBLIC)
                                    .returnType(void.class)
                                    .usingStrings("SendPresenceJob")
                            )
                    ).firstOrNull();
                    return data != null ? data.getMethodInstance(loader) : null;
                }
        );

        if (method != null) {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled("freeze_last_seen", false)) {
                        param.setResult(null); // Prevent outgoing presence timestamp ping
                    }
                }
            });
            XposedBridge.log("[WAEX] Hooked FreezeLastSeen successfully.");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Freeze Last Seen";
    }
}
