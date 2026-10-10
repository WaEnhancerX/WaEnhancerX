package com.waenhancer.xposed.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import com.waenhancer.utils.UniversalVersionValidator
import com.waenhancer.xposed.core.components.WaexBottomSheet
import de.robv.android.xposed.XposedBridge
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

            // ActivityTracker is registered once before verifyAndGuard by MainHook.
            // Its onActivityResumed callback shows the dialog. Avoid globally
            // hooking Activity.onResume (or registering a second lifecycle observer).

            return false
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error verifying version: ${t.message}")
            return false // Fail closed; an unknown host version must not run obfuscated hooks.
        }
    }

    @JvmStatic
    fun onTargetActivityResumed(activity: Activity) {
        val prefs = sPrefs ?: return
        val currentVersion = sCurrentVersion.ifEmpty { return }
        if (dialogShown.get()) return
        if (activity.isFinishing || activity.isDestroyed) return
        val actName = activity.javaClass.name
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

                XposedBridge.log("$TAG Displaying compatibility warning for $currentVersion")

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
                dialogShown.set(false) // A failed dialog must not permanently suppress the notice.
                XposedBridge.log("$TAG Failed to show native bottom sheet: ${t.message}")
                t.printStackTrace()
            }
        }
    }
}

