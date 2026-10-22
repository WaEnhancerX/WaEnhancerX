package com.waenhancer.utils

import com.waenhancer.app.R
import com.waenhancer.utils.UiText

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.waenhancer.config.PreferenceStores
import com.waenhancer.xposed.utils.ModuleStatus
import org.json.JSONArray
import java.io.File

data class InjectedHookItem(
    val name: String,
    val className: String,
    val category: String,
    val status: String,
    val error: String? = null
)

data class HookedAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isInstalled: Boolean,
    val isHooked: Boolean,
    val injectedHooks: List<InjectedHookItem> = emptyList(),
    val lastActiveTime: Long = 0L,
    val runtimeStatus: String = "NOT_DETECTED",
    val bridgeStatus: String = "NOT_REPORTED",
    val initializationError: String? = null,
    val injectionEvidence: InjectionEvidence = if (isHooked) InjectionEvidence.CURRENT else InjectionEvidence.NO_REPORT,
    val processObservedAtElapsed: Long = -1L
)

internal fun hasCurrentInjection(
    isModuleActive: Boolean,
    recordedBoot: Int,
    currentBoot: Int,
    reportedPid: Int,
    runningPids: Set<Int>,
    lastActiveTime: Long,
    now: Long
): Boolean = classifyInjection(isModuleActive, recordedBoot, currentBoot, reportedPid,
    runningPids, lastActiveTime, now) == InjectionEvidence.CURRENT


object WhatsAppPackageDetector {

    private const val MODULE_PACKAGE = "com.waenhancer"

    /** Declared targets are available before Application/Context initialization. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun getSupportedPackages(context: Context?): Set<String> = DeclaredWhatsAppTargets.packages()

    /** Package-name membership only; this does not authenticate a client or confirm hooks. */
    @JvmStatic
    @Suppress("UNUSED_PARAMETER")
    fun isWhatsAppPackageName(context: Context?, packageName: String?): Boolean =
        DeclaredWhatsAppTargets.contains(packageName)

    @JvmStatic
    fun isWhatsAppPackageName(packageName: String?): Boolean =
        DeclaredWhatsAppTargets.contains(packageName)

    /**
     * Registers a hooked WhatsApp package when Xposed injects into it at runtime.
     */
    @JvmStatic
    fun registerHookedPackage(context: Context, packageName: String) {
        // 1. Cross-process IPC to WAEX HookProvider
        try {
            val uri = Uri.parse("content://com.waenhancer.hookprovider")
            val extras = Bundle().apply {
                putString("package", packageName)
                putLong("timestamp", System.currentTimeMillis())
                putInt("boot_count", currentBootCount(context))
            }
            context.contentResolver.call(uri, "register_hooked_package", packageName, extras)
        } catch (_: Throwable) {}

        // 2. Broadcast to WAEX process
        try {
            val intent = Intent("com.waenhancer.ACTION_TARGET_APP_ACTIVE").apply {
                setPackage("com.waenhancer")
                putExtra("PACKAGE", packageName)
                putExtra("TIMESTAMP", System.currentTimeMillis())
            }
            context.sendBroadcast(intent)
        } catch (_: Throwable) {}

        // 3. Local fallback
        try {
            val prefs = PreferenceStores.publicStore(context)
            val existing = LinkedHashSet(prefs.getStringSet("hooked_whatsapp_packages", emptySet()) ?: emptySet())
            if (!existing.contains(packageName)) {
                existing.add(packageName)
                prefs.edit()
                    .putStringSet("hooked_whatsapp_packages", existing)
                    .apply()
            }
        } catch (_: Throwable) {}
    }

    /** Reports sanitized runtime health from a package that is executing WAEX hooks. */
    @JvmStatic
    fun reportRuntimeHealth(
        context: Context,
        packageName: String,
        runtimeStatus: String,
        bridgeStatus: String,
        error: String? = null
    ) = reportRuntimeHealth(context, packageName, runtimeStatus, bridgeStatus, error,
        System.currentTimeMillis())

