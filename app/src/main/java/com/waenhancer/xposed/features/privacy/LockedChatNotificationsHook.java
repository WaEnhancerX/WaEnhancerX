package com.waenhancer.xposed.features.privacy;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.utils.HookErrors;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drops message notifications of locked chats when the "Message notifications" switch is turned
 * off. It works on its own: Hide Locked Chats does not have to be enabled.
 *
 * It intercepts the framework's {@code NotificationManager.notify} rather than a WhatsApp
 * method, and recognises the chat through the notification's shortcut id (the chat JID), so it
 * depends on no obfuscated name. Locked chats come from WhatsApp's own database.
 */
public final class LockedChatNotificationsHook extends BaseFeature {
    private static final String TAG = "[WAEX][LockedNotifications]";
    public static final String PREF_MESSAGE_NOTIFICATIONS = "locked_chats_message_notifications";

    private final AtomicBoolean reportedUnavailable = new AtomicBoolean();
    private final LockedChatIds lockedChatIds = new LockedChatIds(
            () -> LockedChatIds.overDatabase(() -> MessageStore.getInstance(context).getDatabase()),
            System::currentTimeMillis);

    public LockedChatNotificationsHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                       @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    /** The "Message notifications" switch (default on) is off. Independent of Hide Locked Chats. */
    public static boolean isSuppressing(@NonNull SharedPreferences prefs) {
        return !prefs.getBoolean(PREF_MESSAGE_NOTIFICATIONS, true);
    }

    @NonNull
    @Override
    public String getName() {
        return "Locked Chat Notifications";
    }

    @Override
    public void hook() {
        if (!isSuppressing(prefs) || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        XposedBridge.hookAllMethods(NotificationManager.class, "notify", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (shouldDrop(param.args)) param.setResult(null);
            }
        });
    }

    private boolean shouldDrop(Object[] args) {
        try {
            if (!isSuppressing(prefs) || args == null) return false;
            Notification notification = null;
            for (Object arg : args) {
                if (arg instanceof Notification) notification = (Notification) arg;
            }
            if (notification == null) return false;
            String shortcutId = notification.getShortcutId();
            if (shortcutId == null) return false;
            Set<String> locked = lockedChatIds.current();
            if (locked == null) {
                if (reportedUnavailable.compareAndSet(false, true)) {
                    XposedBridge.log(TAG + " locked chats unavailable; notifications are not suppressed");
                }
                return false;
            }
            return LockedNotificationPolicy.isLockedChatNotification(shortcutId, locked);
        } catch (Throwable error) {
            HookErrors.swallowed("LockedChatNotificationsHook", error);
            return false;
        }
    }
}
