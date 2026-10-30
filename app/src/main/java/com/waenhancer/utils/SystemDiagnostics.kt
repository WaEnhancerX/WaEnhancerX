package com.waenhancer.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.waenhancer.config.PreferenceStores
import com.waenhancer.app.BuildConfig
import com.waenhancer.update.UpdateDownloader
import com.waenhancer.xposed.utils.AppRestartHelper
import com.waenhancer.xposed.utils.ModuleStatus
import java.io.File

data class FrameworkInfo(val name: String, val packageName: String?, val version: String?, val api: Int?)

data class SystemDiagnostics(
    val moduleVersion: String,
    val moduleVersionCode: Long,
    val buildCommit: String,
    val mappingId: String,
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
    @Volatile
    private var cachedDiagnostics: SystemDiagnostics? = null

    private val knownManagers = listOf(
        "org.lsposed.manager", "io.github.lsposed.manager",
        "org.meowcat.edxposed.manager", "com.solohsu.android.edxp.manager",
        "de.robv.android.xposed.installer", "me.weishu.exp"
    )

    /** Last successfully collected snapshot for screens recreated during this app process. */
    fun cached(): SystemDiagnostics? = cachedDiagnostics

    fun read(context: Context): SystemDiagnostics {
        val pm = context.packageManager
        @Suppress("DEPRECATION") val own = pm.getPackageInfo(context.packageName, 0)
        val active = ModuleStatus.isModuleActive()
        val runtimeStore = PreferenceStores.publicStore(context)
        val api = runCatching {
            if (active) {
                val method = Class.forName("de.robv.android.xposed.XposedBridge").getMethod("getXposedVersion")
                (method.invoke(null) as? Int)?.takeIf { it > 0 }
            } else null
        }.getOrNull() ?: runtimeStore.getInt("active_xposed_api_version", 0).takeIf { it > 0 }
        val runtimeFramework = if (active) {
            runtimeStore.getString("active_xposed_framework", null)?.takeIf { it.isNotBlank() }
        } else null
        val manager = findFrameworkManager(pm, runtimeFramework)
        val hooked = runtimeStore.getStringSet("hooked_whatsapp_packages", emptySet()).orEmpty()
        val rootAllowed = UpdateDownloader.hasRootAccess()
        return SystemDiagnostics(
            moduleVersion = own.versionName.orEmpty(),
            moduleVersionCode = if (Build.VERSION.SDK_INT >= 28) own.longVersionCode else @Suppress("DEPRECATION") own.versionCode.toLong(),
            buildCommit = BuildConfig.GIT_SHA,
            mappingId = BuildConfig.MAPPING_ID,
            moduleActive = active,
            framework = FrameworkInfo(
                name = runtimeFramework ?: manager?.first
                    ?: if (active) "Xposed-compatible framework" else "Not detected",
                packageName = manager?.second,
                version = manager?.third,
                api = api
            ),
            rootAllowed = rootAllowed,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            kernel = System.getProperty("os.version").orEmpty().ifBlank { "Unknown" },
            selinux = readSelinuxState(rootAllowed),
            hookedTargets = hooked.sorted().joinToString().ifBlank { "None reported" }
        ).also { cachedDiagnostics = it }
    }

    /**
     * Finds metadata for the runtime-reported framework. Package installation alone is not proof
     * that a framework is active; when runtime evidence is unavailable, only an unambiguous single
     * candidate is returned.
     */
    private fun findFrameworkManager(
        pm: PackageManager,
        runtimeFramework: String?
    ): Triple<String, String, String?>? {
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
        val installed = candidates.mapNotNull { pkg ->
            runCatching {
                @Suppress("DEPRECATION") val info = pm.getPackageInfo(pkg, 0)
                val label = pm.getApplicationLabel(info.applicationInfo ?: return@runCatching null).toString()
                Triple(label.ifBlank { frameworkFallback(pkg) }, pkg, info.versionName)
            }.getOrNull()
        }.distinctBy { it.second }

        if (runtimeFramework == null) return installed.singleOrNull()
        return installed.firstOrNull { candidate ->
            managerMatchesRuntime(candidate.first, candidate.second, runtimeFramework)
        }
    }

    private fun managerMatchesRuntime(label: String, packageName: String, runtime: String): Boolean {
        val identity = "$label $packageName".lowercase()
        return when (runtime.lowercase()) {
            "vector" -> identity.contains("vector") || identity.contains("matrix")
            "lsposed" -> identity.contains("lsposed") || identity.contains("lspd")
            "edxposed" -> identity.contains("edxposed") || identity.contains("edxp")
            else -> identity.contains(runtime.lowercase())
        }
    }

    private fun frameworkFallback(pkg: String) = when {
        pkg.contains("lsposed", true) -> "LSPosed"
        pkg.contains("edxposed", true) -> "EdXposed"
        else -> "Xposed"
    }

    private fun readSelinuxState(rootAllowed: Boolean): String {
        // Some devices permit the kernel flag to be read directly, but normal application
        // domains are commonly denied access by SELinux itself.
        runCatching {
            normalizeSelinuxState(File("/sys/fs/selinux/enforce").readText())
        }.getOrNull()?.let { return it }

        // Android exposes the state internally on several platform versions. Reflection is kept
        // best-effort because hidden-API enforcement differs by OS release and vendor build.
        runCatching {
            val selinux = Class.forName("android.os.SELinux")
            val enforced = selinux.getMethod("isSELinuxEnforced").invoke(null) as Boolean
            if (enforced) "Enforcing" else "Permissive"
        }.getOrNull()?.let { return it }

        // Root is the authoritative fallback and mirrors Android's documented `getenforce`
        // diagnostic command. Do not trigger another superuser request when root was not granted.
        if (rootAllowed) {
            normalizeSelinuxState(AppRestartHelper.runRootCommand("getenforce"))
                ?.let { return it }
        }

        return "Unavailable"
    }

    private fun normalizeSelinuxState(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "1", "enforcing" -> "Enforcing"
        "0", "permissive" -> "Permissive"
        "disabled" -> "Disabled"
        else -> null
    }
}
