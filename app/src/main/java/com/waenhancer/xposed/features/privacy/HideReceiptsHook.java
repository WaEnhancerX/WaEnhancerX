package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Message;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.json.JSONObject;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Privacy Receipts Hook:
 * Handles privacy controls for outgoing receipts:
 * 1. Read Receipts (Blue Ticks) - `hide_read_receipts`: Drops SendReadReceiptJob & strips "type=read" on receipt stanzas.
 * 2. Delivery Receipts (Second Tick) - `hide_delivery_receipts`: Suppresses delivery dispatchers, modifies receipt stanza to "type=inactive", and intercepts connection writer dispatch.
 * 3. Stealth Status Viewing - `stealth_status_view` / per-contact `HideViewStatus`: Suppresses status viewed receipt jobs and stanzas.
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
        hookSenderPlayed();
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
     * Hook SendReadReceiptJob to suppress outgoing blue read ticks and stealth status view receipts.
     */
    private void hookSendReadReceiptJob() {
        try {
            Class<?> sendJobClass = null;
            try {
                sendJobClass = XposedHelpers.findClassIfExists("com.whatsapp.jobqueue.job.SendReadReceiptJob", classLoader);
            } catch (Throwable ignored) {}

            Method sendJobMethod = null;
            if (sendJobClass != null) {
                for (Method m : sendJobClass.getDeclaredMethods()) {
                    if ("onRun".equals(m.getName()) && m.getParameterCount() == 0) {
                        sendJobMethod = m;
                        break;
                    }
                }
            }

            if (sendJobMethod == null) {
                sendJobMethod = DexSearchEngine.getInstance().findMethodWithCache(
                        context,
                        classLoader,
                        "wpp_send_read_receipt_job_v6",
                        (bridge, loader) -> {
                            ClassData cd = bridge.findClass(FindClass.create()
                                    .matcher(ClassMatcher.create().className("SendReadReceiptJob", StringMatchType.Contains))
                            ).firstOrNull();
                            if (cd == null) return null;

                            MethodDataList methods = cd.findMethod(FindMethod.create()
                                    .matcher(MethodMatcher.create().addUsingString("receipt", StringMatchType.Contains)));
                            if (!methods.isEmpty()) {
                                return methods.get(0).getMethodInstance(loader);
                            }
                            if (cd.getSuperClass() != null) {
                                MethodDataList superMethods = cd.getSuperClass().findMethod(FindMethod.create()
                                        .matcher(MethodMatcher.create().addUsingString("receipt", StringMatchType.Contains)));
                                if (!superMethods.isEmpty()) {
                                    return superMethods.get(0).getMethodInstance(loader);
                                }
                            }
                            return null;
                        }
                );
            }

            if (sendJobMethod != null) {
                XposedBridge.hookMethod(sendJobMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object job = param.thisObject;
                        if (job == null) return;

                        String jid = extractJidString(job, "jid");
                        String participant = extractJidString(job, "participant");

                        if (isStatusTarget(jid, participant)) {
                            String target = participant != null ? participant : jid;
                            if (shouldHideStatusView(target)) {
                                param.setResult(null); // Suppress status viewed receipt job execution
                                XposedBridge.log(TAG + " Suppressed Status View Receipt via SendReadReceiptJob for " + target);
                            }
                            return;
                        }

                        if (shouldHideReadReceipt(jid)) {
                            param.setResult(null); // Drop read receipt job execution (blue tick suppressed)
                            XposedBridge.log(TAG + " Suppressed SendReadReceiptJob (Blue Tick prevented for " + jid + ")");
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
     * Hook receipt stanza generator method (ProtocolTreeNode).
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

                        String to = getAttributeValue(node, "to");
                        String participant = getAttributeValue(node, "participant");
                        String type = getAttributeValue(node, "type");

                        boolean isStatusStanza = isStatusTarget(to, participant) || "readstatus".equals(type);

                        if (isStatusStanza) {
                            String target = participant != null ? participant : to;
                            if (shouldHideStatusView(target)) {
                                param.setResult(null); // Drop status viewed receipt stanza completely
                                XposedBridge.log(TAG + " Dropped Status Receipt Stanza (Stealth Status View for " + target + ")");
                            }
                            return;
                        }

                        boolean hideDelivery = isHideDeliveryReceiptsEnabled();
                        boolean hideRead = isHideReadReceiptsEnabled();

                        if (hideDelivery) {
                            applyHideDelivery(node);
                        } else if (hideRead) {
                            applyHideRead(node);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked receipt generator method: " + receiptMethod.getName());
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
     * Hook onDispatchMessage (type 419 / 89) for delivery/read suppression.
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

    private String getAttributeValue(Object node, String targetKey) {
        if (fieldAttributes == null || node == null) return null;
        try {
            Object[] currentAttrs = (Object[]) fieldAttributes.get(node);
            if (currentAttrs == null) return null;
            for (Object kv : currentAttrs) {
                if (kv == null) continue;
                String key = getKey(kv);
                if (targetKey.equals(key)) {
                    return getValue(kv);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractJidString(Object obj, String fieldName) {
        if (obj == null || fieldName == null) return null;
        try {
            Object fieldVal = XposedHelpers.getObjectField(obj, fieldName);
            if (fieldVal == null) return null;
            if (fieldVal instanceof String) {
                return (String) fieldVal;
            }
            try {
                Object raw = XposedHelpers.callMethod(fieldVal, "getRawString");
                if (raw != null) return raw.toString();
            } catch (Throwable ignored) {}
            return fieldVal.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isStatusTarget(String jid, String participant) {
        if (jid != null && (jid.equals("status@broadcast") || jid.contains("status") || jid.contains("broadcast"))) {
            return true;
        }
        if (participant != null && (participant.contains("status") || participant.contains("broadcast"))) {
            return true;
        }
        return false;
    }

    private boolean shouldHideStatusView(String target) {
        boolean globalEnabled = isStealthStatusViewEnabled();
        if (target != null) {
            JSONObject contactPrivacy = CustomPrivacyHook.getContactPrivacy(prefs, target);
            if (contactPrivacy != null) {
                return contactPrivacy.optBoolean("HideViewStatus", globalEnabled);
            }
        }
        return globalEnabled;
    }

    private boolean shouldHideReadReceipt(String target) {
        boolean globalEnabled = isHideReadReceiptsEnabled();
        if (target != null) {
            JSONObject contactPrivacy = CustomPrivacyHook.getContactPrivacy(prefs, target);
            if (contactPrivacy != null) {
                return contactPrivacy.optBoolean("HideSeen", globalEnabled);
            }
        }
        return globalEnabled;
    }

    /**
     * Hooks WhatsApp's SenderPlayed dispatcher to suppress outgoing "Played" / "Opened" receipts
     * for Voice Notes (audio) and View-Once media when the corresponding privacy toggles are active.
     */
    private void hookSenderPlayed() {
        try {
            DexSearchEngine engine = DexSearchEngine.getInstance();

            // 1. Resolve SenderPlayed class via anchor string
            Class<?> senderPlayedClass = engine.findClassWithCache(
                    context,
                    classLoader,
                    "wpp_sender_played_class",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().usingStrings("sendmethods/sendClearDirty"))
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            if (senderPlayedClass == null) {
                XposedBridge.log(TAG + " WARNING: SenderPlayed class not found.");
                return;
            }

            // 2. Hook single-message played sender method
            for (Method m : senderPlayedClass.getDeclaredMethods()) {
                if (m.getParameterCount() == 1) {
                    Class<?> pType = m.getParameterTypes()[0];
                    // Parameter is FMessage or media message type
                    if (!pType.isPrimitive() && !pType.getName().startsWith("java.lang.")) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                Object msgObj = param.args[0];
                                if (shouldSuppressPlayedReceipt(msgObj)) {
                                    param.setResult(null);
                                    XposedBridge.log(TAG + " Suppressed Voice Note / View Once Played Receipt.");
                                }
                            }
                        });
                        XposedBridge.log(TAG + " Hooked single SenderPlayed method: " + m.getName());
                    }
                } else if (m.getParameterCount() > 0 && java.util.Set.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    // Hook batch/business set played sender method
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.args[0] instanceof java.util.Set) {
                                java.util.Set<?> set = (java.util.Set<?>) param.args[0];
                                if (!set.isEmpty()) {
                                    Object first = set.iterator().next();
                                    if (shouldSuppressPlayedReceipt(first)) {
                                        param.setResult(null);
                                        XposedBridge.log(TAG + " Suppressed Batch Voice Note / View Once Played Receipt.");
                                    }
                                }
                            }
                        }
                    });
                    XposedBridge.log(TAG + " Hooked batch SenderPlayed method: " + m.getName());
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking sender played: " + t.getMessage());
        }
    }

    private boolean shouldSuppressPlayedReceipt(Object msgObj) {
        if (msgObj == null) return false;

        boolean hideVoiceNote = isHideVoiceNotePlayedEnabled();
        boolean hideViewOnce = isHideViewOnceSeenEnabled();

        if (!hideVoiceNote && !hideViewOnce) return false;

        try {
            // Check media type / view once properties dynamically
            Class<?> cls = msgObj.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    f.setAccessible(true);
                    String name = f.getName().toLowerCase();
                    if (f.getType() == int.class || f.getType() == byte.class) {
                        int val = f.getInt(msgObj);
                        // MediaType 2 = Voice Note / Audio
                        if (hideVoiceNote && val == 2) {
                            return true;
                        }
                    } else if (f.getType() == boolean.class) {
                        boolean bVal = f.getBoolean(msgObj);
                        if (hideViewOnce && (name.contains("viewonce") || name.contains("ephemeral")) && bVal) {
                            return true;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
        } catch (Throwable ignored) {}

        // Fallback: If either toggle is enabled and played method is triggered for this message
        return hideVoiceNote || hideViewOnce;
    }

    private boolean isHideVoiceNotePlayedEnabled() {
        return isEnabled("hide_seen_receipts", false) || isEnabled("hideaudioseen", false);
    }

    private boolean isHideViewOnceSeenEnabled() {
        return isEnabled("hideonceseen", false);
    }

    private boolean isHideReadReceiptsEnabled() {
        return isEnabled("hide_read_receipts", false) || isEnabled("hideread", false);
    }

    private boolean isHideDeliveryReceiptsEnabled() {
        return isEnabled("hide_delivery_receipts", false) || isEnabled("hidereceipt", false);
    }

    private boolean isStealthStatusViewEnabled() {
        return isEnabled("stealth_status_view", false) || isEnabled("hidestatusview", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Hide Receipts";
    }
}
