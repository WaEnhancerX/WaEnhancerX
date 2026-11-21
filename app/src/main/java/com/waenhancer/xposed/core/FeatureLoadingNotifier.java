package com.waenhancer.xposed.core;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;
import com.waenhancer.xposed.utils.HookErrors;
import com.waenhancer.xposed.utils.HostResourceIds;

/**
 * Shows startup feature-loading progress while the DEX descriptor cache is rebuilt.
 *
 * After a WhatsApp or module update every hook target is resolved with a full DexKit scan, so
 * the first launch is noticeably slower. A silent, ongoing notification explains the delay and
 * disappears as soon as the startup features are installed.
 */
public final class FeatureLoadingNotifier {
    private static final String CHANNEL_ID = "waex_feature_loading";
    private static final int NOTIFICATION_ID = 0x57414558;
    private static final long MIN_UPDATE_INTERVAL_MS = 250L;

    private static final Object LOCK = new Object();
    private static Context context;
    private static boolean active;
    private static int total;
    private static int loaded;
    private static long lastUpdateAt;

    private FeatureLoadingNotifier() {}

    /** Returns true when the notification was posted and progress should be reported. */
    public static boolean start(@NonNull Context appContext, int featureCount) {
        if (featureCount <= 0) return false;
        synchronized (LOCK) {
            try {
                NotificationManagerCompat manager = NotificationManagerCompat.from(appContext);
                if (!manager.areNotificationsEnabled()) return false;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                            UiText.get(R.string.waex_ui_feature_loading_channel, "WAEX feature loading"),
                            NotificationManager.IMPORTANCE_LOW);
                    channel.setSound(null, null);
                    channel.enableVibration(false);
                    channel.setShowBadge(false);
                    manager.createNotificationChannel(channel);
                }
                context = appContext;
                active = true;
                total = featureCount;
                loaded = 0;
                post(null);
                return true;
            } catch (Throwable error) {
                HookErrors.swallowed("FeatureLoadingNotifier.start", error);
                active = false;
                return false;
            }
        }
    }

    public static void onFeatureLoaded(@Nullable String featureName) {
        synchronized (LOCK) {
            if (!active) return;
            loaded = Math.min(loaded + 1, total);
            long now = SystemClock.elapsedRealtime();
            if (loaded < total && now - lastUpdateAt < MIN_UPDATE_INTERVAL_MS) return;
            post(featureName);
        }
    }

    public static void finish(@NonNull Context appContext) {
        synchronized (LOCK) {
            if (!active) return;
            active = false;
            context = null;
            try {
                NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID);
            } catch (Throwable error) {
                HookErrors.swallowed("FeatureLoadingNotifier.finish", error);
            }
        }
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled() was checked in start().
    private static void post(@Nullable String featureName) {
        Context target = context;
        if (target == null) return;
        lastUpdateAt = SystemClock.elapsedRealtime();
        try {
            String text = UiText.get(R.string.waex_ui_feature_loading_progress,
                    "Preparing features after an update… %1$d/%2$d", loaded, total);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(target, CHANNEL_ID)
                    .setSmallIcon(smallIcon(target))
                    .setContentTitle(UiText.get(R.string.waex_ui_feature_loading_title, "WaEnhancer X"))
                    .setContentText(text)
                    .setProgress(total, loaded, false)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setSilent(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS);
            if (featureName != null) builder.setSubText(featureName);
            NotificationManagerCompat.from(target).notify(NOTIFICATION_ID, builder.build());
        } catch (Throwable error) {
            HookErrors.swallowed("FeatureLoadingNotifier.post", error);
        }
    }

    private static int smallIcon(Context target) {
        // The notification is posted by WhatsApp, so the icon must be one of its own resources.
        int notifyIcon = HostResourceIds.resolve(target, "notifybar", "drawable");
        if (notifyIcon != 0) return notifyIcon;
        int appIcon = target.getApplicationInfo().icon;
        return appIcon != 0 ? appIcon : android.R.drawable.stat_sys_download;
    }
}
