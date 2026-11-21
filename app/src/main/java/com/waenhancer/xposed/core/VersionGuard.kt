package com.waenhancer.xposed.core

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import com.waenhancer.utils.UniversalVersionValidator
import com.waenhancer.xposed.core.components.WaexBottomSheet
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * VersionGuard applies configured version rules before hook installation. A rule match is
 * not functional acceptance of a host. Unavailable inputs and explicit rejections both
 * pause installation, with distinct diagnostics instead of an automatic bypass.
 */
object VersionGuard {

    private const val TAG = "[WAEX][VersionGuard]"
    private val dialogShown = AtomicBoolean(false)
    private val decisionGeneration = AtomicLong(0L)
    @Volatile private var sCurrentVersion: String = ""
    @Volatile private var sPrefs: SharedPreferences? = null
    @Volatile private var sDecision = UniversalVersionValidator.Decision.VERSION_UNAVAILABLE

    @JvmStatic
    fun runtimeStatus(): String = if (sDecision == UniversalVersionValidator.Decision.UNSUPPORTED)
        "UNSUPPORTED_VERSION" else "COMPATIBILITY_UNAVAILABLE"

    internal fun noticeMessage(version: String, decision: UniversalVersionValidator.Decision): String {
        val reason = when (decision) {
            UniversalVersionValidator.Decision.UNSUPPORTED ->
                UiText.get(R.string.waex_l10n_guard_unsupported,
                    "Your WhatsApp version (%1\$s) does not match the configured version rules.", version)
            UniversalVersionValidator.Decision.RULES_UNAVAILABLE ->
                UiText.get(R.string.waex_l10n_guard_rules_unavailable,
                    "WAEX could not read its version rules from this WhatsApp process. Check that WhatsApp can access the WAEX package and its resources.")
            UniversalVersionValidator.Decision.PREFERENCES_UNAVAILABLE ->
                UiText.get(R.string.waex_l10n_guard_prefs_unavailable,
                    "WAEX could not read the compatibility settings. Check the preference bridge and restart WhatsApp.")
            else -> UiText.get(R.string.waex_l10n_guard_version_unknown,
                "WAEX could not determine the installed WhatsApp version.")
        }
        return reason + "\n\n" +
            UiText.get(R.string.waex_l10n_guard_paused,
                "WAEX features are paused for safety. This is not confirmation of functional compatibility.") +
            "\n\n" + UiText.get(R.string.waex_l10n_guard_review,
                "Open WAEX settings to review the problem. Do not enable a version bypass just to hide an access error.")
    }

    @JvmStatic
    fun verifyAndGuard(
        appContext: Context,
        classLoader: ClassLoader,
        prefs: SharedPreferences
    ): Boolean {
        decisionGeneration.incrementAndGet()
        sPrefs = prefs
        sCurrentVersion = "unknown"
        sDecision = UniversalVersionValidator.Decision.VERSION_UNAVAILABLE
        dialogShown.set(false)
        try {
            val packageInfo: PackageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            val currentVersion = packageInfo.versionName ?: ""
            val decision = UniversalVersionValidator.evaluate(appContext, currentVersion, prefs)
            sDecision = decision
            if (decision.allowed) {
                sPrefs = null
                return true
            }
            sCurrentVersion = currentVersion.ifBlank { "unknown" }

            return false
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Unable to verify WhatsApp version: ${t.javaClass.simpleName}")
            return false // Fail closed: unknown obfuscated methods must not be hooked.
        }
    }

    @JvmStatic
    fun onTargetActivityResumed(activity: Activity) {
        val prefs = sPrefs ?: run {
            return
        }
        val currentVersion = sCurrentVersion.ifEmpty {
            return
        }
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
            return
        }


        val generation = decisionGeneration.get()
        val decision = sDecision
        activity.runOnUiThread {
            // A newer check owns its own dialog flag; an obsolete callback must not reset it.
            if (generation != decisionGeneration.get() || decision.allowed) return@runOnUiThread
            try {
                if (activity.isFinishing || activity.isDestroyed) {
                    dialogShown.set(false)
                    return@runOnUiThread
                }

                WaexBottomSheet(activity)
                    .setTitle(UiText.get(R.string.waex_ui_waex_compatibility_notice, "WAEX Compatibility Notice"))
                    .setMessage(noticeMessage(currentVersion, decision))
                    .setPositiveButton(UiText.get(R.string.waex_ui_open_waex_settings, "Open WAEX Settings")) { dialog, _ ->
                        try {
                            val pm = activity.packageManager
                            val intent = pm.getLaunchIntentForPackage("com.waenhancer") ?: Intent().apply {
                                setClassName("com.waenhancer", "com.waenhancer.app.PermissionsActivity")
                            }
                            intent.putExtra("target_screen", "supported_versions")
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            activity.startActivity(intent)
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Failed to open WAEX settings: ${t.javaClass.simpleName}")
                        }
                        dialog.dismiss()
                    }
                    .show()
            } catch (t: Throwable) {
                // A failed UI attempt must not permanently suppress future warnings.
                dialogShown.set(false)
                XposedBridge.log("$TAG Failed to show native bottom sheet: ${t.javaClass.simpleName}")
            }
        }
    }
}
