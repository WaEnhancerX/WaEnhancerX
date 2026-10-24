package com.waenhancer.ui.navigation

sealed class Screen(val route: String) {
    object MainDashboard : Screen("main_dashboard")
    object DashboardBento : Screen("dashboard_bento")
    object SystemHealth : Screen("system_health")
    object GlobalPrivacySettings : Screen("global_privacy_settings")
    object ConversationEnhancements : Screen("conversation_enhancements")
    object MediaStatusHub : Screen("media_status_hub")
    object AutomationTasker : Screen("automation_tasker")
    object AudioTranscription : Screen("audio_transcription")
    object LicenseActivation : Screen("license_activation")
    object Search : Screen("search")
    object PerContactPrivacyList : Screen("per_contact_privacy_list")
    object StylesSettings : Screen("styles_settings")
    object Changelog : Screen("changelog")
    data class ReleaseDetails(val tagName: String) : Screen("release/$tagName")
    object About : Screen("about")
    object Stargazers : Screen("stargazers")
    object ModuleSettings : Screen("module_settings")
    object HookingModeHelp : Screen("hooking_mode_help")
    object UpdateSettings : Screen("update_settings")
    object SupportedVersions : Screen("supported_versions")
    object BootloaderSpoofer : Screen("bootloader_spoofer")
    object DeletedMessages : Screen("deleted_messages")
    object CallRecordingSettings : Screen("call_recording_settings")
    object TaskerGuide : Screen("tasker_guide")
    object TaskerHistory : Screen("tasker_history")
    object ManageDevices : Screen("manage_devices")
    object FloatingBottomBarCustomizer : Screen("floating_bottom_bar_customizer")
    object FilterItems : Screen("filter_items")
    object CustomAbProps : Screen("custom_ab_props")
    data class CustomAbPropEditor(val index: Int = -1) : Screen("custom_ab_prop_editor/$index")
    data class InjectedHooks(val packageName: String, val appName: String) : Screen("injected_hooks/$packageName")
}



val Screen.isRootScreen: Boolean
    get() = this is Screen.MainDashboard ||
            this is Screen.GlobalPrivacySettings ||
            this is Screen.MediaStatusHub ||
            this is Screen.AutomationTasker ||
            this is Screen.StylesSettings
