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
import java.lang.reflect.Modifier;

/**
 * Freeze Last Seen & Hide Online Status Hook:
 * Intercepts PresenceStateManager setAvailable state transitions and outgoing presence jobs
 * so the user does not broadcast active/online presence to WhatsApp servers or contacts.
 */
public class FreezeLastSeenHook extends BaseFeature {

    private static final String TAG = "[WAEX][FreezeLastSeen]";

    public FreezeLastSeenHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookPresenceSetAvailable();
        hookSendPresenceJob();
    }

    private void hookPresenceSetAvailable() {
        try {
            Method setAvailableMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_presence_set_available",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("presencestatemanager/setAvailable/new-state", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return data != null ? data.getMethodInstance(loader) : null;
                    }
            );

            if (setAvailableMethod != null) {
                XposedBridge.hookMethod(setAvailableMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isPresenceSuppressed()) {
                            param.setResult(null); // Suppress transition to available/online
                            XposedBridge.log(TAG + " Suppressed transition to available (Hide Online / Freeze Last Seen)");
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked PresenceStateManager.setAvailable: " + setAvailableMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking PresenceStateManager.setAvailable: " + t.getMessage());
        }
    }

    private void hookSendPresenceJob() {
        try {
            Method sendJobMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_freeze_last_seen_job",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .modifiers(Modifier.PUBLIC)
                                        .returnType(void.class)
                                        .addUsingString("SendPresenceJob", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return data != null ? data.getMethodInstance(loader) : null;
                    }
            );

            if (sendJobMethod != null) {
                XposedBridge.hookMethod(sendJobMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isPresenceSuppressed()) {
                            param.setResult(null); // Prevent outgoing presence timestamp ping
                            XposedBridge.log(TAG + " Suppressed SendPresenceJob");
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked SendPresenceJob: " + sendJobMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking SendPresenceJob: " + t.getMessage());
        }
    }

    private boolean isPresenceSuppressed() {
        return isEnabled("freeze_last_seen", false) || isEnabled("online_status_indicator", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Freeze Last Seen & Hide Online";
    }
}
