package com.waenhancer.xposed.core

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.waenhancer.utils.UniversalVersionValidator
import com.waenhancer.xposed.core.components.NativeWhatsAppDialog
import com.waenhancer.xposed.utils.AppRestartHelper
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VersionGuard checks whether the running WhatsApp or WA Business version is verified and supported.
 * If unsupported and not bypassed, it halts hook execution and displays a native WhatsApp dialog
 * prompting the user to either enable support for this version or manage it in WAEX settings.
 */
object VersionGuard {

    private const val TAG = "[WAEX][VersionGuard]"
    private val dialogShown = AtomicBoolean(false)

    @JvmStatic
    fun verifyAndGuard(
        appContext: Context,
        classLoader: ClassLoader,
        prefs: SharedPreferences
    ): Boolean {
        try {
            val packageInfo: PackageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val currentVersion = packageInfo.versionName ?: ""
            val isSupported = UniversalVersionValidator.isSupported(appContext, currentVersion, prefs)

            if (isSupported) {
                XposedBridge.log("$TAG Version verified and supported: $currentVersion (${appContext.packageName})")
                return true
            }

            XposedBridge.log("$TAG Unsupported WhatsApp version detected: $currentVersion (${appContext.packageName}). Pausing features.")

            // Version is NOT supported and NOT bypassed -> Show Native In-App Dialog on first Activity launch
            if (appContext is Application) {
                appContext.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        showUnsupportedDialogOnce(activity, currentVersion, prefs, classLoader)
                    }

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                    override fun onActivityStarted(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivityStopped(activity: Activity) {}
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                    override fun onActivityDestroyed(activity: Activity) {}
                })
            }

            return false
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error verifying version: ${t.message}")
            return true // Fallback to allowing execution if packageInfo fails
        }
    }

    private fun showUnsupportedDialogOnce(
        activity: Activity,
        currentVersion: String,
        prefs: SharedPreferences,
        classLoader: ClassLoader
    ) {
        if (dialogShown.getAndSet(true)) return

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                if (activity.isFinishing || activity.isDestroyed) return@postDelayed

                NativeWhatsAppDialog.Companion.initialize(activity, classLoader)

                val dialog = NativeWhatsAppDialog(activity)
                    .setTitle("WAEX Compatibility Notice")
                    .setMessage(
                        "Your WhatsApp version ($currentVersion) is not in the verified supported version range.\n\n" +
                        "WAEX features are temporarily paused for safety to prevent app instability.\n\n" +
                        "You can enable support for this version right now with one tap."
                    )
                    .setPositiveButton("Add & Enable ($currentVersion)") { d, _ ->
                        try {
                            UniversalVersionValidator.addCustomVersion(prefs, currentVersion)
                            Toast.makeText(
                                activity,
                                "Version $currentVersion added to supported list!",
                                Toast.LENGTH_LONG
                            ).show()
                            d.dismiss()

                            // Offer quick restart
                            Handler(Looper.getMainLooper()).postDelayed({
                            val appLabel = try {
                                activity.applicationInfo.loadLabel(activity.packageManager).toString()
                            } catch (_: Throwable) { "WhatsApp" }
                            AppRestartHelper.restartPackage(activity, activity.packageName, appLabel)
                        }, 1000)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG Failed to add custom version: ${t.message}")
                    }
                }
                .setNegativeButton("Open WAEX Settings") { d, _ ->
                    try {
                        val intent = Intent().apply {
                            component = ComponentName(
                                "com.waenhancer",
                                "com.waenhancer.app.PermissionsActivity"
                            )
                            putExtra("target_screen", "supported_versions")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        activity.startActivity(intent)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG Failed to open WAEX settings: ${t.message}")
                    }
                    d.dismiss()
                }

                dialog.show()
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to show native dialog: ${t.message}")
            }
        }, 800)
    }
}
