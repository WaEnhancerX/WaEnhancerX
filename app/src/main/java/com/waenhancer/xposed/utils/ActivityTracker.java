package com.waenhancer.xposed.utils;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.lang.ref.WeakReference;

/**
 * Tracks current top-level Activity and Conversation Activity in WhatsApp.
 */
public final class ActivityTracker implements Application.ActivityLifecycleCallbacks {

    private static volatile ActivityTracker sInstance;
    private static WeakReference<Activity> sCurrentActivity = new WeakReference<>(null);

    private ActivityTracker() {}

    public static synchronized void install(@NonNull Application application) {
        if (sInstance == null) {
            sInstance = new ActivityTracker();
            application.registerActivityLifecycleCallbacks(sInstance);
        }
    }

    @Nullable
    public static Activity getCurrentActivity() {
        return sCurrentActivity.get();
    }

    @Nullable
    public static Activity getCurrentConversation() {
        Activity act = sCurrentActivity.get();
        if (act != null) {
            String name = act.getClass().getSimpleName();
            if (name.equals("Conversation") || name.contains("Conversation")) {
                return act;
            }
        }
        return null;
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
        sCurrentActivity = new WeakReference<>(activity);
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        sCurrentActivity = new WeakReference<>(activity);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        sCurrentActivity = new WeakReference<>(activity);
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (sCurrentActivity.get() == activity) {
            sCurrentActivity = new WeakReference<>(null);
        }
    }
}
