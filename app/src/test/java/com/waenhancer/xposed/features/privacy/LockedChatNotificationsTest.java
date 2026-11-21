package com.waenhancer.xposed.features.privacy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class LockedChatNotificationsTest {
    private static final Set<String> LOCKED =
            new HashSet<>(Arrays.asList("123@lid", "15551234567@s.whatsapp.net"));

    @Test
    public void matchesTheChatJidCaseInsensitivelyForBothIdentities() {
        assertTrue(LockedNotificationPolicy.isLockedChatNotification("123@LID", LOCKED));
        assertTrue(LockedNotificationPolicy.isLockedChatNotification(" 15551234567@s.whatsapp.net ", LOCKED));
    }

    @Test
    public void otherChatsAndMissingDataAreNeverSuppressed() {
        assertFalse(LockedNotificationPolicy.isLockedChatNotification("999@lid", LOCKED));
        assertFalse(LockedNotificationPolicy.isLockedChatNotification("123-456@g.us", LOCKED));
        assertFalse(LockedNotificationPolicy.isLockedChatNotification(null, LOCKED));
        assertFalse(LockedNotificationPolicy.isLockedChatNotification("", LOCKED));
        assertFalse(LockedNotificationPolicy.isLockedChatNotification("123@lid", Collections.emptySet()));
        assertFalse(LockedNotificationPolicy.isLockedChatNotification("123@lid", null));
    }

    @Test
    public void suppressionOnlyDependsOnTheMessageNotificationsSwitchDefaultingToOn() {
        assertFalse("default: notifications shown", LockedChatNotificationsHook.isSuppressing(prefs(null)));
        assertFalse(LockedChatNotificationsHook.isSuppressing(prefs(true)));
        assertTrue(LockedChatNotificationsHook.isSuppressing(prefs(false)));
    }

    private static SharedPreferences prefs(Boolean messageNotifications) {
        return (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (!"getBoolean".equals(method.getName())) return null;
                    if ("locked_chats_message_notifications".equals(args[0])) {
                        return messageNotifications != null ? messageNotifications : args[1];
                    }
                    return args[1];
                });
    }
}
