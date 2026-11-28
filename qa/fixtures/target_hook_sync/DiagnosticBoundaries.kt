package com.waenhancer.utils
import android.content.Context
data class HookedAppInfo(val packageName: String, val isInstalled: Boolean = true, val isHooked: Boolean = false)
data class LsposedScopeSnapshot(val error: String? = null)
data class XposedRuntimeSnapshot(
    val runningPids: Map<String, Set<Int>> = emptyMap(),
    val complete: Boolean = true,
    val exhaustive: Boolean = true,
    val observedPackages: Set<String> = emptySet()
)
object DeclaredWhatsAppTargets {
    fun packages(): Set<String> = linkedSetOf("com.whatsapp", "com.whatsapp.w4b")
}
object LsposedScopeReader {
    const val STAGED_OK_MARKER = "FIXTURE_SCOPE_OK"
    fun stageCommand(context: Context) = "FIXTURE_SCOPE_STAGE"
    fun parseStaged(context: Context, success: Boolean) = LsposedScopeSnapshot("Fixture: no real root probe")
    fun cached(context: Context) = LsposedScopeSnapshot()
}
object WhatsAppPackageDetector {
    fun detectWhatsAppApps(context: Context, runtime: XposedRuntimeSnapshot?, scope: LsposedScopeSnapshot?): List<HookedAppInfo> =
        SyncFixture.detected
}
fun parseProcessList(text: String, observedAt: Long) = XposedRuntimeSnapshot()
