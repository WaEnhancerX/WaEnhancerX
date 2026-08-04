package com.waenhancer.api.contracts

import kotlinx.coroutines.flow.Flow

interface WaexPreferenceRepository {
    val preferencesFlow: Flow<WaexPreferences>
    suspend fun <T : Any> getPreference(key: WaexPreferenceKey<T>): T
    suspend fun <T : Any> updatePreference(key: WaexPreferenceKey<T>, value: T)
    suspend fun clear()
}
