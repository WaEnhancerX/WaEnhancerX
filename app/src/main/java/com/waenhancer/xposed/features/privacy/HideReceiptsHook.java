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
 * Hide Seen & Status Receipts: prevents blue ticks and stealth status viewing.
 */
public class HideReceiptsHook extends BaseFeature {

    public HideReceiptsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }


    @Override
    public void hook() throws Throwable {
        // Read Receipt Job hook
        Method readReceiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_send_read_receipt_job",
                (bridge, loader) -> {
                    for (MethodData data : bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .modifiers(Modifier.PUBLIC)
                                    .usingStrings("SendReadReceiptJob")
                            ))) {
                        if (data.isMethod()) {
                            return data.getMethodInstance(loader);
                        }
                    }
                    return null;
                }
        );

        if (readReceiptMethod != null) {
            XposedBridge.hookMethod(readReceiptMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled("hide_seen_receipts", false)) {
                        param.setResult(null); // Drop outgoing read receipt
                    }
                }
            });
            XposedBridge.log("[WAEX] Hooked HideReadReceipts successfully.");
        }

        // Status view receipt hook
        Method statusReceiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_send_status_read_receipt",
                (bridge, loader) -> {
                    MethodData data = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .modifiers(Modifier.PUBLIC)
                                    .returnType(void.class)
                                    .usingStrings("readstatus")
                            )
                    ).firstOrNull();
                    return data != null ? data.getMethodInstance(loader) : null;
                }
        );

        if (statusReceiptMethod != null) {
            XposedBridge.hookMethod(statusReceiptMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isEnabled("stealth_status_view", false)) {
                        param.setResult(null); // Drop status viewed beacon
                    }
                }
            });
            XposedBridge.log("[WAEX] Hooked StealthStatusView successfully.");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Hide Receipts";
    }
}
