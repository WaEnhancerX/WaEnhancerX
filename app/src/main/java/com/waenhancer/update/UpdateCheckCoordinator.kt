package com.waenhancer.update

import com.waenhancer.utils.UiText

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.waenhancer.app.R
import com.waenhancer.app.MainActivity
import com.waenhancer.config.PreferenceStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import android.content.SharedPreferences

object UpdateCheckCoordinator {
    const val TRIGGER_MODULE = "module"
    const val TRIGGER_WHATSAPP = "whatsapp"
    private const val CHANNEL_ID = "waex_updates"
    const val NOTIFICATION_ID = 7201
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checking = AtomicBoolean(false)

    private val _availableRelease = MutableStateFlow<WaexRelease?>(null)
    val availableRelease: StateFlow<WaexRelease?> = _availableRelease.asStateFlow()

    fun dismissInAppUpdate() {
        _availableRelease.value = null
    }

    @JvmStatic
    fun request(context: Context, trigger: String, force: Boolean = false) {
        val app = context.applicationContext
        val prefs = PreferenceStores.privateStore(app)
        if (!prefs.getBoolean("update_auto_check", true)) return
        if (trigger == TRIGGER_MODULE && !prefs.getBoolean("update_check_module_open", true)) return
        if (trigger == TRIGGER_WHATSAPP && !prefs.getBoolean("update_check_whatsapp_open", true)) return

        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("update_last_check", 0L) < intervalMillis(
                prefs.getString("update_frequency", "daily") ?: "daily")) return
        if (!checking.compareAndSet(false, true)) return

        scope.launch {
            try {
                val installed = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "0"
                val releases = ReleaseRepository.fetchReleases(app)
                prefs.edit().putLong("update_last_check", now).apply()
                val release = ReleaseRepository.findUpdate(releases, installed)
                if (release == null) {
                    clearAvailableUpdate(app)
                    _availableRelease.value = null
                    NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
                    return@launch
                }
                
                // Save update metadata in public store so hooked WhatsApp process can display WDS bottom sheet
                PreferenceStores.publicStore(app).edit()
                    .putString("available_update_version", release.version)
                    .putString("available_update_tag", release.tagName)
                    .putString("available_update_name", release.name)
                    .putString("available_update_changelog", release.body)
                    .apply()

                if (shouldSuppressIgnored(prefs, release.version, now)) return@launch

                // Notify UI state flow if module is currently open
                _availableRelease.value = release
                showNotification(app, release)
            } catch (_: Throwable) {
                // Startup checks are deliberately silent when offline.
            } finally {
                checking.set(false)
            }
        }
    }

    private fun clearAvailableUpdate(context: Context) {
        PreferenceStores.publicStore(context).edit()
            .remove("available_update_version")
            .remove("available_update_tag")
            .remove("available_update_name")
            .remove("available_update_changelog")
            .apply()
    }

    private fun intervalMillis(value: String): Long = when (value) {
        "every_open" -> 0L
        "six_hours" -> 6L * 60L * 60L * 1000L
        "weekly" -> 7L * 24L * 60L * 60L * 1000L
        else -> 24L * 60L * 60L * 1000L
    }

    private fun shouldSuppressIgnored(prefs: SharedPreferences, version: String, now: Long): Boolean {
        if (prefs.getString("ignored_version", "").orEmpty() != version) return false
        val ignoredAt = prefs.getLong("ignored_timestamp", 0L)
        return when (prefs.getString("update_ignored_behavior", "next_release")) {
            "next_check" -> false
            "after_day" -> now - ignoredAt < 24L * 60L * 60L * 1000L
            "after_week" -> now - ignoredAt < 7L * 24L * 60L * 60L * 1000L
            else -> true
        }
    }

    private fun showNotification(context: Context, release: WaexRelease) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) return
        val manager = NotificationManagerCompat.from(context)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "WaEnhancerX updates", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra("release_tag", release.tagName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, NOTIFICATION_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val ignoreIntent = Intent(context, UpdateIgnoreReceiver::class.java)
            .putExtra("version", release.version)
        val ignorePending = PendingIntent.getBroadcast(context, NOTIFICATION_ID + 1, ignoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_waex_download)
            .setContentTitle(UiText.get(R.string.waex_ui_waex_1_s_is_available, "WAEX %1\$s is available", release.version))
            .setContentText(release.name)
            .setStyle(NotificationCompat.BigTextStyle().bigText(release.body.take(500)))
            .setContentIntent(pending)
            .addAction(0, "Ignore", ignorePending)
            .setAutoCancel(true)
            .build())
    }
}
