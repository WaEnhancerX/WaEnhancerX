package com.waenhancer.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object Auth : NavKey
@Serializable data object Home : NavKey
@Serializable data object Tools : NavKey
@Serializable data object Settings : NavKey
