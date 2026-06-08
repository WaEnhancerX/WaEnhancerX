package com.waenhancer.feature.auth

import com.waenhancer.core.base.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class AuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

sealed interface AuthEvent {
    data class Authenticate(val pincode: String) : AuthEvent
    data object ClearError : AuthEvent
}

@HiltViewModel
class AuthViewModel @Inject constructor() : BaseViewModel<AuthState, AuthEvent>(AuthState()) {

    override fun onEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.Authenticate -> {
                updateState { copy(isLoading = true, errorMessage = null) }
                if (event.pincode == "0000") {
                    updateState { copy(isLoading = false, isAuthenticated = true) }
                } else {
                    updateState { copy(isLoading = false, errorMessage = "Invalid PIN. Default is 0000.") }
                }
            }
            is AuthEvent.ClearError -> {
                updateState { copy(errorMessage = null) }
            }
        }
    }
}
