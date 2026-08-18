package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Privacy Receipts Hook inspired by dev4Mod-WaEnhancer:
 * Strictly separates:
 * 1. Hide Read Receipts (Blue Ticks) - `hide_read_receipts`: Drops SendReadReceiptJob & strips "read" attribute on receipt stanza.
 * 2. Hide Delivery Receipts (Second Tick) - `hide_delivery_receipts`: Marks receipt stanza as "inactive".
 * 3. Stealth Status Viewing - `stealth_status_view`: Drops status viewed beacons.
 */
public class HideReceiptsHook extends BaseFeature {

    private static final String TAG = "[WAEX][HideReceipts]";

    public HideReceiptsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookSendReadReceiptJob();
        hookReceiptMethod();
        hookStatusViewReceipts();
    }

    /**
     * Hook SendReadReceiptJob to suppress outgoing blue read ticks.
     * Follows dev4Mod-WaEnhancer hookSendReadReceiptJob strategy.
     */
    private void hookSendReadReceiptJob() {
        try {
            Method sendJobMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_send_read_receipt_job_v3",
                    (bridge, loader) -> {
                        ClassDataList classes = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().className("SendReadReceiptJob", StringMatchType.EndsWith)));
                        if (classes.isEmpty()) return null;

                        for (ClassData cd : classes) {
                            MethodDataList methods = cd.findMethod(FindMethod.create()
                                    .matcher(MethodMatcher.create().addUsingString("receipt", StringMatchType.Equals)));
                            if (!methods.isEmpty()) {
                                return methods.get(0).getMethodInstance(loader);
                            }
                            if (cd.getSuperClass() != null) {
                                MethodDataList superMethods = cd.getSuperClass().findMethod(FindMethod.create()
                                        .matcher(MethodMatcher.create().addUsingString("receipt", StringMatchType.Equals)));
                                if (!superMethods.isEmpty()) {
                                    return superMethods.get(0).getMethodInstance(loader);
                                }
                            }
                        }
                        return null;
                    }
            );

            if (sendJobMethod != null) {
                XposedBridge.hookMethod(sendJobMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (isHideReadReceiptsEnabled()) {
                            param.setResult(null); // Drop read receipt job execution (blue tick suppressed)
                            XposedBridge.log(TAG + " Suppressed SendReadReceiptJob (Blue Tick prevented)");
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked SendReadReceiptJob successfully: " + sendJobMethod.getName());
            } else {
                XposedBridge.log(TAG + " WARNING: SendReadReceiptJob method not resolved");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking SendReadReceiptJob: " + t.getMessage());
        }
    }

    /**
     * Hook receipt stanza generator (ProtocolTreeNode) matching dev4Mod-WaEnhancer hookReceiptMethod.
     */
    private void hookReceiptMethod() {
        try {
            Method receiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_receipt_node_generator_v3",
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
                        if (param.getResult() == null) return;
                        Object node = param.getResult();

                        boolean hideDelivery = isHideDeliveryReceiptsEnabled();
                        boolean hideRead = isHideReadReceiptsEnabled();

                        if (hideDelivery) {
                            // Suppress second delivery tick by marking receipt as inactive
                            mutateReceiptNodeToInactive(node);
                            XposedBridge.log(TAG + " Marked receipt node as inactive (Second Tick suppressed)");
                        } else if (hideRead) {
                            // ONLY strip "read" type so sender receives normal delivery (second grey tick), NOT blue
                            stripReadTypeFromReceiptNode(node);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked delivery receipt generator successfully: " + receiptMethod.getName());
            } else {
                XposedBridge.log(TAG + " WARNING: Receipt method not found");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking receipt method: " + t.getMessage());
        }
    }

    /**
     * Mutates the ProtocolTreeNode attributes to mark delivery receipt as inactive.
     * Follows dev4Mod-WaEnhancer type="inactive" pattern.
     */
    private void mutateReceiptNodeToInactive(Object protocolTreeNode) {
        try {
            boolean foundType = false;
            for (Field field : protocolTreeNode.getClass().getDeclaredFields()) {
                if (field.getType().isArray()) {
                    field.setAccessible(true);
                    Object[] array = (Object[]) field.get(protocolTreeNode);
                    if (array != null) {
                        for (Object kv : array) {
                            if (kv != null) {
                                String key = getKeyValueKey(kv);
                                if ("type".equals(key)) {
                                    setKeyValueValue(kv, "inactive");
                                    foundType = true;
                                }
                            }
                        }
                    }
                }
            }
            if (!foundType) {
                // If type attribute didn't exist, set value on first available type KV
                for (Field field : protocolTreeNode.getClass().getDeclaredFields()) {
                    if (field.getType().isArray()) {
                        field.setAccessible(true);
                        Object[] array = (Object[]) field.get(protocolTreeNode);
                        if (array != null && array.length > 0) {
                            setKeyValueValue(array[0], "inactive");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Strips "read" type from the ProtocolTreeNode attributes.
     * Follows dev4Mod-WaEnhancer type="read" stripping pattern so delivery ticks pass cleanly.
     */
    private void stripReadTypeFromReceiptNode(Object protocolTreeNode) {
        try {
            for (Field field : protocolTreeNode.getClass().getDeclaredFields()) {
                if (field.getType().isArray()) {
                    field.setAccessible(true);
                    Object[] array = (Object[]) field.get(protocolTreeNode);
                    if (array != null) {
                        for (Object kv : array) {
                            if (kv != null) {
                                String key = getKeyValueKey(kv);
                                String val = getKeyValueValue(kv);
                                if ("type".equals(key) && "read".equals(val)) {
                                    setKeyValueValue(kv, "");
                                    XposedBridge.log(TAG + " Stripped type=read from receipt (Blue Tick prevented, Delivered allowed)");
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private String getKeyValueKey(Object kv) {
        try {
            for (Field f : kv.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    String s = (String) f.get(kv);
                    if (s != null && (s.equals("type") || s.equals("to") || s.equals("id") || s.equals("sts"))) {
                        return s;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String getKeyValueValue(Object kv) {
        try {
            Field[] fields = kv.getClass().getDeclaredFields();
            List<Field> strFields = new ArrayList<>();
            for (Field f : fields) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    strFields.add(f);
                }
            }
            if (strFields.size() >= 2) {
                return (String) strFields.get(1).get(kv);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void setKeyValueValue(Object kv, String newValue) {
        try {
            Field[] fields = kv.getClass().getDeclaredFields();
            List<Field> strFields = new ArrayList<>();
            for (Field f : fields) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    strFields.add(f);
                }
            }
            if (strFields.size() >= 2) {
                strFields.get(1).set(kv, newValue);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Hook Stealth Status Viewing (drops status viewed beacon).
     */
    private void hookStatusViewReceipts() {
        try {
            Method statusReceiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_send_status_read_receipt_v3",
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
                            XposedBridge.log(TAG + " Dropped status viewed beacon");
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
