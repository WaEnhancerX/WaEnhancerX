package com.waenhancer.xposed.core.privacy;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.config.DndPreferencePolicy;
import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient;
import com.waenhancer.xposed.core.wa.WaJid;
import com.waenhancer.xposed.features.privacy.CustomPrivacyHook;

import org.json.JSONObject;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Live answers to "should this privacy toggle apply right now, for this chat?".
 *
 * <p>Hooks call these methods on every event instead of caching preference values, so a change
 * written by the settings screen or a quick action ({@link #set}) applies to the very next
 * receipt, presence update or call, without restarting WhatsApp. The preference bridge keeps an
 * in-memory map, so each check is a few hash lookups.</p>
 *
 * <p>Resolution order for a chat: Ghost Mode (when it covers the toggle) &gt; per-contact rule
 * &gt; global switch.</p>
 */
public final class PrivacyPrefs {

    /** Stored per-contact rule keys: "123_privacy", "123@s.whatsapp.net_privacy", "per_contact_rules_x". */
    private static final Pattern CONTACT_RULE_KEY =
            Pattern.compile("^(?:[^:]+:)?(?:per_contact_rules_.+|[0-9]+(?:@[a-z.]+)?_privacy)$");

    @NonNull private final SharedPreferences prefs;
    @Nullable private final String scopePrefix;

    public PrivacyPrefs(@NonNull SharedPreferences prefs, @Nullable String targetPackage) {
        this.prefs = prefs;
        this.scopePrefix = targetPackage == null || targetPackage.isEmpty() ? null : targetPackage + ":";
    }

    public static PrivacyPrefs of(@NonNull Context context, @NonNull SharedPreferences prefs) {
        return new PrivacyPrefs(prefs, context.getPackageName());
    }

    // ─── Global state ────────────────────────────────────────────────────

    /** Whether the toggle is on globally right now (Ghost Mode included). */
    public boolean isOn(@NonNull PrivacyToggle toggle) {
        if (toggle.ghostCovered && isOn(PrivacyToggle.GHOST_MODE)) return true;
        switch (toggle) {
            case GHOST_MODE:
                // A runtime "active" flag left behind after the user explicitly disabled Ghost
                // Mode must not keep hiding everything with no visible control to turn it off.
                // When no availability key was ever stored (older setups), the flag alone counts.
                if (!flag("ghostmode_active")) return false;
                return anyFlag(toggle.availabilityKeys) || !anyContains(toggle.availabilityKeys);
            case ALWAYS_ONLINE:
                // Never announce "online" while the user asked to freeze or hide presence.
                return flag("always_online") && !isOn(PrivacyToggle.FREEZE_LAST_SEEN);
            case DND:
                return DndPreferencePolicy.isEnabled(this::contains, this::flag);
            default:
                return anyFlag(toggle.onKeys);
        }
    }

    /**
     * Whether the toggle is on now OR could be switched on at runtime (it is offered in quick
     * actions, or Ghost Mode is available and covers it). The feature registry installs hooks
     * whenever this is true, which is what makes runtime switching work.
     */
    public boolean isAvailable(@NonNull PrivacyToggle toggle) {
        if (isOn(toggle) || anyFlag(toggle.availabilityKeys)) return true;
        if (toggle == PrivacyToggle.GHOST_MODE) return flag("ghostmode_active");
        if (toggle == PrivacyToggle.DND) return contains("dnd_mode") || flag("dndmode");
        return toggle.ghostCovered && isAvailable(PrivacyToggle.GHOST_MODE);
    }

    /** True if any of the toggles is available; convenience for feature registration. */
    public boolean anyAvailable(@NonNull PrivacyToggle... toggles) {
        for (PrivacyToggle toggle : toggles) {
            if (isAvailable(toggle)) return true;
        }
        return false;
    }

    // ─── Per chat ────────────────────────────────────────────────────────

    /** Whether the toggle applies to this chat, honouring Ghost Mode and per-contact rules. */
    public boolean appliesTo(@NonNull Context context, @NonNull PrivacyToggle toggle, @Nullable String chatJid) {
        if (toggle.ghostCovered && isOn(PrivacyToggle.GHOST_MODE)) return true;
        boolean global = isOn(toggle);
        if (!toggle.supportsPerContact() || chatJid == null) return global;
        JSONObject rule = CustomPrivacyHook.getContactPrivacy(context, prefs, chatJid);
        if (rule == null) return global;
        for (String key : toggle.contactKeys) {
            if (rule.has(key)) return rule.optBoolean(key, global);
        }
        return global;
    }

    /** Read-receipt toggle for a chat: groups and one-to-one chats have separate switches. */
    public boolean hideReadFor(@NonNull Context context, @Nullable String chatJid) {
        PrivacyToggle toggle = WaJid.isGroup(chatJid) ? PrivacyToggle.HIDE_READ_GROUP : PrivacyToggle.HIDE_READ;
        return appliesTo(context, toggle, chatJid);
    }

    /** Whether any per-contact privacy rule is stored (rules can enable a toggle that is off globally). */
    public boolean hasContactRules() {
        if (!prefs.getBoolean("custom_privacy", true)) return false;
        Map<String, ?> all = prefs.getAll();
        if (all == null) return false;
        for (String key : all.keySet()) {
            if (key != null && CONTACT_RULE_KEY.matcher(key).matches()) return true;
        }
        return false;
    }

    // ─── Writing (quick actions / UI) ────────────────────────────────────

    /**
     * Switch a toggle at runtime. Writes the toggle's runtime key in the same scope the user's
     * settings use, so the running hooks pick it up on their next event.
     */
    public void set(@NonNull PrivacyToggle toggle, boolean on) {
        String key = toggle.runtimeKey;
        String scoped = scopePrefix != null ? scopePrefix + key : null;
        String target = scoped != null && !prefs.contains(key) && prefs.contains(scoped) ? scoped : key;
        prefs.edit().putBoolean(target, on).apply();
    }

    // ─── Preference reading ──────────────────────────────────────────────

    private boolean anyContains(@NonNull String[] keys) {
        for (String key : keys) {
            if (contains(key)) return true;
        }
        return false;
    }

    private boolean anyFlag(@NonNull String[] keys) {
        for (String key : keys) {
            if (flag(key)) return true;
        }
        return false;
    }

    /** Root key first, then this target's scoped key; never another target's. */
    public boolean contains(@NonNull String key) {
        return prefs.contains(key) || (scopePrefix != null && prefs.contains(scopePrefix + key));
    }

    /** Boolean value tolerant of legacy String/Number storage. */
    public boolean flag(@NonNull String key) {
        String resolved = prefs.contains(key) ? key
                : scopePrefix != null && prefs.contains(scopePrefix + key) ? scopePrefix + key : null;
        if (resolved == null) return false;
        if (prefs instanceof PreferenceBridgeClient) {
            // The bridge exposes its live map without copying; read the stored value as-is.
            return toFlag(prefs.getAll().get(resolved));
        }
        try {
            return prefs.getBoolean(resolved, false);
        } catch (ClassCastException notBoolean) {
            try {
                String text = prefs.getString(resolved, null);
                return text != null && !text.isEmpty() && !"0".equals(text) && !"false".equalsIgnoreCase(text);
            } catch (ClassCastException notString) {
                try {
                    return prefs.getInt(resolved, 0) != 0;
                } catch (ClassCastException ignored) {
                    return false;
                }
            }
        }
    }

    static boolean toFlag(@Nullable Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value instanceof String) {
            String text = (String) value;
            return !text.isEmpty() && !"0".equals(text) && !"false".equalsIgnoreCase(text);
        }
        return false;
    }
}
