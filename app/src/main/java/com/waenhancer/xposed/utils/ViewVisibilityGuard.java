package com.waenhancer.xposed.utils;

import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Reconciles explicitly resolved feature Views after host binding, without frame polling. */
public final class ViewVisibilityGuard {
    private static final WeakHashMap<View, WeakReference<Registration>> registrations = new WeakHashMap<>();

    private ViewVisibilityGuard() {}

    public static void hideWhileEnabled(View view, SharedPreferences preferences, String key) {
        WeakReference<Registration> reference = registrations.get(view);
        Registration registration = reference != null ? reference.get() : null;
        if (registration == null) {
            registration = new Registration(view);
            registrations.put(view, new WeakReference<>(registration));
            view.addOnAttachStateChangeListener(registration);
        }
        // Constructor chaining and overlapping resolved targets must not accumulate listeners.
        registration.preferences.put(key, preferences);
        registration.reconcile(view);
        if (view.isAttachedToWindow()) registration.onViewAttachedToWindow(view);
    }

    private static final class Registration implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<View> owner;
        private final Map<String, SharedPreferences> preferences = new LinkedHashMap<>();
        private WeakReference<ViewTreeObserver> observer = new WeakReference<>(null);

        Registration(View view) {
            owner = new WeakReference<>(view);
        }

        private void reconcile(View view) {
            // GONE already removes a View from layout; do not zero host-owned LayoutParams.
            // When disabled, the next host bind may restore its own visibility. A restart
            // also restores the original host state without guessing a prior visibility.
            if (view.getVisibility() == View.GONE) return;
            for (Map.Entry<String, SharedPreferences> entry : preferences.entrySet()) {
                if (entry.getValue().getBoolean(entry.getKey(), false)) {
                    view.setVisibility(View.GONE);
                    return;
                }
            }
        }

        @Override public void onViewAttachedToWindow(View view) {
            ViewTreeObserver current = view.getViewTreeObserver();
            if (observer.get() != current) {
                removeObserver();
                if (current.isAlive()) {
                    current.addOnGlobalLayoutListener(this);
                    observer = new WeakReference<>(current);
                }
            }
            reconcile(view);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            removeObserver();
        }

        @Override public void onGlobalLayout() {
            View view = owner.get();
            if (view == null || !view.isAttachedToWindow()) {
                removeObserver();
            } else {
                reconcile(view);
            }
        }

        private void removeObserver() {
            ViewTreeObserver previous = observer.get();
            if (previous != null && previous.isAlive()) previous.removeOnGlobalLayoutListener(this);
            observer.clear();
        }
    }
}
