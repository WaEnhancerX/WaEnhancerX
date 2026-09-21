package com.waenhancer.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.waenhancer.config.PreferenceStores
import com.waenhancer.update.UpdateDownloader
import com.waenhancer.xposed.utils.ModuleStatus

data class FrameworkInfo(val name: String, val packageName: String?, val version: String?, val api: Int?)

data class SystemDiagnostics(
    val moduleVersion: String,
    val moduleVersionCode: Long,
    val moduleActive: Boolean,
    val framework: FrameworkInfo,
    val rootAllowed: Boolean,
    val androidVersion: String,
    val sdk: Int,
    val device: String,
    val abis: String,
    val kernel: String,
    val selinux: String,
    val hookedTargets: String,
)

object SystemDiagnosticsReader {
    private val knownManagers = listOf(
        "org.lsposed.manager", "io.github.lsposed.manager",
        "org.meowcat.edxposed.manager", "com.solohsu.android.edxp.manager",
        "de.robv.android.xposed.installer", "me.weishu.exp"
    )

    fun read(context: Context): SystemDiagnostics {
        val pm = context.packageManager
        @Suppress("DEPRECATION") val own = pm.getPackageInfo(context.packageName, 0)
        val active = ModuleStatus.isModuleActive()
        val api = runCatching {
            if (active) {
                val method = Class.forName("de.robv.android.xposed.XposedBridge").getMethod("getXposedVersion")
                (method.invoke(null) as? Int)?.takeIf { it > 0 }
            } else null
        }.getOrNull() ?: PreferenceStores.publicStore(context).getInt("active_xposed_api_version", 0).takeIf { it > 0 }
        val manager = findFrameworkManager(pm)
        val hooked = PreferenceStores.publicStore(context).getStringSet("hooked_whatsapp_packages", emptySet()).orEmpty()
        return SystemDiagnostics(
            moduleVersion = own.versionName.orEmpty(),
            moduleVersionCode = if (Build.VERSION.SDK_INT >= 28) own.longVersionCode else @Suppress("DEPRECATION") own.versionCode.toLong(),
            moduleActive = active,
            framework = FrameworkInfo(
                name = manager?.first ?: if (active) "Xposed-compatible framework" else "Not detected",
                packageName = manager?.second,
                version = manager?.third,
                api = api
            ),
            rootAllowed = UpdateDownloader.hasRootAccess(),
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            kernel = System.getProperty("os.version").orEmpty().ifBlank { "Unknown" },
            selinux = readSelinuxState(),
            hookedTargets = hooked.sorted().joinToString().ifBlank { "None reported" }
        )
    }

    private fun findFrameworkManager(pm: PackageManager): Triple<String, String, String?>? {
        val candidates = LinkedHashSet<String>().apply {
            addAll(knownManagers)
            runCatching {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { app ->
                    val label = pm.getApplicationLabel(app).toString()
                    if (listOf("lsposed", "xposed", "edxposed", "vector").any { label.contains(it, true) }) add(app.packageName)
                }
            }
        }
        for (pkg in candidates) runCatching {
            @Suppress("DEPRECATION") val info = pm.getPackageInfo(pkg, 0)
            val label = pm.getApplicationLabel(info.applicationInfo ?: return@runCatching).toString()
            return Triple(label.ifBlank { frameworkFallback(pkg) }, pkg, info.versionName)
        }
        return null
    }

    private fun frameworkFallback(pkg: String) = when {
        pkg.contains("lsposed", true) -> "LSPosed"
        pkg.contains("edxposed", true) -> "EdXposed"
        else -> "Xposed"
    }

    private fun readSelinuxState(): String = runCatching {
        when (java.io.File("/sys/fs/selinux/enforce").readText().trim()) {
            "1" -> "Enforcing"
            "0" -> "Permissive"
            else -> "Unknown"
        }
    }.getOrDefault("Unknown")
}
