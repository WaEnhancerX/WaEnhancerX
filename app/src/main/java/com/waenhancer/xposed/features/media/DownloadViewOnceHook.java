package com.waenhancer.xposed.features.media;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.wa.WaMessage;
import com.waenhancer.xposed.core.wa.WaTargets;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.homescreen.MenuIconLoader;
import com.waenhancer.xposed.utils.HookErrors;
import com.waenhancer.xposed.utils.UiThread;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
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

/**
 * Download View Once Media:
 * Adds a download action button to ViewOnceViewerActivity and MediaViewFragment toolbars
 * allowing saving View Once photos and videos directly to the device Gallery.
 */
public class DownloadViewOnceHook extends BaseFeature {

    private static final String TAG = "[WAEX][DownloadViewOnce]";
    private static final int MENU_ID_DOWNLOAD = 0x7EAD0003;

    private static volatile Object sCachedMessageStoreInstance;
    private static volatile Method sGetMessageFromKeyMethod;

    public DownloadViewOnceHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        if (!isEnabled("downloadviewonce", false)) {
            return;
        }
        WaMessage.init(context, classLoader);
        initCachedMessageStoreHook();
        hookViewOnceViewerActivity();
        hookMediaViewMenu();
    }

    private void initCachedMessageStoreHook() {
        try {
            Method getMessageKey = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "cached_message_store_get_message_key", (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create().matcher(
                                MethodMatcher.create().usingStrings("CachedMessageStore/getMessage/key")
                        )).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    });

            if (getMessageKey != null) {
                sGetMessageFromKeyMethod = getMessageKey;
                Class<?> storeClass = getMessageKey.getDeclaringClass();
                for (Constructor<?> ctor : storeClass.getDeclaredConstructors()) {
                    XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            sCachedMessageStoreInstance = param.thisObject;
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " initCachedMessageStoreHook error: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Hook ViewOnceViewerActivity: intercepts both onCreateOptionsMenu and onPrepareOptionsMenu
     * across the activity hierarchy so that the Download button remains visible across full-screen
     * toggles and menu invalidations.
     */
    private void hookViewOnceViewerActivity() {
        try {
            Class<?> viewOnceClass = resolveViewOnceViewerActivityClass();
            if (viewOnceClass == null) {
                XposedBridge.log(TAG + " ViewOnceViewerActivity class not found");
                return;
            }

            XC_MethodHook menuHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isEnabled("downloadviewonce", false) || !(param.thisObject instanceof Activity)) return;
                    // Hooks may sit on shared base classes; only act on the view-once viewer itself.
                    if (!viewOnceClass.isInstance(param.thisObject)) return;

                    Menu menu = null;
                    if (param.args.length > 0 && param.args[0] instanceof Menu) {
                        menu = (Menu) param.args[0];
                    }
                    if (menu == null) return;

                    ensureDownloadMenuItem((Activity) param.thisObject, menu);
                }
            };

            for (Class<?> curr = viewOnceClass; curr != null && curr != Activity.class; curr = curr.getSuperclass()) {
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

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ViewOnceViewerActivity: " + t.getClass().getSimpleName());
        }
    }

    private void ensureDownloadMenuItem(Activity activity, Menu menu) {
        MenuItem existing = menu.findItem(MENU_ID_DOWNLOAD);
        if (existing != null) {
            existing.setVisible(true);
            existing.setEnabled(true);
            return;
        }

        MenuItem item = menu.add(Menu.NONE, MENU_ID_DOWNLOAD, Menu.NONE, UiText.get(R.string.waex_ui_download, "Download"));
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        Drawable icon = MenuIconLoader.load(activity, "ic_waex_download");
        if (icon != null) {
            item.setIcon(icon);
        } else {
            item.setIcon(android.R.drawable.stat_sys_download_done);
        }

        item.setOnMenuItemClickListener(menuItem -> {
            CompletableFuture.runAsync(() -> {
                try {
                    File mediaFile = extractMediaFileFromActivity(activity);
                    if (mediaFile == null || !mediaFile.exists()) {
                        UiThread.toast(activity, "Media not cached or available yet");
                        return;
                    }
                    saveMediaFile(activity, mediaFile);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " Error saving view once from Activity: " + t.getClass().getSimpleName());
                    UiThread.toast(activity, "Failed to save: " + t.getMessage());
                }
            });
            return true;
        });
    }

    /**
     * Hook MediaView menu methods using ic_viewonce number matcher and MediaViewFragment hooks.
     */
    private void hookMediaViewMenu() {
        try {
            // ic_viewonce + MenuItem.setShowAsAction + Menu parameter; cached per WhatsApp build.
            Method menuMethod = WaTargets.viewOnceMediaMenu(context, classLoader);

            // No generic MediaViewFragment fallback: it would add the icon to every media screen.
            if (menuMethod != null) {
                XposedBridge.hookMethod(menuMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled("downloadviewonce", false)) return;

                        Menu menu = null;
                        Object fmessageObj = null;

                        for (Object arg : param.args) {
                            if (arg instanceof Menu) menu = (Menu) arg;
                            else if (arg != null && fmessageObj == null && WaMessage.isMessage(arg)) fmessageObj = arg;
                        }
                        if (fmessageObj == null) {
                            for (Object arg : param.args) {
                                if (arg != null && !(arg instanceof Menu) && isFMessageObject(arg)) {
                                    fmessageObj = arg;
                                    break;
                                }
                            }
                        }

                        if (menu == null) return;
                        // This builder also runs for ordinary media; offer Download only for
                        // view-once messages (when the media type is known on this build).
                        if (fmessageObj != null && WaMessage.isMessage(fmessageObj) && WaMessage.hasMediaType()
                                && !WaMessage.isViewOnceType(WaMessage.mediaType(fmessageObj))) {
                            menu.removeItem(MENU_ID_DOWNLOAD);
                            return;
                        }

                        MenuItem existing = menu.findItem(MENU_ID_DOWNLOAD);
                        if (existing != null) {
                            existing.setVisible(true);
                            existing.setEnabled(true);
                            return;
                        }

                        final Object targetMessage = fmessageObj;
                        MenuItem item = menu.add(Menu.NONE, MENU_ID_DOWNLOAD, Menu.NONE, UiText.get(R.string.waex_ui_download, "Download"));
                        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

                        Drawable icon = MenuIconLoader.load(context, "ic_waex_download");
                        if (icon != null) {
                            item.setIcon(icon);
                        } else {
                            item.setIcon(android.R.drawable.stat_sys_download_done);
                        }

                        item.setOnMenuItemClickListener(menuItem -> {
                            CompletableFuture.runAsync(() -> {
                                try {
                                    File mediaFile = null;
                                    if (targetMessage != null) {
                                        mediaFile = extractFileFromFMessage(targetMessage);
                                    }
                                    if (mediaFile != null && mediaFile.exists()) {
                                        saveMediaFile(context, mediaFile);
                                        return;
                                    }
                                    UiThread.toast(context, "Media not available yet");
                                } catch (Throwable t) {
                                    XposedBridge.log(TAG + " Error saving media: " + t.getClass().getSimpleName());
                                    UiThread.toast(context, "Failed to save: " + t.getMessage());
                                }
                            });
                            return true;
                        });
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error finding media viewer menu method: " + t.getClass().getSimpleName());
        }
    }

    private Class<?> resolveViewOnceViewerActivityClass() {
        String[] candidates = {
                "com.whatsapp.viewonce.ui.messaging.ViewOnceViewerActivity",
                "com.whatsapp.messaging.ViewOnceViewerActivity"
        };
        for (String name : candidates) {
            Class<?> c = XposedHelpers.findClassIfExists(name, classLoader);
            if (c != null) return c;
        }

        try {
            return DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "waex_view_once_viewer_activity",
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
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Extracts the media File from ViewOnceViewerActivity using a multi-tiered pipeline:
     * 1. Extract FMessage.Key from Activity -> query CachedMessageStore -> read FMessage.mediaFile
     * 2. Extract key_id from FMessage.Key -> query MessageStore.db -> read message_media.file_path
     * 3. Traverse attached Fragments (e.g. MediaViewFragment or player fragments) for File / FMessage
     * 4. Deep reflective object graph traversal across Activity fields
     */
    private File extractMediaFileFromActivity(Activity activity) {
        if (activity == null) return null;

        // Tier 1 & 2: Extract FMessage.Key object and resolve message
        Object messageKeyObj = extractMessageKeyObject(activity);
        if (messageKeyObj != null) {
            // Tier 1: Try in-memory CachedMessageStore
            File fileFromCache = resolveFileFromKeyViaCachedStore(activity, messageKeyObj);
            if (fileFromCache != null && fileFromCache.isFile() && fileFromCache.length() > 0) {
                return fileFromCache;
            }

            // Tier 2: Try SQLite MessageStore database
            String keyId = extractKeyIdString(messageKeyObj);
            if (keyId != null && !keyId.isEmpty()) {
                File fileFromDb = resolveFileFromDatabase(activity, keyId);
                if (fileFromDb != null && fileFromDb.isFile() && fileFromDb.length() > 0) {
                    return fileFromDb;
                }
            }
        }

        // Tier 3: Inspect attached Fragments in FragmentActivity
        if (activity instanceof FragmentActivity) {
            try {
                List<Fragment> fragments = ((FragmentActivity) activity).getSupportFragmentManager().getFragments();
                for (Fragment fragment : fragments) {
                    if (fragment == null) continue;
                    File f = MediaFileLocator.find(fragment, 5, MediaFileLocator.Mode.SHALLOW, null);
                    if (f != null && f.isFile() && f.length() > 0) return f;
                }
            } catch (Throwable ignored) { HookErrors.swallowed("DownloadViewOnceHook", ignored); }
        }

        // Tier 4: Deep field search on Activity instance
        return MediaFileLocator.find(activity, 6, MediaFileLocator.Mode.SHALLOW, null);
    }

    private Object extractMessageKeyObject(Activity activity) {
        // Typed: the activity holds the message key in a field of the key type.
        Class<?> keyClass = WaMessage.keyClass();
        if (keyClass != null) {
            for (Class<?> curr = activity.getClass(); curr != null && curr != Activity.class; curr = curr.getSuperclass()) {
                for (Field field : curr.getDeclaredFields()) {
                    if (!keyClass.isAssignableFrom(field.getType())) continue;
                    try {
                        field.setAccessible(true);
                        Object key = field.get(activity);
                        if (key != null) return key;
                    } catch (Throwable ignored) { HookErrors.swallowed("DownloadViewOnceHook", ignored); }
                }
            }
        }
        for (Class<?> curr = activity.getClass(); curr != null && curr != Activity.class; curr = curr.getSuperclass()) {
            for (Field field : curr.getDeclaredFields()) {
                field.setAccessible(true);
                try {
                    Object val = field.get(activity);
                    if (val == null) continue;
                    String typeName = val.getClass().getName();
                    String strVal = val.toString();
                    if (strVal.startsWith("Key(") || typeName.endsWith("Key") || typeName.contains(".FMessage$Key")) {
                        return val;
                    }
                    // Check if object has id (String) and chatJid fields
                    Field idField = findKeyIdField(val.getClass());
                    if (idField != null) return val;
                } catch (Throwable ignored) { HookErrors.swallowed("DownloadViewOnceHook", ignored); }
            }
        }
        return null;
    }

    private Field findKeyIdField(Class<?> keyClass) {
        for (Field f : keyClass.getDeclaredFields()) {
            if (f.getType() == String.class) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }

    private String extractKeyIdString(Object messageKeyObj) {
        if (messageKeyObj == null) return null;
        String typedId = WaMessage.id(messageKeyObj);
        if (typedId != null && !typedId.isEmpty()) return typedId;
        try {
            Field idField = findKeyIdField(messageKeyObj.getClass());
            if (idField != null) {
                Object val = idField.get(messageKeyObj);
                if (val instanceof String && !((String) val).isEmpty()) {
                    return (String) val;
                }
            }
            // Fallback: parse from toString: Key(id=..., isFromMe=...)
            String str = messageKeyObj.toString();
            if (str.contains("id=")) {
                int start = str.indexOf("id=") + 3;
                int end = str.indexOf(",", start);
                if (end < 0) end = str.indexOf(")", start);
                if (end > start) {
                    return str.substring(start, end).trim();
                }
            }
        } catch (Throwable ignored) { HookErrors.swallowed("DownloadViewOnceHook", ignored); }
        return null;
    }

    private File resolveFileFromKeyViaCachedStore(Activity activity, Object messageKeyObj) {
        try {
            Object storeInstance = sCachedMessageStoreInstance;
            Method getMessageMethod = sGetMessageFromKeyMethod;

            // If singleton was not captured by constructor hook, inspect activity fields for CachedMessageStore provider
            if (storeInstance == null || getMessageMethod == null) {
                for (Class<?> curr = activity.getClass(); curr != null && curr != Activity.class; curr = curr.getSuperclass()) {
                    for (Field f : curr.getDeclaredFields()) {
                        f.setAccessible(true);
                        Object obj = f.get(activity);
                        if (obj == null) continue;
                        for (Method m : obj.getClass().getDeclaredMethods()) {
                            if (m.getParameterTypes().length == 0 && !m.getReturnType().isPrimitive()) {
                                m.setAccessible(true);
                                Object inner = m.invoke(obj);
                                if (inner != null && getMessageMethod != null
                                        && getMessageMethod.getDeclaringClass().isAssignableFrom(inner.getClass())) {
                                    storeInstance = inner;
                                    break;
                                }
                            }
                        }
                        if (storeInstance != null) break;
                    }
                    if (storeInstance != null) break;
                }
            }

            if (storeInstance != null && getMessageMethod != null) {
                getMessageMethod.setAccessible(true);
                Object fMessage = getMessageMethod.invoke(storeInstance, messageKeyObj);
                if (fMessage != null) {
                    return extractFileFromFMessage(fMessage);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " resolveFileFromKeyViaCachedStore error: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private File resolveFileFromDatabase(Context ctx, String keyId) {
        try {
            long rowId = MessageStore.getInstance(ctx).getIdFromKey(keyId);
            if (rowId <= 0) return null;

            String mediaPath = MessageStore.getInstance(ctx).getMediaFromId(rowId);
            if (mediaPath == null || mediaPath.isEmpty()) return null;

            File direct = new File(mediaPath);
            if (direct.isFile() && direct.length() > 0) return direct;

            File accountDir = MessageStore.findAccountDataDir(ctx);
            File[] candidates = {
                    new File(ctx.getFilesDir(), mediaPath),
                    new File(accountDir, mediaPath),
                    new File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/" + mediaPath),
                    new File(Environment.getExternalStorageDirectory(), "WhatsApp/" + mediaPath),
                    new File(ctx.getCacheDir(), mediaPath)
            };
            for (File c : candidates) {
                if (c.isFile() && c.length() > 0) return c;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " resolveFileFromDatabase error: " + t.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * Extracts the media File from an FMessage / MediaData / MediaState object.
     */
    private File extractFileFromFMessage(Object fmessage) {
        if (fmessage == null) return null;
        return MediaFileLocator.find(fmessage, 5, MediaFileLocator.Mode.SHALLOW, null);
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
            GalleryMediaExporter.export(ctx, srcFile);
            UiThread.toast(ctx, "Media saved (WAEX)");
        } catch (Exception failure) {
            XposedBridge.log(TAG + " Export failed: " + failure.getClass().getSimpleName());
            UiThread.toast(ctx, "Unable to save this media file");
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Download View Once";
    }
}
