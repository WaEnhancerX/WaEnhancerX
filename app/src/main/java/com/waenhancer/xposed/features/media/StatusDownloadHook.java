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
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.homescreen.MenuIconLoader;
import com.waenhancer.xposed.features.privacy.BlueOnReplyHook;
import com.waenhancer.xposed.utils.ActivityTracker;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
import android.os.Build;
import android.util.Log;
import java.lang.reflect.Proxy;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

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
    private static final int MENU_ID_STATUS_SEEN = 0x7EAD0008;
    private static final String PREF_SAVE_DUPLICATES = "status_download_save_duplicates";
    private static final String PREF_SAVED_IDENTITIES = "status_download_saved_identities";
    private static final Object SAVED_IDENTITIES_LOCK = new Object();
    private volatile Field statusListField;
    private volatile Field currentStatusIndexField;
    private volatile Object latestStatusFragment;
    private final Set<View> watchedStatusRoots = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> watchedStatusPagers = Collections.newSetFromMap(new WeakHashMap<>());
    // A reply bar may survive forward and backward page changes without another layout
    // callback. Keep its live playback source, not the status that happened to be active
    // during the last refresh, and resolve the item only when the user taps the control.
    private final Map<View, Object> statusSeenBindings =
            Collections.synchronizedMap(new WeakHashMap<>());

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
            try {
                statusListField = findStatusListField(statusContactFragClass);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " findStatusListField failed: " + t);
            }
            try {
                currentStatusIndexField = findCurrentStatusIndexField(bridge, statusBaseFragClass);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " findCurrentStatusIndexField failed: " + t);
            }

            try {
                hookMenuRefreshMethods(statusContactFragClass);
                hookMenuRefreshMethods(statusBaseFragClass);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " hookMenuRefreshMethods failed: " + t);
            }

            try {
                hookStatusSeenIcon(statusContactFragClass);
                hookStatusSeenIcon(statusBaseFragClass);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " hookStatusSeenIcon failed: " + t);
            }

            try {
                hookStatusSeenIconActivity();
            } catch (Throwable t) {
                XposedBridge.log(TAG + " hookStatusSeenIconActivity failed: " + t);
            }

            if (menuStatusMethod != null) {
                final Class<?> contactClass = statusContactFragClass;
                final Class<?> baseClass = statusBaseFragClass;

                try {
                    XposedBridge.hookMethod(menuStatusMethod, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (!isFeatureEnabled() && !BlueOnReplyHook.isManualStatusSeenEnabled(prefs)) return;

                            try {
                                handleStatusMenuHook(param, contactClass, baseClass);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " Error handling status menu hook: " + t);
                            }
                        }
                    });
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " hookMethod(menuStatusMethod) failed: " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error setting up status playback hook: " + Log.getStackTraceString(t));
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
        if (BlueOnReplyHook.isManualStatusSeenMenuPlacement(prefs)) injectStatusSeenMenuItem(menu, sourceObject);
        if (BlueOnReplyHook.isManualStatusSeenEnabled(prefs)
                && "icon".equals(prefs.getString("manual_status_seen_placement", "menu"))) {
            scheduleStatusSeenIcon(sourceObject);
        }
        if (!isFeatureEnabled()) return;
        MenuItem item = menu.findItem(MENU_ID_STATUS_DOWNLOAD);
        if (item == null) {
            item = menu.add(Menu.NONE, MENU_ID_STATUS_DOWNLOAD, Menu.NONE, "");
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        }
        configureStatusMenuItem(item, sourceObject);
    }

    private void injectStatusSeenMenuItem(Menu menu, Object sourceObject) {
        if (menu.findItem(MENU_ID_STATUS_SEEN) != null) return;
        MenuItem item = menu.add(Menu.NONE, MENU_ID_STATUS_SEEN, Menu.NONE,
                UiText.get(R.string.waex_ui_send_blue_tick, "Send blue tick"));
        item.setOnMenuItemClickListener(clicked -> {
            boolean sent = BlueOnReplyHook.sendCapturedStatusReceipt(resolveCurrentStatusItem(sourceObject));
            showToast(context, sent
                    ? UiText.get(R.string.waex_ui_blue_tick_sent, "Seen sent")
                    : UiText.get(R.string.waex_ui_no_hidden_read_receipt_to_send, "No hidden read receipt to send"));
            return true;
        });
    }

    private void configureStatusMenuItem(MenuItem item, Object sourceObject) {
        boolean isTextStatus = isCurrentTextStatus(sourceObject);
        item.setTitle(isTextStatus
                ? UiText.get(R.string.waex_ui_copy_text, "Copy text")
                : UiText.get(R.string.waex_ui_download, "Download"));
        Drawable icon = MenuIconLoader.load(context,
                isTextStatus ? "ic_waex_copy" : "ic_waex_download");
        if (icon != null) item.setIcon(icon);
        item.setOnMenuItemClickListener(menuItem -> {
            // Decide again at tap time: the user may swipe while the menu remains alive.
            if (isCurrentMediaStatus(sourceObject)) {
                CompletableFuture.runAsync(() -> {
                    try {
                        downloadCurrentStatus(sourceObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " Download error: " + t.getClass().getSimpleName());
                        showToast(context, UiText.get(R.string.waex_ui_failed_to_download_status,
                                "Failed to download status"));
                    }
                });
            } else {
                String currentTextStatus = findVisibleTextStatus(sourceObject);
                if (currentTextStatus != null) copyToClipboard(context, currentTextStatus);
            }
            return true;
        });
    }

    private boolean isCurrentTextStatus(Object sourceObject) {
        return !isCurrentMediaStatus(sourceObject) && findVisibleTextStatus(sourceObject) != null;
    }

    private boolean isCurrentMediaStatus(Object sourceObject) {
        return extractMediaFileDeep(resolveCurrentStatusItem(sourceObject)) != null;
    }

    private void hookMenuRefreshMethods(Class<?> fragmentClass) {
        if (fragmentClass == null) return;
        for (Method method : fragmentClass.getDeclaredMethods()) {
            boolean acceptsMenu = false;
            for (Class<?> parameter : method.getParameterTypes()) {
                if (Menu.class.isAssignableFrom(parameter)) {
                    acceptsMenu = true;
                    break;
                }
            }
            if (!acceptsMenu) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if ((!isFeatureEnabled() && !BlueOnReplyHook.isManualStatusSeenEnabled(prefs)) || param.hasThrowable()) return;
                    for (Object arg : param.args) {
                        if (arg instanceof Menu) injectDownloadMenuItem((Menu) arg, param.thisObject);
                    }
                }
            });
        }
    }

    /** Adds a compact explicit-seen control beside WhatsApp's status reply/heart controls. */
    private void hookStatusSeenIcon(Class<?> fragmentClass) {
        hookStatusSeenIconOnFragment(fragmentClass);
    }

    private void hookStatusSeenIconOnFragment(Class<?> fragmentClass) {
        if (fragmentClass == null) return;
        for (Method method : fragmentClass.getDeclaredMethods()) {
            if (!"onViewCreated".equals(method.getName()) && !"onStart".equals(method.getName())) continue;
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (BlueOnReplyHook.isManualStatusSeenEnabled(prefs)
                            && "icon".equals(prefs.getString("manual_status_seen_placement", "menu"))) {
                        scheduleStatusSeenIcon(param.thisObject);
                    }
                }
            });
        }
    }

    /**
     * The playback activity is the reliable lifecycle owner for the reply bar.
     * Some WhatsApp builds create that bar after the fragment's view callback,
     * so an activity-resume retry keeps the icon independent of menu inflation.
     */
    /**
     * The playback activity is the reliable lifecycle owner for the reply bar.
     * Some WhatsApp builds create that bar after the fragment's view callback,
     * so an activity-resume retry keeps the icon independent of menu inflation.
     * Tries multiple known class names and falls back to DexKit search.
     */
    private void hookStatusSeenIconActivity() {
        // Known class paths across WA versions
        String[] candidates = {
                "com.whatsapp.status.playback.StatusPlaybackActivity",
                "com.whatsapp.StatusPlaybackActivity",
                "com.whatsapp.status.StatusPlaybackActivity"
        };
        Class<?> playbackActivity = null;
        for (String name : candidates) {
            playbackActivity = XposedHelpers.findClassIfExists(name, classLoader);
            if (playbackActivity != null) break;
        }
        // DexKit fallback: find the activity that uses the reply bar resource name
        if (playbackActivity == null) {
            try {
                var bridge = DexSearchEngine.getInstance().getBridge();
                if (bridge != null) {
                    var mdList = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .usingStrings("status_like_button_container", "reply_bar_tappable")));
                    if (!mdList.isEmpty()) {
                        Class<?> declaring = mdList.first().getMethodInstance(classLoader).getDeclaringClass();
                        if (Activity.class.isAssignableFrom(declaring)) playbackActivity = declaring;
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " DexKit activity search failed: " + t.getClass().getSimpleName());
            }
        }
        if (playbackActivity == null) {
            XposedBridge.log(TAG + " StatusPlaybackActivity not found — icon placement unavailable");
            return;
        }
        final Class<?> activityClass = playbackActivity;
        try {
            XposedBridge.hookAllMethods(activityClass, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (BlueOnReplyHook.isManualStatusSeenEnabled(prefs)
                            && "icon".equals(prefs.getString("manual_status_seen_placement", "menu"))) {
                        scheduleStatusSeenIcon(param.thisObject);
                    }
                }
            });
            XposedBridge.hookAllMethods(activityClass, "onWindowFocusChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (Boolean.TRUE.equals(param.args[0])
                            && BlueOnReplyHook.isManualStatusSeenEnabled(prefs)
                            && "icon".equals(prefs.getString("manual_status_seen_placement", "menu"))) {
                        scheduleStatusSeenIcon(param.thisObject);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Could not hook status icon activity: " + t.getClass().getSimpleName());
        }

        // Safety net: Use ActivityTracker lifecycle callbacks to ensure we always catch the playback activity
        ActivityTracker.addListener(new ActivityTracker.ActivityListener() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                if ("StatusPlaybackActivity".equals(activity.getClass().getSimpleName())
                        || activity.getClass().getName().contains("StatusPlaybackActivity")) {
                    if (BlueOnReplyHook.isManualStatusSeenEnabled(prefs)
                            && "icon".equals(prefs.getString("manual_status_seen_placement", "menu"))) {
                        scheduleStatusSeenIcon(activity);
                    }
                }
            }
        });
    }

    /** Runs after WhatsApp has attached the playback reply bar to the activity window. */
    private void scheduleStatusSeenIcon(Object sourceObject) {
        if (!(sourceObject instanceof Activity)) latestStatusFragment = sourceObject;
        View root = resolveStatusRoot(sourceObject);
        if (root == null) return;
        watchStatusReplyBar(root, sourceObject);
        watchStatusPager(root, sourceObject);
        root.post(() -> installStatusSeenIcon(sourceObject));
        root.postDelayed(() -> installStatusSeenIcon(sourceObject), 250L);
        root.postDelayed(() -> installStatusSeenIcon(sourceObject), 750L);
        root.postDelayed(() -> installStatusSeenIcon(sourceObject), 1500L);
        root.postDelayed(() -> installStatusSeenIcon(sourceObject), 3000L);
    }

    /**
     * Tries multiple known resource IDs for the like/reaction button across WhatsApp versions.
     * Falls back to a view-tree traversal of all known reply-bar containers.
     */
    private View findLikeContainer(View root, String pkg) {
        // Resource names used by different WA versions / build flavours
        String[] likeNames = {
                "status_like_button_container",
                "like_button_container",
                "status_reaction_button",
                "status_like_button",
                "reaction_button_container",
                "status_emoji_reaction_button"
        };
        for (String name : likeNames) {
            int id = root.getResources().getIdentifier(name, "id", pkg);
            if (id != 0) {
                View v = root.findViewById(id);
                if (v != null) return v;
            }
        }
        // Fallback: scan inside any known reply-bar container
        String[] barNames = {
                "reply_bar_tappable",
                "status_reply_bar",
                "reply_bar",
                "status_footer_bar"
        };
        for (String barName : barNames) {
            int id = root.getResources().getIdentifier(barName, "id", pkg);
            if (id == 0) continue;
            View bar = root.findViewById(id);
            if (!(bar instanceof ViewGroup)) continue;
            View found = findImageButtonContainer((ViewGroup) bar, 0);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * BFS scan: returns the last FrameLayout/ViewGroup child that directly wraps
     * a single ImageView — the typical structure WA uses for the heart/reaction button.
     */
    private View findImageButtonContainer(ViewGroup group, int depth) {
        if (depth > 4) return null;
        View best = null;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof ViewGroup)) continue;
            ViewGroup childGroup = (ViewGroup) child;
            // A like-button container is typically a small FrameLayout with one ImageView child.
            if (childGroup.getChildCount() == 1 && childGroup.getChildAt(0) instanceof ImageView) {
                best = childGroup;   // keep scanning to pick the last/rightmost one
            }
            View deeper = findImageButtonContainer(childGroup, depth + 1);
            if (deeper != null) best = deeper;
        }
        return best;
    }



    /** Reinserts the sibling control after WhatsApp replaces its reply bar on a status swipe. */
    private void watchStatusReplyBar(View root, Object sourceObject) {
        synchronized (watchedStatusRoots) {
            if (!watchedStatusRoots.add(root)) return;
        }
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (BlueOnReplyHook.isManualStatusSeenEnabled(prefs)
                    && "icon".equals(prefs.getString("manual_status_seen_placement", "menu"))) {
                watchStatusPager(root, sourceObject);
                installStatusSeenIcon(sourceObject);
            }
        });
    }

    /**
     * WhatsApp changes pages when swiping between statuses and contacts.
     * Listening to ViewPager and page changes ensures the seen icon is injected
     * into every newly presented status page.
     */
    private void watchStatusPager(View root, Object sourceObject) {
        String packageName = root.getContext().getPackageName();
        int pagerId = root.getResources().getIdentifier("playback_pager", "id", packageName);
        View pager = pagerId == 0 ? null : root.findViewById(pagerId);
        if (pager == null) return;
        synchronized (watchedStatusPagers) {
            if (!watchedStatusPagers.add(pager)) return;
        }

        // 1. Layout changes (page commit/re-layout)
        pager.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            if (BlueOnReplyHook.isManualStatusSeenEnabled(prefs)
                    && "icon".equals(prefs.getString("manual_status_seen_placement", "menu"))) {
                root.post(() -> installStatusSeenIcon(sourceObject));
            }
        });

        // 2. ViewPager.OnPageChangeListener via reflection / dynamic proxy
        try {
            Class<?> pageChangeListenerClass = XposedHelpers.findClassIfExists(
                    "androidx.viewpager.widget.ViewPager$OnPageChangeListener",
                    pager.getClass().getClassLoader()
            );
            if (pageChangeListenerClass == null) {
                pageChangeListenerClass = XposedHelpers.findClassIfExists(
                        "androidx.viewpager.widget.ViewPager$h",
                        pager.getClass().getClassLoader()
                );
            }
            if (pageChangeListenerClass != null) {
                Object listenerProxy = Proxy.newProxyInstance(
                        pager.getClass().getClassLoader(),
                        new Class<?>[]{pageChangeListenerClass},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            if ("onPageSelected".equals(name) || "onPageScrollStateChanged".equals(name)) {
                                root.post(() -> installStatusSeenIcon(sourceObject));
                                root.postDelayed(() -> installStatusSeenIcon(sourceObject), 150L);
                                root.postDelayed(() -> installStatusSeenIcon(sourceObject), 400L);
                            }
                            return null;
                        }
                );
                Method addListener = null;
                for (Method m : pager.getClass().getMethods()) {
                    if ("addOnPageChangeListener".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        addListener = m;
                        break;
                    }
                }
                if (addListener != null) {
                    addListener.invoke(pager, listenerProxy);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Could not attach OnPageChangeListener: " + t.getClass().getSimpleName());
        }
    }

    private void installStatusSeenIcon(Object sourceObject) {
        View root = resolveStatusRoot(sourceObject);
        if (root == null) return;
        Context statusContext = root.getContext();
        String statusPackage = statusContext.getPackageName();

        // Locate the like/reaction button using multiple resource names across WA versions.
        View likeContainer = findLikeContainer(root, statusPackage);
        if (likeContainer == null) {
            return;
        }
        if (!(likeContainer.getParent() instanceof ViewGroup)) return;
        ViewGroup controls = (ViewGroup) likeContainer.getParent();

        // Check if the current likeContainer's parent already has the seen button
        View existing = controls.findViewWithTag("waex_status_seen_button");
        if (existing != null) {
            bindStatusSeenControl(existing, sourceObject);
            return;
        }

        // WhatsApp lays out the heart as a 48dp FrameLayout with a centered 32dp ImageButton.
        // Reuse that exact sibling structure and insert before it, rather than altering the reply bar.
        FrameLayout seenContainer = new FrameLayout(statusContext);
        seenContainer.setTag("waex_status_seen_button");
        ViewGroup.LayoutParams heartLayout = likeContainer.getLayoutParams();
        ViewGroup.LayoutParams seenContainerLayout = copyLayoutParams(heartLayout);
        addEndMargin(seenContainerLayout, root, 8);
        copyViewChrome(likeContainer, seenContainer);
        seenContainer.setClickable(true);
        seenContainer.setFocusable(true);

        ImageButton button = new ImageButton(statusContext);
        button.setContentDescription(UiText.get(R.string.manual_status_seen_title, "Send status seen manually"));
        Drawable icon = MenuIconLoader.load(context, "ic_waex_mark_read");
        if (icon != null) button.setImageDrawable(icon);
        else button.setImageResource(android.R.drawable.checkbox_on_background);
        if (likeContainer instanceof ViewGroup && ((ViewGroup) likeContainer).getChildCount() > 0) {
            View heartIcon = ((ViewGroup) likeContainer).getChildAt(0);
            if (heartIcon instanceof ImageView) {
                button.setScaleType(((ImageView) heartIcon).getScaleType());
                button.setImageTintList(((ImageView) heartIcon).getImageTintList());
            }
            ViewGroup.LayoutParams heartIconLayout = copyLayoutParams(heartIcon.getLayoutParams());
            if (heartIconLayout != null) button.setLayoutParams(heartIconLayout);
        }
        if (button.getLayoutParams() == null) {
            int iconSize = (int) (32 * root.getResources().getDisplayMetrics().density);
            button.setLayoutParams(new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        }
        // The container owns the circular/ripple background; the tick itself stays clean.
        button.setBackground(null);
        button.setForeground(null);
        button.setPadding(0, 0, 0, 0);
        button.setClickable(false);
        button.setFocusable(false);
        seenContainer.addView(button);
        seenContainer.setOnClickListener(v -> sendBoundStatusSeenReceipt(seenContainer, statusContext));
        controls.addView(seenContainer, controls.indexOfChild(likeContainer), seenContainerLayout);
        bindStatusSeenControl(seenContainer, sourceObject);
    }

    /** Updates an existing button for every visible status page, including reused reply bars. */
    private void bindStatusSeenControl(View control, Object sourceObject) {
        Object fragment = latestStatusFragment != null ? latestStatusFragment : sourceObject;
        synchronized (statusSeenBindings) {
            if (fragment == null) statusSeenBindings.remove(control);
            else statusSeenBindings.put(control, fragment);
        }
    }

    private void sendBoundStatusSeenReceipt(View control, Context statusContext) {
        Object playbackSource;
        synchronized (statusSeenBindings) {
            playbackSource = statusSeenBindings.get(control);
        }
        Object statusItem = resolveCurrentStatusItem(playbackSource);
        boolean sent = BlueOnReplyHook.hasCapturedStatusReceipt(statusItem)
                && BlueOnReplyHook.sendCapturedStatusReceipt(statusItem);
        showToast(statusContext, sent
                ? UiText.get(R.string.waex_ui_blue_tick_sent, "Seen sent")
                : UiText.get(R.string.waex_ui_no_hidden_read_receipt_to_send, "No hidden read receipt to send"));
    }

    /** Retains any WhatsApp/WDS-specific LayoutParams subclass used by the heart control. */
    private ViewGroup.LayoutParams copyLayoutParams(ViewGroup.LayoutParams source) {
        if (source == null) return null;
        try {
            Constructor<?> constructor = source.getClass().getConstructor(ViewGroup.LayoutParams.class);
            Object copy = constructor.newInstance(source);
            if (copy instanceof ViewGroup.LayoutParams) return (ViewGroup.LayoutParams) copy;
        } catch (Throwable ignored) {}
        if (source instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) source);
        }
        return new ViewGroup.LayoutParams(source);
    }

    private void addEndMargin(ViewGroup.LayoutParams layout, View root, int dp) {
        if (!(layout instanceof ViewGroup.MarginLayoutParams)) return;
        int gap = (int) (dp * root.getResources().getDisplayMetrics().density + 0.5f);
        ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) layout;
        margins.setMarginEnd(margins.getMarginEnd() + gap);
    }

    private void copyViewChrome(View source, View target) {
        target.setPadding(source.getPaddingLeft(), source.getPaddingTop(), source.getPaddingRight(), source.getPaddingBottom());
        target.setMinimumWidth(source.getMinimumWidth());
        target.setMinimumHeight(source.getMinimumHeight());
        target.setAlpha(source.getAlpha());
        Drawable background = copyDrawable(source.getBackground());
        if (background != null) target.setBackground(background);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Drawable foreground = copyDrawable(source.getForeground());
            if (foreground != null) target.setForeground(foreground);
        }
    }

    private Drawable copyDrawable(Drawable source) {
        if (source == null || source.getConstantState() == null) return source;
        return source.getConstantState().newDrawable().mutate();
    }

    private View resolveStatusRoot(Object sourceObject) {
        if (sourceObject instanceof Activity) {
            Activity activity = (Activity) sourceObject;
            if (activity.getWindow() != null) return activity.getWindow().getDecorView();
        }
        View fragmentView = resolveSourceView(sourceObject);
        if (fragmentView != null) return fragmentView.getRootView();
        Activity activity = ActivityTracker.getCurrentActivity();
        return activity != null && activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
    }

    private void downloadCurrentStatus(Object sourceObject) {
        Object currentStatusItem = resolveCurrentStatusItem(sourceObject);
        File mediaFile = extractMediaFileDeep(currentStatusItem);

        if (isMediaFileReady(mediaFile)) {
            saveMediaFile(context, mediaFile);
        } else {
            showToast(
                    context,
                    UiText.get(R.string.waex_ui_please_wait_until_the_status_finishes, "Please wait until the status finishes downloading, then try again"),
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
            Object fallback = null;
            for (List<?> statusList : readStatusLists(fragmentInstance)) {
                int currentIndex = readCurrentStatusIndex(fragmentInstance, statusList.size());
                if (currentIndex < 0 || currentIndex >= statusList.size()) continue;
                Object candidate = statusList.get(currentIndex);
                if (fallback == null) fallback = candidate;
                // Playback fragments have several List fields. Only prefer a candidate
                // that exposes the exact status receipt captured by HideReceiptsHook.
                if (BlueOnReplyHook.hasCapturedStatusReceipt(candidate)) return candidate;
            }
            return fallback;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error resolving current status item: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private List<List<?>> readStatusLists(Object fragmentInstance) {
        List<List<?>> lists = new ArrayList<>();
        try {
            Field cached = statusListField;
            if (cached != null && cached.getDeclaringClass().isInstance(fragmentInstance)) {
                Object value = cached.get(fragmentInstance);
                if (value instanceof List && !((List<?>) value).isEmpty()) lists.add((List<?>) value);
            }
        } catch (Throwable ignored) {}
        for (Class<?> type = fragmentInstance.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(fragmentInstance);
                    if (value instanceof List && !((List<?>) value).isEmpty() && !lists.contains(value)) {
                        lists.add((List<?>) value);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return lists;
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
            DexKitBridge bridge,
            Class<?> statusBaseFragmentClass
    ) {
        if (bridge == null) return null;
        try {
            MethodDataList methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("playbackFragment/setPageActive no-messages ")));
            for (MethodData method : methods) {
                for (UsingFieldData use : method.getUsingFields()) {
                    FieldData fieldData = use.getField();
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
        String identity = isEnabled(PREF_SAVE_DUPLICATES, true)
                ? null : StatusDownloadIdentity.forFile(srcFile);
        if (identity == null) {
            exportStatus(ctx, srcFile);
            return;
        }
        synchronized (SAVED_IDENTITIES_LOCK) {
            if (hasSavedStatus(identity)) {
                showToast(ctx, UiText.get(R.string.status_already_saved, "Status already saved"));
                return;
            }
            if (exportStatus(ctx, srcFile)) markStatusSaved(identity);
        }
    }

    private boolean exportStatus(Context ctx, File srcFile) {
        try {
            StatusMediaExporter.export(ctx, srcFile);
            showToast(ctx, UiText.get(R.string.waex_ui_status_saved_to_gallery_waex,
                    "Status saved to Gallery (WAEX)"));
            return true;
        } catch (Exception failure) {
            XposedBridge.log(TAG + " status export failed: " + failure.getClass().getSimpleName());
            showToast(ctx, UiText.get(R.string.waex_ui_failed_to_download_status,
                    "Failed to download status: ") + failure.getClass().getSimpleName());
            return false;
        }
    }

    private boolean hasSavedStatus(String identity) {
        try {
            return prefs.getStringSet(PREF_SAVED_IDENTITIES, Collections.emptySet()).contains(identity);
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private void markStatusSaved(String identity) {
        try {
            Set<String> saved = new LinkedHashSet<>(prefs.getStringSet(
                    PREF_SAVED_IDENTITIES, Collections.emptySet()));
            saved.add(identity);
            prefs.edit().putStringSet(PREF_SAVED_IDENTITIES, saved).commit();
        } catch (RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " status identity save failed: " + failure.getClass().getSimpleName());
        }
    }

    private void copyToClipboard(Context ctx, String text) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText(UiText.get(R.string.waex_ui_status_text, "Status Text"), text);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(ctx, UiText.get(R.string.waex_ui_copied_status_text_to_clipboard, "Copied status text to clipboard"), Toast.LENGTH_SHORT).show();
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
                    context, classLoader, "waex_status_playback_contact_fragment",
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
                    context, classLoader, "waex_status_playback_base_fragment",
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
