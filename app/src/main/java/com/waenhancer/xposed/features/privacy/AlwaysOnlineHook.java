package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.privacy.PrivacyToggle;
import com.waenhancer.xposed.core.wa.SafeHooks;
import com.waenhancer.xposed.core.wa.WaTargets;

/**
 * Always Online: skips PresenceStateManager's transition to "unavailable", so WhatsApp keeps
 * reporting the user as online. Yields to Freeze Last Seen and Ghost Mode (see PrivacyPrefs).
 */
public class AlwaysOnlineHook extends BaseFeature {

    public AlwaysOnlineHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        SafeHooks.skipWhile(WaTargets.presenceTransitionToUnavailable(context, classLoader),
                () -> getPrivacy().isOn(PrivacyToggle.ALWAYS_ONLINE));
    }

    @NonNull
    @Override
    public String getName() {
        return "Always Online";
    }
}
