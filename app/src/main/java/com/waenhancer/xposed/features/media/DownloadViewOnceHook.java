package com.waenhancer.xposed.features.media;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.homescreen.MenuIconLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/**
 * Download View Once Media:
 * Adds a download action button to the ViewOnceViewerActivity and MediaViewFragment toolbars
 * allowing saving View Once photos and videos directly to the device Gallery.
 *
 * Implements multiple robust discovery and extraction strategies from WaEnhancer.
 */
public class DownloadViewOnceHook extends BaseFeature {

    private static final String TAG = "[WAEX][DownloadViewOnce]";
    private static final int MENU_ID_DOWNLOAD = 0x7EAD0003;

    public DownloadViewOnceHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookViewOnceViewerActivity();
        hookMediaViewMenu();
    }

    /**
     * Hook ViewOnceViewerActivity: intercepts onCreateOptionsMenu to inject Download button.
     */
    private void hookViewOnceViewerActivity() {
        try {
            Class<?> viewOnceClass = resolveViewOnceViewerActivityClass();
            if (viewOnceClass == null) {
                XposedBridge.log(TAG + " ViewOnceViewerActivity class not found");
                return;
            }

            XposedHelpers.findAndHookMethod(viewOnceClass, "onCreateOptionsMenu", Menu.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isEnabled("downloadviewonce", false)) return;

                    Menu menu = (Menu) param.args[0];
                    if (menu == null) return;

                    if (menu.findItem(MENU_ID_DOWNLOAD) != null) return;

                    Activity activity = (Activity) param.thisObject;
                    MenuItem item = menu.add(Menu.NONE, MENU_ID_DOWNLOAD, Menu.NONE, "Download");
                    item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

                    Drawable icon = MenuIconLoader.load(activity, "ic_waex_download");
                    if (icon != null) {
                        item.setIcon(icon);
                    }

                    item.setOnMenuItemClickListener(menuItem -> {
                        CompletableFuture.runAsync(() -> {
                            try {
                                File mediaFile = extractMediaFileFromActivity(activity);
                                if (mediaFile == null || !mediaFile.exists()) {
                                    showToast(activity, "Media not cached or available yet");
                                    return;
                                }
                                saveMediaFile(activity, mediaFile);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " Error saving view once from Activity: " + t.getMessage());
                                showToast(activity, "Failed to save: " + t.getMessage());
                            }
                        });
                        return true;
                    });
                }
            });

            XposedBridge.log(TAG + " Hooked ViewOnceViewerActivity: " + viewOnceClass.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ViewOnceViewerActivity: " + t.getMessage());
        }
    }

    /**
     * Hook MediaView menu methods using WaEnhancer's ic_viewonce number matcher.
     */
    private void hookMediaViewMenu() {
        try {
            DexSearchEngine.getInstance().initialize(context);
            var bridge = DexSearchEngine.getInstance().getBridge();
            if (bridge == null) return;

            Method menuMethod = null;
            int icViewOnceId = context.getResources().getIdentifier("ic_viewonce", "drawable", context.getPackageName());
            if (icViewOnceId > 0) {
                MethodDataList mdList = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingNumbers(icViewOnceId)
                        ));
                for (MethodData md : mdList) {
                    if (md.getParamCount() >= 2 && md.getParamTypeNames().contains(Menu.class.getName())) {
                        menuMethod = md.getMethodInstance(classLoader);
                        break;
                    }
                }
            }

            if (menuMethod == null) {
                Class<?> mediaViewFragmentClass = XposedHelpers.findClassIfExists("com.whatsapp.mediaview.MediaViewFragment", classLoader);
                if (mediaViewFragmentClass != null) {
                    for (Method m : mediaViewFragmentClass.getMethods()) {
                        if (m.getParameterCount() >= 2 && m.getParameterTypes()[0] == Menu.class) {
                            menuMethod = m;
                            break;
                        }
                    }
                }
            }

            if (menuMethod != null) {
                XposedBridge.hookMethod(menuMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled("downloadviewonce", false)) return;

                        Menu menu = null;
                        Object fmessageObj = null;

                        for (Object arg : param.args) {
                            if (arg instanceof Menu) menu = (Menu) arg;
                            else if (arg != null && isFMessageObject(arg)) fmessageObj = arg;
                        }

                        if (menu == null || menu.findItem(MENU_ID_DOWNLOAD) != null) return;

                        final Object targetMessage = fmessageObj;
                        MenuItem item = menu.add(Menu.NONE, MENU_ID_DOWNLOAD, Menu.NONE, "Download");
                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

                        Drawable icon = MenuIconLoader.load(context, "ic_waex_download");
                        if (icon != null) {
                            item.setIcon(icon);
                        }

                        item.setOnMenuItemClickListener(menuItem -> {
                            CompletableFuture.runAsync(() -> {
                                try {
                                    if (targetMessage != null) {
                                        File mediaFile = extractFileFromFMessage(targetMessage);
                                        if (mediaFile != null && mediaFile.exists()) {
                                            saveMediaFile(context, mediaFile);
                                            return;
                                        }
                                    }
                                    showToast(context, "Media not available yet");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + " Error saving media: " + t.getMessage());
                                    showToast(context, "Failed to save: " + t.getMessage());
                                }
                            });
                            return true;
                        });
                    }
                });
                XposedBridge.log(TAG + " Hooked media viewer menu method: " + menuMethod.getName());
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error finding media viewer menu method: " + t.getMessage());
        }
    }

    private Class<?> resolveViewOnceViewerActivityClass() {
        try {
            Class<?> cls = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_view_once_viewer_activity",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .className(".ViewOnceViewerActivity", StringMatchType.EndsWith))
                        ).firstOrNull();
                        if (cd == null) {
                            cd = bridge.findClass(FindClass.create()
                                    .matcher(ClassMatcher.create()
                                            .className("ViewOnceViewerActivity", StringMatchType.Contains))
                            ).firstOrNull();
                        }
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );
            if (cls != null) return cls;
        } catch (Throwable ignored) {}

        String[] fallbacks = {
                "com.whatsapp.viewonce.ui.messaging.ViewOnceViewerActivity",
                "com.whatsapp.messaging.ViewOnceViewerActivity"
        };
        for (String name : fallbacks) {
            Class<?> c = XposedHelpers.findClassIfExists(name, classLoader);
            if (c != null) return c;
        }
        return null;
    }

    /**
     * Extracts the media File from ViewOnceViewerActivity instance via deep field reflection.
     */
    private File extractMediaFileFromActivity(Activity activity) {
        if (activity == null) return null;
        Class<?> current = activity.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    Object val = field.get(activity);
                    if (val == null) continue;

                    if (val instanceof File) {
                        File f = (File) val;
                        if (f.exists() && f.length() > 0) return f;
                    }

                    File f = extractFileFromFMessage(val);
                    if (f != null && f.exists()) return f;

                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
    }

    /**
     * Extracts the media File from an FMessage / MediaData / MediaState object.
     */
    private File extractFileFromFMessage(Object obj) {
        if (obj == null) return null;
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    Object val = field.get(obj);
                    if (val instanceof File) {
                        File f = (File) val;
                        if (f.exists() && f.length() > 0) return f;
                    } else if (val != null && !field.getType().isPrimitive() && !field.getType().getName().startsWith("java.lang.")) {
                        for (Field inner : val.getClass().getDeclaredFields()) {
                            inner.setAccessible(true);
                            if (inner.getType() == File.class) {
                                File f = (File) inner.get(val);
                                if (f != null && f.exists() && f.length() > 0) return f;
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private boolean isFMessageObject(Object obj) {
        if (obj == null) return false;
        String name = obj.getClass().getName();
        return name.contains("FMessage") || name.contains("fmessage") || name.startsWith("X.");
    }

    /**
     * Saves the media File into the device Gallery (Pictures/WAEX or Movies/WAEX).
     */
    private void saveMediaFile(Context ctx, File srcFile) {
        try {
            String name = srcFile.getName();
            boolean isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".3gp") || name.endsWith(".mov");
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String ext = isVideo ? ".mp4" : ".jpg";
            String outName = "WAEX_VO_" + timestamp + ext;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, outName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, isVideo ? "video/mp4" : "image/jpeg");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, isVideo ? Environment.DIRECTORY_MOVIES + "/WAEX" : Environment.DIRECTORY_PICTURES + "/WAEX");

                Uri collection = isVideo ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                Uri itemUri = ctx.getContentResolver().insert(collection, values);
                if (itemUri != null) {
                    try (InputStream in = new FileInputStream(srcFile);
                         OutputStream out = ctx.getContentResolver().openOutputStream(itemUri)) {
                        if (out != null) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = in.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                            showToast(ctx, "Saved to Gallery (WAEX)");
                            return;
                        }
                    }
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(isVideo ? Environment.DIRECTORY_MOVIES : Environment.DIRECTORY_PICTURES), "WAEX");
                if (!dir.exists()) dir.mkdirs();
                File dest = new File(dir, outName);

                try (InputStream in = new FileInputStream(srcFile);
                     OutputStream out = new FileOutputStream(dest)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    showToast(ctx, "Saved to: " + dest.getAbsolutePath());
                    return;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " saveMediaFile error: " + t.getMessage());
            showToast(ctx, "Error saving media: " + t.getMessage());
        }
    }

    private void showToast(Context ctx, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    @NonNull
    @Override
    public String getName() {
        return "Download View Once";
    }
}
