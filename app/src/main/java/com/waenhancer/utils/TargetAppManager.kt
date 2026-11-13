package com.waenhancer.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import com.waenhancer.xposed.utils.AppRestartHelper
import com.waenhancer.xposed.utils.ModuleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** What proved the module is running. Shown by diagnostics; never changes hook permissions. */
enum class ModuleSignal {
    /** The manager process itself is hooked (ModuleStatus sentinel returned true). */
    MANAGER_HOOK,
    /** The manager is not hooked, but a target process is confirmed injected. */
    TARGET_INJECTION,
    NONE
}

data class ModuleActivation(val active: Boolean, val signal: ModuleSignal) {
    companion object {
        val INACTIVE = ModuleActivation(false, ModuleSignal.NONE)

        /**
         * The manager is only hooked when it is itself in the LSPosed scope, which users often skip.
         * A confirmed injection in any target (same pid alive, same boot) is equally valid proof.
         */
        fun resolve(sentinel: Boolean, apps: List<HookedAppInfo>): ModuleActivation = when {
            sentinel -> ModuleActivation(true, ModuleSignal.MANAGER_HOOK)
            apps.any { it.isHooked } -> ModuleActivation(true, ModuleSignal.TARGET_INJECTION)
            else -> INACTIVE
        }
    }
}

/** Result of the one root call that supplies both scope and live processes. */
internal data class RootProbeResult(val scope: LsposedScopeSnapshot, val runtime: XposedRuntimeSnapshot?)

/** One `su` invocation per refresh: stage the LSPosed scope DB and list processes. */
internal object RootProbe {
    private const val PS_BEGIN = "WAEX_PS_BEGIN"
    private const val PS_END = "WAEX_PS_END"

    fun run(context: Context): RootProbeResult {
        val script = LsposedScopeReader.stageCommand(context) +
            "\necho $PS_BEGIN\n(ps -A -o PID,NAME 2>/dev/null || ps 2>/dev/null)\necho $PS_END"
        val startedAt = SystemClock.elapsedRealtime()
        val output = try { AppRestartHelper.runRootCommand(script).orEmpty() }
            catch (_: RuntimeException) { "" } catch (_: LinkageError) { "" }
        val scope = LsposedScopeReader.parseStaged(context, output.contains(LsposedScopeReader.STAGED_OK_MARKER))
        return RootProbeResult(scope, parseRuntime(output, startedAt))
    }

    internal fun parseRuntime(output: String, observedAt: Long): XposedRuntimeSnapshot? {
        val begin = output.indexOf(PS_BEGIN)
        val end = output.indexOf(PS_END)
        if (begin < 0 || end < begin) return null // truncated: an incomplete list must not mean "not running"
        val snapshot = parseProcessList(output.substring(begin + PS_BEGIN.length, end), observedAt)
        return snapshot.takeIf { it.runningPids.isNotEmpty() }
    }
}

/**
 * Universal source of truth for the module's active state and its active target apps (installed
 * apps in the module's LSPosed scope). The module card, the per-app target dropdown, the target
 * compatibility row/sheet and the diagnostics screens must all read from here.
 *
 * WAEX is root-only, so state is read on demand with root (scope DB + live process list) when the
 * home screen resumes or the user refreshes. Nothing polls and nothing listens while the screen is
 * not visible; a visible screen additionally gets a refresh when a target reports.
 */
object TargetAppManager {

    private const val ACTION_TARGET_ACTIVE = "com.waenhancer.ACTION_TARGET_APP_ACTIVE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private var receiverRegistered = false

    private val _scopeSnapshot = MutableStateFlow<LsposedScopeSnapshot?>(null)
    val scopeSnapshot: StateFlow<LsposedScopeSnapshot?> = _scopeSnapshot.asStateFlow()

    private val _runtimeSnapshot = MutableStateFlow<XposedRuntimeSnapshot?>(null)
    val runtimeSnapshot: StateFlow<XposedRuntimeSnapshot?> = _runtimeSnapshot.asStateFlow()

