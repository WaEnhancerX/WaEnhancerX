package com.waenhancer.xposed.features.media;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import com.waenhancer.xposed.utils.ActivityTracker;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import org.luckypray.dexkit.result.MethodDataList;

/**
 * Status Downloader Hook:
 * Injects a Download action into WhatsApp Status playback menus and toolbars,
 * allowing instant saving of status photos, videos, voice notes, and text updates.
 *
 * Implements multi-layered deep object graph traversal and MediaStore integration.
 */
public class StatusDownloadHook extends BaseFeature {

    private static final String TAG = "[WAEX][StatusDownload]";
    private static final int MENU_ID_STATUS_DOWNLOAD = 0x7EAD0007;

    public StatusDownloadHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Status Download";
    }

    @Override
    public void hook() throws Throwable {
        hookStatusPlaybackMenu();
    }

    private boolean isFeatureEnabled() {
        return isEnabled("status_downloader", false)
                || isEnabled("download_status", false)
                || isEnabled("downloadstatus", false);
    }

    /**
     * Finds and hooks the WhatsApp Status playback menu inflation/display method.
     */
    private void hookStatusPlaybackMenu() {
        try {
            DexSearchEngine.getInstance().initialize(context);
            var bridge = DexSearchEngine.getInstance().getBridge();

            Method menuStatusMethod = null;

            // Strategy 1: Search via resource ID of menuitem_conversations_message_contact
            int menuContactId = context.getResources().getIdentifier(
                    "menuitem_conversations_message_contact", "id", context.getPackageName()
            );

            if (bridge != null && menuContactId > 0) {
                MethodDataList mdList = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingNumbers(menuContactId)
                        ));
                if (!mdList.isEmpty()) {
                    menuStatusMethod = mdList.first().getMethodInstance(classLoader);
                }
            }

            // Strategy 2: Search via StatusPlaybackContactFragment menu handlers
            Class<?> statusContactFragClass = resolveStatusPlaybackContactFragmentClass();
            Class<?> statusBaseFragClass = resolveStatusPlaybackBaseFragmentClass();

            if (menuStatusMethod != null) {
                final Class<?> contactClass = statusContactFragClass;
                final Class<?> baseClass = statusBaseFragClass;

                XposedBridge.hookMethod(menuStatusMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isFeatureEnabled()) return;

                        try {
                            handleStatusMenuHook(param, contactClass, baseClass);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " Error handling status menu hook: " + t.getMessage());
                        }
                    }
                });

                XposedBridge.log(TAG + " Hooked status playback menu method: " + menuStatusMethod.getName());
            } else if (statusContactFragClass != null) {
                // Fallback Strategy 3: Hook fragment menu methods directly
                for (Method m : statusContactFragClass.getDeclaredMethods()) {
                    if (m.getParameterCount() >= 1 && Menu.class.isAssignableFrom(m.getParameterTypes()[0])) {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                if (!isFeatureEnabled()) return;
                                try {
                                    Menu menu = (Menu) param.args[0];
                                    if (menu != null) {
                                        injectDownloadMenuItem(menu, param.thisObject);
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                        XposedBridge.log(TAG + " Hooked status contact fragment menu method: " + m.getName());
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error setting up status playback hook: " + t.getMessage());
        }
    }

    private void handleStatusMenuHook(XC_MethodHook.MethodHookParam param, Class<?> contactClass, Class<?> baseClass) {
        Menu menu = null;
        Object targetFragment = null;

        // 1. Resolve Menu object from method arguments or instance fields
        for (Object arg : param.args) {
            if (arg instanceof Menu) {
                menu = (Menu) arg;
                break;
            }
        }

        // 2. Resolve the StatusPlaybackFragment instance
        if (param.thisObject != null) {
            if ((contactClass != null && contactClass.isInstance(param.thisObject))
                    || (baseClass != null && baseClass.isInstance(param.thisObject))) {
                targetFragment = param.thisObject;
            } else {
                Class<?> cls = param.thisObject.getClass();
                while (cls != null && cls != Object.class && targetFragment == null) {
                    for (Field f : cls.getDeclaredFields()) {
                        f.setAccessible(true);
                        try {
                            Object val = f.get(param.thisObject);
                            if (val != null) {
                                if (menu == null && val instanceof Menu) {
                                    menu = (Menu) val;
                                } else if ((contactClass != null && contactClass.isInstance(val))
                                        || (baseClass != null && baseClass.isInstance(val))) {
                                    targetFragment = val;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    cls = cls.getSuperclass();
                }
            }
        }

        final Object capturedContext = targetFragment != null ? targetFragment : param.thisObject;
        if (menu != null) {
            injectDownloadMenuItem(menu, capturedContext);
        }
    }

    private void injectDownloadMenuItem(Menu menu, Object sourceObject) {
        if (menu.findItem(MENU_ID_STATUS_DOWNLOAD) != null) return;

        MenuItem item = menu.add(Menu.NONE, MENU_ID_STATUS_DOWNLOAD, Menu.NONE, "Download");
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        Drawable icon = MenuIconLoader.load(context, "ic_waex_download");
        if (icon != null) {
            item.setIcon(icon);
        }

        item.setOnMenuItemClickListener(menuItem -> {
            CompletableFuture.runAsync(() -> {
                try {
                    downloadCurrentStatus(sourceObject);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " Download error: " + t.getMessage());
                    showToast(context, "Failed to download status: " + t.getMessage());
                }
            });
            return true;
        });
    }

    private void downloadCurrentStatus(Object sourceObject) {
        File mediaFile = null;
        String textCaption = null;

        // Strategy 1: Deep traverse the provided sourceObject (Fragment / StatusData / MenuHolder)
        if (sourceObject != null) {
            Object currentStatusItem = resolveCurrentStatusItem(sourceObject);
            if (currentStatusItem != null) {
                mediaFile = extractMediaFileDeep(currentStatusItem);
                textCaption = extractStatusText(currentStatusItem);
            }
            if (mediaFile == null) {
                mediaFile = extractMediaFileDeep(sourceObject);
            }
        }

        // Strategy 2: Deep traverse active fragments in foreground Activity
        if (mediaFile == null) {
            Activity currentAct = ActivityTracker.getCurrentActivity();
            if (currentAct != null) {
                try {
                    mediaFile = extractMediaFromActivityFragments(currentAct);
                    if (mediaFile == null) {
                        mediaFile = extractMediaFileDeep(currentAct);
                    }
                } catch (Throwable ignored) {}
            }
        }

        // Strategy 3: Check WhatsApp cached .Statuses directory for recently active status media
        if (mediaFile == null) {
            mediaFile = findRecentStatusFileFromDisk();
        }

        // Perform Save / Copy
        if (mediaFile != null && mediaFile.exists() && mediaFile.length() > 0) {
            saveMediaFile(context, mediaFile);
        } else if (textCaption != null && !textCaption.trim().isEmpty()) {
            copyToClipboard(context, textCaption);
        } else {
            showToast(context, "Status media is still loading or not cached yet");
        }
    }

    private Object resolveCurrentStatusItem(Object fragmentInstance) {
        try {
            Class<?> cls = fragmentInstance.getClass();
            List<?> statusList = null;
            int currentIndex = 0;

            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(fragmentInstance);
                        if (val instanceof List && statusList == null) {
                            List<?> list = (List<?>) val;
                            if (!list.isEmpty()) {
                                statusList = list;
                            }
                        } else if (f.getType() == int.class) {
                            int idx = f.getInt(fragmentInstance);
                            if (idx >= 0) {
                                currentIndex = idx;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                cls = cls.getSuperclass();
            }

            if (statusList != null && !statusList.isEmpty()) {
                if (currentIndex >= 0 && currentIndex < statusList.size()) {
                    return statusList.get(currentIndex);
                }
                return statusList.get(0);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error resolving current status item: " + t.getMessage());
        }
        return null;
    }

    private File extractMediaFromActivityFragments(Activity act) {
        try {
            Method getSupportFM = null;
            try {
                getSupportFM = act.getClass().getMethod("getSupportFragmentManager");
            } catch (Throwable ignored) {}

            if (getSupportFM != null) {
                Object fm = getSupportFM.invoke(act);
                if (fm != null) {
                    Method getFragments = fm.getClass().getMethod("getFragments");
                    List<?> fragments = (List<?>) getFragments.invoke(fm);
                    if (fragments != null) {
                        for (Object frag : fragments) {
                            if (frag == null) continue;
                            File f = extractMediaFileDeep(frag);
                            if (f != null && f.exists() && f.length() > 0) {
                                return f;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Recursively traverses object graphs to locate the underlying File instance (e.g. within MediaData / FMessage / FStatus).
     */
    private File extractMediaFileDeep(Object root) {
        if (root == null) return null;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        return findFileRecursive(root, visited, 0, 8);
    }

    private File findFileRecursive(Object obj, Set<Object> visited, int depth, int maxDepth) {
        if (obj == null || depth > maxDepth) return null;
        if (!visited.add(obj)) return null;

        Class<?> clazz = obj.getClass();
        String className = clazz.getName();

        if (className.startsWith("java.lang.") && !(obj instanceof String)) return null;
        if (className.startsWith("android.graphics.") || className.startsWith("android.view.ViewGroup$LayoutParams")) return null;

        if (obj instanceof File) {
            File f = (File) obj;
            if (f.exists() && f.length() > 0) return f;
            return null;
        }

        if (obj instanceof Uri) {
            Uri uri = (Uri) obj;
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path != null) {
                    File f = new File(path);
                    if (f.exists() && f.length() > 0) return f;
                }
            }
        }

        if (obj instanceof String) {
            String str = (String) obj;
            if (str.startsWith("/") || str.startsWith("file://")) {
                String path = str.startsWith("file://") ? str.substring(7) : str;
                File f = new File(path);
                if (f.exists() && f.length() > 0) return f;
            }
            return null;
        }

        if (obj instanceof Iterable) {
            for (Object item : (Iterable<?>) obj) {
                File res = findFileRecursive(item, visited, depth + 1, maxDepth);
                if (res != null) return res;
            }
            return null;
        }

        if (obj instanceof Object[]) {
            for (Object item : (Object[]) obj) {
                File res = findFileRecursive(item, visited, depth + 1, maxDepth);
                if (res != null) return res;
            }
            return null;
        }

        if (obj instanceof Map) {
            for (Object item : ((Map<?, ?>) obj).values()) {
                File res = findFileRecursive(item, visited, depth + 1, maxDepth);
                if (res != null) return res;
            }
            return null;
        }

        // Check 0-arg methods returning File
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() == File.class) {
                    m.setAccessible(true);
                    try {
                        File f = (File) m.invoke(obj);
                        if (f != null && f.exists() && f.length() > 0) return f;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        // Traverse declared fields
        Class<?> curr = clazz;
        while (curr != null && curr != Object.class) {
            Field[] fields;
            try {
                fields = curr.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : fields) {
                if (Modifier.isStatic(f.getModifiers()) && !f.getType().getName().startsWith("com.whatsapp")) continue;
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    if (val != null) {
                        File res = findFileRecursive(val, visited, depth + 1, maxDepth);
                        if (res != null) return res;
                    }
                } catch (Throwable ignored) {}
            }
            curr = curr.getSuperclass();
        }

        return null;
    }

    /**
     * Checks WhatsApp's .Statuses directories on disk for the most recently cached media file.
     */
    private File findRecentStatusFileFromDisk() {
        try {
            List<File> statusDirs = new ArrayList<>();
            File ext = Environment.getExternalStorageDirectory();

            statusDirs.add(new File(ext, "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"));
            statusDirs.add(new File(ext, "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"));
            statusDirs.add(new File(ext, "WhatsApp/Media/.Statuses"));
            statusDirs.add(new File(context.getCacheDir(), "status"));

            long now = System.currentTimeMillis();
            File newest = null;
            long newestTime = 0;

            for (File dir : statusDirs) {
                if (dir != null && dir.exists() && dir.isDirectory()) {
                    File[] files = dir.listFiles();
                    if (files != null) {
                        for (File f : files) {
                            if (f.isFile() && f.length() > 0 && !f.getName().endsWith(".nomedia")) {
                                long mod = f.lastModified();
                                // Checked within the last 15 minutes
                                if (now - mod < 900_000 && mod > newestTime) {
                                    newestTime = mod;
                                    newest = f;
                                }
                            }
                        }
                    }
                }
            }
            return newest;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error searching .Statuses disk directory: " + t.getMessage());
        }
        return null;
    }

    private String extractStatusText(Object obj) {
        if (obj == null) return null;
        Class<?> current = obj.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    Object val = field.get(obj);
                    if (val instanceof String) {
                        String s = (String) val;
                        if (s.length() > 0 && !s.contains("http://") && !s.contains("https://") && !s.contains("@")) {
                            return s;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void saveMediaFile(Context ctx, File srcFile) {
        try {
            String name = srcFile.getName().toLowerCase(Locale.ROOT);
            boolean isVideo = name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".3gp") || name.endsWith(".mov");
            boolean isAudio = name.endsWith(".opus") || name.endsWith(".ogg") || name.endsWith(".mp3") || name.endsWith(".m4a") || name.endsWith(".aac");

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            String ext;
            String mimeType;
            String relativeDir;

            if (isVideo) {
                ext = ".mp4";
                mimeType = "video/mp4";
                relativeDir = Environment.DIRECTORY_MOVIES + "/WAEX";
            } else if (isAudio) {
                ext = ".opus";
                mimeType = "audio/ogg";
                relativeDir = Environment.DIRECTORY_MUSIC + "/WAEX";
            } else {
                ext = ".jpg";
                mimeType = "image/jpeg";
                relativeDir = Environment.DIRECTORY_PICTURES + "/WAEX";
            }

            String outName = "WAEX_STATUS_" + timestamp + ext;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, outName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir);

                Uri collection;
                if (isVideo) {
                    collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
                } else if (isAudio) {
                    collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                } else {
                    collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                }

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
                            showToast(ctx, "Status saved to Gallery (WAEX)");
                            return;
                        }
                    }
                }
            } else {
                File baseDir = Environment.getExternalStoragePublicDirectory(
                        isVideo ? Environment.DIRECTORY_MOVIES : (isAudio ? Environment.DIRECTORY_MUSIC : Environment.DIRECTORY_PICTURES)
                );
                File dir = new File(baseDir, "WAEX");
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
            showToast(ctx, "Error saving status media: " + t.getMessage());
        }
    }

    private void copyToClipboard(Context ctx, String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("Status Text", text);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(ctx, "Copied status text to clipboard", Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {}
        });
    }

    private void showToast(Context ctx, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {}
        });
    }

    private Class<?> resolveStatusPlaybackContactFragmentClass() {
        try {
            return DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_status_playback_contact_fragment",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .className("StatusPlaybackContactFragment", StringMatchType.EndsWith))
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );
        } catch (Throwable ignored) {}
        return XposedHelpers.findClassIfExists("com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment", classLoader);
    }

    private Class<?> resolveStatusPlaybackBaseFragmentClass() {
        try {
            return DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_status_playback_base_fragment",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .className("StatusPlaybackBaseFragment", StringMatchType.EndsWith))
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );
        } catch (Throwable ignored) {}
        return XposedHelpers.findClassIfExists("com.whatsapp.status.playback.fragment.StatusPlaybackBaseFragment", classLoader);
    }
}
