package com.waenhancer.xposed.features.privacy;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.db.HideSeenStore;
import com.waenhancer.xposed.core.wa.WaJid;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Persistent record of messages whose read receipt WhatsApp did not send because Ghost Mode,
 * Hide blue ticks or Hide delivered withheld it. Survives restarts, so the manual "Send blue
 * tick" button and Blue-on-reply can later send receipts for every one of them, including
 * messages from before those features were turned on.
 */
public final class UnsentReadLedger {

    /** Owed receipts older than this are dropped; sending them would be meaningless. */
    static final long OWED_MAX_AGE_MS = TimeUnit.DAYS.toMillis(30);
    static final long SENT_MAX_AGE_MS = TimeUnit.DAYS.toMillis(7);

    private UnsentReadLedger() {}

    /** Only ordinary chats and groups have read receipts that can be sent later. */
    static boolean isTrackable(@Nullable String chat) {
        return chat != null && (WaJid.isPhone(chat) || WaJid.isLid(chat) || WaJid.isGroup(chat));
    }

    /** Record withheld read receipts. {@code sender} is kept for groups only. */
    public static void track(@NonNull Context context, @Nullable String chat, @Nullable String sender,
                             @Nullable Iterable<String> messageIds) {
        String normalizedChat = WaJid.raw(chat);
        if (!isTrackable(normalizedChat) || messageIds == null) return;
        String participant = WaJid.isGroup(normalizedChat) ? WaJid.raw(sender) : null;
        HideSeenStore.getInstance(context).trackUnsentReadsAsync(normalizedChat, participant, messageIds);
    }

    public static void track(@NonNull Context context, @Nullable String chat, @Nullable String sender,
                             @Nullable String messageId) {
        if (messageId == null || messageId.isEmpty()) return;
        track(context, chat, sender, Collections.singletonList(messageId));
    }

    /** A read receipt for these messages went out (from WhatsApp or from a manual send). */
    public static void markSent(@NonNull Context context, @Nullable String chat,
                                @Nullable Iterable<String> messageIds) {
        String normalizedChat = WaJid.raw(chat);
        if (!isTrackable(normalizedChat)) return;
        HideSeenStore.getInstance(context).setReadsSentAsync(normalizedChat, messageIds, true);
    }

    /** A send attempt was vetoed after all; keep the messages owed. */
    public static void markOwed(@NonNull Context context, @Nullable String chat,
                                @Nullable Iterable<String> messageIds) {
        String normalizedChat = WaJid.raw(chat);
        if (!isTrackable(normalizedChat)) return;
        HideSeenStore.getInstance(context).setReadsSentAsync(normalizedChat, messageIds, false);
    }

    /** Owed receipts for a chat under any of its identities (LID and phone). Call off the main thread. */
    @NonNull
    public static List<HideSeenStore.PendingRead> owed(@NonNull Context context, @Nullable String chat, int limit) {
        String normalizedChat = WaJid.raw(chat);
        if (!isTrackable(normalizedChat)) return Collections.emptyList();
        return HideSeenStore.getInstance(context).pendingReads(WaJid.aliases(context, normalizedChat), limit);
    }

    public static void prune(@NonNull Context context) {
        HideSeenStore.getInstance(context).pruneReadsAsync(OWED_MAX_AGE_MS, SENT_MAX_AGE_MS);
    }
}
