package com.waenhancer.utils

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.waenhancer.app.R
import com.waenhancer.config.PreferenceStores
import com.waenhancer.xposed.utils.ModuleStatus

data class HookedAppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isInstalled: Boolean,
    val isHooked: Boolean
)

object WhatsAppPackageDetector {

    private const val MODULE_PACKAGE = "com.waenhancer"

    /**
     * Dynamically reads supported WhatsApp packages & clones from arrays.xml.
     */
    @JvmStatic
    fun getSupportedPackages(context: Context?): Set<String> {
        val packages = LinkedHashSet<String>()
        if (context != null) {
            try {
                val moduleContext = if (context.packageName == MODULE_PACKAGE) {
                    context
                } else {
                    try {
                        context.createPackageContext(MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                    } catch (_: Throwable) {
                        context
                    }
                }
                val resId = moduleContext.resources.getIdentifier("xposed_scope", "array", MODULE_PACKAGE)
                if (resId != 0) {
                    packages.addAll(moduleContext.resources.getStringArray(resId))
                } else {
                    packages.addAll(context.resources.getStringArray(R.array.xposed_scope))
                }
            } catch (_: Throwable) {}
        }
        return packages
    }

    /**
     * Returns true if a package name matches any supported WhatsApp package from arrays.xml
     * or WhatsApp clone pattern.
     */
    @JvmStatic
    fun isWhatsAppPackageName(context: Context?, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val pkg = packageName.lowercase()
        if (pkg == MODULE_PACKAGE || pkg.startsWith("android") || pkg.startsWith("com.android") || pkg.startsWith("com.google.android")) {
            return false
        }

        // 1. Direct match from arrays.xml
        val supportedPackages = getSupportedPackages(context)
        if (supportedPackages.contains(pkg)) {
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

    @JvmStatic
    fun isWhatsAppPackageName(packageName: String?): Boolean {
        return isWhatsAppPackageName(null, packageName)
    }

    /**
     * Registers a hooked WhatsApp package when Xposed injects into it at runtime.
     */
    @JvmStatic
    fun registerHookedPackage(context: Context, packageName: String) {
        // 1. Cross-process IPC to WAEX HookProvider
        try {
            val uri = android.net.Uri.parse("content://com.waenhancer.hookprovider")
            val extras = android.os.Bundle().apply {
                putString("package", packageName)
                putLong("timestamp", System.currentTimeMillis())
            }
            context.contentResolver.call(uri, "register_hooked_package", packageName, extras)
        } catch (_: Throwable) {}

        // 2. Broadcast to WAEX process
        try {
            val intent = android.content.Intent("com.waenhancer.ACTION_TARGET_APP_ACTIVE").apply {
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
    fun detectWhatsAppApps(context: Context): List<HookedAppInfo> {
        val pm = context.packageManager
        val discoveredPackages = LinkedHashSet<String>()

        // 1. Read dynamically from arrays.xml
        discoveredPackages.addAll(getSupportedPackages(context))

        val isGlobalModuleActive = ModuleStatus.isModuleActive()

        // 2. Read packages that have been hooked by Xposed runtime
        var hookedSet: Set<String>? = null
        try {
            val prefs = PreferenceStores.publicStore(context)
            hookedSet = prefs.getStringSet("hooked_whatsapp_packages", null)
            if (hookedSet != null) {
                discoveredPackages.addAll(hookedSet)
            }
        } catch (_: Throwable) {}

        // 3. Scan installed apps for any additional WhatsApp clones
        try {
            val installedApps: List<ApplicationInfo> = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            for (app in installedApps) {
                if (isWhatsAppPackageName(context, app.packageName)) {
                    discoveredPackages.add(app.packageName)
                }
            }
        } catch (_: Throwable) {}

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

                val isHooked = isGlobalModuleActive && (hookedSet?.contains(pkg) == true)

                result.add(
                    HookedAppInfo(
                        packageName = pkg,
                        appName = friendlyName,
                        versionName = "v${pInfo.versionName ?: "Unknown"}",
                        isInstalled = true,
                        isHooked = isHooked
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
