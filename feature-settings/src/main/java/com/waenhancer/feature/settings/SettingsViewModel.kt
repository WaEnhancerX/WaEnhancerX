package com.waenhancer.feature.settings

import com.waenhancer.core.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class SettingsState(
    val isDarkMode: Boolean = true,
    val isNotificationsEnabled: Boolean = true,
    val version: String = "1.0.0"
)

sealed interface SettingsEvent {
    data class ToggleDarkMode(val enabled: Boolean) : SettingsEvent
    data class ToggleNotifications(val enabled: Boolean) : SettingsEvent
}

@HiltViewModel
class SettingsViewModel @Inject constructor() : BaseViewModel<SettingsState, SettingsEvent>(SettingsState()) {

    override fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ToggleDarkMode -> {
                updateState { copy(isDarkMode = event.enabled) }
            }
            is SettingsEvent.ToggleNotifications -> {
                updateState { copy(isNotificationsEnabled = event.enabled) }
            }
        }
    }
}
