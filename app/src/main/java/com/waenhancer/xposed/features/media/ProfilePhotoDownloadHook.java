package com.waenhancer.xposed.features.media;

import com.waenhancer.app.R;
import com.waenhancer.utils.ContactIdentityPolicy;
import com.waenhancer.utils.ContactNameResolver;
import com.waenhancer.utils.UiText;
import com.waenhancer.xposed.features.homescreen.MenuIconLoader;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds a save action to WhatsApp's full-screen profile-photo viewer. */
public final class ProfilePhotoDownloadHook extends BaseFeature {
    private static final String TAG = "[WAEX][ProfilePhotoDownload]";
    private static final int MENU_ID = 0x57414558;
    private static final String PREF_SAVE_DUPLICATES = "download_profile_save_duplicates";
    private static final String PREF_SAVED_IDENTITIES = "download_profile_saved_identities";
    private static final Object SAVED_IDENTITIES_LOCK = new Object();

    private static volatile Object sContactPhotosManagerInstance;
    private static volatile Method sGetProfilePhotoStreamMethod;

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
        if (!isEnabled("download_profile", false)) {
            return;
        }
        Class<?> viewer = resolveViewerClass();
        if (viewer == null) {
            XposedBridge.log(TAG + " profile viewer class not found");
            return;
        }

