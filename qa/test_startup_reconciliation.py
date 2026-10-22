#!/usr/bin/env python3
"""Source-only startup contract checks after reconciliation with runtime-health reporting.

Does not compile Android nor prove LSPosed lifecycle behavior.
"""
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/com/waenhancer/xposed'
main=(SRC/'MainHook.java').read_text()
registry=(SRC/'core/FeatureRegistry.java').read_text()
guard=(SRC/'core/VersionGuard.kt').read_text()
bridge=(SRC/'bridge/client/PreferenceBridgeClient.java').read_text()
detector=(ROOT/'app/src/main/java/com/waenhancer/utils/WhatsAppPackageDetector.kt').read_text()
C={
 'one per-process initializer': main.count('TARGET_INITIALIZED.compareAndSet(false, true)')==1,
 'onCreate fallback': '"onCreate", applicationInitializer' in main,
 'Instrumentation fallback': '"callApplicationOnCreate", android.app.Application.class,' in main,
 'one shared callback': main.count('applicationInitializer')==3,
 'valid Application parameter': 'param.args[0]' in main and 'app instanceof android.app.Application' in main,
 'disk pref read isolated': 'Disk preference snapshot unavailable:' in main and 'XSharedPreferences candidate =' in main,
 'disk pref snapshot nullable': 'if (diskPrefs == null) return null;' in bridge,
 'early bridge optional': 'Early preference bridge unavailable:' in main and 'preloadedPrefs, true' in main,
 'initial provider preference sync': 'resolvedBridge.awaitInitialSync();' in main,
 'activity lifecycle tracking': 'ActivityTracker.install(' in main and 'ActivityTracker initialization failed:' in main,
 'hooked package report': 'WhatsAppPackageDetector.registerHookedPackage(' in main and 'Hooked-package reporting failed:' in main,
 'runtime health INITIALIZING': '"INITIALIZING"' in main,
 'runtime health injected': '"INJECTED", "PENDING"' in main,
 'runtime health READY': '"READY_WITH_ERRORS" : "READY"' in main,
 'runtime health unsupported': 'VersionGuard.runtimeStatus()' in main and '"UNSUPPORTED_VERSION"' in guard and '"COMPATIBILITY_UNAVAILABLE"' in guard,
 'runtime health failed': '"INITIALIZATION_FAILED"' in main,
 'runtime heartbeat': 'HEARTBEAT_STARTED.compareAndSet(false, true)' in main and 'HEARTBEAT_INTERVAL_MS' in main,
 'runtime report kept': 'WhatsAppPackageDetector.reportRuntimeHealth(' in main and 'fun reportRuntimeHealth(' in detector,
 'active framework recorder': 'active_xposed_framework' in main and 'detectActiveFramework()' in main,
 'signed restart': 'appContext.registerReceiver(receiver, filter, RESTART_PERMISSION' in main,
 'exact restart match': 'packageName.equals(pkg)' in main,
 'live preferences': 'com.waenhancer.action.PREFERENCES_CHANGED' in main and 'bridgeClient.requestRefresh();' in main,
 'restart prompt isolated': 'RestartPromptController.initialize(bridgeClient);' in main and 'Restart prompt setup failed:' in main,
 'update sheet isolated': 'WhatsAppUpdateSheet.init(bridgeClient);' in main and 'WhatsApp update-sheet setup failed:' in main,
 'injection toast isolated': 'showInjectionToastOnFirstVisibleActivity();' in main and 'Injection toast unavailable:' in main,
 'visible-activity toast retained': 'public void onActivityResumed(android.app.Activity activity)' in main and '"WAEX hooks injected"' in main,
 'toast listener removed': 'ActivityTracker.removeListener(this);' in main,
 'toast per-process bound': 'INJECTION_TOAST_SHOWN.compareAndSet(false, true)' in main,
 'typed styles registry retained': all(name in registry for name in ('CustomDpiHook.class', 'ChatFilterDisplayHook.class', 'HideHomeTabsHook.class', 'CssThemeHook.class')),
 'registry installed': 'FeatureRegistry registry =' in main and 'registry.initializeAll();' in main,
 'registry health reporting': 'registry.hasFailures()' in main and 'registry.reportInjectedHooks(appContext, packageName)' in main,
 'failure diagnostics': 'showInjectionErrorSheet(t, packageName, lpparam.processName)' in main,
 'version is verified': 'VersionGuard.verifyAndGuard(' in main and 'UniversalVersionValidator.evaluate(appContext, currentVersion, prefs)' in guard and 'if (decision.allowed)' in guard,
 'version failure is fail-closed': 'return false // Fail closed:' in guard,
 'version dialog can retry': 'dialogShown.set(false)' in guard and 'A failed UI attempt must not permanently suppress future warnings' in guard,
 'factory based registry': 'private interface FeatureFactory' in registry,
 'new feature registration': 'MessageDeviceSourceHook.class' in registry and 'FilterItemsHook.class' in registry,
 'feature failure isolation': 'entry.feature = entry.factory.create();' in registry and 'entry.feature.hook();' in registry and 'entry.status = "FAILED";' in registry,
 'error sheet preserved': 'Copy error' in main and 'WaexBottomSheet(' in main,
}
for label, ok in C.items():
 assert ok, f'Startup regression: {label}'
for operation, logline in [
 ('ActivityTracker.install(', 'ActivityTracker initialization failed:'),
 ('WhatsAppPackageDetector.registerHookedPackage(', 'Hooked-package reporting failed:'),
 ('RestartPromptController.initialize(', 'Restart prompt setup failed:'),
 ('WhatsAppUpdateSheet.init(', 'WhatsApp update-sheet setup failed:'),
 ('showInjectionToastOnFirstVisibleActivity();', 'Injection toast unavailable:'),
]:
 assert 0 < main.index(logline)-main.index(operation) < 1100, f'No local catch for {operation}'
print(f'PASS: {len(C)} current startup, health, diagnostics and version guard invariants')
