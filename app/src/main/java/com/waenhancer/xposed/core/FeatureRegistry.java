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
import com.waenhancer.xposed.features.media.MediaPreviewHook;
import com.waenhancer.xposed.features.media.ProximitySensorHook;
import com.waenhancer.xposed.features.media.CallRecordingHook;
import com.waenhancer.xposed.features.media.ProfilePhotoDownloadHook;
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
import com.waenhancer.licensing.features.AudioToVoiceStatusFeature;
import com.waenhancer.licensing.features.MessageBomberFeature;
import com.waenhancer.licensing.features.StatusSplitterFeature;
import com.waenhancer.licensing.features.FileSizeSpooferFeature;
import com.waenhancer.licensing.features.BootloaderSpooferFeature;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.List;

/**
 * Lazy, failure-isolated hook installation. A missing class or an exception in one
 * feature must not prevent all remaining (independent) features from loading.
 */
public final class FeatureRegistry {

    @FunctionalInterface
    private interface FeatureFactory {
        BaseFeature create() throws Throwable;
    }

    @FunctionalInterface
    private interface Installer {
        void install() throws Throwable;
    }

    private static final class RegisteredFeature {
        final String name;
        final FeatureFactory factory;

        RegisteredFeature(String name, FeatureFactory factory) {
            this.name = name;
            this.factory = factory;
        }
    }

    private final Context context;
    private final ClassLoader classLoader;
    private final SharedPreferences prefs;
    private final List<RegisteredFeature> features = new ArrayList<>();

    public FeatureRegistry(@NonNull Context context, @NonNull ClassLoader classLoader,
                           @NonNull SharedPreferences prefs) {
        this.context = context;
        this.classLoader = classLoader;
        this.prefs = prefs;
        registerFeatures();
    }

    private void add(String name, FeatureFactory factory) {
        features.add(new RegisteredFeature(name, factory));
    }

