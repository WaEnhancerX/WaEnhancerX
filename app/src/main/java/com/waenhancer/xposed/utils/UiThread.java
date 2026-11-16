package com.waenhancer.xposed.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Shared main-thread helpers for hooks, so each hook does not allocate its own Handler. */
public final class UiThread {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private UiThread() {}

    public static void post(Runnable action) {
        MAIN.post(action);
    }

    public static void toast(Context context, CharSequence message) {
        toast(context, message, Toast.LENGTH_SHORT);
    }

    /** Shows a toast on the main thread. A toast failure must never break the hooked host. */
    public static void toast(Context context, CharSequence message, int duration) {
        MAIN.post(() -> {
            try {
                Toast.makeText(context, message, duration).show();
            } catch (Throwable ignored) {}
        });
    }
}
