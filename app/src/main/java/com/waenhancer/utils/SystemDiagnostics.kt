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
    @Volatile
    private var cachedSelinux: String? = null

    private val knownManagers = listOf(
        "org.lsposed.manager", "io.github.lsposed.manager",
        "org.meowcat.edxposed.manager", "com.solohsu.android.edxp.manager",
        "de.robv.android.xposed.installer", "me.weishu.exp"
    )

    /** Last successfully collected snapshot for screens recreated during this app process. */
    fun cached(): SystemDiagnostics? = cachedDiagnostics

    /**
     * Fast, non-blocking synchronous snapshot using in-memory state and constants.
     * Takes < 1ms: zero root execution and zero full PackageManager iterations.
     */
    fun getOrFastSnapshot(context: Context, moduleActive: Boolean = ModuleStatus.isModuleActive()): SystemDiagnostics {
        cachedDiagnostics?.let {
            if (it.moduleActive == moduleActive) return it
        }
        return createFastSnapshot(context, moduleActive).also { cachedDiagnostics = it }
    }

    private fun createFastSnapshot(context: Context, moduleActive: Boolean): SystemDiagnostics {
        val runtimeStore = PreferenceStores.publicStore(context)
        val api = runtimeStore.getInt("active_xposed_api_version", 0).takeIf { it > 0 }
        val runtimeFramework = if (moduleActive) {
            runtimeStore.getString("active_xposed_framework", null)?.takeIf { it.isNotBlank() }
        } else null
        val own = runCatching {
            @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        return SystemDiagnostics(
            moduleVersion = own?.versionName ?: BuildConfig.VERSION_NAME,
            moduleVersionCode = own?.let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            } ?: BuildConfig.VERSION_CODE.toLong(),
            buildCommit = BuildConfig.GIT_SHA,
            mappingId = BuildConfig.MAPPING_ID,
            moduleActive = moduleActive,
            framework = FrameworkInfo(
                name = runtimeFramework ?: if (moduleActive) "Xposed-compatible framework" else "Not detected",
                packageName = null,
                version = null,
                api = api
            ),
            rootAllowed = UpdateDownloader.hasCachedRootAccess() ?: false,
            androidVersion = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
            device = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
            abis = Build.SUPPORTED_ABIS.joinToString(", "),
            kernel = System.getProperty("os.version").orEmpty().ifBlank { "Unknown" },
            selinux = cachedSelinux ?: readFastSelinuxState().also { cachedSelinux = it },
            hookedTargets = runtimeStore.getStringSet("hooked_whatsapp_packages", emptySet())
                ?.sorted()?.joinToString()?.ifBlank { "None reported" } ?: "None reported"
        )
    }

    fun read(context: Context, moduleActive: Boolean = ModuleStatus.isModuleActive(), forceRefresh: Boolean = false): SystemDiagnostics {
        val pm = context.packageManager
        @Suppress("DEPRECATION") val own = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        val runtimeStore = PreferenceStores.publicStore(context)
        val api = runCatching {
            if (moduleActive) {
                val method = Class.forName("de.robv.android.xposed.XposedBridge").getMethod("getXposedVersion")
                (method.invoke(null) as? Int)?.takeIf { it > 0 }
            } else null
        }.getOrNull() ?: runtimeStore.getInt("active_xposed_api_version", 0).takeIf { it > 0 }
        val runtimeFramework = if (moduleActive) {
            runtimeStore.getString("active_xposed_framework", null)?.takeIf { it.isNotBlank() }
        } else null
        val manager = findFrameworkManager(pm, runtimeFramework)
        val hooked = runtimeStore.getStringSet("hooked_whatsapp_packages", emptySet()).orEmpty()
        val rootAllowed = UpdateDownloader.hasRootAccess(forceRefresh)
        val selinuxState = cachedSelinux?.takeIf { !forceRefresh } ?: readSelinuxState(rootAllowed).also { cachedSelinux = it }

        return SystemDiagnostics(
            moduleVersion = own?.versionName ?: BuildConfig.VERSION_NAME,
            moduleVersionCode = own?.let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            } ?: BuildConfig.VERSION_CODE.toLong(),
            buildCommit = BuildConfig.GIT_SHA,
            mappingId = BuildConfig.MAPPING_ID,
            moduleActive = moduleActive,
            framework = FrameworkInfo(
                name = runtimeFramework ?: manager?.first
                    ?: if (moduleActive) "Xposed-compatible framework" else "Not detected",
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
            selinux = selinuxState,
            hookedTargets = hooked.sorted().joinToString().ifBlank { "None reported" }
        ).also { cachedDiagnostics = it }
    }

    /**
     * Fast check only testing known manager packages via direct lookup.
     * Avoids O(N) scanning of all installed packages on device with GET_META_DATA.
     */
    private fun findFrameworkManager(
        pm: PackageManager,
        runtimeFramework: String?
    ): Triple<String, String, String?>? {
        val installed = knownManagers.mapNotNull { pkg ->
            runCatching {
                @Suppress("DEPRECATION") val info = pm.getPackageInfo(pkg, 0)
                val label = pm.getApplicationLabel(info.applicationInfo ?: return@runCatching null).toString()
                Triple(label.ifBlank { frameworkFallback(pkg) }, pkg, info.versionName)
            }.getOrNull()
        }.distinctBy { it.second }

        if (runtimeFramework == null) return installed.firstOrNull()
        return installed.firstOrNull { candidate ->
            managerMatchesRuntime(candidate.first, candidate.second, runtimeFramework)
        } ?: installed.firstOrNull()
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

    private fun readFastSelinuxState(): String {
        runCatching {
            normalizeSelinuxState(File("/sys/fs/selinux/enforce").readText())
        }.getOrNull()?.let { return it }

        runCatching {
            val selinux = Class.forName("android.os.SELinux")
            val enforced = selinux.getMethod("isSELinuxEnforced").invoke(null) as Boolean
            return if (enforced) "Enforcing" else "Permissive"
        }.getOrNull()?.let { return it }

        return "Enforcing"
    }

    private fun readSelinuxState(rootAllowed: Boolean): String {
        runCatching {
            normalizeSelinuxState(File("/sys/fs/selinux/enforce").readText())
        }.getOrNull()?.let { return it }

        runCatching {
            val selinux = Class.forName("android.os.SELinux")
            val enforced = selinux.getMethod("isSELinuxEnforced").invoke(null) as Boolean
            return if (enforced) "Enforcing" else "Permissive"
        }.getOrNull()?.let { return it }

        if (rootAllowed) {
            normalizeSelinuxState(AppRestartHelper.runRootCommand("getenforce"))
                ?.let { return it }
        }

        return "Enforcing"
    }

    private fun normalizeSelinuxState(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        "1", "enforcing" -> "Enforcing"
        "0", "permissive" -> "Permissive"
        "disabled" -> "Disabled"
        else -> null
    }
}