        XC_MethodHook menuHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable() || !isEnabled("download_profile", false)
                        || !(param.thisObject instanceof Activity)
                        || param.args.length == 0
                        || !(param.args[0] instanceof Menu)) return;
                installMenuAction((Activity) param.thisObject, (Menu) param.args[0]);
            }
        };

        // Hook both onCreateOptionsMenu and onPrepareOptionsMenu across the entire viewer hierarchy
        // so that the Download item is created, maintained, and remains visible across full-screen
        // toggles and menu invalidations.
        for (Class<?> curr = viewer; curr != null && curr != Activity.class; curr = curr.getSuperclass()) {
            try {
                for (Method m : curr.getDeclaredMethods()) {
                    String mName = m.getName();
                    if (("onCreateOptionsMenu".equals(mName) || "onPrepareOptionsMenu".equals(mName))
                            && m.getParameterTypes().length == 1
                            && Menu.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        XposedBridge.hookMethod(m, menuHook);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " failed to hook menu methods on " + curr.getSimpleName() + ": " + t.getClass().getSimpleName());
            }
        }

        initContactPhotoManagerHook();
    }

    private Class<?> resolveViewerClass() {
        String[] candidates = {
                "com.whatsapp.profile.ui.ViewProfilePhoto",
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
                            ClassMatcher.create().className(".ViewProfilePhoto",
                                    StringMatchType.EndsWith))).firstOrNull();
                    if (data == null) {
                        data = bridge.findClass(FindClass.create().matcher(
                                ClassMatcher.create().className("ViewProfilePhoto",
                                        StringMatchType.Contains))).firstOrNull();
                    }
                    return data == null ? null : data.getInstance(loader);
                });
    }

    private void initContactPhotoManagerHook() {
        try {
            Method getPhotoStreamMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "contact_photos_get_stream_method", (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create().matcher(
                                MethodMatcher.create().usingStrings("contactPhotosBitmapManager/getphotostream/")
                        )).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    });

            if (getPhotoStreamMethod != null) {
                sGetProfilePhotoStreamMethod = getPhotoStreamMethod;
                Class<?> managerClass = getPhotoStreamMethod.getDeclaringClass();
                for (Constructor<?> ctor : managerClass.getDeclaredConstructors()) {
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sContactPhotosManagerInstance = param.thisObject;
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " initContactPhotoManagerHook error: " + t.getClass().getSimpleName());
        }
    }

    private void installMenuAction(Activity activity, Menu menu) {
        MenuItem existing = menu.findItem(MENU_ID);
        if (existing != null) {
            existing.setVisible(true);
            existing.setEnabled(true);
            return;
        }

        MenuItem item = menu.add(Menu.NONE, MENU_ID, Menu.NONE, UiText.get(R.string.waex_ui_download, "Download"));
        Drawable icon = MenuIconLoader.load(activity, "ic_waex_download");
        if (icon != null) {
            item.setIcon(icon);
        } else {
            item.setIcon(android.R.drawable.stat_sys_download_done);
        }
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(ignored -> {
            if (!isEnabled("download_profile", false) || activity.isFinishing()
                    || activity.isDestroyed() || !saveInFlight.compareAndSet(false, true)) return true;

            io.execute(() -> {
                try {
                    boolean saveDuplicates = isEnabled(PREF_SAVE_DUPLICATES, true);
                    String profileIdentity = saveDuplicates ? null : profileIdentity(activity);
                    if (profileIdentity != null && hasSavedProfile(profileIdentity)) {
                        showToast(activity, UiText.get(R.string.profile_photo_already_saved, "Profile photo already saved"));
                        saveInFlight.set(false);
                        return;
                    }

                    boolean saved = extractAndSavePhoto(activity, profileIdentity);
                    if (!saved) {
                        showToast(activity, UiText.get(R.string.waex_ui_profile_photo_is_not_ready_yet, "Profile photo is not ready yet"));
                    }
                } catch (Throwable failure) {
                    logFailure("save profile photo", failure);
                    showToast(activity, UiText.get(R.string.waex_ui_couldn_t_save_profile_photo, "Couldn't save profile photo"));
                } finally {
                    saveInFlight.set(false);
                }
            });
            return true;
        });
    }

    private boolean extractAndSavePhoto(Activity activity, String profileIdentity) {
        Context appCtx = activity.getApplicationContext() != null ? activity.getApplicationContext() : context;

        // Tier 1: Direct File check from photo helper or cache
        File directFile = resolveProfilePhotoFile(activity);
        if (directFile != null && directFile.isFile() && directFile.length() > 0) {
            try (InputStream in = new FileInputStream(directFile)) {
                if (saveStreamToGallery(appCtx, in, profileIdentity)) {
                    notifySaved(activity);
                    return true;
                }
            } catch (Throwable t) {
                logFailure("Tier 1 direct file copy", t);
            }
        }

        // Tier 2: Contact photo stream via ContactPhotosBitmapManager
        InputStream stream = resolveContactPhotoStream(activity);
        if (stream != null) {
            try (InputStream in = stream) {
                if (saveStreamToGallery(appCtx, in, profileIdentity)) {
                    notifySaved(activity);
                    return true;
                }
            } catch (Throwable t) {
                logFailure("Tier 2 stream copy", t);
            }
        }

        // Tier 3: PhotoView bitmap extraction
        Bitmap photoViewBitmap = extractBitmapFromPhotoView(activity);
        if (photoViewBitmap != null) {
            try {
                if (saveBitmapToGallery(appCtx, photoViewBitmap, profileIdentity)) {
                    notifySaved(activity);
                    return true;
                }
            } finally {
                photoViewBitmap.recycle();
            }
        }

        // Tier 4: Window view hierarchy capture fallback
        Bitmap fallback = captureDisplayedPhoto(activity);
        if (fallback != null) {
            try {
                if (saveBitmapToGallery(appCtx, fallback, profileIdentity)) {
                    notifySaved(activity);
                    return true;
                }
            } finally {
                fallback.recycle();
            }
        }

        return false;
    }

    private File resolveProfilePhotoFile(Activity activity) {
        try {
            // Check fields of activity and its superclasses for contact object and photo helper
            Object contact = extractContactObject(activity);
            if (contact != null) {
                for (Class<?> curr = activity.getClass(); curr != null && curr != Activity.class; curr = curr.getSuperclass()) {
                    for (Field field : curr.getDeclaredFields()) {
                        field.setAccessible(true);
                        Object helper = field.get(activity);
                        if (helper == null) continue;
                        for (Method method : helper.getClass().getDeclaredMethods()) {
                            if (File.class.isAssignableFrom(method.getReturnType())) {
                                Class<?>[] params = method.getParameterTypes();
                                if (params.length == 1 && params[0].isAssignableFrom(contact.getClass())) {
                                    method.setAccessible(true);
                                    Object result = method.invoke(helper, contact);
                                    if (result instanceof File) {
                                        File f = (File) result;
                                        if (f.isFile() && f.length() > 0) return f;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Check WhatsApp's cache directory: Profile Pictures/<jid>.j or .jpg
            String rawJid = ContactNameResolver.INSTANCE.extractJidFromActivity(activity);
            if (rawJid != null && !rawJid.isEmpty()) {
                File cacheDir = activity.getCacheDir();
                if (cacheDir != null) {
                    File[] candidates = {
                            new File(cacheDir, "Profile Pictures/" + rawJid + ".j"),
                            new File(cacheDir, "Profile Pictures/" + rawJid + ".jpg"),
                            new File(cacheDir, "Profile Pictures/" + rawJid),
                            new File(activity.getFilesDir(), "me.jpg")
                    };
                    for (File c : candidates) {
                        if (c.isFile() && c.length() > 0) return c;
                    }
                }
            }
        } catch (Throwable t) {
            logFailure("resolveProfilePhotoFile", t);
        }
        return null;
    }

    private InputStream resolveContactPhotoStream(Activity activity) {
        Method method = sGetProfilePhotoStreamMethod;
        Object manager = sContactPhotosManagerInstance;
        if (method == null || manager == null) return null;

        try {
            Object contact = extractContactObject(activity);
            if (contact == null) return null;

            Class<?>[] paramTypes = method.getParameterTypes();
            Object[] args = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                Class<?> type = paramTypes[i];
                if (type == boolean.class || type == Boolean.class) {
                    args[i] = true;
                } else if (type.isAssignableFrom(contact.getClass())) {
                    args[i] = contact;
                } else if (type == int.class || type == Integer.class) {
                    args[i] = 640;
                }
            }
            method.setAccessible(true);
            Object res = method.invoke(manager, args);
            if (res instanceof InputStream) return (InputStream) res;
        } catch (Throwable t) {
            logFailure("resolveContactPhotoStream", t);
        }
        return null;
    }

    private Object extractContactObject(Activity activity) {
        for (Class<?> curr = activity.getClass(); curr != null && curr != Activity.class; curr = curr.getSuperclass()) {
            for (Field field : curr.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    Object val = field.get(activity);
                    if (val == null) continue;
                    String typeName = val.getClass().getName();
                    if (!typeName.startsWith("android.") && !typeName.startsWith("java.")) {
                        for (Method m : val.getClass().getDeclaredMethods()) {
                            String mName = m.getName();
                            if (("getRawString".equals(mName) || "getJid".equals(mName) || "getUserJid".equals(mName))
                                    && m.getParameterTypes().length == 0) {
                                return val;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private Bitmap extractBitmapFromPhotoView(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            View photoView = findPhotoViewRecursive(decor);
            if (photoView != null) {
                // Check getPhoto()
                try {
                    Method getPhoto = photoView.getClass().getMethod("getPhoto");
                    Bitmap b = (Bitmap) getPhoto.invoke(photoView);
                    if (b != null && !b.isRecycled()) {
                        return b.copy(Bitmap.Config.ARGB_8888, false);
                    }
                } catch (Throwable ignored) {}

                // Check getImageViewBitmapDrawable()
                try {
                    Method getBmpDrawable = photoView.getClass().getMethod("getImageViewBitmapDrawable");
                    BitmapDrawable bd = (BitmapDrawable) getBmpDrawable.invoke(photoView);
                    if (bd != null && bd.getBitmap() != null && !bd.getBitmap().isRecycled()) {
                        return bd.getBitmap().copy(Bitmap.Config.ARGB_8888, false);
                    }
                } catch (Throwable ignored) {}

                // Check declared fields for BitmapDrawable or Bitmap (such as A0D)
                for (Class<?> c = photoView.getClass(); c != null && c != View.class; c = c.getSuperclass()) {
                    for (Field f : c.getDeclaredFields()) {
                        f.setAccessible(true);
                        Object v = f.get(photoView);
                        if (v instanceof BitmapDrawable) {
                            Bitmap b = ((BitmapDrawable) v).getBitmap();
                            if (b != null && !b.isRecycled()) return b.copy(Bitmap.Config.ARGB_8888, false);
                        } else if (v instanceof Bitmap) {
                            Bitmap b = (Bitmap) v;
                            if (!b.isRecycled()) return b.copy(Bitmap.Config.ARGB_8888, false);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            logFailure("extractBitmapFromPhotoView", t);
        }
        return null;
    }

    private View findPhotoViewRecursive(View view) {
        if (view == null) return null;
        String name = view.getClass().getName();
        if (name.contains("PhotoView") || name.endsWith("PhotoView")) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) view;
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findPhotoViewRecursive(g.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private Bitmap captureDisplayedPhoto(Activity activity) {
        ImageView best = findLargestImage(activity.getWindow().getDecorView(), null);
        if (best == null) return null;

        Drawable drawable = best.getDrawable();
        if (drawable instanceof BitmapDrawable) {
            Bitmap source = ((BitmapDrawable) drawable).getBitmap();
            if (source != null && !source.isRecycled()) {
                if ((long) source.getWidth() * source.getHeight() > 40_000_000L) return null;
                Bitmap safe = source.copy(Bitmap.Config.ARGB_8888, false);
                if (safe != null) return safe;
            }
        }

        int width = Math.max(1, drawable != null && drawable.getIntrinsicWidth() > 0
                ? drawable.getIntrinsicWidth() : best.getWidth());
        int height = Math.max(1, drawable != null && drawable.getIntrinsicHeight() > 0
                ? drawable.getIntrinsicHeight() : best.getHeight());
        if ((long) width * height > 40_000_000L) return null;

        Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(rendered);
        best.draw(canvas);
        return rendered;
    }

    private ImageView findLargestImage(View view, ImageView current) {
        ImageView best = current;
        if (view instanceof ImageView && view.getVisibility() == View.VISIBLE) {
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

    private boolean saveStreamToGallery(Context ctx, InputStream in, String profileIdentity) {
        Uri output = null;
        boolean success = false;
        try {
            String name = "WAEX_Profile_" + UUID.randomUUID() + ".jpg";
            ContentResolver resolver = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/WAEX/Profile Photos");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            output = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (output == null) throw new IllegalStateException("MediaStore rejected insert");

            try (OutputStream out = resolver.openOutputStream(output)) {
                if (out == null) throw new IllegalStateException("MediaStore stream is null");
                byte[] buf = new byte[32768];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
                out.flush();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(output, ready, null, null);
            } else {
                MediaScannerConnection.scanFile(ctx, new String[]{output.getPath()}, new String[]{"image/jpeg"}, null);
            }
            success = true;
            if (profileIdentity != null) markProfileSaved(profileIdentity);
            return true;
        } catch (Throwable failure) {
            logFailure("saveStreamToGallery", failure);
            if (!success && output != null) {
                try { ctx.getContentResolver().delete(output, null, null); } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    private boolean saveBitmapToGallery(Context ctx, Bitmap bitmap, String profileIdentity) {
        Uri output = null;
        boolean success = false;
        try {
            String name = "WAEX_Profile_" + UUID.randomUUID() + ".jpg";
            ContentResolver resolver = ctx.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/WAEX/Profile Photos");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            output = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (output == null) throw new IllegalStateException("MediaStore rejected insert");

            try (OutputStream stream = resolver.openOutputStream(output)) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) {
                    throw new IllegalStateException("Could not encode image");
                }
                stream.flush();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(output, ready, null, null);
            } else {
                MediaScannerConnection.scanFile(ctx, new String[]{output.getPath()}, new String[]{"image/jpeg"}, null);
            }
            success = true;
            if (profileIdentity != null) markProfileSaved(profileIdentity);
            return true;
        } catch (Throwable failure) {
            logFailure("saveBitmapToGallery", failure);
            if (!success && output != null) {
                try { ctx.getContentResolver().delete(output, null, null); } catch (Throwable ignored) {}
            }
            return false;
        }
    }

    private void notifySaved(Activity activity) {
        showToast(activity, UiText.get(R.string.waex_ui_profile_photo_saved_to_pictures_waex_p,
                "Profile photo saved to Pictures/WAEX/Profile Photos"));
    }

    private void showToast(Activity activity, String msg) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {}
        });
    }

    private static String profileIdentity(Activity activity) {
        try {
            String jid = ContactIdentityPolicy.canonicalJid(
                    ContactNameResolver.INSTANCE.extractJidFromActivity(activity));
            if (jid == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(jid.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                int unsigned = value & 0xff;
                encoded.append(Character.forDigit(unsigned >>> 4, 16));
                encoded.append(Character.forDigit(unsigned & 0x0f, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private boolean hasSavedProfile(String profileIdentity) {
        synchronized (SAVED_IDENTITIES_LOCK) {
            try {
                return prefs.getStringSet(PREF_SAVED_IDENTITIES, Collections.emptySet())
                        .contains(profileIdentity);
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
    }

    private void markProfileSaved(String profileIdentity) {
        synchronized (SAVED_IDENTITIES_LOCK) {
            try {
                Set<String> saved = new LinkedHashSet<>(prefs.getStringSet(
                        PREF_SAVED_IDENTITIES, Collections.emptySet()));
                saved.add(profileIdentity);
                prefs.edit().putStringSet(PREF_SAVED_IDENTITIES, saved).commit();
            } catch (RuntimeException | LinkageError failure) {
                logFailure("remember saved profile", failure);
            }
        }
    }

    private static void logFailure(String operation, Throwable failure) {
        try {
            XposedBridge.log(TAG + " " + operation + " failed: " + failure.getClass().getSimpleName());
        } catch (RuntimeException | LinkageError ignored) {}
    }
}
