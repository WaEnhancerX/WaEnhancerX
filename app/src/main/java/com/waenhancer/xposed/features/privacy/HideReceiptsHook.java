package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Message;
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
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Privacy Receipts Hook:
 * Strictly separates:
 * 1. Hide Read Receipts (Blue Ticks) - `hide_read_receipts`: Drops SendReadReceiptJob & strips "type=read" on receipt stanzas.
 * 2. Hide Delivery Receipts (Second Tick) - `hide_delivery_receipts`: Suppresses delivery dispatchers, modifies receipt stanza to "type=inactive", and intercepts connection writer dispatch.
 * 3. Stealth Status Viewing - `stealth_status_view`: Drops status viewed beacons.
 */
public class HideReceiptsHook extends BaseFeature {

    private static final String TAG = "[WAEX][HideReceipts]";

    private Class<?> protocolTreeNodeClass;
    private Class<?> keyValueClass;
    private Field fieldAttributes;
    private Field fieldKey;
    private Field fieldValue;
    private Constructor<?> keyValueStringConstructor;

    public HideReceiptsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        initProtocolTreeReflection();
        hookSendReadReceiptJob();
        hookReceiptMethod();
        hookDeliveryDispatchers();
        hookOnDispatchMessage();
        hookStatusViewReceipts();
    }

    private void initProtocolTreeReflection() {
        try {
            protocolTreeNodeClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_protocol_tree_node_class",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().usingStrings("ProtocolTreeNode/getAttributeJid"))
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            keyValueClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_key_value_class",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().usingStrings("KeyValue{key="))
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            if (protocolTreeNodeClass != null && keyValueClass != null) {
                Class<?> keyValueArrayType = Array.newInstance(keyValueClass, 0).getClass();
                for (Field f : protocolTreeNodeClass.getDeclaredFields()) {
                    if (f.getType() == keyValueArrayType) {
                        fieldAttributes = f;
                        fieldAttributes.setAccessible(true);
                        break;
                    }
                }

                List<Field> strFields = new ArrayList<>();
                for (Field f : keyValueClass.getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        strFields.add(f);
                    }
                }
                if (strFields.size() >= 2) {
                    fieldKey = strFields.get(0);
                    fieldValue = strFields.get(1);
                }

                for (Constructor<?> c : keyValueClass.getDeclaredConstructors()) {
                    Class<?>[] params = c.getParameterTypes();
                    if (params.length == 2 && params[0] == String.class && params[1] == String.class) {
                        keyValueStringConstructor = c;
                        keyValueStringConstructor.setAccessible(true);
                        break;
                    }
                }
                XposedBridge.log(TAG + " ProtocolTree & KeyValue reflection initialized.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error initializing ProtocolTree reflection: " + t.getMessage());
        }
    }

    /**
     * Hook SendReadReceiptJob to suppress outgoing blue read ticks.
     */
    private void hookSendReadReceiptJob() {
        try {
            Method sendJobMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_send_read_receipt_job_v5",
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
                XposedBridge.log(TAG + " Hooked SendReadReceiptJob: " + sendJobMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking SendReadReceiptJob: " + t.getMessage());
        }
    }

    /**
     * Hook receipt stanza generator method (ProtocolTreeNode) matching dev4Mod Unobfuscator.loadReceiptMethod.
     */
    private void hookReceiptMethod() {
        try {
            Method receiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_receipt_node_generator_dev4mod",
                    (bridge, loader) -> {
                        ClassData classDeviceJid = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().className("jid.DeviceJid", StringMatchType.EndsWith))).firstOrNull();

                        ClassData classNode = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().usingStrings("ProtocolTreeNode/getAttributeJid"))).firstOrNull();

                        if (classDeviceJid != null && classNode != null) {
                            MethodDataList methods = bridge.findMethod(FindMethod.create()
                                    .matcher(MethodMatcher.create()
                                            .addUsingString("receipt", StringMatchType.Contains)
                                            .returnType(classNode.getName())
                                    ));
                            for (MethodData md : methods) {
                                if (md.getParamTypeNames().contains(classDeviceJid.getName())) {
                                    return md.getMethodInstance(loader);
                                }
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
                            applyHideDelivery(node);
                        } else if (hideRead) {
                            applyHideRead(node);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked receipt generator method (dev4Mod): " + receiptMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking receipt method: " + t.getMessage());
        }
    }

    /**
     * Hooks delivery dispatchers to suppress delivery receipts when hide_delivery_receipts is enabled.
     */
    private void hookDeliveryDispatchers() {
        try {
            String[] deliveryAnchors = new String[]{
                    "ReadReceipts/sendDeliveryReadReceipt",
                    "ReadReceipts/sendDeliveryReceiptIfNotRetry"
            };

            for (String anchor : deliveryAnchors) {
                Method deliveryMethod = DexSearchEngine.getInstance().findMethodWithCache(
                        context,
                        classLoader,
                        "wpp_delivery_anchor_" + anchor.replace('/', '_'),
                        (bridge, loader) -> {
                            MethodData md = bridge.findMethod(FindMethod.create()
                                    .matcher(MethodMatcher.create().addUsingString(anchor, StringMatchType.Contains))
                            ).firstOrNull();
                            return md != null ? md.getMethodInstance(loader) : null;
                        }
                );

                if (deliveryMethod != null) {
                    XposedBridge.hookMethod(deliveryMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (isHideDeliveryReceiptsEnabled()) {
                                param.setResult(null); // Drop delivery receipt dispatch
                                XposedBridge.log(TAG + " Dropped delivery receipt dispatcher for: " + anchor);
                            }
                        }
                    });
                    XposedBridge.log(TAG + " Hooked delivery dispatcher: " + anchor);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking delivery dispatchers: " + t.getMessage());
        }
    }

    /**
     * Hook onDispatchMessage (type 419 / 89) for delivery/read suppression matching dev4Mod.
     */
    private void hookOnDispatchMessage() {
        try {
            Method dispatchMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_on_dispatch_message_419",
                    (bridge, loader) -> {
                        MethodDataList methods = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("ConnectionWriter/sendReadReceipts", StringMatchType.Contains)
                                        .paramCount(1, 5)
                                ));
                        for (MethodData md : methods) {
                            if (!md.getParamTypeNames().isEmpty() && md.getParamTypeNames().get(0).contains("Message")) {
                                return md.getMethodInstance(loader);
                            }
                        }
                        return null;
                    }
            );

            if (dispatchMethod != null) {
                XposedBridge.hookMethod(dispatchMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof Message) {
                            Message msg = (Message) param.args[0];
                            if (isHideDeliveryReceiptsEnabled()) {
                                if (msg.arg1 == 419 || msg.arg1 == 89) {
                                    msg.arg1 = -1; // Change/cancel type
                                    XposedBridge.log(TAG + " Suppressed ConnectionWriter message type: " + msg.arg1);
                                }
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked ConnectionWriter onDispatchMessage: " + dispatchMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking onDispatchMessage: " + t.getMessage());
        }
    }

    /**
     * Replaces receipt attributes to have type="inactive" and removes "sts",
     * preventing WhatsApp server from acknowledging delivery to sender.
     */
    private void applyHideDelivery(Object node) {
        if (fieldAttributes == null || keyValueStringConstructor == null) return;
        try {
            Object[] currentAttrs = (Object[]) fieldAttributes.get(node);
            List<Object> newAttrList = new ArrayList<>();
            boolean addedType = false;

            if (currentAttrs != null) {
                for (Object kv : currentAttrs) {
                    if (kv == null) continue;
                    String key = getKey(kv);
                    if ("sts".equals(key)) {
                        continue; // Remove sts
                    }
                    if ("type".equals(key)) {
                        Object inactiveKv = keyValueStringConstructor.newInstance("type", "inactive");
                        newAttrList.add(inactiveKv);
                        addedType = true;
                    } else {
                        newAttrList.add(kv);
                    }
                }
            }

            if (!addedType) {
                Object inactiveKv = keyValueStringConstructor.newInstance("type", "inactive");
                newAttrList.add(inactiveKv);
            }

            Object newArray = Array.newInstance(keyValueClass, newAttrList.size());
            for (int i = 0; i < newAttrList.size(); i++) {
                Array.set(newArray, i, newAttrList.get(i));
            }
            fieldAttributes.set(node, newArray);
            XposedBridge.log(TAG + " Injected type=inactive (Second Tick prevented)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error applying hide delivery: " + t.getMessage());
        }
    }

    /**
     * Strips "type=read" and "sts" attributes so the receipt passes as normal delivery confirmation.
     */
    private void applyHideRead(Object node) {
        if (fieldAttributes == null) return;
        try {
            Object[] currentAttrs = (Object[]) fieldAttributes.get(node);
            if (currentAttrs == null) return;

            List<Object> newAttrList = new ArrayList<>();
            boolean modified = false;

            for (Object kv : currentAttrs) {
                if (kv == null) continue;
                String key = getKey(kv);
                String val = getValue(kv);
                if ("sts".equals(key)) {
                    modified = true;
                    continue;
                }
                if ("type".equals(key) && "read".equals(val)) {
                    modified = true;
                    continue;
                }
                newAttrList.add(kv);
            }

            if (modified) {
                Object newArray = Array.newInstance(keyValueClass, newAttrList.size());
                for (int i = 0; i < newAttrList.size(); i++) {
                    Array.set(newArray, i, newAttrList.get(i));
                }
                fieldAttributes.set(node, newArray);
                XposedBridge.log(TAG + " Stripped type=read (Blue Tick prevented, Delivered tick allowed)");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error applying hide read: " + t.getMessage());
        }
    }

    private String getKey(Object kv) {
        if (fieldKey != null) {
            try {
                return (String) fieldKey.get(kv);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private String getValue(Object kv) {
        if (fieldValue != null) {
            try {
                return (String) fieldValue.get(kv);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /**
     * Hook Stealth Status Viewing (drops status viewed beacon).
     */
    private void hookStatusViewReceipts() {
        try {
            Method statusReceiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_send_status_read_receipt_v5",
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
        return isEnabled("hide_read_receipts", false);
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
