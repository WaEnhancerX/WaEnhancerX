package com.waenhancer.xposed.features.automation;


import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.BaseBundle;
import android.os.Message;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.waenhancer.utils.ContactNameResolver;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;
import org.luckypray.dexkit.DexKitBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.lang.reflect.Modifier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Presence & Activity Toasts Hook:
 * Displays real-time toast alerts when:
 * 1. Read receipts are observed for your status (`toast_viewed_status`).
 * 2. Read receipts are observed for your messages (`toast_viewed_message`).
 * 3. Contacts come online (`showonline` / `typing_online_toasts`).
 */
public class PresenceToastsHook extends BaseFeature {

    private static final String TAG = "[WAEX][PresenceToasts]";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // Bound asynchronous name lookups rather than allowing unbounded queued tasks.
    private static final ExecutorService TOAST_EXECUTOR = new ThreadPoolExecutor(
            0, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(96),
            task -> {
                Thread thread = new Thread(task, "WAEX-toast-lookup");
                thread.setDaemon(true);
                return thread;
            },
            ReceiptToastDispatch::discardOldest);
    private final ExecutorService asyncExecutor = TOAST_EXECUTOR;
    private static final ReceiptObservationPolicy.Seen READ_OBSERVATIONS = new ReceiptObservationPolicy.Seen(2048, 96);
    private static final String MESSAGE_SNAPSHOT = "WAEX:observed-message-read:v1";
    private static final String STATUS_SNAPSHOT = "WAEX:observed-status-read:v1";
    private final ReceiptToastText receiptText;
    private ReceiptTypes receiptTypes;
    private boolean receiptTypesResolved;

