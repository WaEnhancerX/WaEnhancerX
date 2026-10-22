package com.waenhancer.app

import android.app.Application
import android.content.SharedPreferences
import android.content.Intent
import android.net.Uri
import com.waenhancer.config.PreferenceStores
import com.waenhancer.config.PreferenceRestartTracker
import com.waenhancer.config.PreferenceSyncPolicy
import com.waenhancer.config.FeatureCatalog
import dagger.hilt.android.HiltAndroidApp
import android.content.Context
import com.waenhancer.licensing.features.BootloaderSpooferFeature
import com.waenhancer.utils.LocaleHelper
import com.waenhancer.utils.WhatsAppPackageDetector

@HiltAndroidApp
class WaexApp : Application() {
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { preferences, key ->
            if (PreferenceSyncPolicy.shouldNotifyHooks(key)) {
                PreferenceRestartTracker.onPreferenceChanged(preferences, key)
                contentResolver.notifyChange(PREFERENCES_URI, null)
                notifyHookedPackages(key)
            }
        }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrapContext(base))
    }

    override fun onCreate() {
        super.onCreate()
        com.waenhancer.utils.UiText.initialize(this, PreferenceStores.publicStore(this))
        FeatureCatalog.initialize(this)
        val lang = LocaleHelper.getSelectedLanguageCode(this)
        if (lang != "system") {
            LocaleHelper.applyLanguage(this, lang)
        }
        PreferenceStores.publicStore(this).apply {
            registerOnSharedPreferenceChangeListener(preferenceListener)
            val installedVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
            edit().putString("wae_version_name", installedVersion).apply()
        }
        BootloaderSpooferFeature.syncDefaultKeyboxAsync(this)
    }

    private fun notifyHookedPackages(changedKey: String?) {
        val publicPrefs = PreferenceStores.publicStore(this)
        val targets = LinkedHashSet(
            WhatsAppPackageDetector.getSupportedPackages(this)
        )
        targets.addAll(publicPrefs.getStringSet("hooked_whatsapp_packages", emptySet()).orEmpty())
        targets.forEach { packageName ->
            try {
                sendBroadcast(
                    Intent(PREFERENCES_CHANGED_ACTION)
                        .setPackage(packageName)
                        .putExtra("changed_key", changedKey)
                )
            } catch (_: Throwable) {
            }
        }
    }

    private companion object {
        val PREFERENCES_URI: Uri = Uri.parse("content://com.waenhancer.hookprovider/preferences")
        const val PREFERENCES_CHANGED_ACTION = "com.waenhancer.action.PREFERENCES_CHANGED"
    }
}
