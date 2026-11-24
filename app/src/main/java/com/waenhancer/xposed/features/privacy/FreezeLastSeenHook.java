package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.privacy.PrivacyToggle;
import com.waenhancer.xposed.core.wa.SafeHooks;
import com.waenhancer.xposed.core.wa.WaTargets;

/**
 * Freeze Last Seen: skips PresenceStateManager.setAvailable, so WhatsApp never announces that
 * the user came online and the last-seen timestamp stays where it was.
 *
 * One hook on one void method anchored by WhatsApp's own log string
 * ("presencestatemanager/setAvailable/new-state", unchanged since 2024). The switch is read
 * live, so Freeze / Ghost Mode quick actions apply without a restart.
 */
public class FreezeLastSeenHook extends BaseFeature {

    public FreezeLastSeenHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        SafeHooks.skipWhile(WaTargets.presenceSetAvailable(context, classLoader),
                () -> getPrivacy().isOn(PrivacyToggle.FREEZE_LAST_SEEN));
    }

    @NonNull
    @Override
    public String getName() {
        return "Freeze Last Seen";
    }
}
