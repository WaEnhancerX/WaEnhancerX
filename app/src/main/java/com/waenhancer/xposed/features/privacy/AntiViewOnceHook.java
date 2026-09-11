package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * View Once Bypass: converts incoming view-once photos/videos/voice-notes into normal permanent media.
 * Uses WaEnhancer's proven multi-method unobfuscation strategy:
 *   1. Finds method containing SQL "INSERT_VIEW_ONCE_SQL" or "view_once"
 *   2. Hooks all implementations of the ViewOnce state setter interface (param int, return void)
 *   3. Forces view_once state from 1 (View Once) to 0 (Standard media)
 */
public class AntiViewOnceHook extends BaseFeature {

    private static final String TAG = "[WAEX][AntiViewOnce]";

    public AntiViewOnceHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookViewOnceSetters();
    }

    private void hookViewOnceSetters() {
        try {
            DexSearchEngine.getInstance().initialize(context);
            var bridge = DexSearchEngine.getInstance().getBridge();
            if (bridge == null) {
                XposedBridge.log(TAG + " DexKitBridge is null, skipping AntiViewOnce search");
                return;
            }

            List<Method> targets = new ArrayList<>();

            // Strategy 1 (Proven WaEnhancer): Search for INSERT_VIEW_ONCE_SQL caller and find invoked interface
            try {
                MethodDataList sqlMethods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().addUsingString("INSERT_VIEW_ONCE_SQL", StringMatchType.Contains)));

                if (!sqlMethods.isEmpty()) {
                    MethodData caller = sqlMethods.get(0);
                    for (MethodData invoke : caller.getInvokes()) {
                        try {
                            Method m = invoke.getMethodInstance(classLoader);
                            if (m.getDeclaringClass().isInterface() && m.getDeclaringClass().getMethods().length <= 3) {
                                ClassDataList implementors = bridge.findClass(FindClass.create()
                                        .matcher(ClassMatcher.create().addInterface(m.getDeclaringClass().getName())));
                                for (ClassData cd : implementors) {
                                    Class<?> cls = cd.getInstance(classLoader);
                                    for (Method m2 : cls.getDeclaredMethods()) {
                                        if (m2.getParameterCount() == 1
                                                && m2.getParameterTypes()[0] == int.class
                                                && m2.getReturnType() == void.class) {
                                            targets.add(m2);
                                        }
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Strategy 1 search failed: " + t.getMessage());
            }

            // Strategy 2: Direct search for methods with "view_once" string taking an int
            if (targets.isEmpty()) {
                try {
                    MethodDataList directMethods = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .modifiers(Modifier.PUBLIC)
                                    .paramTypes(int.class)
                                    .returnType(void.class)
                                    .usingStrings("view_once")));
                    for (MethodData md : directMethods) {
                        try {
                            Method m = md.getMethodInstance(classLoader);
                            if (m != null) targets.add(m);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " Strategy 2 search failed: " + t.getMessage());
                }
            }

            // Hook all discovered setter methods
            for (Method target : targets) {
                try {
                    XposedBridge.hookMethod(target, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!isEnabled("anti_view_once", false) && !isEnabled("viewonce", false)) return;
                            if (param.args != null && param.args.length > 0 && param.args[0] instanceof Integer) {
                                int state = (int) param.args[0];
                                if (state == 1) {
                                    param.args[0] = 0;
                                }
                            }
                        }
                    });
                    XposedBridge.log(TAG + " Successfully hooked ViewOnce setter: " + target.getDeclaringClass().getName() + "->" + target.getName());
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " Error hooking method " + target.getName() + ": " + t.getMessage());
                }
            }

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error during hookViewOnceSetters: " + t.getMessage());
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Anti-View Once";
    }
}
