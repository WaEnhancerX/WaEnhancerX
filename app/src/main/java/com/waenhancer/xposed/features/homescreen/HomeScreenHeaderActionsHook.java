package com.waenhancer.xposed.features.homescreen;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Menu;
import android.view.MenuItem;
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
 * Dynamically injects quick toggles and shortcuts into WhatsApp's 3-dots options menu:
 * - Ghost Mode Toggle (Master stealth with native WDS bottom sheet confirmation)
 * - Freeze Last Seen Toggle (With native WDS bottom sheet confirmation)
 * - DND Mode Toggle
 * - Restart WhatsApp
 * - WA Enhancer Settings Shortcut
 */
public class HomeScreenHeaderActionsHook extends BaseFeature {

    private static final String TAG = "[WAEX][HomeHeader]";
    private static final int MENU_ID_RESTART = 0x7EAE0001;
    private static final int MENU_ID_SETTINGS = 0x7EAE0002;
    private static final int MENU_ID_DND = 0x7EAE0003;
    private static final int MENU_ID_GHOST_MODE = 0x7EAE0004;
    private static final int MENU_ID_FREEZE_LAST_SEEN = 0x7EAE0005;

    public HomeScreenHeaderActionsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookHomeActivityMenu();
    }

    private void hookHomeActivityMenu() {
        try {
            Class<?> homeActivityClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_home_activity_class",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().className(".HomeActivity", StringMatchType.EndsWith))
                        ).firstOrNull();
                        if (cd == null) {
                            cd = bridge.findClass(FindClass.create()
                                    .matcher(ClassMatcher.create().className("HomeActivity", StringMatchType.Contains))
                            ).firstOrNull();
                        }
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            if (homeActivityClass == null) {
                homeActivityClass = XposedHelpers.findClassIfExists("com.whatsapp.HomeActivity", classLoader);
            }

            if (homeActivityClass != null) {
                XC_MethodHook menuHook = new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof Menu) {
                            Menu menu = (Menu) param.args[0];
                            Activity activity = (param.thisObject instanceof Activity) ? (Activity) param.thisObject : null;
                            injectHeaderMenuItems(menu, activity);
                        }
                    }
                };

                XposedBridge.hookAllMethods(homeActivityClass, "onCreateOptionsMenu", menuHook);
                XposedBridge.hookAllMethods(homeActivityClass, "onPrepareOptionsMenu", menuHook);

                XposedBridge.hookAllMethods(homeActivityClass, "onOptionsItemSelected", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof MenuItem) {
                            MenuItem item = (MenuItem) param.args[0];
                            if (handleMenuItemSelection(item, param.thisObject)) {
                                param.setResult(true);
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " Successfully hooked HomeActivity menu on: " + homeActivityClass.getName());
            } else {
                XposedBridge.log(TAG + " Could not find HomeActivity class!");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking HomeActivity menu: " + t.getMessage());
        }
    }

    private void injectHeaderMenuItems(Menu menu, Activity activity) {
        if (menu == null) return;

        // Clean any previously injected items to avoid duplicates
        menu.removeItem(MENU_ID_GHOST_MODE);
        menu.removeItem(MENU_ID_FREEZE_LAST_SEEN);
        menu.removeItem(MENU_ID_DND);
        menu.removeItem(MENU_ID_RESTART);
        menu.removeItem(MENU_ID_SETTINGS);

        if (isEnabled("ghostmode", false)) {
            boolean ghostActive = prefs.getBoolean("ghostmode_active", false);
            MenuItem ghostItem = menu.add(Menu.NONE, MENU_ID_GHOST_MODE, Menu.NONE, ghostActive ? "Ghost Mode: ON" : "Ghost Mode: OFF");
            ghostItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            ghostItem.setOnMenuItemClickListener(item -> handleMenuItemSelection(item, activity));
        }

        if (isEnabled("freezelastseen", false) || isEnabled("freeze_last_seen_menu", false)) {
            boolean freezeActive = prefs.getBoolean("freeze_last_seen_active", false);
            MenuItem freezeItem = menu.add(Menu.NONE, MENU_ID_FREEZE_LAST_SEEN, Menu.NONE, freezeActive ? "Freeze Last Seen: ON" : "Freeze Last Seen: OFF");
            freezeItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            freezeItem.setOnMenuItemClickListener(item -> handleMenuItemSelection(item, activity));
        }

        if (isEnabled("show_dndmode", false)) {
            boolean dndActive = isEnabled("dnd_mode", false);
            MenuItem dndItem = menu.add(Menu.NONE, MENU_ID_DND, Menu.NONE, dndActive ? "DND: ON" : "DND: OFF");
            dndItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            dndItem.setOnMenuItemClickListener(item -> handleMenuItemSelection(item, activity));
        }

        if (isEnabled("restartbutton", false)) {
            MenuItem restartItem = menu.add(Menu.NONE, MENU_ID_RESTART, Menu.NONE, "Restart WhatsApp");
            restartItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            restartItem.setOnMenuItemClickListener(item -> handleMenuItemSelection(item, activity));
        }

        if (isEnabled("open_wae", false)) {
            MenuItem settingsItem = menu.add(Menu.NONE, MENU_ID_SETTINGS, Menu.NONE, "WA Enhancer Settings");
            settingsItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
            settingsItem.setOnMenuItemClickListener(item -> handleMenuItemSelection(item, activity));
        }
    }

    private boolean handleMenuItemSelection(MenuItem item, Object activityObj) {
        if (item == null) return false;
        int id = item.getItemId();

        if (id == MENU_ID_GHOST_MODE) {
            if (activityObj instanceof Activity) {
                promptGhostModeBottomSheet((Activity) activityObj);
            }
            return true;
        } else if (id == MENU_ID_FREEZE_LAST_SEEN) {
            if (activityObj instanceof Activity) {
                promptFreezeLastSeenBottomSheet((Activity) activityObj);
            }
            return true;
        } else if (id == MENU_ID_RESTART) {
            if (activityObj instanceof Activity) {
                Activity activity = (Activity) activityObj;
                activity.recreate();
            }
            return true;
        } else if (id == MENU_ID_SETTINGS) {
            if (activityObj instanceof Activity) {
                Activity activity = (Activity) activityObj;
                try {
                    android.content.Intent intent = activity.getPackageManager().getLaunchIntentForPackage("com.waenhancer");
                    if (intent != null) {
                        activity.startActivity(intent);
                    }
                } catch (Throwable ignored) {}
            }
            return true;
        } else if (id == MENU_ID_DND) {
            boolean currentDnd = isEnabled("dnd_mode", false);
            prefs.edit().putBoolean("dnd_mode", !currentDnd).apply();
            if (activityObj instanceof Activity) {
                Activity activity = (Activity) activityObj;
                activity.invalidateOptionsMenu();
            }
            return true;
        }
        return false;
    }

    private void promptGhostModeBottomSheet(Activity activity) {
        boolean currentlyActive = prefs.getBoolean("ghostmode_active", false);

        if (!currentlyActive) {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle("Activate Ghost Mode?")
                    .setMessage("Hides your online status, freezes last seen, and silences typing & read receipt indicators.")
                    .setPositiveButton("Activate", (dialog, which) -> {
                        prefs.edit().putBoolean("ghostmode_active", true).apply();
                        Toast.makeText(activity, "Ghost Mode Activated", Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                    .show();
        } else {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle("Deactivate Ghost Mode?")
                    .setMessage("Ghost Mode is currently active.\n\nDeactivating will restore your standard online visibility, typing states, and read receipts.")
                    .setPositiveButton("Deactivate", (dialog, which) -> {
                        prefs.edit().putBoolean("ghostmode_active", false).apply();
                        Toast.makeText(activity, "Ghost Mode Deactivated", Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                    .show();
        }
    }

    private void promptFreezeLastSeenBottomSheet(Activity activity) {
        boolean currentlyActive = prefs.getBoolean("freeze_last_seen_active", false);

        if (!currentlyActive) {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle("Freeze Last Seen?")
                    .setMessage("Freezing your last seen will lock your current last seen timestamp in place.\n\n" +
                            "Your contacts will not see when you come online or use WhatsApp.\n\n" +
                            "Would you like to freeze your last seen now?")
                    .setPositiveButton("Freeze", (dialog, which) -> {
                        prefs.edit().putBoolean("freeze_last_seen_active", true).apply();
                        Toast.makeText(activity, "Last Seen Frozen", Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                    .show();
        } else {
            new WaexBottomSheet(activity)
                    .asBottomSheet()
                    .setTitle("Unfreeze Last Seen?")
                    .setMessage("Last Seen is currently frozen.\n\nUnfreezing will allow WhatsApp to update your last seen timestamp and active online presence normally.")
                    .setPositiveButton("Unfreeze", (dialog, which) -> {
                        prefs.edit().putBoolean("freeze_last_seen_active", false).apply();
                        Toast.makeText(activity, "Last Seen Unfrozen", Toast.LENGTH_SHORT).show();
                        activity.invalidateOptionsMenu();
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss())
                    .show();
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Home Header Actions";
    }
}
