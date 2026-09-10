package com.waenhancer.xposed.features.homescreen;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.components.WaexBottomSheet;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

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
                    if (param.args.length > 0 && param.args[0] instanceof Menu) {
                        Menu menu = (Menu) param.args[0];
                        Activity activity = (param.thisObject instanceof Activity)
                                ? (Activity) param.thisObject : null;
                        injectMenuItems(menu, activity);
                    }
                }
            };

            XposedBridge.hookAllMethods(homeActivityClass, "onCreateOptionsMenu", menuPopulateHook);
            XposedBridge.hookAllMethods(homeActivityClass, "onPrepareOptionsMenu", menuPopulateHook);

            XposedBridge.hookAllMethods(homeActivityClass, "onOptionsItemSelected", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] instanceof MenuItem) {
                        MenuItem item = (MenuItem) param.args[0];
                        if (handleItemSelected(item, param.thisObject)) {
                            param.setResult(true);
                        }
                    }
                }
            });

            XposedBridge.log(TAG + " Hooked HomeActivity: " + homeActivityClass.getName());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error attaching hooks: " + t.getMessage());
        }
    }

    private Class<?> resolveHomeActivityClass() {
        try {
            Class<?> cls = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_home_activity_class",
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
     * Tapping it opens a PopupMenu listing all enabled WAEX items.
     */
    private void addItemsSeparate(Menu menu, Activity activity) {
        if (!hasAnyActiveItem()) return;

        MenuItem waexBtn = menu.add(Menu.NONE, MENU_ID_WAEX_TOOLBAR, Menu.NONE, "WAEX");
        waexBtn.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

        Drawable logo = MenuIconLoader.logoIcon(context);
        if (logo != null) waexBtn.setIcon(logo);

        waexBtn.setOnMenuItemClickListener(mi -> {
            if (activity == null) return false;
            // Find the view for the toolbar button to anchor the popup
            View anchor = activity.findViewById(android.R.id.content);
            PopupMenu popup = new PopupMenu(activity, anchor);
            Menu popupMenu = popup.getMenu();
            appendWaexItems(popupMenu, activity, MenuItem.SHOW_AS_ACTION_NEVER);
            popup.setOnMenuItemClickListener(item -> handleItemSelected(item, activity));
            popup.show();
            return true;
        });
    }

    /**
     * Icons mode: each enabled WAEX item shown as an individual action bar icon
     * with filled/unfilled states for toggleable features.
     */
    private void addItemsWithIcons(Menu menu, Activity activity) {
        appendWaexItems(menu, activity, MenuItem.SHOW_AS_ACTION_IF_ROOM);
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
            boolean active = isEnabled("dnd_mode", false);
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
                    active ? "Ghost Mode: ON" : "Ghost Mode: OFF");
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }

        if (isEnabled("freezelastseen", false)) {
            boolean active = prefs.getBoolean("freeze_last_seen_active", false);
            MenuItem item = target.add(Menu.NONE, MENU_ID_FREEZE_LS, Menu.NONE,
                    active ? "Freeze Last Seen: ON" : "Freeze Last Seen: OFF");
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }

        if (isEnabled("show_dndmode", false)) {
            boolean active = isEnabled("dnd_mode", false);
            MenuItem item = target.add(Menu.NONE, MENU_ID_DND, Menu.NONE,
                    active ? "DND: ON" : "DND: OFF");
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }

        if (isEnabled("restartbutton", false)) {
            MenuItem item = target.add(Menu.NONE, MENU_ID_RESTART, Menu.NONE, "Restart WhatsApp");
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }

        if (isEnabled("open_wae", false)) {
            MenuItem item = target.add(Menu.NONE, MENU_ID_SETTINGS, Menu.NONE, "WA Enhancer Settings");
            item.setShowAsAction(showAsAction);
            item.setOnMenuItemClickListener(mi -> handleItemSelected(mi, activity));
        }
    }

    /** Returns true if at least one injected item should be visible. */
    private boolean hasAnyActiveItem() {
        return isEnabled("ghostmode", false)
                || isEnabled("freezelastseen", false)
                || isEnabled("show_dndmode", false)
                || isEnabled("restartbutton", false)
                || isEnabled("open_wae", false);
    }

    // -------------------------------------------------------------------------
    // Item selection handler
    // -------------------------------------------------------------------------

    private boolean handleItemSelected(MenuItem item, Object activityObj) {
        if (item == null) return false;
        int id = item.getItemId();

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
            boolean current = isEnabled("dnd_mode", false);
            prefs.edit().putBoolean("dnd_mode", !current).apply();
            if (activityObj instanceof Activity) {
                ((Activity) activityObj).invalidateOptionsMenu();
            }
            return true;
        }

        if (id == MENU_ID_RESTART) {
            if (activityObj instanceof Activity) {
                ((Activity) activityObj).recreate();
            }
            return true;
        }

        if (id == MENU_ID_SETTINGS) {
            if (activityObj instanceof Activity) {
                Activity activity = (Activity) activityObj;
                try {
                    android.content.Intent intent =
                            activity.getPackageManager().getLaunchIntentForPackage("com.waenhancer");
                    if (intent != null) activity.startActivity(intent);
                } catch (Throwable ignored) {}
            }
            return true;
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Bottom sheet confirmations
    // -------------------------------------------------------------------------

    private void promptGhostModeSheet(Activity activity) {
        boolean active = prefs.getBoolean("ghostmode_active", false);
        if (!active) {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle("Activate Ghost Mode?")
                    .setMessage("Hides your online status, freezes last seen, and silences typing & read receipt indicators.")
                    .setPositiveButton("Activate", (d, w) -> {
                        prefs.edit().putBoolean("ghostmode_active", true).apply();
                        Toast.makeText(activity, "Ghost Mode Activated", Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                    })
                    .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                    .show();
        } else {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle("Deactivate Ghost Mode?")
                    .setMessage("Deactivating restores your online visibility, typing states, and read receipts.")
                    .setPositiveButton("Deactivate", (d, w) -> {
                        prefs.edit().putBoolean("ghostmode_active", false).apply();
                        Toast.makeText(activity, "Ghost Mode Deactivated", Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                    })
                    .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                    .show();
        }
    }

    private void promptFreezeLastSeenSheet(Activity activity) {
        boolean active = prefs.getBoolean("freeze_last_seen_active", false);
        if (!active) {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle("Freeze Last Seen?")
                    .setMessage("Locks your last seen timestamp so contacts won't see when you're active.")
                    .setPositiveButton("Freeze", (d, w) -> {
                        prefs.edit().putBoolean("freeze_last_seen_active", true).apply();
                        Toast.makeText(activity, "Last Seen Frozen", Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                    })
                    .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                    .show();
        } else {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle("Unfreeze Last Seen?")
                    .setMessage("WhatsApp will resume updating your last seen timestamp normally.")
                    .setPositiveButton("Unfreeze", (d, w) -> {
                        prefs.edit().putBoolean("freeze_last_seen_active", false).apply();
                        Toast.makeText(activity, "Last Seen Unfrozen", Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                    })
                    .setNegativeButton("Cancel", (d, w) -> d.dismiss())
                    .show();
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Home Header Actions";
    }
}
