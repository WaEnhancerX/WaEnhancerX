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
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import com.waenhancer.xposed.features.customization.PropsOverrideHook;
import com.waenhancer.xposed.utils.HookErrors;
import com.waenhancer.xposed.utils.HostFields;
import com.waenhancer.xposed.core.privacy.PrivacyToggle;
import com.waenhancer.xposed.core.wa.SafeHooks;
import com.waenhancer.xposed.core.wa.WaJid;
import com.waenhancer.xposed.core.wa.WaMessage;
import com.waenhancer.xposed.core.wa.WaTargets;
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
        WaMessage.init(context, classLoader);
        UnsentReadLedger.prune(context);
        initProtocolTreeReflection();
        initFMessageReflection();
        hookPlaintextMessageCapture();
        hookSendReadReceiptJob();
        hookReceiptMethod();
        hookDeliveryDispatchers();
        hookOnDispatchMessage();
        hookStanzaWriter();
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
                for (Field f : HostFields.declared(protocolTreeNodeClass)) {
                    if (f.getType() == keyValueArrayType) {
                        fieldAttributes = f;
                        fieldAttributes.setAccessible(true);
                        break;
                    }
                }

                List<Field> strFields = new ArrayList<>();
                for (Field f : HostFields.declared(keyValueClass)) {
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

    /** Resolve the host message type through the shared finder. */
    private void initFMessageReflection() {
        fMessageClass = WaMessage.messageClass();
    }

    /**
     * Observe the same decrypted-message callback used by MessageDeviceSourceHook.
     * Unlike receipt dispatchers, SharedMessageProcessor runs regardless of which
     * activity is visible, including while the matching conversation is open.
     */
    private void hookPlaintextMessageCapture() {
        try {
            Method[] plaintextMethods = DexSearchEngine.getInstance().findMethodsWithCache(
                    context, classLoader, "waex_shared_message_plaintext_v1",
                    (bridge, loader) -> {
                        Set<Method> found = new LinkedHashSet<>();
                        for (MethodData match : bridge.findMethod(FindMethod.create().matcher(
                                MethodMatcher.create().addUsingString(
                                        "SharedMessageProcessor/handlePlaintext",
                                        StringMatchType.Contains)))) {
                            found.add(match.getMethodInstance(loader));
                        }
                        return found.isEmpty() ? null : found.toArray(new Method[0]);
                    });
            if (plaintextMethods == null || plaintextMethods.length == 0) return;
            XC_MethodHook observer = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!SafeHooks.test(() -> anyReadWithholdingAvailable())) return;
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
            } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }

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
                            } else if ((getPrivacy().flag("blueonreply")
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
                        } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
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
                        if (!ReceiptJobExecutionPolicy.isExecutionCall(resolvedJobClass, executionMethod,
                                param.method, param.thisObject, param.args)) return;
                        Object job = param.thisObject;
                        if (BlueOnReplyHook.isManualStatusReceipt(job)) return;
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
                        boolean hideDelivery = shouldHideDeliveryReceipt(target);
                        if (BlueOnReplyHook.isManualChatReceipt(job)
                                && ExplicitReadReceiptPolicy.refreshJobGrant(
                                XposedHelpers.getAdditionalInstanceField(job, ExplicitReadReceiptPolicy.JOB_GRANT_KEY),
                                jid, target, hideDelivery, EXPLICIT_RECEIPT_IDS, SystemClock.elapsedRealtime())) {
                            return;
                        }
                        if (shouldHideReadReceipt(target)) {
                            // Step 1: try the staged two-step path (works when constructor args
                            // contained a String[] of message IDs — most common case).
                            BlueOnReplyHook.recordSuppressedReadJob(job);
                            // Step 2: at execution time the job is fully initialized; extract IDs
                            // directly and ensure PENDING_JOBS has a recipe regardless of whether
                            // Step 1 succeeded. This is the critical fix for in-chat messages
                            // where WhatsApp creates and runs the job before our constructor-time
                            // staging can populate the String[] fields.
                            UnsentReadLedger.track(context, target, participant,
                                    BlueOnReplyHook.readReceiptMessageIds(job));
                            param.setResult(null);
                            return;
                        }
                        if (hideDelivery) {
                            // Read hiding may have been disabled while this explicit job waited.
                            // A delivery/Ghost veto must still preserve its original retry scope.
                            if (BlueOnReplyHook.isManualChatReceipt(job)) {
                                BlueOnReplyHook.recordSuppressedReadJob(job);
                            }
                            UnsentReadLedger.track(context, target, participant,
                                    BlueOnReplyHook.readReceiptMessageIds(job));
                            param.setResult(null);
                            return;
                        }
                        // The receipt is going out: it is no longer owed.
                        UnsentReadLedger.markSent(context, target, BlueOnReplyHook.readReceiptMessageIds(job));
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking SendReadReceiptJob: " + t.getClass().getSimpleName());
        }
    }

    private Method resolveReadReceiptExecution(final Class<?> jobClass) {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(
                context, classLoader, "waex_send_read_receipt_execution_v2",
                (bridge, loader) -> {
                    ClassData jobData = bridge.getClassData(jobClass);
                    if (jobData == null) return null;
                    MethodDataList matches = jobData.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create().paramCount(0).returnType("void")
                                    .addUsingString("receipt", StringMatchType.Equals)));
                    if (matches.isEmpty() && jobData.getSuperClass() != null) {
                        matches = jobData.getSuperClass().findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create().paramCount(0).returnType("void")
                                        .addUsingString("receipt", StringMatchType.Equals)));
                    }
                    if (matches.size() != 1) return null;
                    Method selected = matches.get(0).getMethodInstance(loader);
                    return ReceiptJobExecutionPolicy.isExecutionMethod(jobClass, selected) ? selected : null;
                });
        // Cache hits must satisfy the same contract as fresh native search results.
        return ReceiptJobExecutionPolicy.isExecutionMethod(jobClass, method) ? method : null;
    }

    private boolean isManualSeenTickEnabled() {
        // The manual button can send receipts withheld by any privacy setting.
        return getPrivacy().flag("seentick");
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

                        String type = getAttributeValue(node, "type");
                        boolean isReadReceipt = "read".equals(type);
                        // A receipt node can also carry retry/recovery, own-device sync,
                        // played or future protocol messages. Only an ordinary delivery
                        // receipt (no type) or an explicit read receipt belongs to these
                        // two privacy controls. Preserve all other nodes and their sts.
                        if (type != null && !isReadReceipt) return;

                        String to = getAttributeValue(node, "to");
                        String participant = getAttributeValue(node, "participant");
                        String messageId = getAttributeValue(node, "id");

                        boolean isStatusStanza = isStatusTarget(to, participant);

                        if (isStatusStanza) {
                            // Status view receipts are suppressed via incoming interceptor and SendReadReceiptJob.
                            // Never return null here as WhatsApp requires a non-null ProtocolTreeNode instance.
                            return;
                        }

                        // Only explicit READ receipts may bypass hiding for the exact
                        // recipient + message ID. Preserve delivery hiding and Ghost Mode.
                        String target = resolveConversationTarget(to, participant);
                        boolean hideDelivery = shouldHideDeliveryReceipt(target);
                        // Viewed/handled history can also be set by bounded-queue eviction.
                        // It is not explicit consent. Only the exact ID allowance below may
                        // exempt a read receipt, and never delivery privacy or Ghost Mode.
                        if (ExplicitReadReceiptPolicy.maySend(type, target, messageId,
                                hideDelivery, EXPLICIT_RECEIPT_IDS,
                                SystemClock.elapsedRealtime())) {
                            UnsentReadLedger.markSent(context, target,
                                    messageId != null ? Collections.singletonList(messageId) : null);
                            return;
                        }
                        boolean hideRead = isReadReceipt && shouldHideReadReceipt(target);

                        if (hideDelivery) {
                            // Delivery and read receipts both identify a received message whose
                            // read receipt is now withheld.
                            applyHideDelivery(node);
                            UnsentReadLedger.track(context, target, participant, messageId);
                        } else if (hideRead) {
                            applyHideRead(node);
                            UnsentReadLedger.track(context, target, participant, messageId);
                        } else if (isReadReceipt) {
                            UnsentReadLedger.markSent(context, target,
                                    messageId != null ? Collections.singletonList(messageId) : null);
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

                if (deliveryMethod == null) continue;
                try {
                    // Only a void dispatcher may be skipped; anything else would hand WhatsApp
                    // a null result. A non-void target is reported and left alone.
                    SafeHooks.skipWhile(deliveryMethod, this::isHideDeliveryReceiptsEnabled);
                } catch (NoSuchMethodException changed) {
                    XposedBridge.log(TAG + " " + anchor + " is not void on this build; not hooked");
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
        if (fMessageClass == null || !fMessageClass.isInstance(msgObj)) return;
        // Typed key contract first; the value-scanning fallback below covers builds where it
        // could not be confirmed.
        String typedId = WaMessage.id(msgObj);
        String typedChat = WaMessage.remoteJid(msgObj);
        Boolean typedFromMe = WaMessage.isFromMe(msgObj);
        if (typedId != null && typedChat != null && typedFromMe != null) {
            String target = canonicalJid(typedChat);
            if (typedFromMe || target == null || !withholdsRead(target)) return;
            UnsentReadLedger.track(context, target, WaMessage.senderJid(msgObj), typedId);
            BlueOnReplyHook.recordObservedReceipt(target, WaMessage.remoteJidObject(msgObj), typedId);
            return;
        }
        try {
            Object keyObj = findMessageKey(msgObj);
            if (keyObj == null) return;

            String rawJid = null;
            Object typedJid = null;
            String messageId = null;
            Boolean fromMe = null;
            for (Class<?> type = keyObj.getClass(); type != null && type != Object.class;
                 type = type.getSuperclass()) {
                for (Field field : HostFields.declared(type)) {
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

            if (!withholdsRead(target)) return;

            UnsentReadLedger.track(context, target, WaMessage.senderJid(msgObj), messageId);

            BlueOnReplyHook.recordObservedReceipt(target, typedJid, messageId);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error recording hidden message: "
                    + t.getClass().getSimpleName());
        }
    }

    /** The field that held the message key, per message class: it is the same for every message. */
    private static final ClassValue<AtomicReference<Field>> MESSAGE_KEY_FIELDS =
            new ClassValue<AtomicReference<Field>>() {
                @Override protected AtomicReference<Field> computeValue(Class<?> type) {
                    return new AtomicReference<>();
                }
            };

    private Object findMessageKey(Object message) {
        if (message == null) return null;
        Field known = MESSAGE_KEY_FIELDS.get(message.getClass()).get();
        if (known != null) {
            try {
                Object candidate = known.get(message);
                if (candidate != null) return candidate;
            } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
        }
        // First sight of this class (or an unset key): discover the field. Prefer semantic key
        // wrappers, then fall back to a validated field contract.
        for (Class<?> type = message.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : HostFields.declared(type)) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object candidate = field.get(message);
                    if (candidate == null) continue;
                    String className = candidate.getClass().getName();
                    if (className.contains("Key")
                            || candidate.toString().startsWith("Key(")
                            || isMessageKey(candidate)) {
                        MESSAGE_KEY_FIELDS.get(message.getClass()).set(field);
                        return candidate;
                    }
                } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
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
            for (Field field : HostFields.declared(type)) {
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
                } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
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
            for (Field field : HostFields.declared(type)) {
                if (Modifier.isStatic(field.getModifiers())
                        || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object found = findFMessage(field.get(candidate), seen, depth + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
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
            } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
        }
        return null;
    }

    private String getValue(Object kv) {
        if (fieldValue != null) {
            try {
                String str = (String) fieldValue.get(kv);
                if (str != null) return str;
            } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
        }
        if (fieldJid != null) {
            try {
                Object jidObj = fieldJid.get(kv);
                if (jidObj != null) {
                    try {
                        Object raw = XposedHelpers.callMethod(jidObj, "getRawString");
                        if (raw != null) return raw.toString();
                    } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
                    return jidObj.toString();
                }
            } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
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
        } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
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
            } catch (Throwable ignored) { HookErrors.swallowed("HideReceiptsHook", ignored); }
            return fieldVal.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isStatusTarget(String jid, String participant) {
        // Numeric broadcast-list recipients are ordinary conversations. Matching
        // the namespace or a substring would apply status privacy to their jobs.
        return "status@broadcast".equals(jid) || "status@broadcast".equals(participant);
    }

    private boolean shouldHideStatusView(String target) {
        return getPrivacy().appliesTo(context, PrivacyToggle.STEALTH_STATUS, target);
    }

    /** True when this chat's read receipt is withheld in any form (blue ticks, delivered, Ghost). */
    private boolean withholdsRead(String target) {
        return shouldHideReadReceipt(target) || shouldHideDeliveryReceipt(target);
    }

    /** Cheap global pre-check before inspecting an incoming message. */
    private boolean anyReadWithholdingAvailable() {
        // Ghost Mode is included (it covers all three). Per-contact-only rules are still
        // recorded by the receipt job and receipt node hooks.
        return getPrivacy().isOn(PrivacyToggle.HIDE_READ) || getPrivacy().isOn(PrivacyToggle.HIDE_READ_GROUP)
                || getPrivacy().isOn(PrivacyToggle.HIDE_DELIVERY);
    }

    private boolean shouldHideReadReceipt(String target) {
        return getPrivacy().hideReadFor(context, target);
    }

    private boolean shouldHideDeliveryReceipt(String target) {
        return getPrivacy().appliesTo(context, PrivacyToggle.HIDE_DELIVERY, target);
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
     * Played receipts ("listened" for voice notes, "opened" for view once). WhatsApp sends them
     * for a Set of messages through the sendClearDirty dispatcher. Each message is checked on its
     * own: hidden ones are removed from the batch, the rest are still sent, and the call is
     * skipped only when nothing is left.
     */
    private void hookSenderPlayed() {
        final Method dispatch;
        try {
            dispatch = WaTargets.sendPlayedReceipts(context, classLoader);
        } catch (NoSuchMethodException missing) {
            XposedBridge.log(TAG + " Played-receipt dispatcher not found; voice note / view once privacy unavailable");
            return;
        }
        XposedBridge.hookMethod(dispatch, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!(param.args[0] instanceof Set)) return;
                Set<?> batch = (Set<?>) param.args[0];
                if (batch.isEmpty()) return;
                Set<Object> kept = PlayedReceiptBatch.keep(batch,
                        HideReceiptsHook.this::shouldSuppressPlayedReceipt,
                        HideReceiptsHook.this::recordHiddenPlayed);
                if (kept == null) return;
                if (kept.isEmpty()) {
                    param.setResult(null);
                } else {
                    param.args[0] = kept;
                }
            }
        });
    }

    private boolean shouldSuppressPlayedReceipt(Object message) {
        if (message == null) return false;
        String chat = WaMessage.remoteJid(message);
        boolean hideVoice = getPrivacy().appliesTo(context, PrivacyToggle.HIDE_VOICE_PLAYED, chat);
        boolean hideOnce = getPrivacy().appliesTo(context, PrivacyToggle.HIDE_VIEW_ONCE_OPENED, chat);
        if (!hideVoice && !hideOnce) return false;
        if (!WaMessage.hasMediaType()) {
            // Media type unknown on this build: keep privacy rather than leak a receipt.
            return true;
        }
        int type = WaMessage.mediaType(message);
        return (hideVoice && WaMessage.isVoiceNoteType(type))
                || (hideOnce && WaMessage.isViewOnceType(type));
    }

    private void recordHiddenPlayed(Object message) {
        String chat = canonicalJid(WaMessage.remoteJid(message));
        String id = WaMessage.id(message);
        if (chat != null && id != null) {
            HideSeenStore.getInstance(context).insertHideSeenMessageAsync(
                    chat, id, HideSeenStore.TYPE_PLAYED, false);
        }
    }

    /**
     * Safety net for delivery receipts that reach the socket without passing the receipt builder
     * hook (new WhatsApp code paths appear regularly). Only a plain delivery receipt (no type)
     * for a hidden chat is dropped; call, retry, played, read and server receipts pass untouched.
     * Optional: if the writer cannot be identified, the builder hook still covers the main path.
     */
    private void hookStanzaWriter() {
        Method writer = WaTargets.stanzaWriter(context, classLoader);
        if (writer == null || protocolTreeNodeClass == null || fieldAttributes == null) return;
        final Field tagField = firstFieldOfType(protocolTreeNodeClass, String.class);
        final Field childrenField = firstFieldOfType(protocolTreeNodeClass,
                Array.newInstance(protocolTreeNodeClass, 0).getClass());
        if (tagField == null) return;
        XposedBridge.hookMethod(writer, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object node = param.args[0];
                if (!protocolTreeNodeClass.isInstance(node)) return;
                if (!SafeHooks.test(() -> isLeakedDeliveryReceipt(node, tagField, childrenField))) return;
                String to = getAttributeValue(node, "to");
                String participant = getAttributeValue(node, "participant");
                if (isStatusTarget(to, participant)) return;
                String target = resolveConversationTarget(to, participant);
                if (target == null || WaJid.isNewsletter(target)) return;
                if (SafeHooks.test(() -> shouldHideDeliveryReceipt(target))) {
                    param.setResult(null);
                    UnsentReadLedger.track(context, target, participant, getAttributeValue(node, "id"));
                }
            }
        });
    }

    private static final Set<String> CALL_RECEIPT_CHILDREN =
            new HashSet<>(Arrays.asList("offer", "accept", "reject", "enc_rekey"));

    private boolean isLeakedDeliveryReceipt(Object node, Field tagField, Field childrenField) {
        try {
            if (!"receipt".equals(tagField.get(node))) return false;
            if (getAttributeValue(node, "type") != null) return false;
            if (childrenField != null) {
                Object[] children = (Object[]) childrenField.get(node);
                if (children != null) {
                    for (Object child : children) {
                        if (child != null && CALL_RECEIPT_CHILDREN.contains(tagField.get(child))) return false;
                    }
                }
            }
            return true;
        } catch (IllegalAccessException unreadable) {
            return false;
        }
    }

    private static Field firstFieldOfType(Class<?> owner, Class<?> type) {
        for (Field f : HostFields.declared(owner)) {
            if (!Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    // All switches are read live from PrivacyPrefs (Ghost Mode, aliases and per-contact rules
    // included), so quick actions apply to the next receipt without a restart.

    private boolean isHideDeliveryReceiptsEnabled() {
        return getPrivacy().isOn(PrivacyToggle.HIDE_DELIVERY);
    }

    @NonNull
    @Override
    public String getName() {
        return "Hide Receipts";
    }
}
