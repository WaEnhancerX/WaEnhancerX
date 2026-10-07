package com.waenhancer.xposed.features.media;

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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds a save action to WhatsApp's full-screen profile-photo viewer. */
public final class ProfilePhotoDownloadHook extends BaseFeature {
    private static final String TAG = "[WAEX][ProfilePhotoDownload]";
    private static final int MENU_ID = 0x57414558;
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
        Set<?> hooks = XposedBridge.hookAllMethods(viewer, "onCreateOptionsMenu",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled("download_profile", false)
                                || !(param.thisObject instanceof Activity)
                                || param.args.length == 0
                                || !(param.args[0] instanceof Menu)) return;
                        installMenuAction((Activity) param.thisObject, (Menu) param.args[0]);
                    }
                });
        XposedBridge.log(TAG + " installed on " + viewer.getName() + ", hooks=" + hooks.size());
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
        MenuItem item = menu.add(Menu.NONE, MENU_ID, Menu.NONE, "Download");
        item.setIcon(android.R.drawable.stat_sys_download_done);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(ignored -> {
            Bitmap bitmap = captureDisplayedPhoto(activity);
            if (bitmap == null) {
                Toast.makeText(activity, "Profile photo is not ready yet", Toast.LENGTH_SHORT).show();
                return true;
            }
            io.execute(() -> saveBitmap(activity, bitmap));
            return true;
        });
    }

    private Bitmap captureDisplayedPhoto(Activity activity) {
        ImageView best = findLargestImage(activity.getWindow().getDecorView(), null);
        if (best == null || best.getDrawable() == null) return null;
        Drawable drawable = best.getDrawable();
        if (drawable instanceof BitmapDrawable) {
            Bitmap source = ((BitmapDrawable) drawable).getBitmap();
            if (source != null) {
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
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        drawable.setBounds(left, top, right, bottom);
        return rendered;
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

    private void saveBitmap(Activity activity, Bitmap bitmap) {
        Uri output = null;
        boolean success = false;
        try {
            String name = "WAEX_Profile_" + new SimpleDateFormat(
                    "yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/WAEX/Profile Photos");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            output = activity.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (output == null) throw new IllegalStateException("MediaStore rejected the file");
            try (OutputStream stream = activity.getContentResolver().openOutputStream(output)) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw new IllegalStateException("Could not encode the image");
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                activity.getContentResolver().update(output, ready, null, null);
            }
            success = true;
        } catch (Throwable failure) {
            XposedBridge.log(TAG + " save failed: " + failure);
            if (output != null) try {
                activity.getContentResolver().delete(output, null, null);
            } catch (Throwable ignored) {}
        } finally {
            bitmap.recycle();
            boolean saved = success;
            activity.runOnUiThread(() -> Toast.makeText(activity,
                    saved ? "Profile photo saved to Pictures/WAEX/Profile Photos"
                            : "Couldn't save profile photo",
                    Toast.LENGTH_LONG).show());
        }
    }
}
