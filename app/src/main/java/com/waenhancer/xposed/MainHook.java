package com.waenhancer.xposed;

import android.annotation.SuppressLint;
import android.content.ContextWrapper;
import com.waenhancer.xposed.utils.ModuleStatus;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Main Xposed Entry point for Wa Enhancer X.
 * Handles scope detection, self-hook sentinel verification, and target hooking.
 */
public class MainHook implements IXposedHookLoadPackage, IXposedHookZygoteInit {

    public static final String PACKAGE_MANAGER = "com.waenhancer";
    public static final String PACKAGE_WPP = "com.whatsapp";
    public static final String PACKAGE_BUSINESS = "com.whatsapp.w4b";

    private static XSharedPreferences sPrefs;
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

        // 2. Target Hooking: WhatsApp & WhatsApp Business
        boolean isWhatsApp = packageName.equals(PACKAGE_WPP);
        boolean isBusiness = packageName.equals(PACKAGE_BUSINESS);

        if (isWhatsApp || isBusiness) {
            XposedBridge.log("[WAEX] Injected into target: " + packageName + " (process: " + lpparam.processName + ")");

            // Initial basic hooks to verify runtime execution
            try {
                // Hook Application.onCreate to log activation inside target
                XposedHelpers.findAndHookMethod(
                        "android.app.Application",
                        lpparam.classLoader,
                        "onCreate",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                XposedBridge.log("[WAEX] WhatsApp Application.onCreate hooked successfully!");
                            }
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log("[WAEX] Error hooking Application.onCreate in target: " + t.getMessage());
            }
        }
    }
}
