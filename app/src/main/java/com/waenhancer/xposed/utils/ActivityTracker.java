package com.waenhancer.xposed.utils;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.VersionGuard;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.waenhancer.xposed.core.RestartPromptController;
import com.waenhancer.xposed.features.updates.WhatsAppUpdateSheet;

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
        for (ActivityListener listener : sListeners) {
            try {
                listener.onActivityCreated(activity);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        sCurrentActivity = new WeakReference<>(activity);
    }

    public interface ActivityListener {
        default void onActivityCreated(@NonNull Activity activity) {}

        void onActivityResumed(@NonNull Activity activity);

        default void onActivityStopped(@NonNull Activity activity) {}

        default void onActivityDestroyed(@NonNull Activity activity) {}
    }

    private static final List<ActivityListener> sListeners = new CopyOnWriteArrayList<>();

    public static void addListener(@NonNull ActivityListener listener) {
        if (!sListeners.contains(listener)) {
            sListeners.add(listener);
        }
    }

    public static void removeListener(@NonNull ActivityListener listener) {
        sListeners.remove(listener);
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        sCurrentActivity = new WeakReference<>(activity);
        try {
            VersionGuard.onTargetActivityResumed(activity);
        } catch (Throwable ignored) {}
        try {
            WhatsAppUpdateSheet.onTargetActivityResumed(activity);
        } catch (Throwable ignored) {}
        try {
            RestartPromptController.onTargetActivityResumed(activity);
        } catch (Throwable ignored) {}
        for (ActivityListener listener : sListeners) {
            try {
                listener.onActivityResumed(activity);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
        try {
            RestartPromptController.onTargetActivityPaused(activity);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        for (ActivityListener listener : sListeners) {
            try {
                listener.onActivityStopped(activity);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (sCurrentActivity.get() == activity) {
            sCurrentActivity = new WeakReference<>(null);
        }
        for (ActivityListener listener : sListeners) {
            try {
                listener.onActivityDestroyed(activity);
            } catch (Throwable ignored) {}
        }
    }
}
