package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Changes incoming view-once state 1 to ordinary media state 0 when enabled.
 * A SQL-linked state interface or the direct view_once setter must belong to a
 * verified message type. Outgoing media and unknown identities are preserved.
 */
public class AntiViewOnceHook extends BaseFeature {
    private static final String TAG = "[WAEX][AntiViewOnce]";
    private final Set<Method> installed = new LinkedHashSet<>();

    public AntiViewOnceHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public synchronized void hook() throws Throwable {
        installStateHooks();
        if (installed.isEmpty() && (isEnabled("anti_view_once", false) || isEnabled("viewonce", false))) {
            throw new IllegalStateException("View-once contract unavailable");
        }
    }

    private void installStateHooks() {
        try {
            DexSearchEngine.getInstance().initialize(context);
            DexKitBridge bridge = DexSearchEngine.getInstance().getBridge();
            if (bridge == null) return;
            ViewOnceMessageContract messages = ViewOnceMessageContract.resolve(bridge, classLoader);
            if (messages == null) {
                XposedBridge.log(TAG + " Message ownership contract unavailable; state setters not installed");
                return;
            }
            Set<Method> targets = sqlSetters(bridge, messages);
            if (targets.isEmpty()) addDirectSetters(bridge, messages, targets);
            for (Method target : targets) {
                if (installed.contains(target)) continue;
                try {
                    XposedBridge.hookMethod(target, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            // Keep preference reads live even if the feature was OFF at installation.
                            if (!isEnabled("anti_view_once", false) && !isEnabled("viewonce", false)) return;
                            if (param.args == null || param.args.length != 1
                                    || !(param.args[0] instanceof Integer) || (Integer) param.args[0] != 1) return;
                            if (messages.isIncoming(param.thisObject)) param.args[0] = 0;
                        }
                    });
                    installed.add(target);
                } catch (RuntimeException | LinkageError failure) {
                    XposedBridge.log(TAG + " Setter installation failed: " + failure.getClass().getSimpleName());
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " State contract unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private Set<Method> sqlSetters(DexKitBridge bridge, ViewOnceMessageContract messages) {
        Set<Method> result = new LinkedHashSet<>();
        Set<Method> stateContracts = new LinkedHashSet<>();
        try {
            MethodDataList callers = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .addUsingString("INSERT_VIEW_ONCE_SQL", StringMatchType.Contains)));
            for (MethodData caller : callers) {
                Set<Method> candidates = new LinkedHashSet<>();
                for (MethodData invoke : caller.getInvokes()) {
                    try {
                        if (!invoke.isMethod()) continue;
                        Method setter = ViewOnceMessageContract.interfaceSetter(invoke.getMethodInstance(classLoader));
                        if (setter != null) candidates.add(setter);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                        // This individual invoked member may be absent in this host build.
                    }
                }
                if (candidates.size() == 1) stateContracts.add(candidates.iterator().next());
            }
            for (Method contract : stateContracts) {
                for (ClassData data : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                        .addInterface(contract.getDeclaringClass().getName())))) {
                    try {
                        Class<?> type = data.getInstance(classLoader);
                        if (!messages.isMessageType(type)) continue;
                        // Resolve this exact interface member, including inherited implementations.
                        Method target = type.getMethod(contract.getName(), int.class);
                        if (messages.accepts(target)) result.add(target);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                        // Reject one unsupported implementation without losing other media classes.
                    }
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " SQL state discovery failed: " + failure.getClass().getSimpleName());
        }
        return result;
    }

    private void addDirectSetters(DexKitBridge bridge, ViewOnceMessageContract messages, Set<Method> targets) {
        try {
            Map<Class<?>, Set<Method>> byMessageType = new LinkedHashMap<>();
            MethodDataList direct = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .modifiers(Modifier.PUBLIC).paramTypes(int.class).returnType(void.class)
                    .addUsingString("view_once", StringMatchType.Equals)));
            for (MethodData data : direct) {
                try {
                    if (!data.isMethod()) continue;
                    Method method = data.getMethodInstance(classLoader);
                    if (messages.accepts(method)) byMessageType.computeIfAbsent(method.getDeclaringClass(),
                            ignored -> new LinkedHashSet<>()).add(method);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                    // A direct match still has to satisfy the same message ownership contract.
                }
            }
            for (Set<Method> candidates : byMessageType.values()) {
                if (candidates.size() == 1) targets.add(candidates.iterator().next());
            }
        } catch (RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " Direct state discovery failed: " + failure.getClass().getSimpleName());
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Anti-View Once";
    }
}
