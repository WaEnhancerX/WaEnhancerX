package com.waenhancer.xposed.features.media;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds a save action to WhatsApp's full-screen profile-photo viewer. */
public final class ProfilePhotoDownloadHook extends BaseFeature {
    private static final String TAG = "[WAEX][ProfilePhotoDownload]";
    private static final int MENU_ID = 0x57414558;
    private final AtomicBoolean saveInFlight = new AtomicBoolean();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "WAEX-ProfilePhotoSave");
        thread.setDaemon(true);
        return thread;
    });

    public ProfilePhotoDownloadHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                    @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override public String getName() {
        return "ProfilePhotoDownload";
    }

    @Override public void hook() {
        Class<?> viewer = resolveViewerClass();
        if (viewer == null) {
            XposedBridge.log(TAG + " profile viewer class not found");
            return;
        }
        XposedBridge.hookAllMethods(viewer, "onCreateOptionsMenu",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.hasThrowable() || !isEnabled("download_profile", false)
                                || !(param.thisObject instanceof Activity)
                                || param.args.length == 0
                                || !(param.args[0] instanceof Menu)) return;
                        installMenuAction((Activity) param.thisObject, (Menu) param.args[0]);
                    }
                });
    }

    private Class<?> resolveViewerClass() {
        String[] candidates = {
                "com.whatsapp.profile.ViewProfilePhoto",
                "com.whatsapp.profile.ViewProfilePhotoActivity"
        };
        for (String name : candidates) {
            Class<?> candidate = XposedHelpers.findClassIfExists(name, classLoader);
            if (candidate != null) return candidate;
        }
        return DexSearchEngine.getInstance().findClassWithCache(context, classLoader,
                "profile_photo_viewer_class", (bridge, loader) -> {
                    ClassData data = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().className("ViewProfilePhoto",
                                    StringMatchType.EndsWith))).firstOrNull();
                    return data == null ? null : data.getInstance(loader);
                });
    }

    private void installMenuAction(Activity activity, Menu menu) {
        if (menu.findItem(MENU_ID) != null) return;
        MenuItem item = menu.add(Menu.NONE, MENU_ID, Menu.NONE, UiText.get(R.string.waex_ui_download_d6eafe82, "Download"));
        item.setIcon(android.R.drawable.stat_sys_download_done);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(ignored -> {
            if (!isEnabled("download_profile", false) || activity.isFinishing()
                    || activity.isDestroyed() || !saveInFlight.compareAndSet(false, true)) return true;
            Bitmap captured = null;
            boolean submitted = false;
            try {
                // Admit before copying the displayed bitmap: repeated clicks must not
                // allocate and queue another full-resolution copy while one save runs.
                captured = captureDisplayedPhoto(activity);
                if (captured == null) {
                    Toast.makeText(activity, UiText.get(R.string.waex_ui_profile_photo_is_not_ready_yet_8ff494b5,
                            "Profile photo is not ready yet"), Toast.LENGTH_SHORT).show();
                    return true;
                }
                Context application = activity.getApplicationContext();
                if (application == null) application = context;
                final Context storageContext = application;
                final Bitmap ownedBitmap = captured;
                final WeakReference<Activity> owner = new WeakReference<>(activity);
                io.execute(() -> saveBitmap(storageContext, owner, ownedBitmap));
                submitted = true;
            } catch (RuntimeException | LinkageError failure) {
                logFailure("capture or scheduling", failure);
                Toast.makeText(activity, UiText.get(R.string.waex_ui_couldn_t_save_profile_photo_296d0ed9,
                        "Couldn't save profile photo"), Toast.LENGTH_LONG).show();
            } finally {
                if (!submitted) {
                    if (captured != null) captured.recycle();
                    saveInFlight.set(false);
                }
            }
            return true;
        });
    }

    private Bitmap captureDisplayedPhoto(Activity activity) {
        ImageView best = findLargestImage(activity.getWindow().getDecorView(), null);
        if (best == null || best.getDrawable() == null) return null;
        Drawable drawable = best.getDrawable();
        if (drawable instanceof BitmapDrawable) {
            Bitmap source = ((BitmapDrawable) drawable).getBitmap();
            if (source != null && !source.isRecycled()) {
                // Preserve the existing 40MP capture ceiling for bitmap-backed
                // drawables too, before allocating the independent ARGB copy.
                if ((long) source.getWidth() * source.getHeight() > 40_000_000L) return null;
                Bitmap safe = source.copy(Bitmap.Config.ARGB_8888, false);
                if (safe != null) return safe;
            }
        }
        int width = Math.max(1, drawable.getIntrinsicWidth() > 0
                ? drawable.getIntrinsicWidth() : best.getWidth());
        int height = Math.max(1, drawable.getIntrinsicHeight() > 0
                ? drawable.getIntrinsicHeight() : best.getHeight());
        if ((long) width * height > 40_000_000L) return null;
        Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rendered);
        int left = drawable.getBounds().left;
        int top = drawable.getBounds().top;
        int right = drawable.getBounds().right;
        int bottom = drawable.getBounds().bottom;
        boolean complete = false;
        try {
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            complete = true;
            return rendered;
        } finally {
            boolean restored = false;
            try {
                drawable.setBounds(left, top, right, bottom);
                restored = true;
            } finally {
                if (!complete || !restored) rendered.recycle();
            }
        }
    }

    private ImageView findLargestImage(View view, ImageView current) {
        ImageView best = current;
        if (view instanceof ImageView && view.getVisibility() == View.VISIBLE
                && ((ImageView) view).getDrawable() != null) {
            ImageView image = (ImageView) view;
            long imageArea = photoArea(image);
            if (best == null || imageArea > photoArea(best)) best = image;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                best = findLargestImage(group.getChildAt(i), best);
            }
        }
        return best;
    }

    private long photoArea(ImageView image) {
        Drawable drawable = image.getDrawable();
        int width = drawable != null && drawable.getIntrinsicWidth() > 0
                ? drawable.getIntrinsicWidth() : image.getWidth();
        int height = drawable != null && drawable.getIntrinsicHeight() > 0
                ? drawable.getIntrinsicHeight() : image.getHeight();
        return (long) Math.max(0, width) * Math.max(0, height);
    }

    private void saveBitmap(Context storageContext, WeakReference<Activity> owner, Bitmap bitmap) {
        Uri output = null;
        boolean success = false;
        try {
            String name = "WAEX_Profile_" + UUID.randomUUID() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/WAEX/Profile Photos");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            output = storageContext.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (output == null) throw new IllegalStateException("MediaStore rejected the file");
            try (OutputStream stream = storageContext.getContentResolver().openOutputStream(output)) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw new IllegalStateException("Could not encode the image");
                }
                stream.flush();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (storageContext.getContentResolver().update(output, ready, null, null) != 1) {
                    throw new IllegalStateException("MediaStore did not publish the image");
                }
            }
            success = true;
        } catch (Exception | LinkageError failure) {
            logFailure("save", failure);
        } finally {
            try {
                if (!success && output != null) {
                    try {
                        // Never delete an earlier export or any host-owned image.
                        storageContext.getContentResolver().delete(output, null, null);
                    } catch (RuntimeException | LinkageError failure) {
                        logFailure("rollback", failure);
                    }
                }
            } finally {
                bitmap.recycle();
                saveInFlight.set(false);
            }
            final boolean saved = success;
            Activity activity = owner.get();
            if (activity != null && !activity.isFinishing() && !activity.isDestroyed()) {
                activity.runOnUiThread(() -> {
                    Activity current = owner.get();
                    if (current == null || current.isFinishing() || current.isDestroyed()) return;
                    Toast.makeText(current,
                            saved ? UiText.get(R.string.waex_ui_profile_photo_saved_to_pictures_waex_p_a8c4bd76,
                                    "Profile photo saved to Pictures/WAEX/Profile Photos")
                                    : UiText.get(R.string.waex_ui_couldn_t_save_profile_photo_296d0ed9,
                                    "Couldn't save profile photo"), Toast.LENGTH_LONG).show();
                });
            }
        }
    }

    private static void logFailure(String operation, Throwable failure) {
        try {
            XposedBridge.log(TAG + " " + operation + " failed: " + failure.getClass().getSimpleName());
        } catch (RuntimeException | LinkageError ignored) {}
    }
}
