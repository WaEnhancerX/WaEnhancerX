package com.waenhancer.xposed.core;

import android.view.View;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** One process-wide hook per hot View method, dispatching only to active feature owners. */
public final class ViewHookDispatcher {
    public interface ViewListener { void onView(View view); }
    public interface SetFlagsFilter { int filter(View view, int flags, int mask); }

    private static final ConcurrentHashMap<Object, CopyOnWriteArrayList<ViewListener>> ATTACH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, CopyOnWriteArrayList<ViewListener>> DETACH = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, CopyOnWriteArrayList<SetFlagsFilter>> FLAGS = new ConcurrentHashMap<>();
    private static volatile boolean attachHooked;
    private static volatile boolean detachHooked;
    private static volatile boolean flagsHooked;

    private ViewHookDispatcher() {}

    public static void onAttached(Object owner, ViewListener listener) {
        ATTACH.computeIfAbsent(owner, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        ensureAttachHook();
    }

    public static void onDetached(Object owner, ViewListener listener) {
        DETACH.computeIfAbsent(owner, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        ensureDetachHook();
    }

    public static void onSetFlags(Object owner, SetFlagsFilter filter) {
        FLAGS.computeIfAbsent(owner, ignored -> new CopyOnWriteArrayList<>()).add(filter);
        ensureFlagsHook();
    }

    public static void unregister(Object owner) {
        ATTACH.remove(owner);
        DETACH.remove(owner);
        FLAGS.remove(owner);
    }

    private static synchronized void ensureAttachHook() {
        if (attachHooked) return;
        XposedBridge.hookAllMethods(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                dispatch(ATTACH, (View) param.thisObject);
            }
        });
        attachHooked = true;
    }

    private static synchronized void ensureDetachHook() {
        if (detachHooked) return;
        XposedBridge.hookAllMethods(View.class, "onDetachedFromWindow", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                dispatch(DETACH, (View) param.thisObject);
            }
        });
        detachHooked = true;
    }

    private static synchronized void ensureFlagsHook() {
        if (flagsHooked) return;
        XposedHelpers.findAndHookMethod(View.class, "setFlags", int.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                int flags = (Integer) param.args[0];
                int mask = (Integer) param.args[1];
                for (CopyOnWriteArrayList<SetFlagsFilter> listeners : FLAGS.values()) {
                    for (SetFlagsFilter listener : listeners) {
                        try { flags = listener.filter(view, flags, mask); } catch (Throwable ignored) {}
                    }
                }
                param.args[0] = flags;
            }
        });
        flagsHooked = true;
    }

    private static void dispatch(
            ConcurrentHashMap<Object, CopyOnWriteArrayList<ViewListener>> listeners, View view) {
        for (CopyOnWriteArrayList<ViewListener> owned : listeners.values()) {
            for (ViewListener listener : owned) {
                try { listener.onView(view); } catch (Throwable ignored) {}
            }
        }
    }
}
