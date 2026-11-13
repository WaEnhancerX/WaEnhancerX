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
import android.os.SystemClock

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
    val processObservedAtElapsed: Long = -1L,
    /** True for every target the module should treat as active (anything but NOT_IN_SCOPE). */
    val inLsposedScope: Boolean = false,
    val scopeState: TargetScopeState = TargetScopeState.UNVERIFIED
)

/** How a target's membership in the module's LSPosed scope was established. */
enum class TargetScopeState {
    /** Framework scope lists the target (live read, or last known when the framework is unreachable). */
    IN_SCOPE,
    /** Scope could not be read, but a current runtime report proves the module is injected. */
    INJECTED,
    /** Scope could not be read and there is no evidence either way; keep the target visible. */
    UNVERIFIED,
    /** A live framework read shows the target is not in scope. Never shown as an active target. */
    NOT_IN_SCOPE
}

/**
 * Scope decision for one installed target. Unknown must never hide a target: only a live
 * framework read can say "not in scope", and current injection always wins over stale data.
 */
internal fun classifyScope(packageName: String, scope: LsposedScopeSnapshot, injected: Boolean): TargetScopeState = when {
    scope.isLive && scope.contains(packageName) -> TargetScopeState.IN_SCOPE
    injected -> TargetScopeState.INJECTED
    scope.isLive -> TargetScopeState.NOT_IN_SCOPE
    scope.contains(packageName) -> TargetScopeState.IN_SCOPE
    else -> TargetScopeState.UNVERIFIED
}

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
    ) {
        var accepted = false
        try {
            val extras = Bundle().apply {
                putString("package", packageName)
                putString("runtime_status", runtimeStatus)
                putString("bridge_status", bridgeStatus)
                putString("error", error)
                putLong("timestamp", System.currentTimeMillis())
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
                    putExtra("TIMESTAMP", System.currentTimeMillis())
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

    /** Declared targets that are installed (cheap PackageManager lookups, no root, no scope filter). */
    @JvmStatic
    fun installedTargets(context: Context): List<String> {
        val pm = context.packageManager
        return getSupportedPackages(context).filter { pkg ->
            pkg != MODULE_PACKAGE && runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    /**
     * Discovers the installed target apps that are active for the module. Targets are listed unless
     * a live LSPosed read proves they are out of scope, so a missing root/DB never empties the list.
     */
    @JvmStatic
    fun detectWhatsAppApps(
        context: Context,
        runtimeSnapshot: XposedRuntimeSnapshot? = XposedRuntimeInspector.cached(),
        scopeSnapshot: LsposedScopeSnapshot? = null
    ): List<HookedAppInfo> {
        val pm = context.packageManager
        val sentinelActive = ModuleStatus.isModuleActive()
        // Never launch a root shell here (callers run on the UI thread): use the supplied or cached scope.
        val scope = scopeSnapshot ?: LsposedScopeReader.cached(context) ?: LsposedScopeSnapshot(
            modulePackage = MODULE_PACKAGE,
            packages = emptySet(),
            frameworkReachable = false,
            source = LsposedScopeSnapshot.Source.UNAVAILABLE,
            error = "Scope check pending"
        )
        val prefs = PreferenceStores.publicStore(context)
        val currentBoot = currentBootCount(context)
        val result = mutableListOf<HookedAppInfo>()

        for (pkg in getSupportedPackages(context)) {
            if (pkg == MODULE_PACKAGE) continue
            val pInfo = try { pm.getPackageInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) { continue }
            val label = runCatching { pm.getApplicationLabel(pInfo.applicationInfo ?: return@runCatching "").toString() }
                .getOrDefault("")
            val friendlyName = friendlyName(pkg, label)

            val lastActiveTime = prefs.getLong("last_active_$pkg", 0L)
            // A report delivered by the target through the authorized provider proves the module
            // loaded there even if the manager process itself is outside the LSPosed scope.
            val moduleActive = sentinelActive || lastActiveTime > 0L
            val evidence = classifyInjection(
                isModuleActive = moduleActive,
                recordedBoot = prefs.getInt("runtime_boot_$pkg", Int.MIN_VALUE),
                currentBoot = currentBoot,
                reportedPid = prefs.getInt("runtime_pid_$pkg", -1),
                runningPids = runtimeSnapshot?.pidsFor(pkg, SystemClock.elapsedRealtime()),
                lastActiveTime = lastActiveTime,
                now = System.currentTimeMillis()
            )
            val isInjected = evidence == InjectionEvidence.CURRENT
            val scopeState = classifyScope(pkg, scope, isInjected)
            if (scopeState == TargetScopeState.NOT_IN_SCOPE) continue

            result.add(
                HookedAppInfo(
                    packageName = pkg,
                    appName = friendlyName,
                    versionName = "v${pInfo.versionName ?: UiText.get(R.string.waex_ui_unknown, "Unknown")}",
                    isInstalled = true,
                    isHooked = isInjected,
                    injectedHooks = if (isInjected) getInjectedHooks(context, pkg) else emptyList(),
                    lastActiveTime = lastActiveTime,
                    runtimeStatus = prefs.getString("runtime_status_$pkg", "NOT_DETECTED") ?: "NOT_DETECTED",
                    bridgeStatus = prefs.getString("bridge_status_$pkg", "NOT_REPORTED") ?: "NOT_REPORTED",
                    initializationError = prefs.getString("runtime_error_$pkg", null),
                    injectionEvidence = evidence,
                    processObservedAtElapsed = runtimeSnapshot?.observedAtElapsed ?: -1L,
                    inLsposedScope = true,
                    scopeState = scopeState
                )
            )
        }
        return result.sortedBy { it.appName.lowercase() }
    }

    private fun friendlyName(pkg: String, label: String): String = when {
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
}
