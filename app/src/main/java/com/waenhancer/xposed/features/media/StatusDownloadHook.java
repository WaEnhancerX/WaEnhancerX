package com.waenhancer.xposed.features.media;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.homescreen.MenuIconLoader;
import com.waenhancer.xposed.utils.ActivityTracker;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
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
import org.luckypray.dexkit.result.UsingFieldData;

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
    private volatile Field statusListField;
    private volatile Field currentStatusIndexField;

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
            statusListField = findStatusListField(statusContactFragClass);
            currentStatusIndexField = findCurrentStatusIndexField(bridge, statusBaseFragClass);

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
                            XposedBridge.log(TAG + " Error handling status menu hook: " + t.getClass().getSimpleName());
                        }
                    }
                });

            } else if (statusContactFragClass != null) {
                // Fallback Strategy 3: Hook fragment menu methods directly
                for (Method m : statusContactFragClass.getDeclaredMethods()) {
                    Class<?>[] methodParameters = m.getParameterTypes();
                    if (methodParameters.length >= 1 && Menu.class.isAssignableFrom(methodParameters[0])) {
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
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error setting up status playback hook: " + t.getClass().getSimpleName());
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

        String visibleTextStatus = findVisibleTextStatus(sourceObject);
        boolean isTextStatus = visibleTextStatus != null;
        MenuItem item = menu.add(
                Menu.NONE,
                MENU_ID_STATUS_DOWNLOAD,
                Menu.NONE,
                isTextStatus ? UiText.get(R.string.waex_ui_copy_text_b0ac9cea, "Copy text") : UiText.get(R.string.waex_ui_download_d6eafe82, "Download")
        );
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        Drawable icon = MenuIconLoader.load(
                context,
                isTextStatus ? "ic_waex_copy" : "ic_waex_download"
        );
        if (icon != null) {
            item.setIcon(icon);
        }

        item.setOnMenuItemClickListener(menuItem -> {
            String currentTextStatus = findVisibleTextStatus(sourceObject);
            if (currentTextStatus != null) {
                copyToClipboard(context, currentTextStatus);
                return true;
            }

            CompletableFuture.runAsync(() -> {
                try {
                    downloadCurrentStatus(sourceObject);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " Download error: " + t.getClass().getSimpleName());
                    showToast(context, UiText.get(R.string.waex_ui_failed_to_download_status_19426d76, "Failed to download status: ") + t.getMessage());
                }
            });
            return true;
        });
    }

    private void downloadCurrentStatus(Object sourceObject) {
        Object currentStatusItem = resolveCurrentStatusItem(sourceObject);
        File mediaFile = extractMediaFileDeep(currentStatusItem);

        if (isMediaFileReady(mediaFile)) {
            saveMediaFile(context, mediaFile);
        } else {
            showToast(
                    context,
                    UiText.get(R.string.waex_ui_please_wait_until_the_status_finishes_0ef2b7ff, "Please wait until the status finishes downloading, then try again"),
                    Toast.LENGTH_LONG
            );
        }
    }

    private boolean isMediaFileReady(File mediaFile) {
        if (mediaFile == null || !mediaFile.isFile()) return false;

        String name = mediaFile.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".tmp") || name.endsWith(".part") || name.endsWith(".download")) {
            return false;
        }

        long initialLength = mediaFile.length();
        long initialModified = mediaFile.lastModified();
        if (initialLength <= 0) return false;

        try {
            Thread.sleep(350L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }

        return mediaFile.isFile()
                && mediaFile.length() == initialLength
                && mediaFile.lastModified() == initialModified;
    }

    private String findVisibleTextStatus(Object sourceObject) {
        View sourceView = resolveSourceView(sourceObject);
        String text = findTextStatusInView(sourceView);
        if (text != null) return text;

        Activity activity = ActivityTracker.getCurrentActivity();
        if (activity == null || activity.getWindow() == null) return null;
        return findTextStatusInView(activity.getWindow().getDecorView());
    }

    private View resolveSourceView(Object sourceObject) {
        if (sourceObject instanceof View) return (View) sourceObject;
        if (sourceObject == null) return null;

        try {
            Method getView = sourceObject.getClass().getMethod("getView");
            if (View.class.isAssignableFrom(getView.getReturnType())) {
                Object view = getView.invoke(sourceObject);
                if (view instanceof View) return (View) view;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String findTextStatusInView(View view) {
        if (view == null || !view.isShown()) return null;

        if (view instanceof TextView && view.getId() != View.NO_ID) {
            try {
                String resourceName = view.getResources().getResourceEntryName(view.getId());
                if ("message_text".equals(resourceName) || "status_text".equals(resourceName)) {
                    String text = ((TextView) view).getText().toString().trim();
                    if (!text.isEmpty()) return text;
                }
            } catch (Throwable ignored) {}
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                String text = findTextStatusInView(group.getChildAt(i));
                if (text != null) return text;
            }
        }
        return null;
    }

    private Object resolveCurrentStatusItem(Object fragmentInstance) {
        if (fragmentInstance == null) return null;
        try {
            List<?> statusList = readStatusList(fragmentInstance);
            if (statusList == null || statusList.isEmpty()) return null;

            int currentIndex = readCurrentStatusIndex(fragmentInstance, statusList.size());
            if (currentIndex >= 0 && currentIndex < statusList.size()) {
                return statusList.get(currentIndex);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error resolving current status item: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private List<?> readStatusList(Object fragmentInstance) {
        try {
            Field field = statusListField;
            if (field != null && field.getDeclaringClass().isInstance(fragmentInstance)) {
                Object value = field.get(fragmentInstance);
                if (value instanceof List && !((List<?>) value).isEmpty()) {
                    return (List<?>) value;
                }
            }
        } catch (Throwable ignored) {}

        Class<?> type = fragmentInstance.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(fragmentInstance);
                    if (value instanceof List && !((List<?>) value).isEmpty()) {
                        return (List<?>) value;
                    }
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private int readCurrentStatusIndex(Object fragmentInstance, int itemCount) {
        Field field = currentStatusIndexField;
        if (field != null && field.getDeclaringClass().isInstance(fragmentInstance)) {
            try {
                int index = field.getInt(fragmentInstance);
                if (index >= 0 && index < itemCount) return index;
            } catch (Throwable ignored) {}
        }

        for (String fieldName : new String[]{"A02", "A00"}) {
            try {
                Field fallback = XposedHelpers.findFieldIfExists(fragmentInstance.getClass(), fieldName);
                if (fallback != null && fallback.getType() == int.class) {
                    int index = fallback.getInt(fragmentInstance);
                    if (index >= 0 && index < itemCount) return index;
                }
            } catch (Throwable ignored) {}
        }
        return itemCount == 1 ? 0 : -1;
    }

    private Field findStatusListField(Class<?> fragmentClass) {
        for (Class<?> type = fragmentClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (List.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    return field;
                }
            }
        }
        return null;
    }

    private Field findCurrentStatusIndexField(
            org.luckypray.dexkit.DexKitBridge bridge,
            Class<?> statusBaseFragmentClass
    ) {
        if (bridge == null) return null;
        try {
            MethodDataList methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("playbackFragment/setPageActive no-messages ")));
            for (org.luckypray.dexkit.result.MethodData method : methods) {
                for (UsingFieldData use : method.getUsingFields()) {
                    org.luckypray.dexkit.result.FieldData fieldData = use.getField();
                    if (!"int".equals(fieldData.getTypeName())) continue;
                    Field field = fieldData.getFieldInstance(classLoader);
                    if (statusBaseFragmentClass == null
                            || field.getDeclaringClass().isAssignableFrom(statusBaseFragmentClass)
                            || statusBaseFragmentClass.isAssignableFrom(field.getDeclaringClass())) {
                        field.setAccessible(true);
                        return field;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error resolving current status index: " + t.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Recursively traverses object graphs to locate the underlying File instance (e.g. within MediaData / FMessage / FStatus).
     */
    private File extractMediaFileDeep(Object root) {
        if (root == null) return null;
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<File> candidates = new ArrayList<>();
        collectFilesRecursive(root, visited, candidates, 0, 8);

        File best = null;
        for (File candidate : candidates) {
            try {
                StatusMediaExporter.inspect(candidate);
                if (best == null || candidate.length() > best.length()) {
                    best = candidate;
                }
            } catch (Throwable ignored) {}
        }
        return best;
    }

    private void collectFilesRecursive(
            Object obj,
            Set<Object> visited,
            List<File> candidates,
            int depth,
            int maxDepth
    ) {
        if (obj == null || depth > maxDepth || !visited.add(obj)) return;

        Class<?> clazz = obj.getClass();
        String className = clazz.getName();

        if (className.startsWith("java.lang.") && !(obj instanceof String)) return;
        if (className.startsWith("android.graphics.") || className.startsWith("android.view.ViewGroup$LayoutParams")) return;

        if (obj instanceof File) {
            File f = (File) obj;
            if (f.isFile() && f.length() > 0) candidates.add(f);
            return;
        }

        if (obj instanceof Uri) {
            Uri uri = (Uri) obj;
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path != null) {
                    File f = new File(path);
                    if (f.isFile() && f.length() > 0) candidates.add(f);
                }
            }
            return;
        }

        if (obj instanceof String) {
            String str = (String) obj;
            if (str.startsWith("/") || str.startsWith("file://")) {
                String path = str.startsWith("file://") ? str.substring(7) : str;
                File f = new File(path);
                if (f.isFile() && f.length() > 0) candidates.add(f);
            }
            return;
        }

        if (obj instanceof Iterable) {
            for (Object item : (Iterable<?>) obj) {
                collectFilesRecursive(item, visited, candidates, depth + 1, maxDepth);
            }
            return;
        }

        if (obj instanceof Object[]) {
            for (Object item : (Object[]) obj) {
                collectFilesRecursive(item, visited, candidates, depth + 1, maxDepth);
            }
            return;
        }

        if (obj instanceof Map) {
            for (Object item : ((Map<?, ?>) obj).values()) {
                collectFilesRecursive(item, visited, candidates, depth + 1, maxDepth);
            }
            return;
        }

        // Check 0-arg methods returning File
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterTypes().length == 0 && m.getReturnType() == File.class) {
                    m.setAccessible(true);
                    try {
                        File f = (File) m.invoke(obj);
                        if (f != null && f.isFile() && f.length() > 0) candidates.add(f);
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
                        collectFilesRecursive(val, visited, candidates, depth + 1, maxDepth);
                    }
                } catch (Throwable ignored) {}
            }
            curr = curr.getSuperclass();
        }
    }

    private void saveMediaFile(Context ctx, File srcFile) {
        try {
            StatusMediaExporter.export(ctx, srcFile);
            showToast(ctx, UiText.get(R.string.waex_ui_status_saved_to_gallery_waex_9f345669,
                    "Status saved to Gallery (WAEX)"));
        } catch (Exception failure) {
            XposedBridge.log(TAG + " status export failed: " + failure.getClass().getSimpleName());
            showToast(ctx, UiText.get(R.string.waex_ui_failed_to_download_status_19426d76,
                    "Failed to download status: ") + failure.getClass().getSimpleName());
        }
    }

    private void copyToClipboard(Context ctx, String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText(UiText.get(R.string.waex_ui_status_text_b35d0184, "Status Text"), text);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(ctx, UiText.get(R.string.waex_ui_copied_status_text_to_clipboard_b81c6089, "Copied status text to clipboard"), Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {}
        });
    }

    private void showToast(Context ctx, String msg) {
        showToast(ctx, msg, Toast.LENGTH_SHORT);
    }

    private void showToast(Context ctx, String msg, int duration) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(ctx, msg, duration).show();
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