    private void registerFeatures() {
        // Privacy & Seen Controls
        add("Anti-View Once", () -> new AntiViewOnceHook(context, classLoader, prefs));
        add("Typing Privacy", () -> new TypingPrivacyHook(context, classLoader, prefs));
        add("Freeze Last Seen", () -> new FreezeLastSeenHook(context, classLoader, prefs));
        add("Always Online", () -> new AlwaysOnlineHook(context, classLoader, prefs));
        add("Hide Receipts", () -> new HideReceiptsHook(context, classLoader, prefs));
        add("Blue On Reply", () -> new BlueOnReplyHook(context, classLoader, prefs));
        add("Call Privacy", () -> new CallPrivacyHook(context, classLoader, prefs));
        add("Anti Revoke", () -> new AntiRevokeHook(context, classLoader, prefs));
        add("Hide Forwarded Tag", () -> new HideForwardedTagHook(context, classLoader, prefs));
        add("Custom Privacy", () -> new CustomPrivacyHook(context, classLoader, prefs));
        add("Auto Next Status", () -> new com.waenhancer.xposed.features.privacy.AutoNextStatusHook(context, classLoader, prefs));
        add("Anti Disappearing", () -> new com.waenhancer.xposed.features.privacy.AntiDisappearingMessagesHook(context, classLoader, prefs));
        add("Locked Chats", () -> new com.waenhancer.xposed.features.privacy.LockedChatsEnhancerHook(context, classLoader, prefs));
        add("Hide Archived Chats", () -> new com.waenhancer.xposed.features.privacy.HideArchivedChatsHook(context, classLoader, prefs));
        add("DND Mode", () -> new com.waenhancer.xposed.features.privacy.DndModeHook(context, classLoader, prefs));
        add("Hide Chats Vault", () -> new com.waenhancer.xposed.features.privacy.HideChatsVaultHook(context, classLoader, prefs));

        // Conversation & Message Controls
        add("Anti Edit", () -> new AntiEditMessagesHook(context, classLoader, prefs));
        add("Sticker Confirm", () -> new StickerConfirmHook(context, classLoader, prefs));
        add("Jump First Message", () -> new JumpFirstMessageHook(context, classLoader, prefs));
        add("Preserve Delete For Me", () -> new PreserveDeleteForMeHook(context, classLoader, prefs));
        add("Copy Status Text", () -> new CopyStatusTextHook(context, classLoader, prefs));
        add("Chat Limits", () -> new ChatLimitsBypassHook(context, classLoader, prefs));
        add("Double Tap Reaction", () -> new DoubleTapReactionHook(context, classLoader, prefs));
        add("Minor Fixes", () -> new com.waenhancer.xposed.features.conversation.MinorFixesHook(context, classLoader, prefs));

        // Homescreen & Layout Controls
        add("Home Header Actions", () -> new HomeScreenHeaderActionsHook(context, classLoader, prefs));
        add("Chat List", () -> new ChatListCustomizationsHook(context, classLoader, prefs));

        // Media & Audio Controls
        add("Download View Once", () -> new DownloadViewOnceHook(context, classLoader, prefs));
        add("Status Download", () -> new com.waenhancer.xposed.features.media.StatusDownloadHook(context, classLoader, prefs));
        add("Media Quality", () -> new MediaQualityBypassHook(context, classLoader, prefs));
        add("Media Preview", () -> new MediaPreviewHook(context, classLoader, prefs));
        add("Proximity Sensor", () -> new ProximitySensorHook(context, classLoader, prefs));
        add("Call Recording", () -> new CallRecordingHook(context, classLoader, prefs));
        add("Profile Photo Download", () -> new ProfilePhotoDownloadHook(context, classLoader, prefs));

        // Automation, visual customizations and notifications
        add("Presence Toasts", () -> new PresenceToastsHook(context, classLoader, prefs));
        add("Tasker Integration", () -> new com.waenhancer.xposed.features.automation.TaskerIntegrationHook(context, classLoader, prefs));
        add("Chat Bubble Colors", () -> new com.waenhancer.xposed.features.conversation.ChatBubbleColorsHook(context, classLoader, prefs));
        add("Channel Recommendations", () -> new com.waenhancer.xposed.features.customization.ChannelRecommendationsFilterHook(context, classLoader, prefs));
        add("Presence Indicators", () -> new com.waenhancer.xposed.features.customization.OnlinePresenceIndicatorsHook(context, classLoader, prefs));
        add("Separate Groups", () -> new com.waenhancer.xposed.features.customization.SeparateGroupsHook(context, classLoader, prefs));
        add("Floating Bottom Bar", () -> new com.waenhancer.xposed.features.customization.FloatingBottomBarHook(context, classLoader, prefs));
    }

    private static void installSafely(String name, Installer installer) {
        try {
            installer.install();
        } catch (Throwable failure) {
            XposedBridge.log("[WAEX] Failed to install [" + name + "]: " + failure);
        }
    }

    public void initializeAll() {
        installSafely("Native WhatsApp Dialog", () -> NativeWhatsAppDialog.Companion.initialize(context, classLoader));
        installSafely("Bootloader Spoofer", () -> BootloaderSpooferFeature.install(context, classLoader, prefs));
        for (RegisteredFeature registered : features) {
            installSafely(registered.name, () -> {
                BaseFeature feature = registered.factory.create();
                feature.hook();
            });
        }
        // License checks remain implemented exclusively by the original licensing module.
        installSafely("Message Bomber", () -> MessageBomberFeature.install(context, classLoader, prefs));
        installSafely("Audio To Voice", () -> AudioToVoiceStatusFeature.install(context, classLoader, prefs));
        installSafely("Status Splitter", () -> StatusSplitterFeature.install(context, classLoader, prefs));
        installSafely("File Size Spoofer", () -> FileSizeSpooferFeature.install(context, classLoader, prefs));
    }
}
