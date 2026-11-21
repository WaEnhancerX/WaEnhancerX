package com.waenhancer.xposed.features.privacy;

import java.util.Locale;
import java.util.Set;

/** Decides whether a WhatsApp notification belongs to a locked chat. */
final class LockedNotificationPolicy {
    private LockedNotificationPolicy() {}

    /**
     * WhatsApp publishes a chat's conversation notification with the chat's JID as its shortcut
     * id. {@code lockedIds} are lower-case JID strings (see {@link LockedChatIds}).
     */
    static boolean isLockedChatNotification(String shortcutId, Set<String> lockedIds) {
        if (shortcutId == null || lockedIds == null || lockedIds.isEmpty()) return false;
        String id = shortcutId.trim().toLowerCase(Locale.ROOT);
        return !id.isEmpty() && lockedIds.contains(id);
    }
}
