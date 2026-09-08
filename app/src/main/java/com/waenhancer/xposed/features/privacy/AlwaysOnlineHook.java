package com.waenhancer.xposed.features.privacy;

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
 * Always Online Hook:
 * Prevents WhatsApp presence state manager from transitioning to unavailable state,
 * keeping the user appearing constantly online.
 */
public class AlwaysOnlineHook extends BaseFeature {

    private static final String TAG = "[WAEX][AlwaysOnline]";
    private static final String PREF_KEY = "always_online";

    public AlwaysOnlineHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        Method stateChangeMethod = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_always_online_transition_unavailable",
                (bridge, loader) -> {
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .addUsingString("presencestatemanager/startTransitionToUnavailable/new-state", StringMatchType.Contains)
                            )
                    ).firstOrNull();
                    return data != null ? data.getMethodInstance(loader) : null;
                }
        );

        if (stateChangeMethod != null) {
            XposedBridge.hookMethod(stateChangeMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled(PREF_KEY, false)) {
                        param.setResult(null); // Prevent transition to unavailable
                        XposedBridge.log(TAG + " Suppressed transition to unavailable state.");
                    }
                }
            });
            XposedBridge.log(TAG + " Hooked PresenceStateManager transition method: " + stateChangeMethod.getName());
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Always Online";
    }
}
