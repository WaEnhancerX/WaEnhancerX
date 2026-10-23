package com.waenhancer.xposed.core;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.waenhancer.config.HookingModePolicy;
import com.waenhancer.hooking.HookingEngine;
import com.waenhancer.hooking.HookingEngine.FeaturePolicy;
import com.waenhancer.hooking.HookingEngine.Installation;
import com.waenhancer.hooking.LazyHookCoordinator;
import com.waenhancer.hooking.ScreenScopedFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.utils.ActivityTracker;
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
import com.waenhancer.xposed.features.media.VoiceDictationHook;
import com.waenhancer.xposed.features.media.VoiceNoteRowTranscriptHook;
import com.waenhancer.xposed.features.media.VoiceNoteTranscriptionHook;
import com.waenhancer.xposed.features.privacy.AlwaysOnlineHook;
import com.waenhancer.xposed.features.privacy.AntiDisappearingMessagesHook;
import com.waenhancer.xposed.features.privacy.AntiRevokeHook;
import com.waenhancer.xposed.features.privacy.AntiViewOnceHook;
import com.waenhancer.xposed.features.privacy.AutoNextStatusHook;
import com.waenhancer.xposed.features.privacy.BlueOnReplyHook;
import com.waenhancer.xposed.features.privacy.CallPrivacyHook;
import com.waenhancer.xposed.features.privacy.CustomPrivacyHook;
import com.waenhancer.xposed.features.privacy.DndModeHook;
import com.waenhancer.xposed.features.privacy.FreezeLastSeenHook;
import com.waenhancer.xposed.features.privacy.HideArchivedChatsHook;
import com.waenhancer.xposed.features.privacy.HideChatsVaultHook;
import com.waenhancer.xposed.features.privacy.HideForwardedTagHook;
import com.waenhancer.xposed.features.privacy.HideReceiptsHook;
import com.waenhancer.xposed.features.privacy.LockedChatsEnhancerHook;
import com.waenhancer.xposed.features.privacy.ScreenshotRestrictionBypassHook;
import com.waenhancer.xposed.features.privacy.TypingPrivacyHook;
import com.waenhancer.licensing.features.PlayIntegrityBypassHook;
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
import java.util.Set;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Iterator;

/**
 * Orchestrates and registers all modular hooks in WAEX and reports injected status.
 */
public final class FeatureRegistry {

    private final List<FeatureEntry> features = new ArrayList<>();
    private final LazyHookCoordinator lazyHooks = new LazyHookCoordinator();
    private final Context reportingContext;
    private final String reportingPackage;
    private final String hookingMode;
    private final Map<String, String> customizedInstallations = new HashMap<>();
    private final SharedPreferences prefs;

    @FunctionalInterface
    private interface FeatureFactory {
        BaseFeature create() throws Throwable;
    }

    @FunctionalInterface
    private interface FeatureEnabled {
        boolean isEnabled();
    }

    public static final class FeatureEntry {
        public BaseFeature feature;
        public String name;
        public final String className;
        public final String category;
        private final FeatureFactory factory;
        private final FeatureEnabled enabled;
        // Installation and FeaturePolicy types come from HookingEngine (:licensing)
        final Installation installation;
        final FeaturePolicy policy;
        private final AtomicBoolean installationStarted = new AtomicBoolean(false);
        public String status = "WAITING";
        public String error = null;

        public FeatureEntry(BaseFeature feature, String category) {
            this.feature = feature;
            this.name = feature.getName();
            this.className = feature.getClass().getSimpleName();
            this.category = category;
            this.factory = null;
            this.enabled = () -> true;
            this.installation = Installation.STARTUP;
            this.policy = null;
        }

        public FeatureEntry(String name, String className, String category, String status) {
            this.feature = null;
            this.name = name;
            this.className = className;
            this.category = category;
            this.status = status;
            this.factory = null;
            this.enabled = () -> true;
            this.installation = Installation.STARTUP;
            this.policy = null;
        }

