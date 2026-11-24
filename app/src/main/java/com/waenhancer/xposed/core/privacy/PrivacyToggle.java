package com.waenhancer.xposed.core.privacy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Every privacy switch WAEX understands, with all the preference keys that mean the same thing.
 *
 * This enum is the only place that knows preference key names. Hooks, the feature registry and
 * any future quick action ask {@link PrivacyPrefs} about a toggle; they never read keys directly.
 * Adding a legacy alias or a new quick action therefore never touches hook code.
 *
 * <ul>
 *   <li>{@link #onKeys} - any of them true turns the toggle on (current key + legacy aliases).</li>
 *   <li>{@link #runtimeKey} - the key a quick action writes; read live, no restart needed.</li>
 *   <li>{@link #availabilityKeys} - the user made the toggle available (e.g. shown in quick
 *       actions) without turning it on. Hooks must be installed in that case so a later
 *       runtime switch takes effect immediately.</li>
 *   <li>{@link #ghostCovered} - Ghost Mode forces this toggle on.</li>
 *   <li>{@link #contactKeys} - per-contact rule names that override the global value.</li>
 * </ul>
 */
public enum PrivacyToggle {

    GHOST_MODE(
            keys("ghostmode_active"),
            "ghostmode_active",
            keys("ghostmode", "ghostmode_enabled"),
            false,
            keys()),

    FREEZE_LAST_SEEN(
            keys("freeze_last_seen", "freeze_last_seen_active"),
            "freeze_last_seen_active",
            keys("freezelastseen", "show_freezeLastSeen"),
            true,
            keys()),

    ALWAYS_ONLINE(
            keys("always_online"),
            "always_online",
            keys(),
            false,
            keys()),

    HIDE_TYPING(
            keys("typing_privacy", "hide_typing", "ghostmode_t"),
            "hide_typing",
            keys(),
            true,
            keys("HideTyping", "hide_typing")),

    HIDE_RECORDING(
            keys("typing_privacy", "hide_recording", "ghostmode_r"),
            "hide_recording",
            keys(),
            true,
            keys("HideRecording", "hide_recording")),

    /** Blue ticks in one-to-one chats. */
    HIDE_READ(
            keys("hide_read_receipts", "hideread"),
            "hide_read_receipts",
            keys(),
            true,
            keys("HideSeen")),

    /** Blue ticks in groups. */
    HIDE_READ_GROUP(
            keys("hideread_group"),
            "hideread_group",
            keys(),
            true,
            keys("HideSeen")),

    /** Second (grey) tick. */
    HIDE_DELIVERY(
            keys("hide_delivery_receipts", "hidereceipt"),
            "hide_delivery_receipts",
            keys("show_hidereceipt"),
            true,
            keys("HideReceipt")),

    /** "Played" receipt for voice notes. */
    HIDE_VOICE_PLAYED(
            keys("hide_seen_receipts", "hideaudioseen"),
            "hide_seen_receipts",
            keys(),
            true,
            keys()),

    /** "Opened" receipt for view-once media. */
    HIDE_VIEW_ONCE_OPENED(
            keys("hideonceseen"),
            "hideonceseen",
            keys(),
            true,
            keys()),

    /** Viewing statuses without appearing in the viewer list. */
    STEALTH_STATUS(
            keys("stealth_status_view", "stealth_view_status", "hidestatusview"),
            "stealth_status_view",
            keys(),
            true,
            keys("HideViewStatus")),

    /** Keep received view-once media as normal media. */
    ANTI_VIEW_ONCE(
            keys("anti_view_once", "viewonce"),
            "anti_view_once",
            keys(),
            false,
            keys()),

    /** Do Not Disturb: stop processing incoming traffic. Legacy "dndmode" honoured by presence. */
    DND(
            keys("dnd_mode"),
            "dnd_mode",
            keys("show_dndmode"),
            false,
            keys());

    @NonNull final String[] onKeys;
    @NonNull final String runtimeKey;
    @NonNull final String[] availabilityKeys;
    final boolean ghostCovered;
    @NonNull final String[] contactKeys;

    PrivacyToggle(@NonNull String[] onKeys, @NonNull String runtimeKey,
                  @NonNull String[] availabilityKeys, boolean ghostCovered,
                  @NonNull String[] contactKeys) {
        this.onKeys = onKeys;
        this.runtimeKey = runtimeKey;
        this.availabilityKeys = availabilityKeys;
        this.ghostCovered = ghostCovered;
        this.contactKeys = contactKeys;
    }

    /** The key a quick action (or any UI) should write to switch this toggle at runtime. */
    @NonNull
    public String runtimeKey() {
        return runtimeKey;
    }

    public boolean isGhostCovered() {
        return ghostCovered;
    }

    public boolean supportsPerContact() {
        return contactKeys.length > 0;
    }

    @Nullable
    public static PrivacyToggle fromRuntimeKey(@Nullable String key) {
        if (key == null) return null;
        for (PrivacyToggle toggle : values()) {
            if (toggle.runtimeKey.equals(key)) return toggle;
        }
        return null;
    }

    private static String[] keys(String... keys) {
        return keys;
    }
}
