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
import com.waenhancer.xposed.core.components.WaexBottomSheet
import com.waenhancer.xposed.utils.AppRestartHelper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VersionGuard checks whether the running WhatsApp or WA Business version is verified and supported.
 * If unsupported and not bypassed, it halts hook execution and displays a native WhatsApp bottom sheet
 * prompting the user to either enable support for this version or manage it in WAEX settings.
 */
object VersionGuard {

    private const val TAG = "[WAEX][VersionGuard]"
    private val dialogShown = AtomicBoolean(false)
    private var sCurrentVersion: String = ""
    private var sPrefs: SharedPreferences? = null

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

            sCurrentVersion = currentVersion
            sPrefs = prefs
            dialogShown.set(false)

            XposedBridge.log("$TAG Unsupported WhatsApp version detected: $currentVersion (${appContext.packageName}). Pausing features.")

            // Hook Activity.onResume for immediate, reliable bottom sheet trigger on UI startup
            try {
                XposedHelpers.findAndHookMethod(
                    Activity::class.java,
                    "onResume",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            onTargetActivityResumed(activity)
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Error hooking Activity.onResume: ${t.message}")
            }

            // Fallback via ActivityLifecycleCallbacks
            if (appContext is Application) {
                appContext.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        onTargetActivityResumed(activity)
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

    @JvmStatic
    fun onTargetActivityResumed(activity: Activity) {
        val prefs = sPrefs ?: run {
            XposedBridge.log("$TAG onTargetActivityResumed: sPrefs is null")
            return
        }
        val currentVersion = sCurrentVersion.ifEmpty {
            XposedBridge.log("$TAG onTargetActivityResumed: sCurrentVersion is empty")
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return
        val actName = activity.javaClass.name
        XposedBridge.log("$TAG onTargetActivityResumed received: $actName")
        if (actName.contains("Permission") || actName.contains("Stub") || actName.contains("Splash")) return
        showUnsupportedBottomSheetOnce(activity, currentVersion, prefs)
    }

    private fun showUnsupportedBottomSheetOnce(
        activity: Activity,
        currentVersion: String,
        prefs: SharedPreferences
    ) {
        if (dialogShown.getAndSet(true)) {
            XposedBridge.log("$TAG Bottom sheet already shown or pending.")
            return
        }

        XposedBridge.log("$TAG Posting showUnsupportedBottomSheetOnce for $currentVersion on ${activity.javaClass.name}")

        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    dialogShown.set(false)
                    return@runOnUiThread
                }

                val wildcard = UniversalVersionValidator.toWildcard(currentVersion)
                XposedBridge.log("$TAG Displaying WaexBottomSheet with wildcard: $wildcard")

                WaexBottomSheet(activity)
                    .setTitle("WAEX Compatibility Notice")
                    .setMessage(
                        "Your WhatsApp version ($currentVersion) is not in the verified supported version range.\n\n" +
                        "WAEX features are temporarily paused for safety to prevent app instability.\n\n" +
                        "You can manage supported versions in WAEX settings."
                    )
                    .setPositiveButton("Open WAEX Settings") { dialog, _ ->
                        try {
                            XposedBridge.log("$TAG Open WAEX Settings clicked!")
                            val pm = activity.packageManager
                            val intent = pm.getLaunchIntentForPackage("com.waenhancer") ?: Intent().apply {
                                setClassName("com.waenhancer", "com.waenhancer.app.PermissionsActivity")
                            }
                            intent.putExtra("target_screen", "supported_versions")
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            activity.startActivity(intent)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Failed to open WAEX settings: ${t.message}")
                            t.printStackTrace()
                        }
                        dialog.dismiss()
                    }
                    .show()
            } catch (t: Throwable) {
                XposedBridge.log("$TAG Failed to show native bottom sheet: ${t.message}")
                t.printStackTrace()
            }
        }
    }
}

