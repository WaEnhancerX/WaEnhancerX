package com.waenhancer.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
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
    /** The manager is not hooked, but a target reported through the authorized provider. */
    TARGET_REPORT,
    NONE
}

data class ModuleActivation(val active: Boolean, val signal: ModuleSignal) {
    companion object {
        val INACTIVE = ModuleActivation(false, ModuleSignal.NONE)

        /**
         * The manager is only hooked when it is itself in the LSPosed scope, which users often skip.
         * A fresh, boot-matched report from a target is equally valid proof that the module loaded.
         */
        fun resolve(sentinel: Boolean, apps: List<HookedAppInfo>, nowWall: Long): ModuleActivation = when {
            sentinel -> ModuleActivation(true, ModuleSignal.MANAGER_HOOK)
            apps.any { it.hasFreshTargetReport(nowWall) } -> ModuleActivation(true, ModuleSignal.TARGET_REPORT)
            else -> INACTIVE
        }

        private fun HookedAppInfo.hasFreshTargetReport(nowWall: Long): Boolean =
            isRecentTimestamp(lastActiveTime, nowWall) && injectionEvidence in setOf(
                InjectionEvidence.CURRENT,
                // No root to compare PIDs: the provider already authenticated the reporting uid.
                InjectionEvidence.PROCESS_OBSERVATION_UNAVAILABLE
            )
    }
}

/**
 * Universal source of truth for the module's active state and its active target apps (installed
 * targets that are in the module's LSPosed scope). The module card, the per-app target dropdown,
 * the target compatibility row/sheet and the diagnostics screens must all read from here.
 *
 * Rules:
 *  - A live LSPosed read is the only thing that can remove a target ([TargetScopeState.NOT_IN_SCOPE]).
 *  - Unknown scope (no root, DB unreadable, parse failure) keeps installed targets visible.
 *  - Runtime reports from a target prove injection and therefore scope.
 *  - Refreshes are coalesced, and the root scope read is throttled.
 */
object TargetAppManager {

    private const val ACTION_TARGET_ACTIVE = "com.waenhancer.ACTION_TARGET_APP_ACTIVE"
    private const val LIVE_SCOPE_TTL_MS = 60_000L
    private const val RETRY_SCOPE_TTL_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private val forceScopeRead = AtomicBoolean(false)
    private var isReceiverRegistered = false
    @Volatile private var lastScopeAttemptElapsed = 0L

    private val _scopeSnapshot = MutableStateFlow<LsposedScopeSnapshot?>(null)
    val scopeSnapshot: StateFlow<LsposedScopeSnapshot?> = _scopeSnapshot.asStateFlow()

    private val _runtimeSnapshot = MutableStateFlow<XposedRuntimeSnapshot?>(null)
    val runtimeSnapshot: StateFlow<XposedRuntimeSnapshot?> = _runtimeSnapshot.asStateFlow()

    private val _detectedApps = MutableStateFlow<List<HookedAppInfo>>(emptyList())
    /** Active targets: installed and not proven out of scope. */
    val detectedApps: StateFlow<List<HookedAppInfo>> = _detectedApps.asStateFlow()

    private val _scopedPackages = MutableStateFlow<Set<String>>(emptySet())
    val scopedPackages: StateFlow<Set<String>> = _scopedPackages.asStateFlow()

    private val _moduleActivation = MutableStateFlow(
        ModuleActivation.resolve(ModuleStatus.isModuleActive(), emptyList(), System.currentTimeMillis())
    )
    val moduleActivation: StateFlow<ModuleActivation> = _moduleActivation.asStateFlow()

    private val _refreshEpoch = MutableStateFlow(0L)
    val refreshEpoch: StateFlow<Long> = _refreshEpoch.asStateFlow()

    /** Current module-active answer for non-Compose callers. */
    fun isModuleActive(): Boolean = _moduleActivation.value.active

    private val targetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // Heartbeats arrive every few seconds: re-evaluate evidence but do not re-read the
            // LSPosed database each time.
            if (context != null) refresh(context.applicationContext, force = false)
        }
    }

    /** Primes from cache so the UI never starts blank, registers the listener once and refreshes. */
    fun init(context: Context) {
        val appContext = context.applicationContext

        if (_scopeSnapshot.value == null) {
            val cachedScope = LsposedScopeReader.cached(appContext)
            val cachedRuntime = XposedRuntimeInspector.cached()
            publish(
                WhatsAppPackageDetector.detectWhatsAppApps(appContext, cachedRuntime, cachedScope),
                cachedScope,
                cachedRuntime
            )
        }

        synchronized(this) {
            if (!isReceiverRegistered) {
                val filter = IntentFilter(ACTION_TARGET_ACTIVE)
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(targetReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appContext.registerReceiver(targetReceiver, filter)
                }
                isReceiverRegistered = true
            }
        }

        refresh(appContext, force = true)
    }

    /**
     * Re-evaluates targets and module state. [force] additionally re-reads the LSPosed scope now.
     * Concurrent calls are coalesced; a request made during a run triggers exactly one more run.
     */
    fun refresh(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (force) forceScopeRead.set(true)
        rerunRequested.set(true)
        scope.launch { drain(appContext) }
    }

    private fun drain(context: Context) {
        while (true) {
            if (!running.compareAndSet(false, true)) return // active loop will observe the request
            try {
                while (rerunRequested.getAndSet(false)) {
                    runCatching { refreshOnce(context, forceScopeRead.getAndSet(false)) }
                }
            } finally {
                running.set(false)
            }
            if (!rerunRequested.get()) return // a request raced in after the last check: loop again
        }
    }

    private fun refreshOnce(context: Context, forceScope: Boolean) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val previous = _scopeSnapshot.value
        val ttl = if (previous?.isLive == true) LIVE_SCOPE_TTL_MS else RETRY_SCOPE_TTL_MS
        val liveScope = if (forceScope || previous == null || nowElapsed - lastScopeAttemptElapsed >= ttl) {
            lastScopeAttemptElapsed = nowElapsed
            LsposedScopeReader.read(context)
        } else previous

        // Only probe processes for targets that could be active; skip ones a live read excludes.
        val probeTargets = WhatsAppPackageDetector.installedTargets(context)
            .filter { !liveScope.isLive || liveScope.contains(it) || hasReport(context, it) }
        val runtime = XposedRuntimeInspector.inspect(probeTargets)

        publish(WhatsAppPackageDetector.detectWhatsAppApps(context, runtime, liveScope), liveScope, runtime)
    }

    private fun hasReport(context: Context, pkg: String): Boolean =
        runCatching {
            com.waenhancer.config.PreferenceStores.publicStore(context).getLong("last_active_$pkg", 0L) > 0L
        }.getOrDefault(false)

    private fun publish(
        detected: List<HookedAppInfo>,
        scopeSnapshot: LsposedScopeSnapshot?,
        runtime: XposedRuntimeSnapshot?
    ) {
        _scopeSnapshot.value = scopeSnapshot
        _runtimeSnapshot.value = runtime
        _detectedApps.value = detected
        _scopedPackages.value = detected.filter { it.isInstalled && it.inLsposedScope }.map { it.packageName }.toCollection(LinkedHashSet())
        _moduleActivation.value = ModuleActivation.resolve(ModuleStatus.isModuleActive(), detected, System.currentTimeMillis())
        _refreshEpoch.value = System.currentTimeMillis()
    }
}