    private val _detectedApps = MutableStateFlow<List<HookedAppInfo>>(emptyList())
    /** Active targets: installed apps in the module's scope (or confirmed injected). */
    val detectedApps: StateFlow<List<HookedAppInfo>> = _detectedApps.asStateFlow()

    private val _scopedPackages = MutableStateFlow<Set<String>>(emptySet())
    val scopedPackages: StateFlow<Set<String>> = _scopedPackages.asStateFlow()

    private val _moduleActivation = MutableStateFlow(ModuleActivation.resolve(ModuleStatus.isModuleActive(), emptyList()))
    val moduleActivation: StateFlow<ModuleActivation> = _moduleActivation.asStateFlow()

    private val _probeError = MutableStateFlow<String?>(null)
    /** Non-null when the last root probe could not read the scope or processes; show it, don't hide it. */
    val probeError: StateFlow<String?> = _probeError.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    /** False until the first root probe finished, so UIs can show "checking" instead of "none". */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _refreshEpoch = MutableStateFlow(0L)
    val refreshEpoch: StateFlow<Long> = _refreshEpoch.asStateFlow()

    /** Current module-active answer for non-Compose callers. */
    fun isModuleActive(): Boolean = _moduleActivation.value.active

    private val targetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context != null) refresh(context.applicationContext)
        }
    }

    /**
     * Call when the home screen becomes visible: primes from cache so the UI never starts blank,
     * listens for target reports while visible, and reads fresh state with root.
     */
    fun attach(context: Context) {
        val appContext = context.applicationContext
        if (_scopeSnapshot.value == null) {
            val cachedScope = LsposedScopeReader.cached(appContext)
            publish(WhatsAppPackageDetector.detectWhatsAppApps(appContext, null, cachedScope), cachedScope, null, probed = false)
        }
        synchronized(this) {
            if (!receiverRegistered) {
                val filter = IntentFilter(ACTION_TARGET_ACTIVE)
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(targetReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appContext.registerReceiver(targetReceiver, filter)
                }
                receiverRegistered = true
            }
        }
        refresh(appContext)
    }

    /** Call when the home screen is no longer visible: stop listening. */
    fun detach(context: Context) {
        synchronized(this) {
            if (receiverRegistered) {
                runCatching { context.applicationContext.unregisterReceiver(targetReceiver) }
                receiverRegistered = false
            }
        }
    }

    /**
     * Re-reads scope and processes with root. Concurrent calls are coalesced: a request made during
     * a run triggers exactly one more run, so no update is lost and no two probes overlap.
     */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        rerunRequested.set(true)
        scope.launch { drain(appContext) }
    }

    private fun drain(context: Context) {
        while (true) {
            if (!running.compareAndSet(false, true)) return // the active loop will observe the request
            try {
                while (rerunRequested.getAndSet(false)) {
                    try { refreshOnce(context) } catch (e: Exception) { _probeError.value = "Refresh failed: ${e.javaClass.simpleName}" }
                }
            } finally {
                running.set(false)
            }
            if (!rerunRequested.get()) return // a request raced in after the last check: loop again
        }
    }

    private fun refreshOnce(context: Context) {
        val probe = RootProbe.run(context)
        publish(
            WhatsAppPackageDetector.detectWhatsAppApps(context, probe.runtime, probe.scope),
            probe.scope, probe.runtime, probed = true
        )
    }

    private fun publish(
        detected: List<HookedAppInfo>,
        scopeSnapshot: LsposedScopeSnapshot?,
        runtime: XposedRuntimeSnapshot?,
        probed: Boolean
    ) {
        _scopeSnapshot.value = scopeSnapshot
        _runtimeSnapshot.value = runtime
        _detectedApps.value = detected
        _scopedPackages.value = detected.map { it.packageName }.toCollection(LinkedHashSet())
        _moduleActivation.value = ModuleActivation.resolve(ModuleStatus.isModuleActive(), detected)
        if (probed) {
            _probeError.value = scopeSnapshot?.error ?: if (runtime == null) "Root process check failed" else null
            _loaded.value = true
        }
        _refreshEpoch.value = System.currentTimeMillis()
    }
}