        FeatureEntry(String className, String category, Installation installation,
                     FeaturePolicy policy, FeatureEnabled enabled, FeatureFactory factory) {
            this.feature = null;
            this.name = className;
            this.className = className;
            this.category = category;
            this.factory = factory;
            this.enabled = enabled;
            this.installation = installation;
            this.policy = policy;
        }
    }

    public FeatureRegistry(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        this.prefs = prefs;
        this.reportingContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        this.reportingPackage = this.reportingContext.getPackageName();
        // The provider resolves entitlement in the module process. Never ask LicenseManager
        // with the injected WhatsApp context: that would read WhatsApp's unrelated private
        // preferences and incorrectly downgrade Lightweight to Maximum.
        final boolean isProActive = prefs.getBoolean("waex_pro_active", false);
        String storedHookingMode = prefs.getString(HookingModePolicy.KEY, HookingModePolicy.BALANCED);
        this.hookingMode = HookingModePolicy.normalize(storedHookingMode, isProActive);
        if (HookingModePolicy.CUSTOMIZED.equals(this.hookingMode)) {
            try {
                JSONObject custom = new JSONObject(prefs.getString(
                        HookingModePolicy.CUSTOMIZATIONS_KEY, "{}"));
                Iterator<String> keys = custom.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    customizedInstallations.put(key, custom.optString(key, ""));
                }
            } catch (Throwable ignored) {}
        }
        try {
            BootloaderSpooferFeature.install(context, classLoader, prefs);
            features.add(new FeatureEntry("Bootloader Spoofing & Integrity", "BootloaderSpooferFeature", "Licensing", "ACTIVE"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("Bootloader Spoofing & Integrity", "BootloaderSpooferFeature", "Licensing", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }

        try {
            if (prefs.getBoolean(BootloaderSpooferFeature.ENABLED, true)) {
                PlayIntegrityBypassHook.install(context, classLoader, prefs);
                features.add(new FeatureEntry("Play Integrity & Roadblock Bypass", "PlayIntegrityBypassHook", "Licensing", "ACTIVE"));
            }
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("Play Integrity & Roadblock Bypass", "PlayIntegrityBypassHook", "Licensing", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }

        // ==========================================
        // Unified Global Feature & Policy Registry
        // (Every hook/feature, its mode policies, activation condition, and factory defined in one place)
        // ==========================================

        // Privacy & Seen Controls
        registerFeature(AntiViewOnceHook.class, "Privacy",
                () -> prefs.getBoolean("anti_view_once", false) || prefs.getBoolean("viewonce", false),
                () -> new AntiViewOnceHook(context, classLoader, prefs));

        registerFeature(ScreenshotRestrictionBypassHook.class, "Privacy",
                () -> prefs.getBoolean("bypass_screenshot_restriction", false),
                () -> new ScreenshotRestrictionBypassHook(context, classLoader, prefs));

        registerFeature(TypingPrivacyHook.class, "Privacy",
                () -> prefs.getBoolean("typing_privacy", false) || prefs.getBoolean("hide_typing", false)
                        || prefs.getBoolean("hide_recording", false) || prefs.getBoolean("ghostmode_t", false)
                        || prefs.getBoolean("ghostmode_r", false),
                () -> new TypingPrivacyHook(context, classLoader, prefs));

        registerFeature(FreezeLastSeenHook.class, "Privacy",
                () -> prefs.getBoolean("freeze_last_seen", false)
                        || prefs.getBoolean("freeze_last_seen_active", false)
                        || prefs.getBoolean("ghostmode_active", false)
                        || prefs.getBoolean("online_status_indicator", false),
                () -> new FreezeLastSeenHook(context, classLoader, prefs));

        registerFeature(AlwaysOnlineHook.class, "Privacy",
                () -> prefs.getBoolean("always_online", false),
                () -> new AlwaysOnlineHook(context, classLoader, prefs));

        registerFeature(HideReceiptsHook.class, "Privacy",
                () -> prefs.getBoolean("hide_read_receipts", false) || prefs.getBoolean("hideread", false)
                        || prefs.getBoolean("hide_delivery_receipts", false) || prefs.getBoolean("hidereceipt", false)
                        || prefs.getBoolean("hide_seen_receipts", false) || prefs.getBoolean("hideaudioseen", false)
                        || prefs.getBoolean("hideonceseen", false) || prefs.getBoolean("ghostmode_active", false)
                        || prefs.getBoolean("stealth_status_view", false) || prefs.getBoolean("hidestatusview", false),
                () -> new HideReceiptsHook(context, classLoader, prefs));

        // These hooks observe transport/database/call events. They must be installed at process
        // startup: a notification, revoke, receipt, or call can arrive on any screen.
        registerFeature(BlueOnReplyHook.class, "Privacy",
                () -> prefs.getBoolean("blueonreply", false)
                        || prefs.getBoolean("manual_status_seen", false),
                () -> new BlueOnReplyHook(context, classLoader, prefs));

        registerFeature(CallPrivacyHook.class, "Privacy",
                () -> !"0".equals(prefs.getString("call_privacy", "0"))
                        || prefs.getBoolean("call_privacy_enabled", false)
                        || prefs.getBoolean("call_info", false),
                () -> new CallPrivacyHook(context, classLoader, prefs));

        registerFeature(AntiRevokeHook.class, "Privacy",
                () -> prefs.getBoolean("anti_revoke", false)
                        || prefs.getBoolean("preserve_own_revoke", false)
                        || prefs.getBoolean("antirevokestatus", false),
                () -> new AntiRevokeHook(context, classLoader, prefs));

        registerFeature(HideForwardedTagHook.class, "Privacy",
                () -> prefs.getBoolean("hide_forwarded_tag", false) || prefs.getBoolean("hidetag", false),
                () -> new HideForwardedTagHook(context, classLoader, prefs));

        registerFeature(CustomPrivacyHook.class, "Privacy",
                () -> new CustomPrivacyHook(context, classLoader, prefs));

        registerFeature(AutoNextStatusHook.class, "Privacy",
                () -> new AutoNextStatusHook(context, classLoader, prefs));

        registerFeature(AntiDisappearingMessagesHook.class, "Privacy",
                () -> prefs.getBoolean("antidisappearing", false),
                () -> new AntiDisappearingMessagesHook(context, classLoader, prefs));

        registerFeature(LockedChatsEnhancerHook.class, "Privacy",
                () -> prefs.getBoolean("locked_chats_enhancer", false)
                        || prefs.getBoolean("lockedchats_enhancer", false),
                () -> new LockedChatsEnhancerHook(context, classLoader, prefs));

        registerFeature(HideArchivedChatsHook.class, "Privacy",
                () -> new HideArchivedChatsHook(context, classLoader, prefs));

        registerFeature(DndModeHook.class, "Privacy",
                () -> new DndModeHook(context, classLoader, prefs));

        registerFeature(HideChatsVaultHook.class, "Privacy",
                () -> prefs.getBoolean("hide_chats", false),
                () -> new HideChatsVaultHook(context, classLoader, prefs));

        // Conversation & Message Controls
        registerFeature(AntiEditMessagesHook.class, "Conversation",
                () -> prefs.getBoolean("anti_edit", false) || prefs.getBoolean("antieditmessages", false),
                () -> new AntiEditMessagesHook(context, classLoader, prefs));

        registerFeature(StickerConfirmHook.class, "Conversation",
                () -> prefs.getBoolean("sticker_confirm_alert", false),
                () -> new StickerConfirmHook(context, classLoader, prefs));

        registerFeature(JumpFirstMessageHook.class, "Conversation",
                () -> prefs.getBoolean("jump_to_first_message", false),
                () -> new JumpFirstMessageHook(context, classLoader, prefs));

        registerFeature(PreserveDeleteForMeHook.class, "Conversation",
                () -> prefs.getBoolean("preserve_delete_for_me", false),
                () -> new PreserveDeleteForMeHook(context, classLoader, prefs));

        registerFeature(CopyStatusTextHook.class, "Conversation",
                () -> prefs.getBoolean("copy_status_text", false),
                () -> new CopyStatusTextHook(context, classLoader, prefs));

        registerFeature(ChatLimitsBypassHook.class, "Conversation",
                () -> prefs.getBoolean("unlimited_pinned_chats", false)
                        || prefs.getBoolean("pinnedlimit", false)
                        || prefs.getBoolean("chat_limits_bypass", false)
                        || prefs.getBoolean("removeforwardlimit", false)
                        || prefs.getBoolean("revokeallmessages", false),
                () -> new ChatLimitsBypassHook(context, classLoader, prefs));

        registerFeature(DoubleTapReactionHook.class, "Conversation",
                () -> prefs.getBoolean("doubletap2like", false),
                () -> new DoubleTapReactionHook(context, classLoader, prefs));

        registerFeature(MessageDeviceSourceHook.class, "Conversation",
                () -> prefs.getBoolean("message_device_source", false),
                () -> new MessageDeviceSourceHook(context, classLoader, prefs));

        registerFeature(MinorFixesHook.class, "Conversation",
                () -> new MinorFixesHook(context, classLoader, prefs));

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
        registerFeature(HomeScreenHeaderActionsHook.class, "Customization",
                () -> prefs.getBoolean("ghostmode", false)
                        || prefs.getBoolean("freezelastseen", false)
                        || prefs.getBoolean("show_dndmode", false)
                        || prefs.getBoolean("restartbutton", false)
                        || prefs.getBoolean("open_wae", false)
                        || prefs.getBoolean("archive_open_via_menu", true)
                        || prefs.getBoolean("archive_open_via_title_long_press", true),
                () -> new HomeScreenHeaderActionsHook(context, classLoader, prefs));

        registerFeature(ChatListCustomizationsHook.class, "Customization",
                () -> prefs.getBoolean("metaai", false)
                        || prefs.getBoolean("dotonline", false)
                        || prefs.getBoolean("showonlinetext", false),
                () -> new ChatListCustomizationsHook(context, classLoader, prefs));

        // Media & Audio Controls
        registerFeature(DownloadViewOnceHook.class, "Media",
                () -> prefs.getBoolean("downloadviewonce", false),
                () -> new DownloadViewOnceHook(context, classLoader, prefs));

        registerFeature(StatusDownloadHook.class, "Media",
                () -> prefs.getBoolean("status_downloader", false)
                        || prefs.getBoolean("download_status", false)
                        || prefs.getBoolean("downloadstatus", false),
                () -> new StatusDownloadHook(context, classLoader, prefs));

        registerFeature(MediaQualityBypassHook.class, "Media",
                () -> new MediaQualityBypassHook(context, classLoader, prefs));

        registerFeature(MediaPreviewHook.class, "Media",
                () -> prefs.getBoolean("media_preview", prefs.getBoolean("enable_media_preview", true)),
                () -> new MediaPreviewHook(context, classLoader, prefs));

        registerFeature(ProximitySensorHook.class, "Media",
                () -> prefs.getBoolean("disable_sensor_proximity", false)
                        || prefs.getBoolean("proximity_audios", false),
                () -> new ProximitySensorHook(context, classLoader, prefs));

        registerFeature(CallRecordingHook.class, "Media",
                () -> prefs.getBoolean("call_recording_enabled", false),
                () -> new CallRecordingHook(context, classLoader, prefs));

        registerFeature(ProfilePhotoDownloadHook.class, "Media",
                () -> prefs.getBoolean("download_profile", false),
                () -> new ProfilePhotoDownloadHook(context, classLoader, prefs));

        registerFeature(VoiceNoteTranscriptionHook.class, "Media",
                () -> new VoiceNoteTranscriptionHook(context, classLoader, prefs));

        registerFeature(VoiceNoteRowTranscriptHook.class, "Media",
                () -> new VoiceNoteRowTranscriptHook(context, classLoader, prefs));

        registerFeature(VoiceDictationHook.class, "Media",
                () -> new VoiceDictationHook(context, classLoader, prefs));

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
        registerFeature(PresenceToastsHook.class, "Automation",
                () -> new PresenceToastsHook(context, classLoader, prefs));

        registerFeature(TaskerIntegrationHook.class, "Automation",
                () -> new TaskerIntegrationHook(context, classLoader, prefs));

        registerFeature(ChatBubbleColorsHook.class, "Customization",
                () -> new ChatBubbleColorsHook(context, classLoader, prefs));

        registerFeature(ChannelRecommendationsFilterHook.class, "Customization",
                () -> prefs.getBoolean("channels_enhancements", false),
                () -> new ChannelRecommendationsFilterHook(context, classLoader, prefs));

        registerFeature(ChatFilterDisplayHook.class, "Customization",
                () -> !"2".equals(prefs.getString("chatfilter", "2")),
                () -> new ChatFilterDisplayHook(context, classLoader, prefs));

        registerFeature(CustomDpiHook.class, "Customization",
                () -> new CustomDpiHook(context, classLoader, prefs));

        registerFeature(CustomTimeHook.class, "Customization",
                () -> new CustomTimeHook(context, classLoader, prefs));

        registerFeature(CssThemeHook.class, "Customization",
                () -> new CssThemeHook(context, classLoader, prefs));

        registerFeature(HideHomeTabsHook.class, "Customization",
                () -> {
                    Set<String> tabs = prefs.getStringSet("hidetabs", null);
                    return tabs != null && !tabs.isEmpty();
                },
                () -> new HideHomeTabsHook(context, classLoader, prefs));

        registerFeature(IGStatusHook.class, "Customization",
                () -> prefs.getBoolean("igstatus", false),
                () -> new IGStatusHook(context, classLoader, prefs));

        registerFeature(StatusStyleHook.class, "Customization",
                () -> !"1".equals(prefs.getString("status_style", "1")),
                () -> new StatusStyleHook(context, classLoader, prefs));

        registerFeature(PropsOverrideHook.class, "Core",
                () -> new PropsOverrideHook(context, classLoader, prefs));

        registerFeature(NewSettingsStyleHook.class, "Customization",
                () -> prefs.getBoolean("novaconfig", false),
                () -> new NewSettingsStyleHook(context, classLoader, prefs));

        registerFeature(OnlinePresenceIndicatorsHook.class, "Customization",
                () -> new OnlinePresenceIndicatorsHook(context, classLoader, prefs));

        registerFeature(SeparateGroupsHook.class, "Customization",
                () -> prefs.getBoolean("separate_groups_tabs", false)
                        || prefs.getBoolean("separategroups", false),
                () -> new SeparateGroupsHook(context, classLoader, prefs));

        registerFeature(FloatingBottomBarHook.class, "Customization",
                () -> prefs.getBoolean("floating_bottom_bar", false),
                () -> new FloatingBottomBarHook(context, classLoader, prefs));

        registerFeature(FilterItemsHook.class, "Customization",
                () -> new FilterItemsHook(context, classLoader, prefs));

        try {
            UnlockWaPlusFeature.install(context, classLoader, prefs);
            features.add(new FeatureEntry("Unlock WA Plus", "UnlockWaPlusFeature", "Customization", "ACTIVE"));
        } catch (Throwable t) {
            FeatureEntry entry = new FeatureEntry("Unlock WA Plus", "UnlockWaPlusFeature", "Customization", "FAILED");
            entry.error = t.getMessage();
            features.add(entry);
        }
    }

    private void registerFeature(Class<? extends BaseFeature> type, String category,
                                 FeatureFactory factory) {
        registerFeature(type, category, () -> true, factory);
    }

    private void registerFeature(Class<? extends BaseFeature> type, String category,
                                 FeatureEnabled enabled,
                                 FeatureFactory factory) {
        String className = type.getSimpleName();
        FeaturePolicy policy = HookingEngine.policyFor(className);
        Installation effectiveInstallation = effectiveInstallation(policy, className, category);
        FeatureEntry entry = new FeatureEntry(className, category,
                effectiveInstallation, policy, enabled, factory);
        features.add(entry);
        if (effectiveInstallation != Installation.STARTUP) {
            lazyHooks.register(screenFor(effectiveInstallation), () -> install(entry));
            lazyHooks.registerDisposer(screenFor(effectiveInstallation),
                    activity -> disposeLazyEntry(entry, activity));
        }
    }

    private Installation effectiveInstallation(FeaturePolicy policy, String className, String category) {
        if (!HookingModePolicy.CUSTOMIZED.equals(hookingMode)) {
            return HookingEngine.effectiveInstallation(policy, hookingMode);
        }
        String override = customizedInstallations.get(className);
        // An absent value deliberately inherits Balanced, making Customized safe by default.
        if (!"lazy".equals(override)) {
            return "startup".equals(override)
                    ? Installation.STARTUP
                    : HookingEngine.effectiveInstallation(policy, HookingModePolicy.BALANCED);
        }
        Installation balanced = HookingEngine.effectiveInstallation(policy, HookingModePolicy.BALANCED);
        if (balanced != Installation.STARTUP) return balanced;
        Installation lightweight = HookingEngine.effectiveInstallation(policy, HookingModePolicy.LIGHTWEIGHT);
        if (lightweight != Installation.STARTUP) return lightweight;
        if ("Conversation".equals(category) || "Privacy".equals(category)) return Installation.LAZY_CONVERSATION;
        if ("Media".equals(category)) return Installation.LAZY_MEDIA;
        return Installation.LAZY_HOME;
    }

    private static LazyHookCoordinator.Screen screenFor(Installation installation) {
        return HookingEngine.screenFor(installation);
    }

    public void initializeAll() {
        applyUiPreHooks();
        for (FeatureEntry entry : features) {
            if (entry.factory == null) continue;
            if (entry.installation == Installation.STARTUP) install(entry);
            else if (isEntryEnabled(entry)) {
                entry.status = "WAITING";
            } else {
                markDisabled(entry);
            }
        }
        // DexKit's native index is only required while a feature is resolving its targets.
        // Lazy features reopen it on their first matching screen and close it again afterwards.
        DexSearchEngine.getInstance().close();
        // Bridge ActivityTracker (app) -> LazyHookCoordinator (licensing) without a circular dep.
        ActivityTracker.addListener(new ActivityTracker.ActivityListener() {
            @Override public void onActivityCreated(@NonNull Activity activity) {
                lazyHooks.onActivityCreated(activity);
            }
            @Override public void onActivityResumed(@NonNull Activity activity) {
                lazyHooks.onActivityResumed(activity);
            }
            @Override public void onActivityStopped(@NonNull Activity activity) {
                // A stopped Activity remains alive in WhatsApp's back stack. Do not dispose
                // its lazy UI state or process-wide hooks while another Activity covers it.
            }
            @Override public void onActivityDestroyed(@NonNull Activity activity) {
                lazyHooks.onActivityDestroyed(activity);
            }
        });
        Activity current = ActivityTracker.getCurrentActivity();
        if (current != null) lazyHooks.onActivityResumed(current);
    }

    private void applyUiPreHooks() {
        // This prop bridge must run before the deferred NewSettingsStyleHook installs.
        boolean newSettingsEnabled = prefs.getBoolean("novaconfig", false);
        PropsOverrideHook.setBooleanOverride(14862, newSettingsEnabled, true);
        PropsOverrideHook.setIntegerOverride(18564, newSettingsEnabled, 2);
        boolean metaAiFullyDisabled = prefs.getBoolean("metaai", false)
                && prefs.getBoolean("metaai_remove_entry_points", true)
                && prefs.getBoolean("metaai_block_chat", true);
        PropsOverrideHook.setIntegerOverride(15535, metaAiFullyDisabled, 0);
        PropsOverrideHook.setBooleanOverride(8025, metaAiFullyDisabled, false);
        PropsOverrideHook.setBooleanOverride(6251, metaAiFullyDisabled, false);
        PropsOverrideHook.setBooleanOverride(8026, metaAiFullyDisabled, false);
        PropsOverrideHook.setBooleanOverride(14886, metaAiFullyDisabled, false);
        for (FeatureEntry entry : features) {
            if (entry.policy == null || entry.policy.preHook == null || !isEntryEnabled(entry)) continue;
            try {
                entry.policy.preHook.apply(prefs);
            } catch (Throwable error) {
                XposedBridge.log("[WAEX] UI pre-hook failed for " + entry.className + ": "
                        + error.getClass().getSimpleName());
            }
        }
    }

    private void install(FeatureEntry entry) {
        synchronized (entry) {
            installLocked(entry);
        }
    }

    private void installLocked(FeatureEntry entry) {
        if (entry.factory == null || !isEntryEnabled(entry)) {
            markDisabled(entry);
            return;
        }
        if (!entry.installationStarted.compareAndSet(false, true)) return;
        try {
            entry.feature = entry.factory.create();
            entry.name = entry.feature.getName();
            entry.feature.beginHookTracking();
            try {
                entry.feature.hook();
            } finally {
                entry.feature.endHookTracking();
            }
            entry.status = "ACTIVE";
        } catch (Throwable t) {
            if (entry.feature != null) entry.feature.unhookAll();
            entry.feature = null;
            entry.installationStarted.set(false);
            entry.status = "FAILED";
            entry.error = t.getMessage() != null ? t.getMessage() : t.getClass().getName();
            XposedBridge.log("[WAEX] Error initializing feature [" + entry.name + "]: " + entry.error);
            XposedBridge.log(t);
        } finally {
            if (entry.installation != Installation.STARTUP) {
                DexSearchEngine.getInstance().close();
            }
            reportInjectedHooks(reportingContext, reportingPackage);
        }
    }

    private void disposeLazyEntry(FeatureEntry entry, @NonNull Activity activity) {
        if (entry.installation == Installation.STARTUP) return;
        synchronized (entry) {
            BaseFeature feature = entry.feature;
            if (feature == null) return;
            try {
                if (feature instanceof ScreenScopedFeature) {
                    ((ScreenScopedFeature) feature).onScreenDisposed(activity);
                }
                feature.unhookAll();
                entry.feature = null;
                entry.installationStarted.set(false);
                entry.status = isEntryEnabled(entry) ? "WAITING" : "DISABLED";
                entry.error = null;
            } catch (Throwable error) {
                XposedBridge.log("[WAEX] Lazy dispose failed for " + entry.className + ": "
                        + error.getClass().getSimpleName());
            }
        }
        reportInjectedHooks(reportingContext, reportingPackage);
    }

    private static boolean isEntryEnabled(FeatureEntry entry) {
        try {
            return entry.enabled.isEnabled();
        } catch (Throwable error) {
            entry.error = error.getClass().getSimpleName();
            return false;
        }
    }

    /**
     * R8 may rename feature implementation classes in the injected process. Resolve the stable
     * user-facing title without calling hook(), then release the short-lived metadata instance.
     */
    private void markDisabled(FeatureEntry entry) {
        entry.status = "DISABLED";
        entry.error = null;
        if (entry.factory == null || entry.feature != null) return;
        try {
            BaseFeature metadata = entry.factory.create();
            entry.name = metadata.getName();
        } catch (Throwable ignored) {
            // A display-name failure is not a runtime hook failure. Keep the feature disabled.
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
            extras.putString("hooking_mode", hookingMode);
            extras.putLong("timestamp", System.currentTimeMillis());

            context.getContentResolver().call(uri, "register_injected_hooks", packageName, extras);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Failed to report injected hooks: " + t.getMessage());
        }
    }
}