    /** Keep the observation time when a bounded background queue delays delivery. */
    @JvmStatic
    fun reportRuntimeHealth(
        context: Context,
        packageName: String,
        runtimeStatus: String,
        bridgeStatus: String,
        error: String?,
        observedAtMillis: Long
    ) {
        var accepted = false
        try {
            val extras = Bundle().apply {
                putString("package", packageName)
                putString("runtime_status", runtimeStatus)
                putString("bridge_status", bridgeStatus)
                putString("error", error)
                putLong("timestamp", observedAtMillis)
                putInt("boot_count", currentBootCount(context))
            }
            accepted = context.contentResolver.call(
                Uri.parse("content://com.waenhancer.hookprovider"),
                "report_runtime_health",
                packageName,
                extras
            )?.getBoolean("success", false) == true
        } catch (_: Throwable) {}

        // Wake the dashboard when fresh runtime health details arrive so users do not have to
        // reopen either app to refresh the card.
        if (accepted) {
            try {
                context.sendBroadcast(Intent("com.waenhancer.ACTION_TARGET_APP_ACTIVE").apply {
                    setPackage(MODULE_PACKAGE)
                    putExtra("PACKAGE", packageName)
                    putExtra("TIMESTAMP", observedAtMillis)
                })
            } catch (_: Throwable) {}
        }
    }

