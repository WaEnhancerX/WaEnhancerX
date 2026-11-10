package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Message;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.db.HideSeenStore;
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
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.waenhancer.xposed.features.customization.PropsOverrideHook;
import java.lang.reflect.Modifier;

/**
 * Privacy Receipts Hook:
 * Handles privacy controls for outgoing receipts:
 * 1. Read Receipts (Blue Ticks) - `hide_read_receipts`: Drops incoming read receipt dispatchers, SendReadReceiptJob & strips "type=read" on receipt stanzas.
 * 2. Delivery Receipts (Second Tick) - `hide_delivery_receipts`: Suppresses delivery dispatchers, modifies receipt stanza to "type=inactive", and intercepts connection writer dispatch.
 * 3. Stealth Status Viewing - `stealth_status_view` / per-contact `HideViewStatus`: Suppresses status viewed receipt jobs, incoming dispatchers, and stanzas.
 */
public class HideReceiptsHook extends BaseFeature {

    private static final String TAG = "[WAEX][HideReceipts]";
    private static final long BLUE_REPLY_WINDOW_MS = 120_000L;
    // Only an exact recipient + message ID may be exempted from read suppression.
    // A 120-second JID-wide bypass would leak unrelated receipts and override Ghost Mode.
    private static final RecipientReceiptAllowance EXPLICIT_RECEIPT_IDS =
            new RecipientReceiptAllowance(BLUE_REPLY_WINDOW_MS, 4096);

    private Class<?> protocolTreeNodeClass;
    private Class<?> keyValueClass;
    private Field fieldAttributes;
    private Field fieldKey;
    private Field fieldValue;
    private Field fieldJid;
    private Constructor<?> keyValueStringConstructor;

    // Decrypted-message observation is independent of receipt-job creation.
    private volatile Class<?> fMessageClass;

