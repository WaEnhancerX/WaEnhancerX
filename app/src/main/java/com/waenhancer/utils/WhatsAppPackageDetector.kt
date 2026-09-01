package com.waenhancer.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.waenhancer.config.PreferenceStores

data class HookedAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isInstalled: Boolean,
    val isHooked: Boolean
)

object WhatsAppPackageDetector {

    /**
     * Comprehensive database of all known WhatsApp official builds, clones, and variants.
     */
    val KNOWN_WHATSAPP_PACKAGES = listOf(
        // Official Builds
        "com.whatsapp",
        "com.whatsapp.w4b",

        // Major Modded Ecosystems & Clones
        "com.gbwhatsapp",
        "com.fmwhatsapp",
        "com.yowhatsapp",
        "com.aerowtsapp",
        "com.aero",
        "com.delta",
        "com.ultra",
        "com.delight",
        "com.universe.messenger",
        "com.directchat.app",
        "com.sathwbg.easymessager",

        // LiteX & Lightweight Variants
        "com.whatsapplitex",
        "com.whatsapplitex2",
        "com.whatsapp.litex",
        "com.wa.litex",

        // Specialty & Regional Clones
        "online.whatsticker",
        "com.gbwhatsapp.sofid",
        "com.yowa",
        "com.ymwhatsapp",
        "com.nowha",
        "com.nowha2",
        "com.whatsapp2",
        "com.wa",
        "com.wago",

        // Additional Recognized Clones Worldwide
        "com.whatsapp.plus",
        "com.ogwhatsapp",
        "com.soula2",
        "com.co.whatsapp",
        "com.bwhatsapp",
        "com.fouadwhatsapp",
        "com.samwhatsapp",
        "com.hewhatsapp",
        "com.mbwhatsapp",
        "com.rcwhatsapp",
        "com.na4whatsapp",
        "com.na7whatsapp",
        "com.anwhatsapp",
        "com.obwhatsapp",
        "com.ob2whatsapp",
        "com.ob3whatsapp",
        "com.ob4whatsapp",
        "com.ob5whatsapp",
        "com.ob6whatsapp",
        "com.whatsapp.dual",
        "com.whatsapp.clone"
    )

    /**
     * Returns true if a package name matches any known WhatsApp official build or clone variant.
     */
    @JvmStatic
    fun isWhatsAppPackageName(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        if (pkg == "com.waenhancer" || pkg.startsWith("android") || pkg.startsWith("com.android") || pkg.startsWith("com.google.android")) {
            return false
        }

        // 1. Direct match in database
        if (KNOWN_WHATSAPP_PACKAGES.contains(pkg)) {
            return true
        }

        // 2. Pattern recognition for custom clones & dual app wrappers
        return pkg.contains("whatsapp") ||
                pkg.contains("w4b") ||
                pkg.contains("gbwa") ||
                pkg.contains("fmwa") ||
                pkg.contains("yowa") ||
                pkg.contains("nowha") ||
                pkg.contains("aerow") ||
                pkg.contains("delight") ||
                pkg.contains("wtsapp") ||
                pkg.contains("wago") ||
                pkg.contains("ymwa") ||
                pkg.endsWith(".litex")
    }

    /**
     * Registers a hooked WhatsApp package when Xposed injects into it at runtime.
     */
    @JvmStatic
    fun registerHookedPackage(context: Context, packageName: String) {
        try {
            val prefs = PreferenceStores.publicStore(context)
            val existing = LinkedHashSet(prefs.getStringSet("hooked_whatsapp_packages", emptySet()) ?: emptySet())
            if (!existing.contains(packageName)) {
                existing.add(packageName)
                prefs.edit()
                    .putStringSet("hooked_whatsapp_packages", existing)
                    .apply()
            }
        } catch (ignored: Throwable) {}
    }

    /**
     * Discovers all installed and active WhatsApp-based applications on the device.
     */
    @JvmStatic
    fun detectWhatsAppApps(context: Context): List<HookedAppInfo> {
        val pm = context.packageManager
        val discoveredPackages = LinkedHashSet<String>()

        // 1. Check known WhatsApp package names
        discoveredPackages.addAll(KNOWN_WHATSAPP_PACKAGES)

        // 2. Read packages that have been hooked by Xposed runtime
        try {
            val prefs = PreferenceStores.publicStore(context)
            val hookedSet = prefs.getStringSet("hooked_whatsapp_packages", null)
            if (hookedSet != null) {
                discoveredPackages.addAll(hookedSet)
            }
        } catch (ignored: Throwable) {}

        // 3. Scan installed apps for any additional WhatsApp clones
        try {
            val installedApps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                if (isWhatsAppPackageName(app.packageName)) {
                    discoveredPackages.add(app.packageName)
                }
            }
        } catch (ignored: Throwable) {}

        val result = mutableListOf<HookedAppInfo>()

        for (pkg in discoveredPackages) {
            try {
                val pInfo = pm.getPackageInfo(pkg, 0)
                val label = pm.getApplicationLabel(pInfo.applicationInfo ?: continue).toString()
                val friendlyName = when {
                    label.isNotBlank() && label != pkg -> label
                    pkg == "com.whatsapp" -> "WhatsApp"
                    pkg == "com.whatsapp.w4b" -> "WA Business"
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

                result.add(
                    HookedAppInfo(
                        packageName = pkg,
                        appName = friendlyName,
                        versionName = "v${pInfo.versionName ?: "Unknown"}",
                        isInstalled = true,
                        isHooked = true
                    )
                )
            } catch (e: PackageManager.NameNotFoundException) {
                // Only list standard WPP / Business if uninstalled as placeholders if no apps are installed
                if (pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b") {
                    val name = if (pkg == "com.whatsapp") "WhatsApp" else "WA Business"
                    result.add(
                        HookedAppInfo(
                            packageName = pkg,
                            appName = name,
                            versionName = "Not Installed",
                            isInstalled = false,
                            isHooked = false
                        )
                    )
                }
            }
        }

        // Filter out uninstalled placeholders if at least one app is installed
        val installedCount = result.count { it.isInstalled }
        return if (installedCount > 0) {
            result.filter { it.isInstalled }
        } else {
            result
        }
    }
}
