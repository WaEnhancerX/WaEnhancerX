package com.waenhancer.xposed.core;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import com.waenhancer.config.PreferenceRestartTracker;
import com.waenhancer.xposed.core.components.WaexBottomSheet;
import com.waenhancer.xposed.utils.WhatsAppProcessRestarter;
import de.robv.android.xposed.XposedBridge;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.ref.WeakReference;
import com.waenhancer.utils.PreferenceLabels;

/** Shows a WhatsApp-styled restart request after settings change in the manager app. */
public final class RestartPromptController {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean SHOWING = new AtomicBoolean(false);
    private static volatile SharedPreferences preferences;
    private static volatile WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private static final SharedPreferences.OnSharedPreferenceChangeListener CHANGE_LISTENER =
            (sharedPreferences, key) -> {
                if (!PreferenceRestartTracker.NEED_RESTART.equals(key)
                        || !sharedPreferences.getBoolean(
                                PreferenceRestartTracker.NEED_RESTART, false)) {
                    return;
                }
                Activity activity = resumedActivity.get();
                if (activity != null) MAIN.post(() -> showIfNeeded(activity));
            };

    private RestartPromptController() {}

    public static synchronized void initialize(@NonNull SharedPreferences sharedPreferences) {
        SharedPreferences previous = preferences;
        if (previous != null) previous.unregisterOnSharedPreferenceChangeListener(CHANGE_LISTENER);
        preferences = sharedPreferences;
        // A newly created target process has already loaded the changed settings, so an old
        // restart request is fulfilled and must not be shown on its first Activity resume.
        if (sharedPreferences.getBoolean(PreferenceRestartTracker.NEED_RESTART, false)
                || !sharedPreferences.getBoolean(
                        PreferenceRestartTracker.SHOW_RESTART_SHEET, true)) {
            sharedPreferences.edit()
                    .putBoolean(PreferenceRestartTracker.NEED_RESTART, false)
                    .remove(PreferenceRestartTracker.PENDING_CHANGES)
                    .apply();
        }
        sharedPreferences.registerOnSharedPreferenceChangeListener(CHANGE_LISTENER);
    }

    public static void onTargetActivityResumed(@NonNull Activity activity) {
        resumedActivity = new WeakReference<>(activity);
        showIfNeeded(activity);
        // Provider refresh is asynchronous; retries cover a return-to-app race while the
        // preference listener remains the primary real-time delivery path.
        MAIN.postDelayed(() -> showIfCurrent(activity), 250L);
        MAIN.postDelayed(() -> showIfCurrent(activity), 750L);
    }

    public static void onTargetActivityPaused(@NonNull Activity activity) {
        if (resumedActivity.get() == activity) resumedActivity = new WeakReference<>(null);
    }

    private static void showIfCurrent(Activity activity) {
        if (resumedActivity.get() == activity) showIfNeeded(activity);
    }

    private static void showIfNeeded(Activity activity) {
        SharedPreferences prefs = preferences;
        if (prefs == null || activity.isFinishing() || activity.isDestroyed()
                || !prefs.getBoolean(PreferenceRestartTracker.SHOW_RESTART_SHEET, true)
                || !prefs.getBoolean(PreferenceRestartTracker.NEED_RESTART, false)
                || !SHOWING.compareAndSet(false, true)) {
            return;
        }

        Set<String> changed = prefs.getStringSet(
                PreferenceRestartTracker.PENDING_CHANGES, Collections.emptySet());
        String message = buildMessage(changed);

        // Consume this request once the sheet is presented. A later setting change creates a new one.
        prefs.edit()
                .putBoolean(PreferenceRestartTracker.NEED_RESTART, false)
                .remove(PreferenceRestartTracker.PENDING_CHANGES)
                .apply();

        try {
            Dialog dialog = new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle(UiText.get(R.string.waex_ui_restart_required, "Restart required"))
                    .setMessage(message)
                    .setPositiveButton(UiText.get(R.string.waex_ui_restart_whatsapp, "Restart WhatsApp"), (d, which) -> {
                        if (d != null) d.dismiss();
                        WhatsAppProcessRestarter.restart(activity);
                    })
                    .setNegativeButton(UiText.get(R.string.waex_ui_later, "Later"), null)
                    .show();
            if (dialog == null) {
                SHOWING.set(false);
                return;
            }
            dialog.setOnDismissListener(ignored -> SHOWING.set(false));
        } catch (Throwable throwable) {
            SHOWING.set(false);
            XposedBridge.log("[WAEX] Restart prompt failed: " + throwable.getMessage());
        }
    }

    static String buildMessage(Set<String> changes) {
        if (changes == null || changes.isEmpty()) {
            return UiText.get(R.string.waex_ui_your_wa_enhancer_preferences_changed_r, "Your WA Enhancer preferences changed. Restart WhatsApp to apply them.");
        }
        Set<String> resolved = new LinkedHashSet<>();
        for (String change : changes) {
            resolved.add(PreferenceLabels.text(change));
        }
        List<String> sorted = new ArrayList<>(resolved);
        sorted.sort(String.CASE_INSENSITIVE_ORDER);
        StringBuilder message = new StringBuilder(UiText.get(R.string.waex_ui_restart_whatsapp_to_apply, "Restart WhatsApp to apply:\n\n"));
        int shown = Math.min(sorted.size(), 8);
        for (int i = 0; i < shown; i++) message.append("• ").append(sorted.get(i)).append('\n');
        if (sorted.size() > shown) {
            message.append("• ").append(sorted.size() - shown).append(UiText.get(R.string.waex_ui_more, " more\n"));
        }
        return message.toString().trim();
    }

}
