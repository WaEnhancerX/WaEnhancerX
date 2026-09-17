package com.waenhancer.xposed.core;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.components.NativeWhatsAppDialog;
import com.waenhancer.xposed.features.automation.PresenceToastsHook;
import com.waenhancer.xposed.features.conversation.AntiEditMessagesHook;
import com.waenhancer.xposed.features.conversation.ChatLimitsBypassHook;
import com.waenhancer.xposed.features.conversation.CopyStatusTextHook;
import com.waenhancer.xposed.features.conversation.DoubleTapReactionHook;
import com.waenhancer.xposed.features.conversation.JumpFirstMessageHook;
import com.waenhancer.xposed.features.conversation.PreserveDeleteForMeHook;
import com.waenhancer.xposed.features.conversation.StickerConfirmHook;
import com.waenhancer.xposed.features.homescreen.ChatListCustomizationsHook;
import com.waenhancer.xposed.features.homescreen.HomeScreenHeaderActionsHook;
import com.waenhancer.xposed.features.media.DownloadViewOnceHook;
import com.waenhancer.xposed.features.media.MediaQualityBypassHook;
import com.waenhancer.xposed.features.media.ProximitySensorHook;
import com.waenhancer.xposed.features.privacy.AlwaysOnlineHook;
import com.waenhancer.xposed.features.privacy.AntiRevokeHook;
import com.waenhancer.xposed.features.privacy.AntiViewOnceHook;
import com.waenhancer.xposed.features.privacy.BlueOnReplyHook;
import com.waenhancer.xposed.features.privacy.CallPrivacyHook;
import com.waenhancer.xposed.features.privacy.CustomPrivacyHook;
import com.waenhancer.xposed.features.privacy.FreezeLastSeenHook;
import com.waenhancer.xposed.features.privacy.HideForwardedTagHook;
import com.waenhancer.xposed.features.privacy.HideReceiptsHook;
import com.waenhancer.xposed.features.privacy.TypingPrivacyHook;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates and registers all modular hooks in WAEX.
 */
public final class FeatureRegistry {

    private final List<BaseFeature> features = new ArrayList<>();

    public FeatureRegistry(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        // Initialize Native WhatsApp WDS / Material Dialog engine
        NativeWhatsAppDialog.Companion.initialize(context, classLoader);

        // Privacy & Seen Controls
        features.add(new AntiViewOnceHook(context, classLoader, prefs));
        features.add(new TypingPrivacyHook(context, classLoader, prefs));
        features.add(new FreezeLastSeenHook(context, classLoader, prefs));
        features.add(new AlwaysOnlineHook(context, classLoader, prefs));
        features.add(new HideReceiptsHook(context, classLoader, prefs));
        features.add(new BlueOnReplyHook(context, classLoader, prefs));
        features.add(new CallPrivacyHook(context, classLoader, prefs));
        features.add(new AntiRevokeHook(context, classLoader, prefs));
        features.add(new HideForwardedTagHook(context, classLoader, prefs));
        features.add(new CustomPrivacyHook(context, classLoader, prefs));
        features.add(new com.waenhancer.xposed.features.privacy.AutoNextStatusHook(context, classLoader, prefs));
        features.add(new com.waenhancer.xposed.features.privacy.AntiDisappearingMessagesHook(context, classLoader, prefs));
        features.add(new com.waenhancer.xposed.features.privacy.LockedChatsEnhancerHook(context, classLoader, prefs));
        features.add(new com.waenhancer.xposed.features.privacy.HideArchivedChatsHook(context, classLoader, prefs));
        features.add(new com.waenhancer.xposed.features.privacy.DndModeHook(context, classLoader, prefs));
        features.add(new com.waenhancer.xposed.features.privacy.HideChatsVaultHook(context, classLoader, prefs));

        // Conversation & Message Controls
        features.add(new AntiEditMessagesHook(context, classLoader, prefs));
        features.add(new StickerConfirmHook(context, classLoader, prefs));
        features.add(new JumpFirstMessageHook(context, classLoader, prefs));
        features.add(new PreserveDeleteForMeHook(context, classLoader, prefs));
        features.add(new CopyStatusTextHook(context, classLoader, prefs));
        features.add(new ChatLimitsBypassHook(context, classLoader, prefs));
        features.add(new DoubleTapReactionHook(context, classLoader, prefs));

        // Homescreen & Layout Controls
        features.add(new HomeScreenHeaderActionsHook(context, classLoader, prefs));
        features.add(new ChatListCustomizationsHook(context, classLoader, prefs));

        // Media & Audio Controls
        features.add(new DownloadViewOnceHook(context, classLoader, prefs));
        features.add(new MediaQualityBypassHook(context, classLoader, prefs));
        features.add(new ProximitySensorHook(context, classLoader, prefs));

        // Automation & Notification Controls
        features.add(new PresenceToastsHook(context, classLoader, prefs));
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
