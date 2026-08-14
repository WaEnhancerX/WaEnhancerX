package com.waenhancer.xposed.core;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.features.privacy.AntiRevokeHook;
import com.waenhancer.xposed.features.privacy.AntiViewOnceHook;
import com.waenhancer.xposed.features.privacy.FreezeLastSeenHook;
import com.waenhancer.xposed.features.privacy.HideReceiptsHook;
import com.waenhancer.xposed.features.privacy.TypingPrivacyHook;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates and registers all modern modular hooks in WAEX.
 */
public final class FeatureRegistry {

    private final List<BaseFeature> features = new ArrayList<>();

    public FeatureRegistry(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        // Privacy Core Features
        features.add(new AntiViewOnceHook(context, classLoader, prefs));
        features.add(new TypingPrivacyHook(context, classLoader, prefs));
        features.add(new FreezeLastSeenHook(context, classLoader, prefs));
        features.add(new HideReceiptsHook(context, classLoader, prefs));
        features.add(new AntiRevokeHook(context, classLoader, prefs));
    }


    public void initializeAll() {
        for (BaseFeature feature : features) {
            try {
                feature.hook();
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Error initializing feature [" + feature.getName() + "]: " + t.getMessage());
            }
        }
    }
}
