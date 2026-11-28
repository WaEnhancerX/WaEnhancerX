package com.waenhancer.config
import android.content.Context
import com.waenhancer.utils.SyncFixture
class FixturePreferences {
    fun getLong(key: String, fallback: Long): Long = SyncFixture.preferences[key]?.get() ?: fallback
}
object PreferenceStores {
    private val prefs = FixturePreferences()
    fun publicStore(context: Context): FixturePreferences = prefs
}
