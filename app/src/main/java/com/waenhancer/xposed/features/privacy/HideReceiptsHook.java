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
import org.luckypray.dexkit.result.MethodDataList;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/**
 * Manages separate privacy controls for:
 * 1. Hide Read Receipts (Blue Ticks) - `hide_read_receipts`
 * 2. Hide Delivery Receipts (Second Tick) - `hide_delivery_receipts`
 * 3. Stealth Status Viewing - `stealth_status_view`
 */
public class HideReceiptsHook extends BaseFeature {

    private static final String TAG = "[WAEX][HideReceipts]";

    public HideReceiptsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookReadReceipts();
        hookDeliveryReceipts();
        hookStatusViewReceipts();
    }

    /**
     * Hooks the Read Receipt job to prevent sending Blue Ticks.
     */
    private void hookReadReceipts() {
        try {
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
                        if (isHideReadReceiptsEnabled()) {
                            param.setResult(null); // Drop outgoing read receipt (blue tick)
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked HideReadReceipts (Blue Ticks) successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking HideReadReceipts: " + t.getMessage());
        }
    }

    /**
     * Hooks the Receipt creation method to prevent sending Delivery Receipts (Second Tick).
     */
    private void hookDeliveryReceipts() {
        try {
            Method receiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_receipt_node_method",
                    (bridge, loader) -> {
                        MethodDataList methods = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .usingStrings(Arrays.asList("receipt", "read", "played"), StringMatchType.Contains, false)
                                        .paramCount(4, 15)));

                        for (MethodData md : methods) {
                            if (md.isMethod()) {
                                return md.getMethodInstance(loader);
                            }
                        }
                        return null;
                    }
            );

            if (receiptMethod != null) {
                XposedBridge.hookMethod(receiptMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (isHideDeliveryReceiptsEnabled()) {
                            // If hide delivery receipts is enabled, suppress delivery receipt node
                            param.setResult(null);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked HideDeliveryReceipts (Second Tick) successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking HideDeliveryReceipts: " + t.getMessage());
        }
    }

    /**
     * Hooks status view beacons for stealth status viewing.
     */
    private void hookStatusViewReceipts() {
        try {
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
                        if (isStealthStatusViewEnabled()) {
                            param.setResult(null); // Drop status viewed beacon
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked StealthStatusView successfully.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking StealthStatusView: " + t.getMessage());
        }
    }

    private boolean isHideReadReceiptsEnabled() {
        return isEnabled("hide_read_receipts", isEnabled("hide_seen_receipts", false));
    }

    private boolean isHideDeliveryReceiptsEnabled() {
        return isEnabled("hide_delivery_receipts", false);
    }

    private boolean isStealthStatusViewEnabled() {
        return isEnabled("stealth_status_view", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Hide Receipts";
    }
}
