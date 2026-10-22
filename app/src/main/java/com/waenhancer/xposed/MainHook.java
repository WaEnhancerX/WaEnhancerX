package com.waenhancer.xposed;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient;
import com.waenhancer.xposed.core.FeatureRegistry;
import com.waenhancer.xposed.core.RuntimeReportDispatcher;
import com.waenhancer.xposed.core.RuntimeReportDispatcher.Kind;
import com.waenhancer.xposed.utils.ModuleStatus;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import java.util.concurrent.atomic.AtomicBoolean;



/**
 * Main Xposed Entry point for Wa Enhancer X.
 * Handles scope detection, self-hook sentinel verification, and target hooking.
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    public static final String RESTART_PERMISSION = "com.waenhancer.permission.RESTART_TARGET";

    public static final String PACKAGE_MANAGER = "com.waenhancer";
    public static final String PACKAGE_WPP = "com.whatsapp";
    public static final String PACKAGE_BUSINESS = "com.whatsapp.w4b";

    private static XSharedPreferences sPrefs;
    private static volatile PreferenceBridgeClient sPreferenceBridge;
    private static final AtomicBoolean TARGET_INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean HEARTBEAT_STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean INJECTION_TOAST_SHOWN = new AtomicBoolean(false);
    private static final long HEARTBEAT_INTERVAL_MS = 10_000L;
    private static volatile RuntimeState sRuntimeState = new RuntimeState("INJECTED", "PENDING", null);
    private static final RuntimeReportDispatcher RUNTIME_REPORTS = new RuntimeReportDispatcher(
            failure -> XposedBridge.log("[WAEX] Runtime report delivery failed: "
                    + failure.getClass().getSimpleName()));

    private static final class RuntimeState {
        final String status;
        final String bridge;
        final String error;
        RuntimeState(String status, String bridge, String error) {
            this.status = status;
            this.bridge = bridge;
            this.error = error;
        }
    }
    private String mModulePath;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        mModulePath = startupParam.modulePath;
    }

    public static synchronized XSharedPreferences getPrefs() {
        if (sPrefs == null) {
            try {
                XSharedPreferences candidate = new XSharedPreferences(PACKAGE_MANAGER, "waex_prefs");
                candidate.reload();
                sPrefs = candidate;
            } catch (Throwable t) {
                // Provider-backed preferences can operate without the optional disk snapshot.
                XposedBridge.log("[WAEX] Disk preference snapshot unavailable: "
                        + t.getClass().getSimpleName());
            }
        }
        return sPrefs;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String packageName = lpparam.packageName;
        if (packageName == null) return;

        // 1. Self Hook: verify module active inside WaEnhancerX UI
        if (packageName.equals(PACKAGE_MANAGER)) {
            try {
                XposedHelpers.findAndHookMethod(
                        ModuleStatus.class.getName(),
                        lpparam.classLoader,
                        "isModuleActive",
                        XC_MethodReplacement.returnConstant(true)
                );
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Failed to hook ModuleStatus sentinel: " + t.getMessage());
            }

            // Persist the API supplied by the active framework for the manager diagnostics UI.
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Application",
                        lpparam.classLoader,
                        "onCreate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    Context context = (Context) param.thisObject;
                                    int apiVersion = XposedBridge.getXposedVersion();
                                    com.waenhancer.config.PreferenceStores.publicStore(context)
                                            .edit()
                                            .putInt("active_xposed_api_version", apiVersion)
                                            .putString("active_xposed_framework", detectActiveFramework())
                                            .commit();
                                } catch (Throwable t) {
                                    XposedBridge.log("[WAEX] Failed to save Xposed API version: " + t.getMessage());
                                }
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Failed to register Xposed API recorder: " + t.getMessage());
            }

            // Bypass Android 7+ MODE_WORLD_READABLE check
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.ContextImpl",
                        lpparam.classLoader,
                        "checkMode",
                        int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                param.setResult(null);
                            }
                        }
                );
            } catch (Throwable ignored) {}
            return;
        }

        // 2. Target Hooking: WhatsApp, WA Business & Clones / Modded variants
        boolean isWhatsAppTarget = com.waenhancer.utils.WhatsAppPackageDetector.isWhatsAppPackageName(packageName);

        if (isWhatsAppTarget) {
            // Only hook the main package process (e.g. "com.whatsapp", not isolated or secondary background workers)
            if (lpparam.processName != null && !lpparam.processName.equals(packageName)) {
                return;
            }

            // Read the world-readable snapshot before Application startup, then begin the
            // authoritative provider fetch as soon as a Context becomes available. This overlaps
            // preference IPC with the target's attach -> onCreate work without using stale
            // entitlement/config values when hooks are installed.
            final XSharedPreferences preloadedPrefs = getPrefs();
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Application",
                        lpparam.classLoader,
                        "attach",
                        Context.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Context context = (Context) param.thisObject;
                                try {
                                    if (sPreferenceBridge == null) {
                                        synchronized (MainHook.class) {
                                            if (sPreferenceBridge == null) {
                                                sPreferenceBridge = new PreferenceBridgeClient(
                                                        context, preloadedPrefs, true);
                                            }
                                        }
                                    }
                                } catch (Throwable t) {
                                    // Application.onCreate can still build a provider-backed bridge.
                                    XposedBridge.log("[WAEX] Early preference bridge unavailable: "
                                            + t.getClass().getSimpleName());
                                }
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Failed to start early preference preload: "
                        + t.getMessage());
            }

            // Reuse an initializer from the Application and Instrumentation entry points.
            // Some WhatsApp/clone Application subclasses bypass superclass onCreate().
            XC_MethodHook applicationInitializer = new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Object app = param.thisObject instanceof android.app.Application
                                        ? param.thisObject
                                        : (param.args != null && param.args.length == 1 ? param.args[0] : null);
                                if (!(app instanceof android.app.Application)) return;
                                Context appContext = (Context) app;
                                if (!TARGET_INITIALIZED.compareAndSet(false, true)) {
                                    return;
                                }
                                // Optional observers and reports may fail without disabling hooks.
                                try {
                                    com.waenhancer.xposed.utils.ActivityTracker.install(
                                            (android.app.Application) appContext);
                                } catch (Throwable t) {
                                    XposedBridge.log("[WAEX] ActivityTracker initialization failed: "
                                            + t.getClass().getSimpleName());
                                }

                                RUNTIME_REPORTS.submitLatest(Kind.PACKAGE, () -> {
                                    try {
                                        com.waenhancer.utils.WhatsAppPackageDetector.registerHookedPackage(
                                                appContext, packageName);
                                    } catch (Throwable t) {
                                        XposedBridge.log("[WAEX] Hooked-package reporting failed: "
                                                + t.getClass().getSimpleName());
                                    }
                                });
                                reportTargetRuntime(
                                        appContext, packageName, "INJECTED", "PENDING", null);

                                try {
                                    android.content.IntentFilter filter = new android.content.IntentFilter("com.waenhancer.WHATSAPP.RESTART");
                                    android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                                        @Override
                                        public void onReceive(Context context, android.content.Intent intent) {
                                            String pkg = intent.getStringExtra("PKG");
                                            if (packageName.equals(pkg)) {
                                                android.os.Process.killProcess(android.os.Process.myPid());
                                            }
                                        }
                                    };
                                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                                        appContext.registerReceiver(receiver, filter, RESTART_PERMISSION,
                                                null, Context.RECEIVER_EXPORTED);
                                    } else {
                                        appContext.registerReceiver(receiver, filter, RESTART_PERMISSION, null);
                                    }
                                } catch (Throwable ignored) {}

                                boolean showHookToast = true;
                                try {
                                    PreferenceBridgeClient resolvedBridge = sPreferenceBridge;
                                    if (resolvedBridge == null) {
                                        resolvedBridge = new PreferenceBridgeClient(appContext, preloadedPrefs);
                                        sPreferenceBridge = resolvedBridge;
                                    } else {
                                        resolvedBridge.awaitInitialSync();
                                    }
                                    final PreferenceBridgeClient bridgeClient = resolvedBridge;
                                    com.waenhancer.utils.UiText.initialize(appContext, bridgeClient);
                                    reportTargetRuntime(
                                            appContext,
                                            packageName,
                                            "INITIALIZING",
                                            bridgeClient.getLastSyncSource(),
                                            bridgeClient.getLastSyncError());
                                    try {
                                        com.waenhancer.xposed.core.RestartPromptController.initialize(bridgeClient);
                                    } catch (Throwable t) {
                                        XposedBridge.log("[WAEX] Restart prompt setup failed: "
                                                + t.getClass().getSimpleName());
                                    }
                                    try {
                                        android.content.IntentFilter prefsFilter = new android.content.IntentFilter(
                                                "com.waenhancer.action.PREFERENCES_CHANGED");
                                        android.content.BroadcastReceiver prefsReceiver = new android.content.BroadcastReceiver() {
                                            @Override
                                            public void onReceive(Context context, android.content.Intent intent) {
                                                bridgeClient.requestRefresh();
                                            }
                                        };
                                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                                            appContext.registerReceiver(prefsReceiver, prefsFilter, RESTART_PERMISSION,
                                                    null, Context.RECEIVER_EXPORTED);
                                        } else {
                                            appContext.registerReceiver(prefsReceiver, prefsFilter,
                                                    RESTART_PERMISSION, null);
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("[WAEX] Failed to register preference refresh receiver: "
                                                + t.getMessage());
                                    }
                                    showHookToast = bridgeClient.getBoolean("show_hook_toast", true);

                                    // Verify universal version compatibility before loading hooks
                                    boolean isSupported = com.waenhancer.xposed.core.VersionGuard.verifyAndGuard(
                                            appContext,
                                            lpparam.classLoader,
                                            bridgeClient
                                    );

                                    if (isSupported) {
                                        try {
                                            com.waenhancer.xposed.features.updates.WhatsAppUpdateSheet.init(bridgeClient);
                                        } catch (Throwable t) {
                                            XposedBridge.log("[WAEX] WhatsApp update-sheet setup failed: "
                                                    + t.getClass().getSimpleName());
                                        }
                                        if (showHookToast) {
                                            try {
                                                showInjectionToastOnFirstVisibleActivity();
                                            } catch (Throwable t) {
                                                XposedBridge.log("[WAEX] Injection toast unavailable: "
                                                        + t.getClass().getSimpleName());
                                            }
                                        }
                                        FeatureRegistry registry = new FeatureRegistry(appContext, lpparam.classLoader, bridgeClient);
                                        registry.initializeAll();
                                        RUNTIME_REPORTS.submitLatest(Kind.HOOKS,
                                                () -> registry.reportInjectedHooks(appContext, packageName));
                                        reportTargetRuntime(
                                                appContext,
                                                packageName,
                                                registry.hasFailures() ? "READY_WITH_ERRORS" : "READY",
                                                bridgeClient.getLastSyncSource(),
                                                bridgeClient.getLastSyncError());
                                    } else {
                                        reportTargetRuntime(
                                                appContext,
                                                packageName,
                                                com.waenhancer.xposed.core.VersionGuard.runtimeStatus(),
                                                bridgeClient.getLastSyncSource(),
                                                null);
                                    }
                                } catch (Throwable t) {
                                    PreferenceBridgeClient bridge = sPreferenceBridge;
                                    String bridgeStatus = bridge == null ? "FAILED" : bridge.getLastSyncSource();
                                    String detail = t.getClass().getSimpleName() + ": "
                                            + (t.getMessage() == null ? "No detail" : t.getMessage());
                                    reportTargetRuntime(
                                            appContext,
                                            packageName,
                                            "INITIALIZATION_FAILED",
                                            bridgeStatus,
                                            detail);
                                    XposedBridge.log("[WAEX] Error initializing FeatureRegistry: " + t.getMessage());
                                    showInjectionErrorSheet(t, packageName, lpparam.processName);
                                }
                                RUNTIME_REPORTS.submitLatest(Kind.UPDATE_CHECK, () -> {
                                    try {
                                        appContext.getContentResolver().call(
                                                android.net.Uri.parse("content://com.waenhancer.hookprovider"),
                                                "request_update_check", packageName, null);
                                    } catch (Throwable ignored) {}
                                });
                            }
                        };

            // Each lifecycle entry point may independently fail to register.
            // TARGET_INITIALIZED guarantees initialization occurs at most once.
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Application", lpparam.classLoader,
                        "onCreate", applicationInitializer);
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Error hooking Application.onCreate in target: "
                        + t.getClass().getSimpleName());
            }
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Instrumentation", lpparam.classLoader,
                        "callApplicationOnCreate", android.app.Application.class,
                        applicationInitializer);
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Cannot hook Instrumentation.callApplicationOnCreate: "
                        + t.getClass().getSimpleName());
            }
        }
    }

    /**
     * A target process may be created for background work without the user opening WhatsApp.
     * Only acknowledge injection when its first Activity becomes visible, and only once for the
     * lifetime of that process. A force-stop/process death resets these in-memory guards.
     */
    private static void showInjectionToastOnFirstVisibleActivity() {
        com.waenhancer.xposed.utils.ActivityTracker.ActivityListener listener =
                new com.waenhancer.xposed.utils.ActivityTracker.ActivityListener() {
                    @Override
                    public void onActivityResumed(android.app.Activity activity) {
                        com.waenhancer.xposed.utils.ActivityTracker.removeListener(this);
                        if (!INJECTION_TOAST_SHOWN.compareAndSet(false, true)) return;
                        activity.runOnUiThread(() -> android.widget.Toast.makeText(
                                activity,
                                UiText.get(R.string.waex_ui_waex_hooks_injected_0b677cb2, "WAEX hooks injected"),
                                android.widget.Toast.LENGTH_SHORT
                        ).show());
                    }
                };
        com.waenhancer.xposed.utils.ActivityTracker.addListener(listener);
    }

    private static void reportTargetRuntime(Context context, String packageName,
                                            String runtimeStatus, String bridgeStatus,
                                            String error) {
        Context appContext = context.getApplicationContext() != null
                ? context.getApplicationContext() : context;
        RuntimeState state = new RuntimeState(runtimeStatus, bridgeStatus, error);
        sRuntimeState = state;
        enqueueRuntimeReport(appContext, packageName, state);

        if (HEARTBEAT_STARTED.compareAndSet(false, true)) {
            Handler handler = new Handler(Looper.getMainLooper());
            Runnable heartbeat = new Runnable() {
                @Override
                public void run() {
                    enqueueRuntimeReport(appContext, packageName, sRuntimeState);
                    handler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
                }
            };
            handler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS);
        }
    }

    private static void enqueueRuntimeReport(Context context, String packageName, RuntimeState state) {
        long observedAtMillis = System.currentTimeMillis();
        RUNTIME_REPORTS.submitLatest(Kind.HEALTH,
                () -> com.waenhancer.utils.WhatsAppPackageDetector.reportRuntimeHealth(
                        context, packageName, state.status, state.bridge, state.error, observedAtMillis));
    }

    /**
     * Identifies the framework that actually loaded this module. Installed manager packages are
     * deliberately not consulted here: more than one manager may remain installed while only one
     * framework owns the current hooked process.
     */
    private static String detectActiveFramework() {
        ClassLoader moduleLoader = MainHook.class.getClassLoader();
        ClassLoader bridgeLoader = XposedBridge.class.getClassLoader();

        if (classLoaderChainContains(moduleLoader, "matrix.vector")
                || classLoaderChainContains(moduleLoader, "VectorModuleClassLoader")
                || canLoad("org.matrix.vector.impl.di.VectorBootstrap", bridgeLoader)
                || canLoad("org.matrix.vector.impl.utils.VectorModuleClassLoader", bridgeLoader)) {
            return "Vector";
        }

        if (classLoaderChainContains(moduleLoader, "lsposed")
                || classLoaderChainContains(moduleLoader, "LspModuleClassLoader")
                || canLoad("org.lsposed.lspd.impl.LSPosedBridge", bridgeLoader)
                || canLoad("org.lsposed.lspd.core.Startup", bridgeLoader)) {
            return "LSPosed";
        }

        if (canLoad("com.elderdrivers.riru.edxp.core.Main", bridgeLoader)) {
            return "EdXposed";
        }

        return "Xposed-compatible framework";
    }

    private static boolean classLoaderChainContains(ClassLoader loader, String marker) {
        String expected = marker.toLowerCase(java.util.Locale.ROOT);
        for (ClassLoader current = loader; current != null; current = current.getParent()) {
            String identity = (current.getClass().getName() + " " + current)
                    .toLowerCase(java.util.Locale.ROOT);
            if (identity.contains(expected)) return true;
        }
        return false;
    }

    private static boolean canLoad(String className, ClassLoader loader) {
        if (loader == null) return false;
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void showInjectionErrorSheet(Throwable throwable, String packageName, String processName) {
        String error = throwable.getMessage() != null
                ? throwable.getMessage()
                : throwable.getClass().getSimpleName();

        java.io.StringWriter stackTrace = new java.io.StringWriter();
        throwable.printStackTrace(new java.io.PrintWriter(stackTrace));
        String diagnostic = "WaEnhancer X hook injection failed\n"
                + "Package: " + packageName + "\n"
                + "Process: " + processName + "\n\n"
                + stackTrace;

        android.app.Activity currentActivity =
                com.waenhancer.xposed.utils.ActivityTracker.getCurrentActivity();
        if (currentActivity != null) {
            showInjectionErrorSheet(currentActivity, error, diagnostic);
            return;
        }

        com.waenhancer.xposed.utils.ActivityTracker.addListener(
                new com.waenhancer.xposed.utils.ActivityTracker.ActivityListener() {
                    @Override
                    public void onActivityResumed(android.app.Activity activity) {
                        com.waenhancer.xposed.utils.ActivityTracker.removeListener(this);
                        showInjectionErrorSheet(activity, error, diagnostic);
                    }
                }
        );
    }

    private static void showInjectionErrorSheet(
            android.app.Activity activity,
            String error,
            String diagnostic
    ) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed()) return;

            new com.waenhancer.xposed.core.components.WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle(UiText.get(R.string.waex_ui_waenhancer_x_injection_failed_8387f7f8, "WaEnhancer X injection failed"))
                    .setMessage(
                            UiText.get(R.string.waex_ui_waenhancer_x_could_not_finish_loading_b154030c, "WaEnhancer X could not finish loading its hooks.\n\n")
                                    + error
                                    + UiText.get(R.string.waex_ui_copy_the_error_details_when_reporting_8ec1cba5, "\n\nCopy the error details when reporting this issue.")
                    )
                    .setPositiveButton(UiText.get(R.string.waex_ui_copy_error_19dc540f, "Copy error"), (dialog, which) -> {
                        android.content.ClipboardManager clipboard =
                                (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(
                                    android.content.ClipData.newPlainText(UiText.get(R.string.waex_ui_waenhancer_x_injection_error_87f8b907, "WaEnhancer X injection error"), diagnostic)
                            );
                        }
                    })
                    .setNegativeButton(UiText.get(R.string.waex_ui_close_7d9eb7ac, "Close"), (dialog, which) -> dialog.dismiss())
                    .show();
        });
    }
}
