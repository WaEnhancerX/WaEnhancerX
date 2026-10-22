package com.waenhancer.xposed.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.components.NativeWhatsAppDialog;
import com.waenhancer.xposed.features.automation.PresenceToastsHook;
import com.waenhancer.xposed.features.automation.TaskerIntegrationHook;
import com.waenhancer.xposed.features.conversation.AntiEditMessagesHook;
import com.waenhancer.xposed.features.conversation.ChatBubbleColorsHook;
import com.waenhancer.xposed.features.conversation.ChatLimitsBypassHook;
import com.waenhancer.xposed.features.conversation.CopyStatusTextHook;
import com.waenhancer.xposed.features.conversation.DoubleTapReactionHook;
import com.waenhancer.xposed.features.conversation.JumpFirstMessageHook;
import com.waenhancer.xposed.features.conversation.MessageDeviceSourceHook;
import com.waenhancer.xposed.features.conversation.MinorFixesHook;
import com.waenhancer.xposed.features.conversation.PreserveDeleteForMeHook;
import com.waenhancer.xposed.features.conversation.StickerConfirmHook;
import com.waenhancer.xposed.features.customization.ChannelRecommendationsFilterHook;
import com.waenhancer.xposed.features.customization.ChatFilterDisplayHook;
import com.waenhancer.xposed.features.customization.CustomDpiHook;
import com.waenhancer.xposed.features.customization.CustomTimeHook;
import com.waenhancer.xposed.features.customization.CssThemeHook;
import com.waenhancer.xposed.features.customization.FilterItemsHook;
import com.waenhancer.xposed.features.customization.FloatingBottomBarHook;
import com.waenhancer.xposed.features.customization.OnlinePresenceIndicatorsHook;
import com.waenhancer.xposed.features.customization.HideHomeTabsHook;
import com.waenhancer.xposed.features.customization.IGStatusHook;
import com.waenhancer.xposed.features.customization.NewSettingsStyleHook;
import com.waenhancer.xposed.features.customization.PropsOverrideHook;
import com.waenhancer.xposed.features.customization.SeparateGroupsHook;
import com.waenhancer.xposed.features.customization.StatusStyleHook;
import com.waenhancer.xposed.features.homescreen.ChatListCustomizationsHook;
import com.waenhancer.xposed.features.homescreen.HomeScreenHeaderActionsHook;
import com.waenhancer.xposed.features.media.CallRecordingHook;
import com.waenhancer.xposed.features.media.DownloadViewOnceHook;
import com.waenhancer.xposed.features.media.MediaPreviewHook;
import com.waenhancer.xposed.features.media.MediaQualityBypassHook;
import com.waenhancer.xposed.features.media.ProfilePhotoDownloadHook;
import com.waenhancer.xposed.features.media.ProximitySensorHook;
import com.waenhancer.xposed.features.media.StatusDownloadHook;
import com.waenhancer.xposed.features.privacy.AlwaysOnlineHook;
import com.waenhancer.xposed.features.privacy.AntiDisappearingMessagesHook;
import com.waenhancer.xposed.features.privacy.AntiRevokeHook;
import com.waenhancer.xposed.features.privacy.AntiViewOnceHook;
import com.waenhancer.xposed.features.privacy.AutoNextStatusHook;
import com.waenhancer.xposed.features.privacy.BlueOnReplyHook;
import com.waenhancer.xposed.features.privacy.CallPrivacyHook;
import com.waenhancer.xposed.features.privacy.CallTypeControllerHook;
import com.waenhancer.xposed.features.privacy.CustomPrivacyHook;
import com.waenhancer.xposed.features.privacy.DndModeHook;
import com.waenhancer.xposed.features.privacy.FreezeLastSeenHook;
import com.waenhancer.xposed.features.privacy.HideArchivedChatsHook;
import com.waenhancer.xposed.features.privacy.HideChatsVaultHook;
import com.waenhancer.xposed.features.privacy.HideForwardedTagHook;
import com.waenhancer.xposed.features.privacy.HideReceiptsHook;
import com.waenhancer.xposed.features.privacy.LockedChatsEnhancerHook;
import com.waenhancer.xposed.features.privacy.TypingPrivacyHook;
import com.waenhancer.licensing.features.AudioToVoiceStatusFeature;
import com.waenhancer.licensing.features.BootloaderSpooferFeature;
import com.waenhancer.licensing.features.FileSizeSpooferFeature;
import com.waenhancer.licensing.features.MessageBomberFeature;
import com.waenhancer.licensing.features.NewChatFeature;
import com.waenhancer.licensing.features.StatusSplitterFeature;
import com.waenhancer.licensing.features.UnlockWaPlusFeature;
import de.robv.android.xposed.XposedBridge;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates and registers all modular hooks in WAEX and reports injected status.
 */
