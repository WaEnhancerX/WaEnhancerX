package com.waenhancer.xposed;

import android.annotation.SuppressLint;
import android.content.Context;
import com.waenhancer.xposed.bridge.client.PreferenceBridgeClient;
import com.waenhancer.xposed.core.FeatureRegistry;
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

    public static final String PACKAGE_MANAGER = "com.waenhancer";
    public static final String PACKAGE_WPP = "com.whatsapp";
    public static final String PACKAGE_BUSINESS = "com.whatsapp.w4b";
    public static final String RESTART_PERMISSION = "com.waenhancer.permission.RESTART_TARGET";

    private static XSharedPreferences sPrefs;
    // Xposed loads this class separately in each target process.
    private static final AtomicBoolean targetInitialized = new AtomicBoolean(false);
    private String mModulePath;

    @Override
    public void initZygote(StartupParam startupParam) throws Throwable {
        mModulePath = startupParam.modulePath;
    }

    public static synchronized XSharedPreferences getPrefs() {
        if (sPrefs == null) {
            sPrefs = new XSharedPreferences(PACKAGE_MANAGER, "waex_prefs");
            sPrefs.makeWorldReadable();
            sPrefs.reload();
        }
        return sPrefs;
    }

    @SuppressLint("WorldReadableFiles")
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

            XposedBridge.log("[WAEX] Injected into target: " + packageName + " (process: " + lpparam.processName + ")");

            // Instrumentation still runs when a target Application overrides onCreate
            // without calling super. Application.onCreate is a fallback for OEM runtimes.
            boolean instrumentationHooked = false;
            try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Instrumentation", lpparam.classLoader,
                        "callApplicationOnCreate", android.app.Application.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                initializeTarget((Context) param.args[0], packageName, lpparam.classLoader);
                            }
                        });
                instrumentationHooked = true;
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Cannot hook Instrumentation.callApplicationOnCreate: " + t);
            }
            if (!instrumentationHooked) try {
                XposedHelpers.findAndHookMethod(
                        "android.app.Application",
                        lpparam.classLoader,
                        "onCreate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                initializeTarget((Context) param.thisObject, packageName, lpparam.classLoader);
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Error hooking Application.onCreate in target: " + t.getMessage());
            }
        }
    }

    private static void initializeTarget(Context context, String packageName, ClassLoader loader) {
        if (!(context instanceof android.app.Application)
                || !targetInitialized.compareAndSet(false, true)) return;
        android.app.Application application = (android.app.Application) context;

        try {
            com.waenhancer.xposed.utils.ActivityTracker.install(application);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] ActivityTracker startup failed: " + t);
        }
        try {
            com.waenhancer.utils.WhatsAppPackageDetector.registerHookedPackage(application, packageName);
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Hooked-package registration failed: " + t);
        }
        // Without a signature permission, any installed application could kill WhatsApp.
        try {
            android.content.IntentFilter filter = new android.content.IntentFilter("com.waenhancer.WHATSAPP.RESTART");
            android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, android.content.Intent intent) {
                    if (intent != null && packageName.equals(intent.getStringExtra("PKG"))) {
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }
            };
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(receiver, filter, RESTART_PERMISSION,
                        null, Context.RECEIVER_EXPORTED);
            } else {
                application.registerReceiver(receiver, filter, RESTART_PERMISSION, null);
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Restart receiver registration failed: " + t);
        }

        try {
            PreferenceBridgeClient bridgeClient = new PreferenceBridgeClient(application, getPrefs());
            if (com.waenhancer.xposed.core.VersionGuard.verifyAndGuard(application, loader, bridgeClient)) {
                new FeatureRegistry(application, loader, bridgeClient).initializeAll();
            } else {
                XposedBridge.log("[WAEX] Hooks paused: WhatsApp version not verified.");
            }
        } catch (Throwable t) {
            XposedBridge.log("[WAEX] Error initializing FeatureRegistry: " + t);
        }
    }
}
