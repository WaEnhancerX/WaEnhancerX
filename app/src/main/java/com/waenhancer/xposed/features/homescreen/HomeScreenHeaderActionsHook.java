package com.waenhancer.xposed.features.homescreen;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.waenhancer.config.DndPreferencePolicy;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.components.WaexBottomSheet;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.privacy.ArchivedChatsPreferencePolicy;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import com.waenhancer.xposed.utils.WhatsAppProcessRestarter;
import java.util.Locale;
import java.lang.reflect.Method;

/**
 * Home Screen Header Actions Hook:
 * Dynamically injects quick toggles and shortcuts into WhatsApp's 3-dots options menu.
 *
 * Supports three display modes controlled by the "waex_menu_style" preference:
 *   "grouped"  (default) — items nested under a "WAEX" submenu entry
 *   "separate"            — items injected directly in the root options menu
 *   "icons"               — items shown as action icons in the app bar
 */
public class HomeScreenHeaderActionsHook extends BaseFeature {

    private static final String TAG = "[WAEX][HomeHeader]";

    // Unique IDs for injected menu items
    private static final int MENU_ID_WAEX_GROUP    = 0x7EAE0000;
    private static final int MENU_ID_GHOST_MODE    = 0x7EAE0001;
    private static final int MENU_ID_FREEZE_LS     = 0x7EAE0002;
    private static final int MENU_ID_DND           = 0x7EAE0003;
    private static final int MENU_ID_RESTART       = 0x7EAE0004;
    private static final int MENU_ID_SETTINGS      = 0x7EAE0005;
    /** Separate-mode: the single WAEX shield toolbar button */
    private static final int MENU_ID_WAEX_TOOLBAR  = 0x7EAE0006;
    private static final int MENU_ID_ARCHIVED      = 0x7EAE0007;
    private static final int MENU_ID_LOCKED        = 0x7EAE0008;
    private static final AtomicLong LAST_ARCHIVE_OPEN_MS = new AtomicLong(-1L);
    private static final AtomicBoolean TITLE_LONG_PRESS_HOOKED = new AtomicBoolean(false);
    private static final AtomicBoolean RESTART_SCHEDULED = new AtomicBoolean(false);
    private final Set<View> archiveTitleViews = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));
    private long lastArchiveTitleTapMs = -1L;
    private int archiveTitleTapCount = 0;

    // Style constants
    private static final String STYLE_GROUPED  = "grouped";
    private static final String STYLE_SEPARATE = "separate";
    private static final String STYLE_ICONS    = "icons";

    public HomeScreenHeaderActionsHook(@NonNull Context context,
                                       @NonNull ClassLoader classLoader,
                                       @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        attachMenuHooks();
    }

    private boolean isDndEnabled() {
        return DndPreferencePolicy.isEnabled(prefs::contains, key -> isEnabled(key, false));
    }

    // -------------------------------------------------------------------------
    // Hook attachment
    // -------------------------------------------------------------------------

    private void attachMenuHooks() {
        try {
            Class<?> homeActivityClass = resolveHomeActivityClass();
            if (homeActivityClass == null) {
                XposedBridge.log(TAG + " Could not find HomeActivity class!");
                return;
            }

            XC_MethodHook menuPopulateHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!homeActivityClass.isInstance(param.thisObject)) return;
                    if (param.args.length > 0 && param.args[0] instanceof Menu) {
                        Menu menu = (Menu) param.args[0];
                        Activity activity = (param.thisObject instanceof Activity)
                                ? (Activity) param.thisObject : null;
                        injectMenuItems(menu, activity);
                        scheduleHomeTitleLongPress(activity);
                    }
                }
            };

            hookHierarchyMethod(homeActivityClass, "onCreateOptionsMenu",
                    new Class<?>[] { Menu.class }, menuPopulateHook);
            hookHierarchyMethod(homeActivityClass, "onPrepareOptionsMenu",
                    new Class<?>[] { Menu.class }, menuPopulateHook);

            attachHomeTitleLongPressHook(homeActivityClass);

            hookHierarchyMethod(homeActivityClass, "onPostResume", new Class<?>[0], new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!homeActivityClass.isInstance(param.thisObject)) return;
                    if (param.thisObject instanceof Activity) {
                        scheduleHomeTitleLongPress((Activity) param.thisObject);
                    }
                }
            });

            hookHierarchyMethod(homeActivityClass, "onOptionsItemSelected",
                    new Class<?>[] { MenuItem.class }, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!homeActivityClass.isInstance(param.thisObject)) return;
                    if (param.args.length > 0 && param.args[0] instanceof MenuItem) {
                        MenuItem item = (MenuItem) param.args[0];
                        if (handleItemSelected(item, param.thisObject)) {
                            param.setResult(true);
                        }
                    }
                }
            });

            // Also hook onMenuItemSelected — WhatsApp sometimes routes action bar taps here directly
            hookHierarchyMethod(homeActivityClass, "onMenuItemSelected",
                    new Class<?>[] { int.class, MenuItem.class }, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!homeActivityClass.isInstance(param.thisObject)) return;
                    // param.args[0] = featureId (int), param.args[1] = MenuItem
                    if (param.args.length > 1 && param.args[1] instanceof MenuItem) {
                        MenuItem item = (MenuItem) param.args[1];
                        if (handleItemSelected(item, param.thisObject)) {
                            param.setResult(true);
                        }
                    }
                }
            });

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error attaching hooks: " + t.getMessage());
        }
    }

    /**
     * WhatsApp 2.26 moved several HomeActivity callbacks to an obfuscated base activity.
     * XposedBridge.hookAllMethods only examines methods declared by the supplied class, so
     * silently attaching it to HomeActivity no longer observes those inherited callbacks.
     */
    private void hookHierarchyMethod(Class<?> leaf, String name, Class<?>[] parameters,
                                     XC_MethodHook callback) throws NoSuchMethodException {
        Class<?> current = leaf;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(name, parameters);
                XposedBridge.hookMethod(method, callback);
                XposedBridge.log(TAG + " Hooked " + name + " on " + current.getName());
                return;
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(leaf.getName() + "#" + name);
    }

    /**
     * Keep the archive recovery gesture independent of WhatsApp's frequently changing
     * toolbar implementation. View.performLongClick() is stable Android API; strict
     * HomeActivity and title checks prevent the gesture from affecting chat titles or
     * unrelated long-press actions.
     */
    private void attachHomeTitleLongPressHook(Class<?> homeActivityClass) {
        if (!TITLE_LONG_PRESS_HOOKED.compareAndSet(false, true)) return;
        try {
            XposedHelpers.findAndHookMethod(View.class, "performLongClick", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof View)) return;
                    View view = (View) param.thisObject;
                    Activity activity = findActivity(view.getContext());
                    if (activity == null || !homeActivityClass.isInstance(activity)) return;
                    if (!isWhatsAppHomeTitle(view, activity)) return;
                    if (openLongPressDestination(activity)) param.setResult(true);
                }
            });
        } catch (Throwable t) {
            TITLE_LONG_PRESS_HOOKED.set(false);
            XposedBridge.log(TAG + " Could not attach home-title long press: "
                    + t.getClass().getSimpleName());
        }
    }

    private Activity findActivity(Context candidate) {
        Context current = candidate;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) return null;
            current = next;
        }
        return null;
    }

    private void scheduleHomeTitleLongPress(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        View decor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
        if (decor == null) return;
        decor.post(() -> installHomeTitleLongPress(decor, activity));
    }

    private void installHomeTitleLongPress(View root, Activity activity) {
        View title = findExactToolbarLogo(activity);
        if (title == null) title = findHomeTitle(root, activity);
        if (title != null && archiveTitleViews.add(title)) {
            title.setLongClickable(true);
            title.setOnLongClickListener(clicked -> openLongPressDestination(activity));
            title.setClickable(true);
            title.setOnClickListener(clicked -> handleArchiveTitleTap(activity));
        }

        // Also attach to title's immediate parent (which holds WA Business logo/title)
        if (title != null && title.getParent() instanceof View) {
            View parent = (View) title.getParent();
            if (archiveTitleViews.add(parent)) {
                parent.setLongClickable(true);
                parent.setOnLongClickListener(clicked -> openLongPressDestination(activity));
                parent.setClickable(true);
                parent.setOnClickListener(clicked -> handleArchiveTitleTap(activity));
            }
        }
    }

    private synchronized void handleArchiveTitleTap(Activity activity) {
        if (!hasTripleTapDestination()) {
            archiveTitleTapCount = 0;
            lastArchiveTitleTapMs = -1L;
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastArchiveTitleTapMs < 0L || now < lastArchiveTitleTapMs
                || now - lastArchiveTitleTapMs > 700L) {
            archiveTitleTapCount = 1;
        } else {
            archiveTitleTapCount++;
        }
        lastArchiveTitleTapMs = now;
        if (archiveTitleTapCount >= 3) {
            archiveTitleTapCount = 0;
            lastArchiveTitleTapMs = -1L;
            openTripleTapDestination(activity);
        }
    }

    private boolean openLongPressDestination(Activity activity) {
        if (isArchiveTitleLongPressEnabled()) {
            openArchivedConversations(activity);
            return true;
        }
        if (isLockedTitleLongPressEnabled()) {
            openLockedConversations(activity);
            return true;
        }
        return false;
    }

    private boolean hasTripleTapDestination() {
        return isArchiveTitleTripleTapEnabled() || isLockedTitleTripleTapEnabled();
    }

    private void openTripleTapDestination(Activity activity) {
        if (isArchiveTitleTripleTapEnabled()) {
            openArchivedConversations(activity);
        } else if (isLockedTitleTripleTapEnabled()) {
            openLockedConversations(activity);
        }
    }

    private View findExactToolbarLogo(Activity activity) {
        try {
            int id = activity.getResources().getIdentifier(
                    "toolbar_logo", "id", activity.getPackageName());
            return id != 0 ? activity.findViewById(id) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private View findHomeTitle(View candidate, Activity activity) {
        if (isWhatsAppHomeTitle(candidate, activity)) return candidate;
        if (!(candidate instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) candidate;
        for (int i = 0; i < group.getChildCount(); i++) {
            View match = findHomeTitle(group.getChildAt(i), activity);
            if (match != null) return match;
        }
        return null;
    }

    private boolean isWhatsAppHomeTitle(View view, Activity activity) {
        if (!view.isShown() || view.getWindowToken() == null) {
            return false;
        }

        String entryName = resourceEntryName(view);
        // Current WhatsApp/Business home headers use a logo ImageView rather
        // than a TextView. Confirmed resource: <host package>:id/toolbar_logo.
        if (view instanceof ImageView && entryName.equals("toolbar_logo")) {
            return true;
        }
        if (!(view instanceof TextView)) return false;

        TextView textView = (TextView) view;
        CharSequence text = textView.getText();
        if (text == null || text.length() == 0) return false;
        boolean titleId = entryName.equals("title") || entryName.contains("toolbar_title")
                || entryName.contains("action_bar_title") || entryName.contains("app_title");

        boolean toolbarAncestor = false;
        ViewParent parent = view.getParent();
        for (int depth = 0; parent instanceof View && depth < 5; depth++) {
            View parentView = (View) parent;
            String className = parentView.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (className.contains("toolbar") || className.contains("actionbar")
                    || className.contains("appbar")) {
                toolbarAncestor = true;
                break;
            }
            parent = parentView.getParent();
        }

        String actual = text.toString().trim();
        String activityTitle = activity.getTitle() != null
                ? activity.getTitle().toString().trim() : "";
        String appLabel = "";
        try {
            appLabel = activity.getApplicationInfo().loadLabel(activity.getPackageManager())
                    .toString().trim();
        } catch (Throwable ignored) {}
        boolean expectedText = actual.equals(activityTitle) || actual.equals(appLabel)
                || actual.equalsIgnoreCase("WhatsApp")
                || actual.equalsIgnoreCase("WhatsApp Business");
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        int rootHeight = view.getRootView() != null ? view.getRootView().getHeight() : 0;
        boolean nearTop = rootHeight > 0 && location[1] >= 0 && location[1] < rootHeight / 5;
        return expectedText && (titleId || toolbarAncestor || nearTop);
    }

    private String resourceEntryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return "no-id";
        try {
            return view.getResources().getResourceEntryName(id)
                    .toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private Class<?> resolveHomeActivityClass() {
        try {
            Class<?> cls = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "waex_home_activity_class",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .className(".HomeActivity", StringMatchType.EndsWith))
                        ).firstOrNull();
                        if (cd == null) {
                            cd = bridge.findClass(FindClass.create()
                                    .matcher(ClassMatcher.create()
                                            .className("HomeActivity", StringMatchType.Contains))
                            ).firstOrNull();
                        }
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );
            if (cls != null) return cls;
        } catch (Throwable ignored) {}
        Class<?> currentHome = XposedHelpers.findClassIfExists(
                "com.whatsapp.home.ui.HomeActivity", classLoader);
        if (currentHome != null) return currentHome;
        return XposedHelpers.findClassIfExists("com.whatsapp.HomeActivity", classLoader);
    }

    // -------------------------------------------------------------------------
    // Menu injection — dispatches to style-specific builders
    // -------------------------------------------------------------------------

    private void injectMenuItems(Menu menu, Activity activity) {
        if (menu == null) return;

        // Always clean previously injected items first
        removeInjectedItems(menu);

        String style = prefs.getString("waex_menu_style", STYLE_GROUPED);
        if (style == null || style.isEmpty()) style = STYLE_GROUPED;

        switch (style) {
            case STYLE_SEPARATE:
                // Single WAEX toolbar icon; tapping opens a PopupMenu with all items
                addItemsSeparate(menu, activity);
                break;
            case STYLE_ICONS:
                // Each item placed directly in the action bar with its own vector icon
                addItemsWithIcons(menu, activity);
                break;
            case STYLE_GROUPED:
            default:
                addItemsGrouped(menu, activity);
                break;
        }
    }

    /** Removes all previously injected WAEX items to prevent duplication on re-inflate. */
    private void removeInjectedItems(Menu menu) {
        menu.removeItem(MENU_ID_WAEX_GROUP);
        menu.removeItem(MENU_ID_WAEX_TOOLBAR);
        menu.removeItem(MENU_ID_GHOST_MODE);
        menu.removeItem(MENU_ID_FREEZE_LS);
        menu.removeItem(MENU_ID_DND);
        menu.removeItem(MENU_ID_RESTART);
        menu.removeItem(MENU_ID_SETTINGS);
        menu.removeItem(MENU_ID_ARCHIVED);
        menu.removeItem(MENU_ID_LOCKED);
    }

    /** Grouped mode: all WAEX items nested inside a single "WAEX" submenu entry in the 3-dots menu. */
    private void addItemsGrouped(Menu menu, Activity activity) {
        if (!hasAnyActiveItem()) return;

        SubMenu sub = menu.addSubMenu(Menu.NONE, MENU_ID_WAEX_GROUP, Menu.NONE, "WAEX");
        sub.getItem().setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);

        appendWaexItems(sub, activity, MenuItem.SHOW_AS_ACTION_NEVER);
    }

    /**
     * Separate mode: injects a single WAEX shield icon into the action bar.
     * Item selection is handled via the hooked onOptionsItemSelected → handleItemSelected.
     */
    private void addItemsSeparate(Menu menu, Activity activity) {
        if (!hasAnyActiveItem()) return;

        if (hasNormalWaexAction()) {
            MenuItem waexBtn = menu.add(Menu.NONE, MENU_ID_WAEX_TOOLBAR, Menu.NONE, "WAEX");
            waexBtn.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            Drawable logo = MenuIconLoader.logoIcon(context);
            if (logo != null) waexBtn.setIcon(logo);
        }
        // In separate mode the quick-action sheet is unchanged; the archive
        // is reachable from the overflow menu even if it is hidden in the list.
        appendRecoveryEntries(menu, activity);
    }

    /**
     * Icons mode: each enabled WAEX item shown as an individual action bar icon
     * with filled/unfilled states for toggleable features.
     *
     * SHOW_AS_ACTION_ALWAYS is required here — IF_ROOM causes items added later
     * (e.g. Restart, Settings) to fall into the 3-dots overflow when the toolbar
     * is already occupied by WhatsApp's own native icons.
     */
    private void addItemsWithIcons(Menu menu, Activity activity) {
        appendWaexItems(menu, activity, MenuItem.SHOW_AS_ACTION_ALWAYS);
        attachIconsToItems(menu);
    }

    /** Attaches vector drawables to already-added items in icons mode. */
    private void attachIconsToItems(Menu menu) {
        MenuItem ghost = menu.findItem(MENU_ID_GHOST_MODE);
        if (ghost != null) {
            boolean active = prefs.getBoolean("ghostmode_active", false);
            Drawable icon = MenuIconLoader.ghostIcon(context, active);
            if (icon != null) ghost.setIcon(icon);
        }

        MenuItem freeze = menu.findItem(MENU_ID_FREEZE_LS);
        if (freeze != null) {
            boolean active = prefs.getBoolean("freeze_last_seen_active", false);
            Drawable icon = MenuIconLoader.freezeIcon(context, active);
            if (icon != null) freeze.setIcon(icon);
        }

        MenuItem dnd = menu.findItem(MENU_ID_DND);
        if (dnd != null) {
            boolean active = isDndEnabled();
            Drawable icon = MenuIconLoader.dndIcon(context, active);
            if (icon != null) dnd.setIcon(icon);
        }

        MenuItem restart = menu.findItem(MENU_ID_RESTART);
        if (restart != null) {
            Drawable icon = MenuIconLoader.restartIcon(context);
            if (icon != null) restart.setIcon(icon);
        }

        MenuItem settings = menu.findItem(MENU_ID_SETTINGS);
        if (settings != null) {
            Drawable icon = MenuIconLoader.settingsIcon(context);
            if (icon != null) settings.setIcon(icon);
        }
    }

    // -------------------------------------------------------------------------
    // Shared item builder (works with both Menu and SubMenu since SubMenu extends Menu)
    // -------------------------------------------------------------------------

    private void appendWaexItems(Menu target, Activity activity, int showAsAction) {
        if (isEnabled("ghostmode", false)) {
            boolean active = prefs.getBoolean("ghostmode_active", false);
            MenuItem item = target.add(Menu.NONE, MENU_ID_GHOST_MODE, Menu.NONE,
                    active ? UiText.get(R.string.waex_ui_ghost_mode_on, "Ghost Mode: ON") : UiText.get(R.string.waex_ui_ghost_mode_off, "Ghost Mode: OFF"));
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }

        if (isEnabled("freezelastseen", false)) {
            boolean active = prefs.getBoolean("freeze_last_seen_active", false);
            MenuItem item = target.add(Menu.NONE, MENU_ID_FREEZE_LS, Menu.NONE,
                    active ? UiText.get(R.string.waex_ui_freeze_last_seen_on, "Freeze Last Seen: ON") : UiText.get(R.string.waex_ui_freeze_last_seen_off, "Freeze Last Seen: OFF"));
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }

        if (isEnabled("show_dndmode", false)) {
            boolean active = isDndEnabled();
            MenuItem item = target.add(Menu.NONE, MENU_ID_DND, Menu.NONE,
                    active ? UiText.get(R.string.waex_ui_dnd_on, "DND: ON") : UiText.get(R.string.waex_ui_dnd_off, "DND: OFF"));
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }

        if (isEnabled("restartbutton", false)) {
            MenuItem item = target.add(Menu.NONE, MENU_ID_RESTART, Menu.NONE, UiText.get(R.string.waex_ui_restart_whatsapp, "Restart WhatsApp"));
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }

        if (isEnabled("open_wae", false)) {
            MenuItem item = target.add(Menu.NONE, MENU_ID_SETTINGS, Menu.NONE, UiText.get(R.string.waex_ui_wa_enhancer_settings, "WA Enhancer Settings"));
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }
        appendRecoveryEntries(target, activity);
    }

    /** Always place recovery actions in overflow, not as conspicuous toolbar icons. */
    private void appendRecoveryEntries(Menu target, Activity activity) {
        if (isArchivedHidden() && isArchiveMenuEnabled()) {
            MenuItem item = target.add(Menu.NONE, MENU_ID_ARCHIVED, Menu.NONE, UiText.get(R.string.waex_ui_open_archived_chats_label, "Open archived chats"));
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }
        if (isHideLockedChatsEnabled() && isLockedMenuEnabled()) {
            MenuItem item = target.add(Menu.NONE, MENU_ID_LOCKED, Menu.NONE, UiText.get(R.string.waex_ui_open_locked_chats_label, "Open locked chats"));
            item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }
    }

    private boolean isArchivedHidden() {
        try {
            return ArchivedChatsPreferencePolicy.isHidden(prefs.getAll().get("typearchive"));
        } catch (Throwable ignored) { return false; }
    }

    private boolean isArchiveMenuEnabled() {
        return prefs.getBoolean("archive_open_via_menu", true);
    }

    private boolean isArchiveTitleLongPressEnabled() {
        return prefs.getBoolean("archive_open_via_title_long_press", true);
    }

    private boolean isArchiveTitleTripleTapEnabled() {
        return prefs.getBoolean("archive_open_via_title_triple_tap", false);
    }

    private boolean isHideLockedChatsEnabled() {
        return prefs.getBoolean("hide_chats", false);
    }

    private boolean isLockedMenuEnabled() {
        return prefs.getBoolean("locked_open_via_menu", false)
                && !(isArchivedHidden() && isArchiveMenuEnabled());
    }

    private boolean isLockedTitleLongPressEnabled() {
        return prefs.getBoolean("locked_open_via_title_long_press", false)
                && !(isArchivedHidden() && isArchiveTitleLongPressEnabled());
    }

    private boolean isLockedTitleTripleTapEnabled() {
        return prefs.getBoolean("locked_open_via_title_triple_tap", false)
                && !(isArchivedHidden() && isArchiveTitleTripleTapEnabled());
    }

    private boolean hasNormalWaexAction() {
        return isEnabled("ghostmode", false) || isEnabled("freezelastseen", false)
                || isEnabled("show_dndmode", false) || isEnabled("restartbutton", false)
                || isEnabled("open_wae", false);
    }

    /** Returns true if at least one injected item should be visible. */
    private boolean hasAnyActiveItem() {
        return hasNormalWaexAction()
                || (isArchivedHidden() && isArchiveMenuEnabled())
                || (isHideLockedChatsEnabled() && isLockedMenuEnabled());
    }

    // -------------------------------------------------------------------------
    // Item selection handler
    // -------------------------------------------------------------------------

    private boolean handleItemSelected(MenuItem item, Object activityObj) {
        if (item == null) return false;
        int id = item.getItemId();

        if (id == MENU_ID_ARCHIVED) {
            if (activityObj instanceof Activity) openArchivedConversations((Activity) activityObj);
            return true;
        }

        if (id == MENU_ID_LOCKED) {
            if (activityObj instanceof Activity) openLockedConversations((Activity) activityObj);
            return true;
        }

        // Separate mode: WAEX toolbar button opens the native quick-action sheet
        if (id == MENU_ID_WAEX_TOOLBAR) {
            if (!(activityObj instanceof Activity)) return true;
            Activity activity = (Activity) activityObj;

            WaexQuickActionsSheet.show(
                    activity,
                    prefs,
                    /* onGhostToggle  */ () -> promptGhostModeSheet(activity),
                    /* onFreezeToggle */ () -> promptFreezeLastSeenSheet(activity),
                    /* onDndToggle    */ () -> promptDndModeSheet(activity),
                    /* onRestart   */ () -> forceRestartWhatsApp(activity),
                    /* onSettings  */ () -> {
                        try {
                            Intent intent =
                                    activity.getPackageManager().getLaunchIntentForPackage("com.waenhancer");
                            if (intent != null) activity.startActivity(intent);
                        } catch (Throwable ignored) {}
                    },
                    /* onInvalidate */ activity::invalidateOptionsMenu
            );
            return true;
        }


        if (id == MENU_ID_GHOST_MODE) {
            if (activityObj instanceof Activity) {
                promptGhostModeSheet((Activity) activityObj);
            }
            return true;
        }

        if (id == MENU_ID_FREEZE_LS) {
            if (activityObj instanceof Activity) {
                promptFreezeLastSeenSheet((Activity) activityObj);
            }
            return true;
        }

        if (id == MENU_ID_DND) {
            if (activityObj instanceof Activity) {
                promptDndModeSheet((Activity) activityObj);
            }
            return true;
        }

        if (id == MENU_ID_RESTART) {
            if (activityObj instanceof Activity) {
                forceRestartWhatsApp((Activity) activityObj);
            }
            return true;
        }

        if (id == MENU_ID_SETTINGS) {
            if (activityObj instanceof Activity) {
                Activity activity = (Activity) activityObj;
                try {
                    Intent intent =
                            activity.getPackageManager().getLaunchIntentForPackage("com.waenhancer");
                    if (intent != null) activity.startActivity(intent);
                } catch (Throwable ignored) {}
            }
            return true;
        }

        return false;
    }

    /**
     * Dispatches an action by raw menu item ID.
     * Used by the Separate mode bottom sheet which doesn't have a real MenuItem object.
     */
    private void handleWaexItemById(int itemId, Activity activity) {
        if (itemId == MENU_ID_GHOST_MODE) {
            promptGhostModeSheet(activity);
        } else if (itemId == MENU_ID_FREEZE_LS) {
            promptFreezeLastSeenSheet(activity);
        } else if (itemId == MENU_ID_DND) {
            promptDndModeSheet(activity);
        } else if (itemId == MENU_ID_RESTART) {
            forceRestartWhatsApp(activity);
        } else if (itemId == MENU_ID_SETTINGS) {
            try {
                Intent intent =
                        activity.getPackageManager().getLaunchIntentForPackage("com.waenhancer");
                if (intent != null) activity.startActivity(intent);
            } catch (Throwable ignored) {}
        }
    }

    /** Launch only the verified in-package WhatsApp archive Activity. */
    private void openArchivedConversations(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        long previous = LAST_ARCHIVE_OPEN_MS.get();
        if (previous >= 0L && now >= previous && now - previous < 800L) return;
        if (!LAST_ARCHIVE_OPEN_MS.compareAndSet(previous, now)) return;
        try {
            Class<?> archived = XposedHelpers.findClassIfExists(
                    "com.whatsapp.conversation.conversationslist.ArchivedConversationsActivity",
                    classLoader);
            if (archived == null || !Activity.class.isAssignableFrom(archived)) {
                Toast.makeText(activity, UiText.get(R.string.waex_ui_archived_chats_unavailable_in_this_wha, "Archived chats unavailable in this WhatsApp build"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(activity, archived);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Cannot open archived chats: " + t.getClass().getSimpleName());
            Toast.makeText(activity, UiText.get(R.string.waex_ui_could_not_open_archived_chats, "Could not open archived chats"), Toast.LENGTH_SHORT).show();
        }
    }

    /** Launch only the verified in-package WhatsApp locked-conversations Activity. */
    private void openLockedConversations(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        long now = SystemClock.elapsedRealtime();
        long previous = LAST_ARCHIVE_OPEN_MS.get();
        if (previous >= 0L && now >= previous && now - previous < 800L) return;
        if (!LAST_ARCHIVE_OPEN_MS.compareAndSet(previous, now)) return;
        try {
            Class<?> locked = XposedHelpers.findClassIfExists(
                    "com.whatsapp.conversation.conversationslist.LockedConversationsActivity",
                    classLoader);
            if (locked == null || !Activity.class.isAssignableFrom(locked)) {
                Toast.makeText(activity, UiText.get(R.string.waex_ui_locked_chats_unavailable_in_this_whats, "Locked chats unavailable in this WhatsApp build"),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(activity, locked);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Cannot open locked chats: " + t.getClass().getSimpleName());
            Toast.makeText(activity, UiText.get(R.string.waex_ui_could_not_open_locked_chats, "Could not open locked chats"), Toast.LENGTH_SHORT).show();
        }
    }

    // -------------------------------------------------------------------------
    // Force restart
    // -------------------------------------------------------------------------

    /**
     * Force-closes WhatsApp and relaunches it:
     * Uses Intent.makeRestartActivityTask to relaunch the activity task, followed by Process.killProcess/System.exit(0).
     */
    private void forceRestartWhatsApp(Activity activity) {
        if (activity == null) return;
        if (!RESTART_SCHEDULED.compareAndSet(false, true)) return;
        Toast.makeText(activity,
                UiText.get(R.string.waex_ui_restarting_whatsapp_short, "Restarting WhatsApp…"),
                Toast.LENGTH_SHORT).show();
        activity.getWindow().getDecorView().postDelayed(
                () -> WhatsAppProcessRestarter.restart(activity),
                350L);
    }

    // -------------------------------------------------------------------------
    // Bottom sheet confirmations
    // -------------------------------------------------------------------------

    private void promptGhostModeSheet(Activity activity) {
        boolean active = prefs.getBoolean("ghostmode_active", false);
        if (!active) {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle(UiText.get(R.string.waex_ui_activate_ghost_mode, "Activate Ghost Mode?"))
                    .setMessage(UiText.get(R.string.waex_ui_ghost_mode_hides_your_online_status_fr, "Ghost Mode hides your online status, freezes last seen, and silences typing & read receipts from all contacts.\n\nRestart WhatsApp to apply changes?"))
                    .setPositiveButton(UiText.get(R.string.waex_ui_activate_restart, "Activate & Restart"), (d, w) -> {
                        prefs.edit().putBoolean("ghostmode_active", true).apply();
                        Toast.makeText(activity, UiText.get(R.string.waex_ui_ghost_mode_activated, "Ghost Mode Activated"), Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                        d.dismiss();
                        forceRestartWhatsApp(activity);
                    })
                    .setNegativeButton(UiText.get(R.string.action_cancel, "Cancel"), (d, w) -> d.dismiss())
                    .show();
        } else {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle(UiText.get(R.string.waex_ui_deactivate_ghost_mode, "Deactivate Ghost Mode?"))
                    .setMessage(UiText.get(R.string.waex_ui_deactivating_restores_your_normal_onli, "Deactivating restores your normal online visibility, typing indicators, and read receipts.\n\nRestart WhatsApp to apply changes?"))
                    .setPositiveButton(UiText.get(R.string.waex_ui_deactivate_restart, "Deactivate & Restart"), (d, w) -> {
                        prefs.edit().putBoolean("ghostmode_active", false).apply();
                        Toast.makeText(activity, UiText.get(R.string.waex_ui_ghost_mode_deactivated, "Ghost Mode Deactivated"), Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                        d.dismiss();
                        forceRestartWhatsApp(activity);
                    })
                    .setNegativeButton(UiText.get(R.string.action_cancel, "Cancel"), (d, w) -> d.dismiss())
                    .show();
        }
    }

    private void promptFreezeLastSeenSheet(Activity activity) {
        boolean active = prefs.getBoolean("freeze_last_seen_active", false);
        if (!active) {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle(UiText.get(R.string.waex_ui_freeze_last_seen, "Activate Freeze Last Seen?"))
                    .setMessage(UiText.get(R.string.waex_ui_locks_your_last_seen_timestamp_so_cont, "Locks your last seen timestamp so contacts won't see when you're active.\n\nRestart WhatsApp to apply changes?"))
                    .setPositiveButton(UiText.get(R.string.waex_ui_freeze_restart, "Freeze & Restart"), (d, w) -> {
                        prefs.edit().putBoolean("freeze_last_seen_active", true).apply();
                        Toast.makeText(activity, UiText.get(R.string.waex_ui_last_seen_frozen, "Last Seen Frozen"), Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                        d.dismiss();
                        forceRestartWhatsApp(activity);
                    })
                    .setNegativeButton(UiText.get(R.string.action_cancel, "Cancel"), (d, w) -> d.dismiss())
                    .show();
        } else {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle(UiText.get(R.string.waex_ui_unfreeze_last_seen, "Deactivate Freeze Last Seen?"))
                    .setMessage(UiText.get(R.string.waex_ui_whatsapp_will_resume_updating_your_las, "WhatsApp will resume updating your last seen timestamp normally.\n\nRestart WhatsApp to apply changes?"))
                    .setPositiveButton(UiText.get(R.string.waex_ui_unfreeze_restart, "Unfreeze & Restart"), (d, w) -> {
                        prefs.edit().putBoolean("freeze_last_seen_active", false).apply();
                        Toast.makeText(activity, UiText.get(R.string.waex_ui_last_seen_unfrozen, "Last Seen Unfrozen"), Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                        d.dismiss();
                        forceRestartWhatsApp(activity);
                    })
                    .setNegativeButton(UiText.get(R.string.action_cancel, "Cancel"), (d, w) -> d.dismiss())
                    .show();
        }
    }

    private void promptDndModeSheet(Activity activity) {
        boolean active = isDndEnabled();
        String title = active ? "Deactivate DND Mode?" : "Activate DND Mode?";
        String message = active
                ? "WhatsApp will resume normal incoming notifications.\n\nRestart WhatsApp to apply changes?"
                : "DND Mode mutes all incoming WhatsApp notifications.\n\nRestart WhatsApp to apply changes?";
        String action = active ? "Deactivate & Restart" : "Activate & Restart";
        String toast = active ? "DND Mode Deactivated" : "DND Mode Activated";

        new WaexBottomSheet(activity)
                .asBottomSheet()
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(action, (d, w) -> {
                    prefs.edit().putBoolean("dnd_mode", !active).apply();
                    Toast.makeText(activity, toast, Toast.LENGTH_SHORT).show();
                    activity.invalidateOptionsMenu();
                    d.dismiss();
                    forceRestartWhatsApp(activity);
                })
                .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                .show();
    }

    @NonNull
    @Override
    public String getName() {
        return "Home Header Actions";
    }
}