public final class FeatureRegistry {

    private final List<FeatureEntry> features = new ArrayList<>();

    @FunctionalInterface
    private interface FeatureFactory {
        BaseFeature create() throws Throwable;
    }

    public static final class FeatureEntry {
        public BaseFeature feature;
        public String name;
        public final String className;
        public final String category;
        private final FeatureFactory factory;
        public String status = "INITIALIZED";
        public String error = null;

        public FeatureEntry(BaseFeature feature, String category) {
            this.feature = feature;
            this.name = feature.getName();
            this.className = feature.getClass().getSimpleName();
            this.category = category;
            this.factory = null;
        }

        public FeatureEntry(String name, String className, String category, String status) {
            this.feature = null;
            this.name = name;
            this.className = className;
            this.category = category;
            this.status = status;
            this.factory = null;
        }

        private FeatureEntry(String className, String category, FeatureFactory factory) {
            this.feature = null;
            this.name = className;
            this.className = className;
            this.category = category;
            this.factory = factory;
        }
    }

    public FeatureRegistry(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        // Initialize Native WhatsApp WDS / Material Dialog engine
        try {
            NativeWhatsAppDialog.Companion.initialize(context, classLoader);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Native dialog initialization failed: " + t.getMessage());
        }
        try {
            BootloaderSpooferFeature.install(context, classLoader, prefs);
            features.add(new FeatureEntry("Bootloader Spoofing & Integrity", "BootloaderSpooferFeature", "Licensing", "ACTIVE"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("Bootloader Spoofing & Integrity", "BootloaderSpooferFeature", "Licensing", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }

        // Privacy & Seen Controls
        addFeature(AntiViewOnceHook.class, "Privacy", () -> new AntiViewOnceHook(context, classLoader, prefs));
        addFeature(TypingPrivacyHook.class, "Privacy", () -> new TypingPrivacyHook(context, classLoader, prefs));
        addFeature(FreezeLastSeenHook.class, "Privacy", () -> new FreezeLastSeenHook(context, classLoader, prefs));
        addFeature(AlwaysOnlineHook.class, "Privacy", () -> new AlwaysOnlineHook(context, classLoader, prefs));
        addFeature(HideReceiptsHook.class, "Privacy", () -> new HideReceiptsHook(context, classLoader, prefs));
        addFeature(BlueOnReplyHook.class, "Privacy", () -> new BlueOnReplyHook(context, classLoader, prefs));
        addFeature(CallPrivacyHook.class, "Privacy", () -> new CallPrivacyHook(context, classLoader, prefs));
        addFeature(CallTypeControllerHook.class, "Privacy", () -> new CallTypeControllerHook(context, classLoader, prefs));
        addFeature(AntiRevokeHook.class, "Privacy", () -> new AntiRevokeHook(context, classLoader, prefs));
        addFeature(HideForwardedTagHook.class, "Privacy", () -> new HideForwardedTagHook(context, classLoader, prefs));
        addFeature(CustomPrivacyHook.class, "Privacy", () -> new CustomPrivacyHook(context, classLoader, prefs));
        addFeature(AutoNextStatusHook.class, "Privacy", () -> new AutoNextStatusHook(context, classLoader, prefs));
        addFeature(AntiDisappearingMessagesHook.class, "Privacy", () -> new AntiDisappearingMessagesHook(context, classLoader, prefs));
        addFeature(LockedChatsEnhancerHook.class, "Privacy", () -> new LockedChatsEnhancerHook(context, classLoader, prefs));
        addFeature(HideArchivedChatsHook.class, "Privacy", () -> new HideArchivedChatsHook(context, classLoader, prefs));
        addFeature(DndModeHook.class, "Privacy", () -> new DndModeHook(context, classLoader, prefs));
        addFeature(HideChatsVaultHook.class, "Privacy", () -> new HideChatsVaultHook(context, classLoader, prefs));

        // Conversation & Message Controls
        addFeature(AntiEditMessagesHook.class, "Conversation", () -> new AntiEditMessagesHook(context, classLoader, prefs));
        addFeature(StickerConfirmHook.class, "Conversation", () -> new StickerConfirmHook(context, classLoader, prefs));
        addFeature(JumpFirstMessageHook.class, "Conversation", () -> new JumpFirstMessageHook(context, classLoader, prefs));
        addFeature(PreserveDeleteForMeHook.class, "Conversation", () -> new PreserveDeleteForMeHook(context, classLoader, prefs));
        addFeature(CopyStatusTextHook.class, "Conversation", () -> new CopyStatusTextHook(context, classLoader, prefs));
        addFeature(ChatLimitsBypassHook.class, "Conversation", () -> new ChatLimitsBypassHook(context, classLoader, prefs));
        addFeature(DoubleTapReactionHook.class, "Conversation", () -> new DoubleTapReactionHook(context, classLoader, prefs));
        addFeature(MessageDeviceSourceHook.class, "Conversation", () -> new MessageDeviceSourceHook(context, classLoader, prefs));
        addFeature(MinorFixesHook.class, "Conversation", () -> new MinorFixesHook(context, classLoader, prefs));
        try {
            boolean installed = NewChatFeature.install(classLoader, prefs);
            features.add(new FeatureEntry("New Chat", "NewChatFeature", "Conversation",
                    installed ? "ACTIVE" : "DISABLED"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("New Chat", "NewChatFeature", "Conversation", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }
        try {
            MessageBomberFeature.install(context, classLoader, prefs);
            features.add(new FeatureEntry("Message Bomber", "MessageBomberFeature", "Conversation", "ACTIVE"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("Message Bomber", "MessageBomberFeature", "Conversation", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }

        // Homescreen & Layout Controls
        addFeature(HomeScreenHeaderActionsHook.class, "Customization", () -> new HomeScreenHeaderActionsHook(context, classLoader, prefs));
        addFeature(ChatListCustomizationsHook.class, "Customization", () -> new ChatListCustomizationsHook(context, classLoader, prefs));

        // Media & Audio Controls
        addFeature(DownloadViewOnceHook.class, "Media", () -> new DownloadViewOnceHook(context, classLoader, prefs));
        addFeature(StatusDownloadHook.class, "Media", () -> new StatusDownloadHook(context, classLoader, prefs));
        addFeature(MediaQualityBypassHook.class, "Media", () -> new MediaQualityBypassHook(context, classLoader, prefs));
        addFeature(MediaPreviewHook.class, "Media", () -> new MediaPreviewHook(context, classLoader, prefs));
        addFeature(ProximitySensorHook.class, "Media", () -> new ProximitySensorHook(context, classLoader, prefs));
        addFeature(CallRecordingHook.class, "Media", () -> new CallRecordingHook(context, classLoader, prefs));
        addFeature(ProfilePhotoDownloadHook.class, "Media", () -> new ProfilePhotoDownloadHook(context, classLoader, prefs));
        try {
            AudioToVoiceStatusFeature.install(context, classLoader, prefs);
            features.add(new FeatureEntry("Audio to Voice Status", "AudioToVoiceStatusFeature", "Media", "ACTIVE"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("Audio to Voice Status", "AudioToVoiceStatusFeature", "Media", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }
        try {
            StatusSplitterFeature.install(context, classLoader, prefs);
            features.add(new FeatureEntry("Status Splitter", "StatusSplitterFeature", "Media", "ACTIVE"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("Status Splitter", "StatusSplitterFeature", "Media", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }
        try {
            FileSizeSpooferFeature.install(context, classLoader, prefs);
            features.add(new FeatureEntry("File Size Spoofer", "FileSizeSpooferFeature", "Media", "ACTIVE"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("File Size Spoofer", "FileSizeSpooferFeature", "Media", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }

        // Automation & Notification Controls
        addFeature(PresenceToastsHook.class, "Automation", () -> new PresenceToastsHook(context, classLoader, prefs));
        addFeature(TaskerIntegrationHook.class, "Automation", () -> new TaskerIntegrationHook(context, classLoader, prefs));
        addFeature(ChatBubbleColorsHook.class, "Customization", () -> new ChatBubbleColorsHook(context, classLoader, prefs));
        addFeature(ChannelRecommendationsFilterHook.class, "Customization", () -> new ChannelRecommendationsFilterHook(context, classLoader, prefs));
        addFeature(ChatFilterDisplayHook.class, "Customization", () -> new ChatFilterDisplayHook(context, classLoader, prefs));
        addFeature(CustomDpiHook.class, "Customization", () -> new CustomDpiHook(context, classLoader, prefs));
        addFeature(CustomTimeHook.class, "Customization", () -> new CustomTimeHook(context, classLoader, prefs));
        addFeature(CssThemeHook.class, "Customization", () -> new CssThemeHook(context, classLoader, prefs));
        addFeature(HideHomeTabsHook.class, "Customization", () -> new HideHomeTabsHook(context, classLoader, prefs));
        addFeature(IGStatusHook.class, "Customization", () -> new IGStatusHook(context, classLoader, prefs));
        addFeature(StatusStyleHook.class, "Customization", () -> new StatusStyleHook(context, classLoader, prefs));
        addFeature(PropsOverrideHook.class, "Core", () -> new PropsOverrideHook(context, classLoader, prefs));
        addFeature(NewSettingsStyleHook.class, "Customization", () -> new NewSettingsStyleHook(context, classLoader, prefs));
        addFeature(OnlinePresenceIndicatorsHook.class, "Customization", () -> new OnlinePresenceIndicatorsHook(context, classLoader, prefs));
        addFeature(SeparateGroupsHook.class, "Customization", () -> new SeparateGroupsHook(context, classLoader, prefs));
        addFeature(FloatingBottomBarHook.class, "Customization", () -> new FloatingBottomBarHook(context, classLoader, prefs));
        addFeature(FilterItemsHook.class, "Customization", () -> new FilterItemsHook(context, classLoader, prefs));
        try {
            UnlockWaPlusFeature.install(context, classLoader, prefs);
            features.add(new FeatureEntry("Unlock WA Plus", "UnlockWaPlusFeature", "Customization", "ACTIVE"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("Unlock WA Plus", "UnlockWaPlusFeature", "Customization", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }
    }

    private void addFeature(Class<? extends BaseFeature> type, String category, FeatureFactory factory) {
        features.add(new FeatureEntry(type.getSimpleName(), category, factory));
    }

    public void initializeAll() {
        for (FeatureEntry entry : features) {
            if (entry.factory != null) {
                try {
                    entry.feature = entry.factory.create();
                    entry.name = entry.feature.getName();
                    entry.feature.hook();
                    entry.status = "ACTIVE";
                } catch (Throwable t) {
                    entry.status = "FAILED";
                    entry.error = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
                    XposedBridge.log("[WAEX] Error initializing feature [" + entry.name + "]: " + entry.error);
                    XposedBridge.log(t);
                }
            }
        }
    }

    public boolean hasFailures() {
        for (FeatureEntry entry : features) {
            if ("FAILED".equals(entry.status)) return true;
        }
        return false;
    }

    public void reportInjectedHooks(@NonNull Context context, @NonNull String packageName) {
        try {
            JSONArray array = new JSONArray();
            for (FeatureEntry entry : features) {
                JSONObject obj = new JSONObject();
                obj.put("name", entry.name);
                obj.put("className", entry.className);
                obj.put("category", entry.category);
                obj.put("status", entry.status);
                if (entry.error != null) {
                    obj.put("error", entry.error);
                }
                array.put(obj);
            }

            Uri uri = Uri.parse("content://com.waenhancer.hookprovider");
            Bundle extras = new Bundle();
            extras.putString("package", packageName);
            extras.putString("hooks_json", array.toString());
            extras.putLong("timestamp", System.currentTimeMillis());

            context.getContentResolver().call(uri, "register_injected_hooks", packageName, extras);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Failed to report injected hooks: " + t.getMessage());
        }
    }
}
