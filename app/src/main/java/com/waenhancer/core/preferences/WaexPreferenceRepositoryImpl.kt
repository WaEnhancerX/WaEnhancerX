package com.waenhancer.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.waenhancer.api.contracts.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "waex_preferences")

@Singleton
class WaexPreferenceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : WaexPreferenceRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override val preferencesFlow: Flow<WaexPreferences> = context.dataStore.data.map { preferences ->
        object : WaexPreferences {
            override fun <T : Any> get(key: WaexPreferenceKey<T>): T {
                return getPreferenceValue(preferences, key)
            }

            override fun getAll(): Map<String, Any> {
                val map = mutableMapOf<String, Any>()
                preferences.asMap().forEach { (prefKey, value) ->
                    map[prefKey.name] = value
                }
                return map
            }
        }
    }

    override suspend fun <T : Any> getPreference(key: WaexPreferenceKey<T>): T {
        val preferences = context.dataStore.data.first()
        return getPreferenceValue(preferences, key)
    }

    override suspend fun <T : Any> updatePreference(key: WaexPreferenceKey<T>, value: T) {
        context.dataStore.edit { preferences ->
            when (val type = key.type) {
                is WaexPreferenceType.BooleanType -> {
                    val k = booleanPreferencesKey(key.key)
                    preferences[k] = value as Boolean
                }
                is WaexPreferenceType.IntType -> {
                    val k = intPreferencesKey(key.key)
                    preferences[k] = value as Int
                }
                is WaexPreferenceType.LongType -> {
                    val k = longPreferencesKey(key.key)
                    preferences[k] = value as Long
                }
                is WaexPreferenceType.StringType -> {
                    val k = stringPreferencesKey(key.key)
                    preferences[k] = value as String
                }
                is WaexPreferenceType.EnumType<*> -> {
                    val k = stringPreferencesKey(key.key)
                    preferences[k] = (value as Enum<*>).name
                }
                is WaexPreferenceType.JsonType<*> -> {
                    val k = stringPreferencesKey(key.key)
                    @Suppress("UNCHECKED_CAST")
                    val serializer = type.serializer as kotlinx.serialization.KSerializer<T>
                    preferences[k] = json.encodeToString(serializer, value)
                }
            }
        }
    }

    override suspend fun clear() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    private fun <T : Any> getPreferenceValue(preferences: Preferences, key: WaexPreferenceKey<T>): T {
        @Suppress("UNCHECKED_CAST")
        return when (val type = key.type) {
            is WaexPreferenceType.BooleanType -> {
                val k = booleanPreferencesKey(key.key)
                (preferences[k] ?: key.defaultValue) as T
            }
            is WaexPreferenceType.IntType -> {
                val k = intPreferencesKey(key.key)
                (preferences[k] ?: key.defaultValue) as T
            }
            is WaexPreferenceType.LongType -> {
                val k = longPreferencesKey(key.key)
                (preferences[k] ?: key.defaultValue) as T
            }
            is WaexPreferenceType.StringType -> {
                val k = stringPreferencesKey(key.key)
                (preferences[k] ?: key.defaultValue) as T
            }
            is WaexPreferenceType.EnumType<*> -> {
                val k = stringPreferencesKey(key.key)
                val name = preferences[k] ?: return key.defaultValue
                try {
                    val enumClass = type.enumClass
                    @Suppress("UPPER_BOUND_VIOLATED", "UNCHECKED_CAST")
                    java.lang.Enum.valueOf(enumClass, name) as T
                } catch (e: Exception) {
                    key.defaultValue
                }
            }
            is WaexPreferenceType.JsonType<*> -> {
                val k = stringPreferencesKey(key.key)
                val jsonStr = preferences[k] ?: return key.defaultValue
                try {
                    @Suppress("UNCHECKED_CAST")
                    val serializer = type.serializer as kotlinx.serialization.KSerializer<T>
                    json.decodeFromString(serializer, jsonStr) as T
                } catch (e: Exception) {
                    key.defaultValue
                }
            }
        }
    }
}
