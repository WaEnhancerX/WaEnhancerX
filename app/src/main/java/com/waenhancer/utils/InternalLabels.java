package com.waenhancer.utils;
import com.waenhancer.app.R;
/** Presentation-only labels. Never pass message bodies, contact names or server content. */
public final class InternalLabels {
    private InternalLabels() {}
    /** Only locally generated HMA reasons; preserve template names and unknown payloads. */
    public static String hmaReason(String value) {
        if (value == null) return "";
        final String prefix = "Hidden by blacklist template \"";
        if (value.startsWith(prefix) && value.endsWith("\"") && value.length() > prefix.length()) {
            String template = value.substring(prefix.length(), value.length() - 1);
            return UiText.get(R.string.waex_ui_hma_reason_template,
                    "Hidden by blacklist template \"%1$s\"", template);
        }
        switch (value) {
            case "Not configured in HMA scope": return UiText.get(R.string.waex_ui_hma_reason_not_configured, "Not configured in HMA scope");
            case "Active in HMA, module visible": return UiText.get(R.string.waex_ui_hma_reason_visible, "Active in HMA, module visible");
            case "Hidden via Whitelist mode (in invisible apps list)": return UiText.get(R.string.waex_ui_hma_reason_allowlist_invisible, "Hidden via Whitelist mode (in invisible apps list)");
            case "Hidden via Whitelist mode (module not included in visible apps)": return UiText.get(R.string.waex_ui_hma_reason_allowlist_missing, "Hidden via Whitelist mode (module not included in visible apps)");
            case "Hidden via Blacklist mode (in additional apps to hide)": return UiText.get(R.string.waex_ui_hma_reason_blocklist_extra, "Hidden via Blacklist mode (in additional apps to hide)");
            case "Hidden by HMA \"xposed\" preset": return UiText.get(R.string.waex_ui_hma_reason_preset, "Hidden by HMA \"xposed\" preset");
            default: return value;
        }
    }
    public static String text(String value) {
        if (value == null) return "";
        final String obsoletePrefix = "Last report (not current): ";
        if (value.startsWith(obsoletePrefix)) {
            final String suffix = value.substring(obsoletePrefix.length());
            return UiText.get(R.string.waex_l10n_last_report,
                    "Last report (not current): %1$s", text(suffix));
        }
        switch (value) {
            case "All": return UiText.get(R.string.filter_all, "All");
            case "Light": return UiText.get(R.string.theme_light, "Light");
            case "Dark": return UiText.get(R.string.theme_dark, "Dark");
            case "System": return UiText.get(R.string.waex_ui_system, "System");
            case "Stable": return UiText.get(R.string.waex_ui_stable, "Stable");
            case "Beta": return UiText.get(R.string.waex_ui_beta, "Beta");
            case "Release": return UiText.get(R.string.waex_ui_release, "Release");
            case "Debug": return UiText.get(R.string.waex_ui_debug, "Debug");
            case "ACTIVE": return UiText.get(R.string.waex_ui_active, "ACTIVE");
            case "FAILED": return UiText.get(R.string.waex_ui_failed_uppercase, "FAILED");
            case "DISABLED": return UiText.get(R.string.waex_ui_disabled_uppercase, "DISABLED");
            case "PENDING": return UiText.get(R.string.waex_ui_pending, "PENDING");
            case "WAITING": return "WAITING";
            case "Active": return UiText.get(R.string.diag_status_active, "Active");
            case "Failed": return UiText.get(R.string.waex_ui_failed_label, "Failed");
            case "Disabled": return UiText.get(R.string.waex_ui_disabled_label, "Disabled");
            case "Privacy": return UiText.get(R.string.tab_privacy, "Privacy");
            case "Conversation": return UiText.get(R.string.tab_conversation, "Conversation");
            case "Customization": return UiText.get(R.string.customization_coming_soon_title, "Customization");
            case "Media": return UiText.get(R.string.catalog_tab_media, "Media");
            case "Automation": return UiText.get(R.string.subtab_automation, "Automation");
            case "Licensing": return UiText.get(R.string.waex_ui_licensing, "Licensing");
            case "General": return UiText.get(R.string.waex_ui_general, "General");
            case "Permissive": return UiText.get(R.string.waex_ui_permissive, "Permissive");
            case "Enforcing": return UiText.get(R.string.waex_ui_enforcing, "Enforcing");
            case "Unknown": return UiText.get(R.string.waex_ui_unknown, "Unknown");
            case "Not detected": return UiText.get(R.string.waex_ui_not_detected, "Not detected");
            case "None reported": return UiText.get(R.string.waex_ui_none_reported, "None reported");
            case "Xposed-compatible framework": return UiText.get(R.string.waex_ui_xposed_compatible_framework, "Xposed-compatible framework");
            case "Hide Receipts": return UiText.get(R.string.waex_ui_hide_receipts, "Hide Receipts");
            case "Hide Seen": return UiText.get(R.string.waex_ui_hide_seen, "Hide Seen");
            case "Freeze Last Seen": return UiText.get(R.string.feat_freezelastseen_title, "Freeze Last Seen");
            case "Anti-Revoke": return UiText.get(R.string.waex_ui_anti_revoke_feature, "Anti-Revoke");
            case "Anti Revoke": return UiText.get(R.string.waex_ui_anti_revoke_label, "Anti Revoke");
            case "Anti-Revoke Status": return UiText.get(R.string.waex_ui_anti_revoke_status, "Anti-Revoke Status");
            case "Anti-Edit Messages": return UiText.get(R.string.waex_ui_anti_edit_messages_hyphenated, "Anti-Edit Messages");
            case "Anti-Disappearing Messages": return UiText.get(R.string.waex_ui_anti_disappearing_messages, "Anti-Disappearing Messages");
            case "Hide Typing": return UiText.get(R.string.feat_always_typing_title, "Hide Typing");
            case "Always Online": return UiText.get(R.string.feat_always_online_title, "Always Online");
            case "Call Privacy": return UiText.get(R.string.waex_ui_call_privacy, "Call Privacy");
            case "Hide Forwarded Tag": return UiText.get(R.string.waex_ui_hide_forwarded_tag, "Hide Forwarded Tag");
            case "Custom Privacy": return UiText.get(R.string.waex_ui_custom_privacy, "Custom Privacy");
            case "Hide Recording": return UiText.get(R.string.waex_ui_hide_recording, "Hide Recording");
            case "Hide Online": return UiText.get(R.string.waex_ui_hide_online, "Hide Online");
            case "Blue On Reply": return UiText.get(R.string.waex_ui_blue_on_reply_title, "Blue On Reply");
            case "Blue on Reply": return UiText.get(R.string.waex_ui_blue_on_reply_label, "Blue on Reply");
            case "New Chat": return UiText.get(R.string.feat_direct_chat_dialer_title, "New Chat");
            case "Message Bomber": return UiText.get(R.string.feat_message_bomber_title, "Message Bomber");
            case "Audio to Voice Status": return UiText.get(R.string.feat_send_audio_as_voice_status_title, "Audio to Voice Status");
            case "Status Splitter": return UiText.get(R.string.waex_ui_status_splitter, "Status Splitter");
            case "File Size Spoofer": return UiText.get(R.string.feat_file_size_spoofer_title, "File Size Spoofer");
            case "Unlock WA Plus": return UiText.get(R.string.feat_unlock_premium_customization_title, "Unlock WA Plus");
            case "Bootloader Spoofing & Integrity": return UiText.get(R.string.waex_ui_bootloader_spoofing_integrity, "Bootloader Spoofing & Integrity");
            case "Chat Limits Bypass": return UiText.get(R.string.waex_ui_chat_limits_bypass, "Chat Limits Bypass");
            case "Double Tap Reaction": return UiText.get(R.string.waex_ui_double_tap_reaction, "Double Tap Reaction");
            case "MinorFixes": return UiText.get(R.string.waex_ui_minorfixes, "MinorFixes");
            case "MessageDeviceSource": return UiText.get(R.string.waex_ui_messagedevicesource, "MessageDeviceSource");
            case "Home Header Actions": return UiText.get(R.string.waex_ui_home_header_actions, "Home Header Actions");
            case "Chat List Customizations": return UiText.get(R.string.waex_ui_chat_list_customizations, "Chat List Customizations");
            case "Download View Once": return UiText.get(R.string.feat_download_view_once_title, "Download View Once");
            case "Status Download": return UiText.get(R.string.waex_ui_status_download, "Status Download");
            case "Media Quality Bypass": return UiText.get(R.string.waex_ui_media_quality_bypass, "Media Quality Bypass");
            case "Media Preview": return UiText.get(R.string.waex_ui_media_preview, "Media Preview");
            case "Disable Proximity Sensor": return UiText.get(R.string.waex_ui_disable_proximity_sensor, "Disable Proximity Sensor");
            case "Call Recording": return UiText.get(R.string.feat_call_recording_title, "Call Recording");
            case "Profile Photo Download": return UiText.get(R.string.waex_ui_profile_photo_download, "Profile Photo Download");
            case "Presence Toasts": return UiText.get(R.string.waex_ui_presence_toasts, "Presence Toasts");
            case "Online Presence Indicators": return UiText.get(R.string.waex_ui_online_presence_indicators, "Online Presence Indicators");
            case "Tasker Integration": return UiText.get(R.string.waex_ui_tasker_integration, "Tasker Integration");
            case "Filter Items": return UiText.get(R.string.waex_ui_filter_items, "Filter Items");
            case "Custom DPI": return UiText.get(R.string.waex_ui_custom_dpi, "Custom DPI");
            case "Custom Time Format": return UiText.get(R.string.waex_ui_custom_time_format, "Custom Time Format");
            case "Custom Toolbar": return UiText.get(R.string.waex_ui_custom_toolbar, "Custom Toolbar");
            case "Bubble Colors": return UiText.get(R.string.waex_ui_bubble_colors, "Bubble Colors");
            case "Hide Home Tabs": return UiText.get(R.string.waex_ui_hide_home_tabs_title, "Hide Home Tabs");
            case "Instagram Status Layout": return UiText.get(R.string.waex_ui_instagram_status_layout, "Instagram Status Layout");
            case "Separate Groups": return UiText.get(R.string.feat_separate_groups_tabs_title, "Separate Groups");
            case "Floating Bottom Bar": return UiText.get(R.string.feat_floating_bottom_bar_title, "Floating Bottom Bar");
            case "CSS Theme": return UiText.get(R.string.waex_ui_css_theme, "CSS Theme");
            case "Chat Filters": return UiText.get(R.string.waex_ui_chat_filters, "Chat Filters");
            case "Stickers": return UiText.get(R.string.waex_ui_stickers, "Stickers");
            case "Prevent Revoke": return UiText.get(R.string.waex_ui_prevent_revoke, "Prevent Revoke");
            case "Anti View Once": return UiText.get(R.string.waex_ui_anti_view_once_label, "Anti View Once");
            case "Custom Theme CSS": return UiText.get(R.string.feat_custom_theme_title, "Custom Theme CSS");
            case "FilterItems": return UiText.get(R.string.waex_ui_filteritems, "FilterItems");
            case "Custom Privacy (Per-Contact)": return UiText.get(R.string.waex_ui_custom_privacy_per_contact, "Custom Privacy (Per-Contact)");
            case "Chat Filter Display": return UiText.get(R.string.waex_ui_chat_filter_display_feature, "Chat Filter Display");
            case "Custom Timestamp":
            case "Seconds on Timestamp": return UiText.get(R.string.feat_custom_time_format_title, "Custom Timestamp");
            case "Enable IGStatus on Home Screen": return UiText.get(R.string.waex_ui_enable_igstatus_on_home_screen, "Enable IGStatus on Home Screen");
            case "Native IGStatus": return UiText.get(R.string.waex_ui_native_igstatus, "Native IGStatus");
            case "New Settings Style": return UiText.get(R.string.waex_ui_new_settings_style, "New Settings Style");
            case "Separate Chats and Groups": return UiText.get(R.string.waex_ui_separate_chats_and_groups, "Separate Chats and Groups");
            case "CallRecording": return UiText.get(R.string.waex_ui_callrecording, "CallRecording");
            case "ProfilePhotoDownload": return UiText.get(R.string.waex_ui_profilephotodownload, "ProfilePhotoDownload");
            case "AntiDisappearingMessagesHook": return UiText.get(R.string.waex_ui_antidisappearingmessageshook, "AntiDisappearingMessagesHook");
            case "Anti-View Once": return UiText.get(R.string.waex_ui_anti_view_once_feature, "Anti-View Once");
            case "AutoNextStatusHook": return UiText.get(R.string.waex_ui_autonextstatushook, "AutoNextStatusHook");
            case "Do Not Disturb (DND) Mode": return UiText.get(R.string.waex_ui_do_not_disturb_dnd_mode, "Do Not Disturb (DND) Mode");
            case "Freeze Last Seen & Hide Online": return UiText.get(R.string.waex_ui_freeze_last_seen_hide_online, "Freeze Last Seen & Hide Online");
            case "Hide Archived Chats": return UiText.get(R.string.waex_ui_hide_archived_chats, "Hide Archived Chats");
            case "Hide Locked Chats": return UiText.get(R.string.waex_ui_hide_locked_chats, "Hide Locked Chats");
            case "Locked Chats Enhancer": return UiText.get(R.string.waex_ui_locked_chats_enhancer, "Locked Chats Enhancer");
            case "Typing Privacy": return UiText.get(R.string.waex_ui_typing_privacy, "Typing Privacy");
            case "Hide Read Receipts": return UiText.get(R.string.waex_ui_hide_read_receipts, "Hide Read Receipts");
            case "Anti Edit Messages": return UiText.get(R.string.waex_ui_anti_edit_messages, "Anti Edit Messages");
            case "Show as Deleted Message": return UiText.get(R.string.waex_ui_show_as_deleted, "Show as Deleted Message");
            case "Message Device Source": return UiText.get(R.string.waex_ui_message_device_source, "Message Device Source");
            case "Minor Fixes": return UiText.get(R.string.waex_ui_minor_fixes, "Minor Fixes");
            case "Preserve Delete For Me": return UiText.get(R.string.waex_ui_preserve_delete_for_me, "Preserve Delete For Me");
            case "Copy Status Text": return UiText.get(R.string.waex_ui_copy_status_text, "Copy Status Text");
            case "Unknown Feature": return UiText.get(R.string.waex_ui_unknown_feature, "Unknown Feature");
            case "Not Installed": return UiText.get(R.string.waex_ui_not_installed, "Not Installed");
            case "Not reported yet": return UiText.get(R.string.waex_ui_not_reported_yet, "Not reported yet");
            case "Overview": return UiText.get(R.string.waex_ui_overview, "Overview");
            case "Fixes": return UiText.get(R.string.waex_ui_fixes, "Fixes");
            case "Fixed": return UiText.get(R.string.waex_ui_fixed, "Fixed");
            case "Improvements": return UiText.get(R.string.waex_ui_improvements, "Improvements");
            case "Added": return UiText.get(R.string.waex_ui_added, "Added");
            case "New Language": return UiText.get(R.string.waex_ui_new_language, "New Language");
            case "Community contribution": return UiText.get(R.string.waex_ui_community_contribution, "Community contribution");
            case "Notes": return UiText.get(R.string.waex_ui_notes, "Notes");
            case "Chat Bubble Colors": return UiText.get(R.string.waex_ui_chat_bubble_colors, "Chat Bubble Colors");
            case "Channel Recommendations Filter": return UiText.get(R.string.waex_ui_channel_recommendations_filter, "Channel Recommendations Filter");
            case "Jump to First Message": return UiText.get(R.string.feat_jump_to_first_message_title, "Jump to First Message");
            case "Anti Edit": return UiText.get(R.string.waex_ui_anti_edit, "Anti Edit");
            case "Media Status": return UiText.get(R.string.waex_ui_media_status, "Media Status");
            case "Not installed": return UiText.get(R.string.waex_runtime_status_not_installed, "Not installed");
            case "Module inactive": return UiText.get(R.string.waex_runtime_status_module_inactive, "Module inactive");
            case "Injection confirmed": return UiText.get(R.string.waex_runtime_status_injection_confirmed, "Injection confirmed");
            case "Injection unconfirmed: boot identity unavailable": return UiText.get(R.string.waex_runtime_status_boot_identity_unavailable, "Injection unconfirmed: boot identity unavailable");
            case "Injection unconfirmed: report from another boot": return UiText.get(R.string.waex_runtime_status_report_from_another_boot, "Injection unconfirmed: report from another boot");
            case "Injection unconfirmed: no runtime report": return UiText.get(R.string.waex_runtime_status_no_runtime_report, "Injection unconfirmed: no runtime report");
            case "Injection unconfirmed: report expired or clock changed": return UiText.get(R.string.waex_runtime_status_report_expired, "Injection unconfirmed: report expired or clock changed");
            case "Injection unconfirmed: process check unavailable": return UiText.get(R.string.waex_runtime_status_process_check_unavailable, "Injection unconfirmed: process check unavailable");
            case "Reported process is not running": return UiText.get(R.string.waex_runtime_status_reported_process_not_running, "Reported process is not running");
            case "Injection unconfirmed: process changed": return UiText.get(R.string.waex_runtime_status_process_changed, "Injection unconfirmed: process changed");
            case "Injected • Compatibility check unavailable": return UiText.get(R.string.waex_runtime_detail_compatibility_check_unavailable, "Injected • Compatibility check unavailable");
            case "Injected • Paused by target version guard": return UiText.get(R.string.waex_runtime_detail_paused_by_version_guard, "Injected • Paused by target version guard");
            case "Injected • Initialization failed": return UiText.get(R.string.waex_runtime_detail_initialization_failed, "Injected • Initialization failed");
            case "Injected • Local version check requires review": return UiText.get(R.string.waex_runtime_detail_version_check_requires_review, "Injected • Local version check requires review");
            case "Injected • Initialization in progress": return UiText.get(R.string.waex_runtime_detail_initialization_in_progress, "Injected • Initialization in progress");
            case "Injected • Settings connection unavailable": return UiText.get(R.string.waex_runtime_detail_settings_connection_unavailable, "Injected • Settings connection unavailable");
            case "Injected • Using settings fallback": return UiText.get(R.string.waex_runtime_detail_using_settings_fallback, "Injected • Using settings fallback");
            case "Injected • Some features failed": return UiText.get(R.string.waex_runtime_detail_some_features_failed, "Injected • Some features failed");
            case "Injected • Feature readiness unconfirmed": return UiText.get(R.string.waex_runtime_detail_feature_readiness_unconfirmed, "Injected • Feature readiness unconfirmed");
            case "Injected • Settings connection unconfirmed": return UiText.get(R.string.waex_runtime_detail_settings_connection_unconfirmed, "Injected • Settings connection unconfirmed");
            case "Injected • Version bypass active": return UiText.get(R.string.waex_runtime_detail_version_bypass_active, "Injected • Version bypass active");
            case "Injected • Custom version allowance; unverified": return UiText.get(R.string.waex_runtime_detail_custom_version_unverified, "Injected • Custom version allowance; unverified");
            case "Injected • Initialization reported complete": return UiText.get(R.string.waex_runtime_detail_initialization_complete, "Injected • Initialization reported complete");
            case "Connected directly": return UiText.get(R.string.waex_runtime_connection_direct, "Connected directly");
            case "Using disk fallback": return UiText.get(R.string.waex_runtime_connection_disk_fallback, "Using disk fallback");
            case "Unavailable": return UiText.get(R.string.waex_runtime_status_unavailable, "Unavailable");
            case "Not reported": return UiText.get(R.string.waex_runtime_status_not_reported, "Not reported");
            case "Completed": return UiText.get(R.string.waex_runtime_status_completed, "Completed");
            case "Completed with hook failures": return UiText.get(R.string.waex_runtime_status_completed_with_failures, "Completed with hook failures");
            case "In progress": return UiText.get(R.string.waex_runtime_status_in_progress, "In progress");
            case "Paused: compatibility check unavailable": return UiText.get(R.string.waex_runtime_status_paused_compatibility_unavailable, "Paused: compatibility check unavailable");
            case "Paused: unsupported version": return UiText.get(R.string.waex_runtime_status_paused_unsupported_version, "Paused: unsupported version");
            case "Injection detected": return UiText.get(R.string.waex_runtime_status_injection_detected, "Injection detected");
            case "No installed target detected": return UiText.get(R.string.waex_runtime_status_no_target_installed, "No installed target detected");
            case "Version rules require review": return UiText.get(R.string.waex_runtime_status_version_rules_require_review, "Version rules require review");
            case "Target runtime requires review": return UiText.get(R.string.waex_runtime_status_target_runtime_requires_review, "Target runtime requires review");
            case "Version bypass active; compatibility unverified": return UiText.get(R.string.waex_runtime_status_bypass_unverified, "Version bypass active; compatibility unverified");
            case "Custom version allowance; compatibility unverified": return UiText.get(R.string.waex_runtime_status_custom_version_unverified, "Custom version allowance; compatibility unverified");
            case "Version rules accepted; runtime unconfirmed": return UiText.get(R.string.waex_runtime_status_rules_accepted_runtime_unconfirmed, "Version rules accepted; runtime unconfirmed");
            case "Rules accepted; initialization reported": return UiText.get(R.string.waex_runtime_status_rules_accepted_initialization_reported, "Rules accepted; initialization reported");
            default: return value;
        }
    }
}