    public HideReceiptsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        PropsOverrideHook.BOOLEANS.put(19148, false); // Route IQ through receiptMethod
        initProtocolTreeReflection();
        initFMessageReflection();
        hookPlaintextMessageCapture();
        hookEnforceHiding();
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
                    "waex_protocol_tree_node_class",
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
                    "waex_key_value_class",
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
                    } else if (!f.getType().isPrimitive() && (f.getType().getName().endsWith("jid.Jid") || f.getType().getName().endsWith("jid.UserJid"))) {
                        f.setAccessible(true);
                        fieldJid = f;
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
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error initializing ProtocolTree reflection: " + t.getClass().getSimpleName());
        }
    }

    /** Resolve the host message type; members are discovered by value contracts. */
    private void initFMessageReflection() {
        try {
            fMessageClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "waex_fmessage_class",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(
                                FindMethod.create().matcher(MethodMatcher.create()
                                        .addUsingString("FMessage/getSenderUserJid/key.id",
                                                StringMatchType.Contains)))
                                .firstOrNull();
                        return data != null ? data.getMethodInstance(loader).getDeclaringClass() : null;
                    });

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error initializing FMessage reflection: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Observe the same decrypted-message callback used by MessageDeviceSourceHook.
     * Unlike receipt dispatchers, SharedMessageProcessor runs regardless of which
     * activity is visible, including while the matching conversation is open.
     */
    private void hookPlaintextMessageCapture() {
        try {
            DexSearchEngine engine = DexSearchEngine.getInstance();
            engine.initialize(context);
            if (engine.getBridge() == null) return;
            MethodDataList matches = engine.getBridge().findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().addUsingString(
                            "SharedMessageProcessor/handlePlaintext",
                            StringMatchType.Contains)));
            Set<Method> plaintextMethods = new LinkedHashSet<>();
            for (MethodData match : matches) {
                plaintextMethods.add(match.getMethodInstance(classLoader));
            }
            if (plaintextMethods.isEmpty()) return;
            XC_MethodHook observer = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isHideReadReceiptsEnabled()) return;
                    Object message = findFMessage(param.args);
                    if (message != null) captureIncomingMessageIfHidden(message);
                }
            };
            for (Method method : plaintextMethods) XposedBridge.hookMethod(method, observer);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " plaintext message observer error: "
                    + t.getClass().getSimpleName());
        }
    }

    /**
     * Enforce suppression at the root incoming receipt method.
     * Hooks ReadReceipts/sendReceiptForIncomingMessage to prevent status or message read receipts from ever generating.
     */
    private void hookEnforceHiding() {
        try {
            Method readReceiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_read_receipt_incoming_message",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("ReadReceipts/sendReceiptForIncomingMessage", StringMatchType.Contains))
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (readReceiptMethod != null) {
                XposedBridge.hookMethod(readReceiptMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args == null || param.args.length == 0) return;
                        Object fMessageObj = param.args[0];
                        if (fMessageObj == null) return;

                        String jid = extractJidString(fMessageObj, "key");
                        String participant = extractParticipantOrJid(fMessageObj);
                        String target = resolveConversationTarget(jid, participant);

                        if (isStatusTarget(jid, participant)) {
                            // Let WhatsApp construct its complete status receipt job. The
                            // constructor hook retains that authentic recipe and the verified
                            // execution hook below suppresses automatic delivery. Cancelling here
                            // loses participant, receipt-class and timestamp metadata required by
                            // an explicit status blue tick.
                            return;
                        }

                        if (shouldHideReadReceipt(target)) {
                            // Do not cancel this call. On current WhatsApp builds the open-chat
                            // path may combine delivery and read acknowledgement generation. The
                            // ProtocolTreeNode hook below downgrades only type=read, preserving the
                            // sender's ordinary delivered (double-tick) acknowledgement.
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking enforce hiding: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Hook SendReadReceiptJob to suppress outgoing blue read ticks and stealth status view receipts.
     */
    private void hookSendReadReceiptJob() {
        try {
            Class<?> sendJobClass = null;
            try {
                sendJobClass = XposedHelpers.findClassIfExists(
                        "com.whatsapp.messaging.receipts.jobqueue.job.SendReadReceiptJob", classLoader);
                if (sendJobClass == null) {
                    sendJobClass = XposedHelpers.findClassIfExists(
                            "com.whatsapp.jobqueue.job.SendReadReceiptJob", classLoader);
                }
            } catch (Throwable ignored) {}

            if (sendJobClass == null) {
                sendJobClass = DexSearchEngine.getInstance().findClassWithCache(
                        context,
                        classLoader,
                        "waex_send_read_receipt_job_class",
                        (bridge, loader) -> {
                            ClassData cd = bridge.findClass(FindClass.create()
                                    .matcher(ClassMatcher.create().className("SendReadReceiptJob", StringMatchType.EndsWith))
                            ).firstOrNull();
                            return cd != null ? cd.getInstance(loader) : null;
                        }
                );
            }

            if (sendJobClass != null) {
                // Hook all constructors to detect status read jobs
                XposedBridge.hookAllConstructors(sendJobClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object job = param.thisObject;
                            String jid = extractJidString(job, "jid");
                            String participant = extractJidString(job, "participant");
                            if (isStatusTarget(jid, participant)) {
                                String target = (participant != null && !participant.contains("@broadcast")) ? participant : jid;
                                if (shouldHideStatusView(target)) {
                                    if (BlueOnReplyHook.isManualStatusSeenEnabled(prefs)) {
                                        BlueOnReplyHook.captureSuppressedStatusReadJob((Constructor<?>) param.method, param.args);
                                    }
                                    XposedHelpers.setAdditionalInstanceField(job, "waex_hide_status_view", true);
                                }
                            } else if ((isEnabled("blueonreply", false)
                                    || isManualSeenTickEnabled())
                                    && !hasExplicitReceiptIds(jid, param.args)) {
                                // The serialized job recipient is authoritative. A group participant
                                // or an arbitrary argument is not an alternate conversation identity.
                                String captureJid = canonicalJid(jid);
                                if (captureJid != null) {
                                    BlueOnReplyHook.prepareSuppressedReadJob(
                                            captureJid, (Constructor<?>) param.method, param.args, job);
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });

                // Match the host's receipt execution method on the job class or its direct
                // superclass. Current WhatsApp moves this method between the two classes.
                final Class<?> resolvedJobClass = sendJobClass;
                final Method executionMethod = resolveReadReceiptExecution(resolvedJobClass);
                if (executionMethod == null) {
                    XposedBridge.log(TAG + " Status receipt execution contract unavailable; job privacy coverage incomplete");
                    return;
                }
                XposedBridge.hookMethod(executionMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!resolvedJobClass.isInstance(param.thisObject)) return;
                        Object job = param.thisObject;
                        if (BlueOnReplyHook.isManualStatusReceipt(job)) return;
                        // This is a newly created explicit chat read receipt. It must bypass
                        if (BlueOnReplyHook.isManualChatReceipt(job)) return;
                        if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(
                                job, "waex_hide_status_view"))) {
                            param.setResult(null);
                            return;
                        }
                        String jid = extractJidString(job, "jid");
                        String participant = extractJidString(job, "participant");
                        if (isStatusTarget(jid, participant)) {
                            String target = participant != null && !participant.contains("@broadcast")
                                    ? participant : jid;
                            if (shouldHideStatusView(target)) param.setResult(null);
                            return;
                        }
                        // Never rewrite dispatcher type 89: SendReadReceiptJob waits for its
                        // acknowledgement. Cancel its verified execution body instead.
                        String target = resolveConversationTarget(jid, participant);
                        if (shouldHideReadReceipt(target)) {
                            // Step 1: try the staged two-step path (works when constructor args
                            // contained a String[] of message IDs — most common case).
                            BlueOnReplyHook.recordSuppressedReadJob(job);
                            // Step 2: at execution time the job is fully initialized; extract IDs
                            // directly and ensure PENDING_JOBS has a recipe regardless of whether
                            // Step 1 succeeded. This is the critical fix for in-chat messages
                            // where WhatsApp creates and runs the job before our constructor-time
                            // staging can populate the String[] fields.
                            Set<String> msgIds = BlueOnReplyHook.readReceiptMessageIds(job);
                            if (!msgIds.isEmpty() && target != null) {
                                HideSeenStore.getInstance(context).insertHideSeenMessagesAsync(
                                        target, msgIds, HideSeenStore.TYPE_READ, false);
                            }
                            param.setResult(null);
                            return;
                        }
                        if (shouldHideDeliveryReceipt(target)) param.setResult(null);
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking SendReadReceiptJob: " + t.getClass().getSimpleName());
        }
    }

    private Method resolveReadReceiptExecution(final Class<?> jobClass) {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(
                context, classLoader, "waex_send_read_receipt_execution_v1",
                (bridge, loader) -> {
                    ClassData jobData = bridge.getClassData(jobClass);
                    if (jobData == null) return null;
                    MethodDataList matches = jobData.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .addUsingString("receipt", StringMatchType.Equals)));
                    if (matches.isEmpty() && jobData.getSuperClass() != null) {
                        matches = jobData.getSuperClass().findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("receipt", StringMatchType.Equals)));
                    }
                    if (matches.size() != 1) return null;
                    return matches.get(0).getMethodInstance(loader);
                });
        return method;
    }

    private boolean isManualSeenTickEnabled() {
        if (!isHideReadReceiptsEnabled()) return false;
        Object value = prefs.getAll().get("seentick");
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value instanceof String) {
            String text = (String) value;
            return !text.isEmpty() && !"0".equals(text)
                    && !"false".equalsIgnoreCase(text);
        }
        return false;
    }

    private static String findJidInArguments(Object[] arguments) {
        if (arguments == null) return null;
        for (Object argument : arguments) {
            if (argument == null || argument instanceof String[]) continue;
            String value = String.valueOf(argument);
            String jid = canonicalJid(value);
            if (jid != null) return jid;
        }
        return null;
    }

    private static boolean hasExplicitReceiptIds(String recipient, Object[] constructorArguments) {
        if (constructorArguments == null) return false;
        boolean foundIds = false;
        for (Object argument : constructorArguments) {
            if (!(argument instanceof String[])) continue;
            String[] ids = (String[]) argument;
            if (ids.length == 0) continue;
            foundIds = true;
            for (String id : ids) {
                if (!isExplicitReceiptAllowed(recipient, id)) return false;
            }
        }
        return foundIds;
    }

    /**
     * Hook receipt stanza generator method (ProtocolTreeNode).
     */
    private void hookReceiptMethod() {
        try {
            Method receiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_receipt_node_generator",
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
                        String messageId = getAttributeValue(node, "id");

                        boolean isStatusStanza = isStatusTarget(to, participant) || "readstatus".equals(type);

                        if (isStatusStanza) {
                            // Status view receipts are suppressed via incoming interceptor and SendReadReceiptJob.
                            // Never return null here as WhatsApp requires a non-null ProtocolTreeNode instance.
                            return;
                        }

                        // Only explicit READ receipts may bypass hiding for the exact
                        // recipient + message ID. Preserve delivery hiding and Ghost Mode.
                        String target = resolveConversationTarget(to, participant);
                        boolean alreadyViewed = HideSeenStore.getInstance(context).isAlreadyViewed(target, messageId, HideSeenStore.TYPE_READ);
                        if (alreadyViewed) {
                            return; // Already marked as read by Send Blue Tick / Blue on Reply!
                        }

                        boolean hideDelivery = shouldHideDeliveryReceipt(target);
                        if (ExplicitReadReceiptPolicy.maySend(type, target, messageId,
                                hideDelivery, EXPLICIT_RECEIPT_IDS,
                                SystemClock.elapsedRealtime())) return;
                        boolean hideRead = shouldHideReadReceipt(target);

                        if (hideDelivery) {
                            applyHideDelivery(node);
                        } else if (hideRead) {
                            applyHideRead(node);
                            if (target != null && messageId != null) {
                                HideSeenStore.getInstance(context).insertHideSeenMessageAsync(
                                        target, messageId, HideSeenStore.TYPE_READ, false);
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking receipt method: " + t.getClass().getSimpleName());
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
                        "waex_delivery_anchor_" + anchor.replace('/', '_'),
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
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking delivery dispatchers: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Hook onDispatchMessage for delivery/read suppression.
     *
     * Type 89 belongs to SendReadReceiptJob. Rewriting it prevents the job's
     * acknowledgement future from completing and can stall WhatsApp's job queue.
     * Job suppression must happen in the verified execution hook above instead.
     *
     * Incoming-message recording is intentionally handled by the decrypted-message
     * observer, not by dispatcher codes that vary with the visible activity.
     */
    private void hookOnDispatchMessage() {
        try {
            Method dispatchMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_on_dispatch_message_419",
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
                        if (param.args.length == 0 || !(param.args[0] instanceof Message)) return;
                        Message msg = (Message) param.args[0];

                        if (isHideDeliveryReceiptsEnabled() && msg.arg1 == 419) {
                            msg.arg1 = -1; // Cancel only the ordinary receipt dispatcher.
                            return;
                        }

                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking onDispatchMessage: " + t.getClass().getSimpleName());
        }
    }

    /** Record one incoming decrypted message independently of current UI state. */
    private void captureIncomingMessageIfHidden(Object msgObj) {
        if (!isHideReadReceiptsEnabled()) return;
        if (fMessageClass == null || !fMessageClass.isInstance(msgObj)) return;
        try {
            Object keyObj = findMessageKey(msgObj);
            if (keyObj == null) return;

            String rawJid = null;
            Object typedJid = null;
            String messageId = null;
            Boolean fromMe = null;
            for (Class<?> type = keyObj.getClass(); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    if (field.getType() == String.class) {
                        String value = (String) field.get(keyObj);
                        String jid = canonicalJid(value);
                        if (jid != null) rawJid = jid;
                        else if (isMessageId(value)) messageId = value;
                    } else if (field.getType() == boolean.class && fromMe == null) {
                        fromMe = field.getBoolean(keyObj);
                    } else if (!field.getType().isPrimitive()) {
                        Object value = field.get(keyObj);
                        String jid = extractTypedJid(value);
                        if (jid != null) {
                            rawJid = jid;
                            typedJid = value;
                        }
                    }
                }
            }

            if (rawJid == null || messageId == null || messageId.isEmpty()) return;
            if (Boolean.TRUE.equals(fromMe)) return;

            String target = canonicalJid(rawJid);
            if (target == null) return;

            if (!shouldHideReadReceipt(target)) return;

            HideSeenStore.getInstance(context).insertHideSeenMessageAsync(
                    target, messageId, HideSeenStore.TYPE_READ, false);

            BlueOnReplyHook.recordObservedReceipt(target, typedJid, messageId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error recording hidden message: "
                    + t.getClass().getSimpleName());
        }
    }

    private Object findMessageKey(Object message) {
        if (message == null) return null;
        // Prefer semantic key wrappers, then fall back to a validated field contract.
        for (Class<?> type = message.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object candidate = field.get(message);
                    if (candidate == null) continue;
                    String className = candidate.getClass().getName();
                    if (className.contains("Key")
                            || candidate.toString().startsWith("Key(")) return candidate;
                    if (isMessageKey(candidate)) return candidate;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private boolean isMessageKey(Object candidate) {
        if (candidate == null) return false;
        boolean jid = false;
        boolean id = false;
        boolean direction = false;
        for (Class<?> type = candidate.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    if (field.getType() == String.class) {
                        String value = (String) field.get(candidate);
                        if (canonicalJid(value) != null) jid = true;
                        else if (isMessageId(value)) id = true;
                    } else if (field.getType() == boolean.class) {
                        direction = true;
                    } else if (!field.getType().isPrimitive()
                            && extractTypedJid(field.get(candidate)) != null) {
                        jid = true;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return jid && id && direction;
    }

    private static boolean isMessageId(String value) {
        return value != null && value.length() >= 8 && value.length() <= 256
                && value.indexOf('@') < 0 && value.indexOf(' ') < 0
                && value.indexOf('\0') < 0;
    }

    private static String extractTypedJid(Object value) {
        if (value == null) return null;
        // WhatsApp's concrete JID implementations may be fully obfuscated. Detect
        // the stable Jid contract rather than depending on a class-name substring.
        try {
            Object raw = XposedHelpers.callMethod(value, "getRawString");
            return raw == null ? null : canonicalJid(raw.toString());
        } catch (Throwable ignored) {
            String className = value.getClass().getName();
            return (className.contains("Jid") || className.contains("jid"))
                    ? canonicalJid(value.toString()) : null;
        }
    }

    private Object findFMessage(Object[] candidates) {
        if (candidates == null) return null;
        for (Object candidate : candidates) {
            Object message = findFMessage(candidate, new IdentityHashMap<>(), 0);
            if (message != null) return message;
        }
        return null;
    }

    private Object findFMessage(Object candidate, IdentityHashMap<Object, Boolean> seen,
                                int depth) {
        if (candidate == null || fMessageClass == null || depth > 4
                || seen.put(candidate, Boolean.TRUE) != null) return null;
        if (fMessageClass.isInstance(candidate)) return candidate;
        Class<?> candidateClass = candidate.getClass();
        if (candidateClass.isArray()) {
            int length = Array.getLength(candidate);
            for (int i = 0; i < length; i++) {
                Object found = findFMessage(Array.get(candidate, i), seen, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (candidate instanceof Iterable) {
            for (Object value : (Iterable<?>) candidate) {
                Object found = findFMessage(value, seen, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        String className = candidateClass.getName();
        if (className.startsWith("java.") || className.startsWith("android.")) return null;
        for (Class<?> type = candidateClass; type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object found = findFMessage(field.get(candidate), seen, depth + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {}
            }
        }
        return null;
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
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error applying hide delivery: " + t.getClass().getSimpleName());
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
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error applying hide read: " + t.getClass().getSimpleName());
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
                String str = (String) fieldValue.get(kv);
                if (str != null) return str;
            } catch (Throwable ignored) {}
        }
        if (fieldJid != null) {
            try {
                Object jidObj = fieldJid.get(kv);
                if (jidObj != null) {
                    try {
                        Object raw = XposedHelpers.callMethod(jidObj, "getRawString");
                        if (raw != null) return raw.toString();
                    } catch (Throwable ignored) {}
                    return jidObj.toString();
                }
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

    private String extractParticipantOrJid(Object fMessageObj) {
        if (fMessageObj == null) return null;
        try {
            Class<?> curr = fMessageObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    f.setAccessible(true);
                    Object val = f.get(fMessageObj);
                    if (val != null) {
                        String s = val.toString();
                        if (s.contains("@s.whatsapp.net") || s.contains("@lid") || s.contains("@broadcast")) {
                            return s;
                        }
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
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
        if (isGhostModeActive()) {
            return true;
        }
        boolean isGroup = target != null && (target.contains("@g.us") || target.contains("-"));
        boolean globalEnabled = isGroup ? isEnabled("hideread_group", false) : isHideReadReceiptsEnabled();
        if (target != null) {
            JSONObject contactPrivacy = CustomPrivacyHook.getContactPrivacy(prefs, target);
            if (contactPrivacy != null) {
                return contactPrivacy.optBoolean("HideSeen", globalEnabled);
            }
        }
        return globalEnabled;
    }

    private boolean shouldHideDeliveryReceipt(String target) {
        if (isGhostModeActive()) return true;
        boolean globalEnabled = isEnabled("hide_delivery_receipts", false)
                || isEnabled("hidereceipt", false);
        if (target != null) {
            JSONObject contactPrivacy = CustomPrivacyHook.getContactPrivacy(prefs, target);
            if (contactPrivacy != null) {
                return contactPrivacy.optBoolean("HideReceipt", globalEnabled);
            }
        }
        return globalEnabled;
    }

    /**
     * Stage exactly the message IDs scheduled for one recipient.
     * Must be called before constructing the read job, because its constructor
     * is hooked; failed enqueue attempts must revoke these tentative grants.
     */
    public static void allowExplicitReceiptIds(String recipient, Object[] constructorArguments) {
        String key = canonicalJid(recipient);
        if (key == null || constructorArguments == null) return;
        long now = SystemClock.elapsedRealtime();
        for (Object argument : constructorArguments) {
            if (!(argument instanceof String[])) continue;
            for (String messageId : (String[]) argument) {
                EXPLICIT_RECEIPT_IDS.allow(key, messageId, now);
            }
        }
    }

    /** Revoke tentative permissions when the explicit receipt could not be enqueued. */
    public static void revokeExplicitReceiptIds(String recipient, Object[] constructorArguments) {
        String key = canonicalJid(recipient);
        if (key == null || constructorArguments == null) return;
        for (Object argument : constructorArguments) {
            if (!(argument instanceof String[])) continue;
            for (String messageId : (String[]) argument) {
                EXPLICIT_RECEIPT_IDS.revoke(key, messageId);
            }
        }
    }

    private static boolean isExplicitReceiptAllowed(String recipient, String messageId) {
        return EXPLICIT_RECEIPT_IDS.isAllowed(canonicalJid(recipient), messageId,
                SystemClock.elapsedRealtime());
    }

    private static String canonicalJid(String value) {
        return ReceiptJidPolicy.canonical(value);
    }

    /** Prefer the conversation/group address over a group participant's device address. */
    private static String resolveConversationTarget(String keyValue, String participantValue) {
        String keyJid = canonicalJid(keyValue);
        String participantJid = canonicalJid(participantValue);
        if (keyJid != null && (keyJid.endsWith("@g.us") || keyJid.endsWith("@broadcast"))) {
            return keyJid;
        }
        if (participantJid != null
                && (participantJid.endsWith("@g.us") || participantJid.endsWith("@broadcast"))) {
            return participantJid;
        }
        return keyJid != null ? keyJid : participantJid;
    }

    /**
     * Hooks WhatsApp's SenderPlayed dispatcher to suppress outgoing "Played" / "Opened" receipts
     * for Voice Notes (audio) and View-Once media when the corresponding privacy toggles are active.
     */
    private void hookSenderPlayed() {
        try {
            Method dispatch = resolvePlayedReceiptDispatch();
            if (dispatch == null) {
                XposedBridge.log(TAG + " WARNING: Verified played dispatcher unavailable; audio/view-once privacy coverage is incomplete.");
                return;
            }
            XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!PlayedReceiptDispatchPolicy.isDispatchCall(
                            dispatch, param.method, param.thisObject, param.args)) return;
                    Set<?> messages = (Set<?>) param.args[0];
                    // Retain existing played-batch privacy semantics. Media-kind classification
                    // and mixed-batch granularity require an independently verified contract.
                    if (!messages.isEmpty()
                            && shouldSuppressPlayedReceipt(messages.iterator().next())) {
                        param.setResult(null);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking sender played: " + t.getClass().getSimpleName());
        }
    }

    private Method resolvePlayedReceiptDispatch() {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(
                context, classLoader, "waex_played_batch_dispatch_v1",
                (bridge, loader) -> {
                    // sendClearDirty identifies a multi-purpose dispatcher, not a played method.
                    // Require the actual constructor call edge observed for the receipt job.
                    MethodDataList matches = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .returnType("void")
                                    .paramCount(2)
                                    .addParamType("java.util.Set")
                                    .addParamType("boolean")
                                    .addInvoke(MethodMatcher.create()
                                            .declaredClass("com.whatsapp.messaging.receipts.jobqueue.job.SendPlayedReceiptJobV2")
                                            .name("<init>")
                                            .paramCount(2)
                                            .addUsingString("played-receipt-v2-", StringMatchType.Equals))));
                    if (matches.size() != 1) return null;
                    Method candidate = matches.get(0).getMethodInstance(loader);
                    return PlayedReceiptDispatchPolicy.isDispatchMethod(candidate) ? candidate : null;
                });
        return PlayedReceiptDispatchPolicy.isDispatchMethod(method) ? method : null;
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

    private boolean isGhostModeActive() {
        return isEnabled("ghostmode_active", false);
    }

    private boolean isHideVoiceNotePlayedEnabled() {
        return isEnabled("hide_seen_receipts", false) || isEnabled("hideaudioseen", false) || isGhostModeActive();
    }

    private boolean isHideViewOnceSeenEnabled() {
        return isEnabled("hideonceseen", false) || isGhostModeActive();
    }

    private boolean isHideReadReceiptsEnabled() {
        return isEnabled("hide_read_receipts", false) || isEnabled("hideread", false) || isGhostModeActive();
    }

    private boolean isHideDeliveryReceiptsEnabled() {
        return isEnabled("hide_delivery_receipts", false) || isEnabled("hidereceipt", false) || isGhostModeActive();
    }

    private boolean isStealthStatusViewEnabled() {
        if (isGhostModeActive()) {
            return true;
        }
        try {
            if (prefs.contains("stealth_status_view")) {
                Object val = prefs.getAll().get("stealth_status_view");
                if (val instanceof Boolean) return (Boolean) val;
                if (val instanceof String) return !"0".equals(val) && !"false".equalsIgnoreCase((String) val);
                if (val instanceof Number) return ((Number) val).intValue() != 0;
            }
            if (prefs.contains("hidestatusview")) {
                Object val = prefs.getAll().get("hidestatusview");
                if (val instanceof Boolean) return (Boolean) val;
                if (val instanceof String) return !"0".equals(val) && !"false".equalsIgnoreCase((String) val);
                if (val instanceof Number) return ((Number) val).intValue() != 0;
            }
        } catch (Throwable ignored) {}
        return isEnabled("stealth_status_view", false) || isEnabled("hidestatusview", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Hide Receipts";
    }
}
