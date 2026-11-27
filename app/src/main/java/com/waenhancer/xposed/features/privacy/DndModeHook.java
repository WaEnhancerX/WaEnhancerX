package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.privacy.PrivacyToggle;
import com.waenhancer.xposed.core.wa.SafeHooks;
import com.waenhancer.xposed.core.wa.WaTargets;

/**
 * Do Not Disturb (DND) Mode: skips WhatsApp's MessageHandler/start so no incoming traffic is
 * processed while the device stays online. The anchor string has not changed since 2024.
 */
public class DndModeHook extends BaseFeature {

    public DndModeHook(@NonNull Context context,
                       @NonNull ClassLoader classLoader,
                       @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Do Not Disturb (DND) Mode";
    }

    @Override
    public void hook() throws Throwable {
        SafeHooks.skipWhile(WaTargets.messageHandlerStart(context, classLoader),
                () -> getPrivacy().isOn(PrivacyToggle.DND));
    }
}
