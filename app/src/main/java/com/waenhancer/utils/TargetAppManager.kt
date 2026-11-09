package com.waenhancer.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Unified single source of truth for scoped target apps and LSPosed module scope.
 *
 * Ensures the top bar dropdown and dashboard target cards share the exact same state,
 * use the exact same listener (ACTION_TARGET_APP_ACTIVE and onResume), and never
 * perform duplicate or conflicting root shell queries.
 */
object TargetAppManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRefreshing = AtomicBoolean(false)
    private var isReceiverRegistered = false

    private val _scopeSnapshot = MutableStateFlow<LsposedScopeSnapshot?>(null)
    val scopeSnapshot: StateFlow<LsposedScopeSnapshot?> = _scopeSnapshot.asStateFlow()

    private val _runtimeSnapshot = MutableStateFlow<XposedRuntimeSnapshot?>(null)
    val runtimeSnapshot: StateFlow<XposedRuntimeSnapshot?> = _runtimeSnapshot.asStateFlow()

    private val _detectedApps = MutableStateFlow<List<HookedAppInfo>>(emptyList())
    val detectedApps: StateFlow<List<HookedAppInfo>> = _detectedApps.asStateFlow()

    private val _scopedPackages = MutableStateFlow<Set<String>>(emptySet())
    val scopedPackages: StateFlow<Set<String>> = _scopedPackages.asStateFlow()

    private val _refreshEpoch = MutableStateFlow(0L)
    val refreshEpoch: StateFlow<Long> = _refreshEpoch.asStateFlow()

    private val targetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (context != null) {
                refresh(context.applicationContext, force = true)
            }
        }
    }

    /**
     * Initializes or primes the manager synchronously with cached data,
     * registers the global broadcast receiver once, and triggers an initial refresh.
     */
    fun init(context: Context) {
        val appContext = context.applicationContext

        // 1. Synchronously prime from cache if not already loaded so UI never starts blank
        if (_scopeSnapshot.value == null) {
            val cachedScope = LsposedScopeReader.cached(appContext)
            val cachedRuntime = XposedRuntimeInspector.cached()
            val cachedDetected = WhatsAppPackageDetector.detectWhatsAppApps(appContext, cachedRuntime, cachedScope)
            val cachedScoped = cachedDetected.filter { it.isInstalled && it.inLsposedScope }.map { it.packageName }.toSet()

            _scopeSnapshot.value = cachedScope
            _runtimeSnapshot.value = cachedRuntime
            _detectedApps.value = cachedDetected
            _scopedPackages.value = cachedScoped
        }

        // 2. Register global broadcast receiver for target app activation
        synchronized(this) {
            if (!isReceiverRegistered) {
                val filter = IntentFilter("com.waenhancer.ACTION_TARGET_APP_ACTIVE")
                if (Build.VERSION.SDK_INT >= 33) {
                    appContext.registerReceiver(targetReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    appContext.registerReceiver(targetReceiver, filter)
                }
                isReceiverRegistered = true
            }
        }

        // 3. Trigger initial refresh
        refresh(appContext)
    }

    /**
     * Refreshes target apps and LSPosed scope.
     * Guaranteed to update detectedApps, scopedPackages, scopeSnapshot, and runtimeSnapshot atomically.
     */
    fun refresh(context: Context, force: Boolean = false) {
        val appContext = context.applicationContext
        if (!force && isRefreshing.get()) return

        scope.launch {
            if (!isRefreshing.compareAndSet(false, true) && !force) return@launch
            try {
                val liveScope = LsposedScopeReader.read(appContext)
                val installedTargets = WhatsAppPackageDetector.detectWhatsAppApps(
                    appContext,
                    _runtimeSnapshot.value,
                    liveScope
                ).filter { it.isInstalled }.map { it.packageName }

                val refreshedRuntime = XposedRuntimeInspector.inspect(installedTargets)
                val detected = WhatsAppPackageDetector.detectWhatsAppApps(
                    appContext,
                    refreshedRuntime,
                    liveScope
                )
                val scoped = detected.filter { it.isInstalled && it.inLsposedScope }.map { it.packageName }.toSet()

                _scopeSnapshot.value = liveScope
                _runtimeSnapshot.value = refreshedRuntime
                _detectedApps.value = detected
                _scopedPackages.value = scoped
                _refreshEpoch.value = System.currentTimeMillis()
            } finally {
                isRefreshing.set(false)
            }
        }
    }
}