    @JvmStatic
    fun currentBootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrElse {
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim().hashCode() }
            .getOrDefault(-1)
    }

    /**
     * Retrieves the list of injected hooks parsed from cached JSON in HookProvider / SharedPreferences.
     */
    @JvmStatic
    fun getInjectedHooks(context: Context, packageName: String): List<InjectedHookItem> {
        val hooks = mutableListOf<InjectedHookItem>()
        try {
            val prefs = PreferenceStores.publicStore(context)
            val jsonStr = prefs.getString("injected_hooks_$packageName", null) ?: return emptyList()
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                hooks.add(
                    InjectedHookItem(
                        name = obj.optString("name", "Unknown Feature"),
                        className = obj.optString("className", ""),
                        category = obj.optString("category", "General"),
                        status = obj.optString("status", "ACTIVE"),
                        error = if (obj.has("error")) obj.optString("error") else null
                    )
                )
            }
        } catch (_: Throwable) {}
        return hooks
    }

    /**
     * Loads the App Icon as an ImageBitmap safely for Compose.
     */
    @JvmStatic
    fun getAppIcon(context: Context, packageName: String): ImageBitmap? {
        return try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                drawable.bitmap.asImageBitmap()
            } else {
                val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
                val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bitmap.asImageBitmap()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Discovers all installed and active WhatsApp-based applications on the device.
     */
    @JvmStatic
    fun detectWhatsAppApps(
        context: Context,
        runtimeSnapshot: XposedRuntimeSnapshot? = XposedRuntimeInspector.cached()
    ): List<HookedAppInfo> {
        val pm = context.packageManager
        val discoveredPackages = LinkedHashSet<String>()

        // 1. Use the same declared targets as the pre-Context Xposed entry point.
        discoveredPackages.addAll(getSupportedPackages(context))

        val isGlobalModuleActive = ModuleStatus.isModuleActive()

        // 2. Read packages that have been hooked by Xposed runtime
        var hookedSet: Set<String>? = null
        val prefs = PreferenceStores.publicStore(context)
        try {
            hookedSet = prefs.getStringSet("hooked_whatsapp_packages", null)
            if (hookedSet != null) {
                discoveredPackages.addAll(hookedSet.filter { isWhatsAppPackageName(it) })
            }
        } catch (_: Throwable) {}

        // Do not scan labels/substrings or promote historical reports to new targets.
        // Membership does not bypass HookProvider caller authorization or LSPosed scope.

        discoveredPackages.remove(MODULE_PACKAGE)

        val result = mutableListOf<HookedAppInfo>()

        for (pkg in discoveredPackages) {
            try {
                val pInfo = pm.getPackageInfo(pkg, 0)
                val label = pm.getApplicationLabel(pInfo.applicationInfo ?: continue).toString()
                val friendlyName = when {
                    label.isNotBlank() && label != pkg -> label
                    pkg == "com.whatsapp" -> "WhatsApp"
                    pkg == "com.whatsapp.w4b" -> "WhatsApp Business"
                    pkg == "com.gbwhatsapp" -> "GBWhatsApp"
                    pkg == "com.fmwhatsapp" -> "FMWhatsApp"
                    pkg == "com.yowhatsapp" || pkg == "com.yowa" -> "YoWhatsApp"
                    pkg == "com.aerowtsapp" || pkg == "com.aero" -> "Aero WhatsApp"
                    pkg == "com.delta" -> "Delta WhatsApp"
                    pkg == "com.ultra" -> "Ultra WhatsApp"
                    pkg == "com.delight" -> "Delight WhatsApp"
                    pkg == "com.ymwhatsapp" -> "YMWhatsApp"
                    pkg == "com.nowha" || pkg == "com.nowha2" -> "NO-WA"
                    pkg.contains("litex") -> "WhatsApp LiteX"
                    else -> "WhatsApp ($pkg)"
                }

                val lastActiveTime = prefs.getLong("last_active_$pkg", 0L)
                val recordedBoot = prefs.getInt("runtime_boot_$pkg", Int.MIN_VALUE)
                val currentBoot = currentBootCount(context)
                val reportedPid = prefs.getInt("runtime_pid_$pkg", -1)
                val evidence = classifyInjection(
                    isModuleActive = isGlobalModuleActive,
                    recordedBoot = recordedBoot,
                    currentBoot = currentBoot,
                    reportedPid = reportedPid,
                    runningPids = runtimeSnapshot?.pidsFor(pkg, android.os.SystemClock.elapsedRealtime()),
                    lastActiveTime = lastActiveTime,
                    now = System.currentTimeMillis()
                )
                val isInjected = evidence == InjectionEvidence.CURRENT
                val injectedHooks = if (isInjected) getInjectedHooks(context, pkg) else emptyList()

                result.add(
                    HookedAppInfo(
                        packageName = pkg,
                        appName = friendlyName,
                        versionName = "v${pInfo.versionName ?: UiText.get(R.string.waex_ui_unknown_b764cdc0, "Unknown")}",
                        isInstalled = true,
                        isHooked = isInjected,
                        injectedHooks = injectedHooks,
                        lastActiveTime = lastActiveTime,
                        runtimeStatus = prefs.getString("runtime_status_$pkg", "NOT_DETECTED") ?: "NOT_DETECTED",
                        bridgeStatus = prefs.getString("bridge_status_$pkg", "NOT_REPORTED") ?: "NOT_REPORTED",
                        initializationError = prefs.getString("runtime_error_$pkg", null),
                        injectionEvidence = evidence,
                        processObservedAtElapsed = runtimeSnapshot?.observedAtElapsed ?: -1L
                    )
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // Only list standard WPP / Business if uninstalled as placeholders if no apps are installed
                if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                    val name = if (pkg == "com.whatsapp") "WhatsApp" else "WhatsApp Business"
                    result.add(
                        HookedAppInfo(
                            packageName = pkg,
                            appName = name,
                            versionName = UiText.get(R.string.waex_ui_not_installed_d08041a7, "Not Installed"),
                            isInstalled = false,
                            isHooked = false,
                            injectedHooks = emptyList(),
                            lastActiveTime = 0L
                        )
                    )
                }
            }
        }

        // Filter out uninstalled placeholders if at least one app is installed
        val installedCount = result.count { it.isInstalled }
        val visible = if (installedCount > 0) {
            result.filter { it.isInstalled }
        } else {
            result
        }
        return visible.sortedBy { it.appName.lowercase() }
    }
}
