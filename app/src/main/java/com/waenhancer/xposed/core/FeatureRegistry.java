package com.waenhancer.xposed.core;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.components.NativeWhatsAppDialog;
import com.waenhancer.xposed.features.conversation.AntiEditMessagesHook;
import com.waenhancer.xposed.features.conversation.CopyStatusTextHook;
import com.waenhancer.xposed.features.conversation.JumpFirstMessageHook;
import com.waenhancer.xposed.features.conversation.PreserveDeleteForMeHook;
import com.waenhancer.xposed.features.conversation.StickerConfirmHook;
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
        // Initialize Native WhatsApp WDS / Material Dialog engine
        NativeWhatsAppDialog.Companion.initialize(context, classLoader);

        // Privacy Core Features
        features.add(new AntiViewOnceHook(context, classLoader, prefs));
        features.add(new TypingPrivacyHook(context, classLoader, prefs));
        features.add(new FreezeLastSeenHook(context, classLoader, prefs));
        features.add(new HideReceiptsHook(context, classLoader, prefs));
        features.add(new AntiRevokeHook(context, classLoader, prefs));
        features.add(new com.waenhancer.xposed.features.privacy.HideForwardedTagHook(context, classLoader, prefs));
        features.add(new com.waenhancer.xposed.features.privacy.CustomPrivacyHook(context, classLoader, prefs));

        // Conversation & Message Controls Features
        features.add(new AntiEditMessagesHook(context, classLoader, prefs));
        features.add(new StickerConfirmHook(context, classLoader, prefs));
        features.add(new JumpFirstMessageHook(context, classLoader, prefs));
        features.add(new PreserveDeleteForMeHook(context, classLoader, prefs));
        features.add(new CopyStatusTextHook(context, classLoader, prefs));
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
