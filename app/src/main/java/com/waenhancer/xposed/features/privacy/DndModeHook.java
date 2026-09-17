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
 * Do Not Disturb (DND) Mode Hook:
 * Dynamically blocks all incoming traffic and messages without disconnecting device internet.
 */
public class DndModeHook extends BaseFeature {

    private static final String TAG = "[WAEX][DNDMode]";
    private static final String PREF_KEY = "dnd_mode";

    public DndModeHook(@NonNull Context context,
                       @NonNull ClassLoader classLoader,
                       @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Do Not Disturb (DND) Mode";
    }

    private boolean isDndActive() {
        return isEnabled(PREF_KEY, false) || isEnabled("dndmode", false);
    }

    @Override
    public void hook() throws Throwable {
        try {
            hookMessageHandler();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking message handler: " + t.getMessage());
        }
    }

    private void hookMessageHandler() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        Method dndMethod = engine.findMethodWithCache(
                context,
                classLoader,
                "wpp_dnd_message_handler_start",
                (bridge, loader) -> {
                    MethodData md = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .addUsingString("MessageHandler/start", StringMatchType.Equals)
                            )
                    ).firstOrNull();

                    if (md == null) {
                        md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("MessageHandler/start", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                    }

                    return md != null ? md.getMethodInstance(loader) : null;
                }
        );

        if (dndMethod != null) {
            XposedBridge.hookMethod(dndMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isDndActive()) {
                        param.setResult(null); // Block incoming message handler execution
                        XposedBridge.log(TAG + " DND active: Suppressed MessageHandler intake.");
                    }
                }
            });
            XposedBridge.log(TAG + " Hooked DND MessageHandler method: " + dndMethod.getName());
        }
    }
}