    public PresenceToastsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
        receiptText = new ReceiptToastText(context);
    }

    @Override
    public void hook() throws Throwable {
        hookIncomingPresence();
        hookStatusViewedReceipt();
        hookOnInsertReceipt();
    }

    private void hookIncomingPresence() {
        try {
            Method presenceMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "waex_presence_message_handler_v4",
                    (bridge, loader) -> {
                        // The old available literal belongs to a multiplexed Runnable.
                        // Match the Handler envelope instead, including both event branches.
                        MethodDataList matches = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .name("handleMessage")
                                        .paramTypes("android.os.Message")
                                        .returnType("void")
                                        .addUsingString("MessageHandler/handleConnectionThreadReady connectionready",
                                                StringMatchType.Equals)
                                        .addUsingString("lastSeen", StringMatchType.Equals)
                                        .addUsingString("presence", StringMatchType.Equals)
                                        .addInvoke(MethodMatcher.create().returnType("void").paramCount(3)
                                                .addUsingString("MessageHandler/handleDisconnected error=",
                                                        StringMatchType.Equals))));
                        if (matches.size() != 1) return null;
                        Method candidate = matches.get(0).getMethodInstance(loader);
                        return isPresenceHandler(candidate) ? candidate : null;
                    });
            if (!isPresenceHandler(presenceMethod)) {
                XposedBridge.log(TAG + " Presence handler contract unavailable; online notifications not installed");
                return;
            }
            XposedBridge.hookMethod(presenceMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // Observe only successfully handled host events. Never cancel/mutate them.
                    if (param.hasThrowable() || param.args == null || param.args.length != 1
                            || !(param.args[0] instanceof Message)) return;
                    if (PresenceEventPolicy.isTransportInvalidation(((Message) param.args[0]).what)) {
                        PresenceStateStore.invalidateAll();
                        return;
                    }
                    PresenceEventPolicy.Event event = decodePresenceEvent(param.args);
                    if (event == null) return;
                    if (!event.known) {
                        PresenceStateStore.invalidate(event.jid);
                        return;
                    }
                    final long transitionId = PresenceStateStore.updateAndGetTransition(event.jid, event.online);
                    if (transitionId != 0L && event.online && isOnlineToastEnabled()) {
                        asyncExecutor.execute(() -> {
                            if (!PresenceStateStore.isCurrentOnline(event.jid, transitionId)) return;
                            try {
                                String name = ContactNameResolver.INSTANCE.resolveName(context, event.jid, null, null);
                                showToastSafely(name + UiText.get(R.string.waex_ui_is_online, " is online"), () -> isOnlineToastEnabled()
                                        && PresenceStateStore.isCurrentOnline(event.jid, transitionId));
                            } catch (RuntimeException | LinkageError ignored) { /* Optional name lookup. */ }
                        });
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking presence toasts: " + t.getClass().getSimpleName());
        }
    }

    private static boolean isPresenceHandler(Method method) {
        if (method == null || !Handler.class.isAssignableFrom(method.getDeclaringClass())) return false;
        int flags = method.getModifiers();
        Class<?>[] parameters = method.getParameterTypes();
        return Modifier.isPublic(flags)
                && !Modifier.isStatic(flags)
                && !Modifier.isAbstract(flags)
                && !Modifier.isNative(flags)
                && !method.isBridge() && !method.isSynthetic()
                && "handleMessage".equals(method.getName()) && method.getReturnType() == void.class
                && parameters.length == 1 && parameters[0] == Message.class;
    }

    private static PresenceEventPolicy.Event decodePresenceEvent(Object[] args) {
        if (args == null || args.length != 1 || !(args[0] instanceof Message)) return null;
        Message message = (Message) args[0];
        if (message.what != PresenceEventPolicy.EVENT_FAMILY
                || !PresenceEventPolicy.isPresenceCode(message.arg1)
                || !(message.obj instanceof BaseBundle)) return null;
        try {
            BaseBundle payload = (BaseBundle) message.obj;
            return PresenceEventPolicy.decode(message.what, message.arg1,
                    payload.get("jid"), payload.get("presence"));
        } catch (RuntimeException unreadablePayload) {
            return null;
        }
    }

    /** Common receipt identity types are resolved once, outside all host callbacks. */
    private ReceiptTypes resolveReceiptTypes() throws ReflectiveOperationException {
        if (receiptTypesResolved) return receiptTypes;
        receiptTypesResolved = true;
        DexSearchEngine.getInstance().initialize(context);
        DexKitBridge bridge = DexSearchEngine.getInstance().getBridge();
        if (bridge == null) return null;
        Class<?> jid = classLoader.loadClass("com.whatsapp.infra.core.jid.Jid");
        Class<?> user = classLoader.loadClass("com.whatsapp.infra.core.jid.UserJid");
        Class<?> device = classLoader.loadClass("com.whatsapp.infra.core.jid.DeviceJid");
        MethodDataList senders = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .paramCount(0).returnType(user.getName())
                .addUsingString("FMessage/getSenderUserJid/key.id=", StringMatchType.Equals)));
        if (senders.size() != 1 || !senders.get(0).isMethod()) return null;
        MethodData senderData = senders.get(0);
        Method sender = senderData.getMethodInstance(classLoader);
        if (!ReceiptObservationContract.concreteInstance(sender, user)) return null;
        Class<?> message = sender.getDeclaringClass();
        Field messageKey = null;
        ReceiptObservationContract.Keys keys = null;
        for (UsingFieldData use : senderData.getUsingFields()) {
            Field field = use.getField().getFieldInstance(classLoader);
            if (field.getDeclaringClass() != message || !ReceiptObservationContract.immutable(field)) continue;
            ReceiptObservationContract.Keys candidate = ReceiptObservationContract.Keys.create(field.getType(), jid);
            if (candidate == null) continue;
            if (messageKey != null && !messageKey.equals(field)) return null;
            messageKey = field;
            keys = candidate;
        }
        if (keys == null) return null;
        receiptTypes = new ReceiptTypes(bridge, user, device, message, keys, messageKey);
        return receiptTypes;
    }

    private static final class ReceiptTypes {
        final DexKitBridge bridge;
        final Class<?> user;
        final Class<?> device;
        final Class<?> message;
        final ReceiptObservationContract.Keys keys;
        final Field messageKey;

        ReceiptTypes(DexKitBridge bridge, Class<?> user, Class<?> device, Class<?> message,
                     ReceiptObservationContract.Keys keys, Field messageKey) {
            this.bridge = bridge; this.user = user; this.device = device;
            this.message = message; this.keys = keys; this.messageKey = messageKey;
        }
    }

    /** Observe a typed status read receipt; a normal void return is not a storage acknowledgement. */
    private void hookStatusViewedReceipt() {
        try {
            ReceiptTypes types = resolveReceiptTypes();
            if (types == null) return;
            MethodDataList methods = types.bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .paramTypes(types.user.getName(), null, "int", "long").returnType("void")
                    .addUsingString("StatusReceiptStore/insertOrUpdateSeenReceiptForStatus status key=", StringMatchType.Equals)
                    .addUsingString("StatusReceiptStore/insertOrUpdateSeenReceiptForStatus insert failed", StringMatchType.Equals)
                    .addUsingString("seen_timestamp", StringMatchType.Equals)));
            MethodDataList keyConstructors = types.bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .name("<init>")
                    .addUsingString("Invalid remoteChatJid passed into FStatusKey constructor", StringMatchType.Equals)));
            if (methods.size() != 1 || keyConstructors.size() != 1 || !methods.get(0).isMethod()
                    || !keyConstructors.get(0).isConstructor()) return;
            MethodData data = methods.get(0);
            Method handler = data.getMethodInstance(classLoader);
            Method self = null;
            for (MethodData call : data.getInvokes()) {
                if (!call.isMethod() || !"boolean".equals(call.getReturnTypeName()) || call.getParamCount() != 2) continue;
                Method candidate = call.getMethodInstance(classLoader);
                Class<?>[] args = candidate.getParameterTypes();
                if (!Modifier.isStatic(candidate.getModifiers()) || args[0].isPrimitive()
                        || !args[1].isAssignableFrom(types.user)) continue;
                if (self != null && !self.equals(candidate)) return;
                self = candidate;
            }
            if (self == null) return;
            List<Field> providers = new ArrayList<>();
            for (UsingFieldData use : data.getUsingFields()) {
                Field field = use.getField().getFieldInstance(classLoader);
                if (field.getDeclaringClass() == handler.getDeclaringClass()
                        && !Modifier.isStatic(field.getModifiers())
                        && field.getType() == self.getParameterTypes()[0]) providers.add(field);
            }
            providers = new ArrayList<>(new LinkedHashSet<>(providers));
            if (providers.size() != 1) return;
            ReceiptObservationContract.Statuses model = ReceiptObservationContract.Statuses.create(handler,
                    types.user, keyConstructors.get(0).getClassInstance(classLoader), types.keys, providers.get(0), self);
            if (model == null) return;
            XposedBridge.hookMethod(handler, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isStatusViewedToastEnabled())
                        param.setObjectExtra(STATUS_SNAPSHOT, model.snapshot(param.thisObject, param.args));
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object snapshot = param.getObjectExtra(STATUS_SNAPSHOT);
                    if (!param.hasThrowable() && snapshot instanceof ReceiptObservationPolicy.Observation)
                        emitReceiptObservation((ReceiptObservationPolicy.Observation) snapshot);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " Status receipt contract unavailable: " + failure.getClass().getSimpleName());
        }
    }

    /** Observe message read receipts without inferring domain from the enabled switches. */
    private void hookOnInsertReceipt() {
        try {
            ReceiptTypes types = resolveReceiptTypes();
            if (types == null) return;
            MethodDataList methods = types.bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .paramTypes("java.util.Collection").returnType("java.util.List")
                    .addUsingString("upsertUserReceiptForMessage/INSERT_RECEIPT_USER", StringMatchType.Equals)));
            MethodDataList constructors = types.bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .name("<init>").paramTypes(types.device.getName(), types.user.getName(),
                            types.message.getName(), "int", "long")));
            if (methods.size() != 1 || constructors.size() != 1 || !methods.get(0).isMethod()
                    || !constructors.get(0).isConstructor()) return;
            Method handler = methods.get(0).getMethodInstance(classLoader);
            if (!ReceiptObservationContract.concreteInstance(handler, List.class, Collection.class)) return;
            ReceiptObservationContract.Messages model = ReceiptObservationContract.Messages.create(
                    constructors.get(0).getClassInstance(classLoader), types.message, types.user, types.device,
                    types.keys, types.messageKey);
            if (model == null) return;
            XposedBridge.hookMethod(handler, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (isMessageViewedToastEnabled() && param.args != null && param.args.length == 1)
                        param.setObjectExtra(MESSAGE_SNAPSHOT, model.snapshot(param.args[0]));
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object snapshot = param.getObjectExtra(MESSAGE_SNAPSHOT);
                    if (param.hasThrowable() || !(snapshot instanceof List)) return;
                    for (Object value : (List<?>) snapshot)
                        if (value instanceof ReceiptObservationPolicy.Observation)
                            emitReceiptObservation((ReceiptObservationPolicy.Observation) value);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " Message receipt contract unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private static final ToastThrottle TOAST_THROTTLE = new ToastThrottle(1500L, 2048);
    private static final AtomicInteger PRESENCE_DIAGNOSTICS = new AtomicInteger();

    private boolean isReceiptToastEnabled(ReceiptObservationPolicy.Kind kind) {
        return kind == ReceiptObservationPolicy.Kind.STATUS ? isStatusViewedToastEnabled() : isMessageViewedToastEnabled();
    }

    private void emitReceiptObservation(ReceiptObservationPolicy.Observation event) {
        ReceiptToastDispatch.submit(event, READ_OBSERVATIONS, asyncExecutor, mainHandler::post,
                () -> isReceiptToastEnabled(event.kind), () -> {
                    String name = ContactNameResolver.INSTANCE.resolveName(context, event.viewer, null, null);
                    return receiptText.format(event.kind, name);
                }, message -> {
                    // This runs on the main thread: only a local preference recheck and toast request.
                    if (!TOAST_THROTTLE.shouldEmit("observed-read/" + event.kind + "/" + event.viewer,
                            SystemClock.elapsedRealtime())) return false;
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
                    return true;
                });
    }

    public static void showDeletedMessageToast(Context context, SharedPreferences prefs,
                                               String senderJid, Handler handler) {
        showDeletedToast(context, prefs, senderJid, handler, "message");
    }

    public static void showDeletedStatusToast(Context context, SharedPreferences prefs,
                                              String senderJid, Handler handler) {
        showDeletedToast(context, prefs, senderJid, handler, "status");
    }

    private static void showDeletedToast(Context context, SharedPreferences prefs,
                                         String senderJid, Handler handler, String subject) {
        if (context == null || prefs == null || handler == null) return;
        boolean enabled = prefs.getBoolean("toastdeleted", false);
        if (!enabled) return;

        String key = (senderJid != null ? senderJid : "unknown") + "_deleted_" + subject;
        long now = System.currentTimeMillis();
        if (!TOAST_THROTTLE.shouldEmit(key, now)) return;

        TOAST_EXECUTOR.execute(() -> {
            try {
                String name = senderJid != null
                        ? ContactNameResolver.INSTANCE.resolveName(context, senderJid, null, null)
                        : null;
                if (!ContactNameResolver.INSTANCE.isValidDisplayName(name)
                        || "Unknown".equalsIgnoreCase(name)) {
                    name = UiText.get(R.string.waex_ui_a_contact, "A contact");
                }
                String text = name + ("status".equals(subject)
                        ? UiText.get(R.string.waex_ui_deleted_a_status, " deleted a status")
                        : UiText.get(R.string.waex_ui_deleted_a_message, " deleted a message"));
                handler.post(() -> {
                    try {
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        });
    }

    private void showToastSafely(String message) {
        showToastSafely(message, () -> true);
    }

    private void showToastSafely(String message, BooleanSupplier stillValid) {
        mainHandler.post(() -> {
            try {
                // A queued name lookup must not announce an already invalidated transition.
                if (stillValid.getAsBoolean()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    private boolean isStatusViewedToastEnabled() {
        return isEnabled("toast_viewed_status", false);
    }

    private boolean isMessageViewedToastEnabled() {
        return isEnabled("toast_viewed_message", false);
    }

    private boolean isOnlineToastEnabled() {
        return isEnabled("showonline", false) || isEnabled("typing_online_toasts", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Presence Toasts";
    }
}
