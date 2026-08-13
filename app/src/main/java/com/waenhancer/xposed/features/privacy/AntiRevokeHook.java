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
 * Anti-Revoke / Anti-Delete Messages: prevents revoked messages from being deleted from the local chat view.
 */
public class AntiRevokeHook extends BaseFeature {

    public AntiRevokeHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull XSharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        Method revokeMethod = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_revoke_message_handler",
                (bridge, loader) -> {
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .modifiers(Modifier.PUBLIC)
                                    .returnType(void.class)
                                    .usingStrings("revoke")
                            )
                    ).firstOrNull();
                    return data != null ? data.getMethodInstance(loader) : null;
                }
        );

        if (revokeMethod != null) {
            XposedBridge.hookMethod(revokeMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled("anti_revoke", false)) {
                        // Prevent message store from altering message body to "This message was deleted"
                        param.setResult(null);
                    }
                }
            });
            XposedBridge.log("[WAEX] Hooked AntiRevoke successfully.");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Anti-Revoke";
    }
}
