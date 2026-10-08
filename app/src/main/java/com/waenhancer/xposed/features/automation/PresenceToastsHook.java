package com.waenhancer.xposed.features.automation;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.os.BaseBundle;
import android.os.Bundle;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Presence & Activity Toasts Hook:
 * Displays real-time toast alerts when:
 * 1. Contacts view your status (`toast_viewed_status`).
 * 2. Contacts read your messages (`toast_viewed_message`).
 * 3. Contacts come online (`showonline` / `typing_online_toasts`).
 */
public class PresenceToastsHook extends BaseFeature {

    private static final String TAG = "[WAEX][PresenceToasts]";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor();

    public PresenceToastsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
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
                    context,
                    classLoader,
                    "wpp_presence_incoming_update_v2",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("app/xmpp/recv/handle_available", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        if (md == null) {
                            md = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                                    .addUsingString("MessageHandler/handleConnectionThreadReady connectionready", StringMatchType.Contains)))
                                    .firstOrNull();
                        }
                        if (md == null) {
                            md = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                                    .addUsingString("PresenceManager/onPresenceReceived", StringMatchType.Contains)))
                                    .firstOrNull();
                        }
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (presenceMethod != null) {
                XposedBridge.hookMethod(presenceMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        PresenceEvent event = decodePresenceEvent(param.args, param.thisObject);
                        if (event == null) {
                            if (PRESENCE_DIAGNOSTICS.getAndIncrement() < 5) {
                                XposedBridge.log(TAG + " Presence callback could not be decoded: receiver="
                                        + (param.thisObject == null ? "static" : param.thisObject.getClass().getName())
                                        + ", args=" + (param.args == null ? 0 : param.args.length));
                            }
                            return;
                        }
                        if (PRESENCE_DIAGNOSTICS.getAndIncrement() < 5) {
                            XposedBridge.log(TAG + " Presence event jid=" + event.jid + ", online=" + event.online);
                        }
                        boolean transition = PresenceStateStore.update(event.jid, event.online);
                        if (transition && event.online && isOnlineToastEnabled()) {
                            asyncExecutor.execute(() -> {
                                String name = ContactNameResolver.INSTANCE.resolveName(context, event.jid, null, null);
                                showToastSafely(name + " is online");
                            });
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked presence incoming update: " + presenceMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking presence toasts: " + t.getMessage());
        }
    }

    private static PresenceEvent decodePresenceEvent(Object[] args, Object receiver) {
        Message message = null;
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof Message) { message = (Message) arg; break; }
            }
        }
        if (message == null) message = findCapturedMessage(receiver);
        if (message != null) {
            Bundle data = message.getData();
            String jid = data != null ? firstJid(data) : null;
            if (jid == null && message.obj instanceof BaseBundle) jid = firstJid((BaseBundle) message.obj);
            if (jid == null) return null;
            // WhatsApp's available event is code 5. Other presence callbacks for the same JID
            // represent the transition away from available and become the last-seen timestamp.
            return new PresenceEvent(jid, message.arg1 == 5);
        }
        String capturedJid = findCapturedJid(receiver, 4,
                java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
        if (capturedJid != null) return new PresenceEvent(capturedJid, true);
        return null;
    }

    private static String findCapturedJid(Object value, int depth, java.util.Set<Object> seen) {
        if (value == null || depth < 0 || !seen.add(value)) return null;
        try {
            java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                    "[+0-9A-Za-z._:-]+@lid|[+0-9A-Za-z._:-]+@(s\\.)?whatsapp\\.net")
                    .matcher(value.toString());
            if (matcher.find()) return matcher.group();
        } catch (Throwable ignored) {}
        Class<?> type = value.getClass();
        while (type != null && !type.getName().startsWith("java.")
                && !type.getName().startsWith("android.")) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    String jid = findCapturedJid(field.get(value), depth - 1, seen);
                    if (jid != null) return jid;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static Message findCapturedMessage(Object receiver) {
        return findCapturedMessage(receiver, 4, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
    }

    private static Message findCapturedMessage(Object receiver, int depth, java.util.Set<Object> seen) {
        if (receiver == null || depth < 0 || !seen.add(receiver)) return null;
        if (receiver instanceof Message) return (Message) receiver;
        Class<?> type = receiver.getClass();
        while (type != null && !type.getName().startsWith("java.")) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(receiver);
                    if (value instanceof Message) return (Message) value;
                    Message nested = findCapturedMessage(value, depth - 1, seen);
                    if (nested != null) return nested;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static String firstJid(BaseBundle bundle) {
        for (String key : new String[]{"jid", "user_jid", "participant", "from"}) {
            try {
                Object value = bundle.get(key);
                if (value != null && value.toString().contains("@")) return value.toString();
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static final class PresenceEvent {
        final String jid;
        final boolean online;
        PresenceEvent(String jid, boolean online) { this.jid = jid; this.online = online; }
    }

    /**
     * Hooks StatusReceiptStore/insertOrUpdateSeenReceiptForStatus for real-time status viewed alerts.
     */
    private void hookStatusViewedReceipt() {
        try {
            Method statusReceiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_status_seen_receipt_store",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("StatusReceiptStore/insertOrUpdateSeenReceiptForStatus", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (statusReceiptMethod != null) {
                XposedBridge.hookMethod(statusReceiptMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isStatusViewedToastEnabled()) return;

                        if (param.args != null && param.args.length > 0) {
                            Object jidObj = param.args[0];
                            String rawJid = jidObj != null ? jidObj.toString() : null;
                            if (rawJid != null && !rawJid.isEmpty()) {
                                asyncExecutor.execute(() -> {
                                    String contactName = ContactNameResolver.INSTANCE.resolveName(context, rawJid, null, null);
                                    showToastSafely(contactName + " viewed your status");
                                });
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked status viewed receipt: " + statusReceiptMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking status viewed receipt: " + t.getMessage());
        }
    }

    /**
     * Hooks OnInsertReceipt for broader receipt status updates (Viewed status / Viewed messages).
     */
    private void hookOnInsertReceipt() {
        try {
            Method onInsertReceiptMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_on_insert_receipt_user",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("INSERT_RECEIPT_USER", StringMatchType.Contains)
                                        .paramCount(1)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (onInsertReceiptMethod != null) {
                XposedBridge.hookMethod(onInsertReceiptMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean statusToast = isStatusViewedToastEnabled();
                        boolean msgToast = isMessageViewedToastEnabled();
                        if (!statusToast && !msgToast) return;

                        Object arg = param.args[0];
                        if (arg == null) return;

                        if (arg instanceof Collection) {
                            for (Object item : (Collection<?>) arg) {
                                processReceiptItem(item, statusToast, msgToast);
                            }
                        } else {
                            processReceiptItem(arg, statusToast, msgToast);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked onInsertReceiptMethod: " + onInsertReceiptMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking onInsertReceipt: " + t.getMessage());
        }
    }

    private static final java.util.Map<String, Long> LAST_TOAST_MAP = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger PRESENCE_DIAGNOSTICS = new java.util.concurrent.atomic.AtomicInteger();
    private static final long MIN_TOAST_INTERVAL_MS = 1500;

    private void processReceiptItem(Object receiptObj, boolean statusToast, boolean msgToast) {
        if (receiptObj == null) return;
        try {
            Class<?> cls = receiptObj.getClass();
            Field intField = null;
            Field jidField = null;

            for (Field f : cls.getDeclaredFields()) {
                f.setAccessible(true);
                if (f.getType() == int.class) {
                    intField = f;
                } else if (f.getType().getName().contains("Jid")) {
                    jidField = f;
                }
            }

            if (intField != null && jidField != null) {
                int receiptType = intField.getInt(receiptObj);
                Object jidVal = jidField.get(receiptObj);
                if (jidVal == null) return;
                String rawJid = jidVal.toString();

                // Type 13 = Read / Viewed status receipt
                if (receiptType == 13 && statusToast) {
                    emitToastWithThrottle(rawJid, "status", () -> {
                        String name = ContactNameResolver.INSTANCE.resolveName(context, rawJid, null, null);
                        return name + " viewed your status";
                    });
                } else if ((receiptType == 13 || receiptType == 5) && msgToast) {
                    // Type 5 / 13 for read message receipt
                    emitToastWithThrottle(rawJid, "msg", () -> {
                        String name = ContactNameResolver.INSTANCE.resolveName(context, rawJid, null, null);
                        return name + " viewed your message";
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void emitToastWithThrottle(String rawJid, String actionKey, java.util.concurrent.Callable<String> messageBuilder) {
        String key = rawJid + "_" + actionKey;
        long now = System.currentTimeMillis();
        Long lastTime = LAST_TOAST_MAP.get(key);
        if (lastTime != null && (now - lastTime) < MIN_TOAST_INTERVAL_MS) {
            return;
        }
        LAST_TOAST_MAP.put(key, now);

        asyncExecutor.execute(() -> {
            try {
                String message = messageBuilder.call();
                if (message != null && !message.isEmpty()) {
                    showToastSafely(message);
                }
            } catch (Throwable ignored) {}
        });
    }

    public static void showDeletedMessageToast(Context context, String senderJid, Handler handler) {
        if (context == null) return;
        android.content.SharedPreferences prefs = context.getSharedPreferences("com.waenhancer_preferences", Context.MODE_PRIVATE);
        boolean enabled = prefs.getBoolean("toastdeleted", false);
        if (!enabled) return;

        String key = (senderJid != null ? senderJid : "unknown") + "_deleted";
        long now = System.currentTimeMillis();
        Long lastTime = LAST_TOAST_MAP.get(key);
        if (lastTime != null && (now - lastTime) < MIN_TOAST_INTERVAL_MS) {
            return;
        }
        LAST_TOAST_MAP.put(key, now);

        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                String name = senderJid != null ? ContactNameResolver.INSTANCE.resolveName(context, senderJid, null, null) : "A contact";
                String text = name + " deleted a message";
                handler.post(() -> {
                    try {
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        });
    }

    private void showToastSafely(String message) {
        mainHandler.post(() -> {
            try {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
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
