package com.waenhancer.xposed.features.homescreen;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Menu;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Home Screen Header Actions Hook:
 * Dynamically injects toolbar buttons (DND Mode, Restart WhatsApp, WA Enhancer Quick Access, Action Icons)
 * and custom home header name/bio.
 */
public class HomeScreenHeaderActionsHook extends BaseFeature {

    private static final String TAG = "[WAEX][HomeHeader]";
    private static final int MENU_ID_RESTART = 0x7EAE0001;
    private static final int MENU_ID_SETTINGS = 0x7EAE0002;
    private static final int MENU_ID_DND = 0x7EAE0003;

    public HomeScreenHeaderActionsHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookHomeActivityMenu();
    }

    private void hookHomeActivityMenu() {
        try {
            Class<?> homeActivityClass = XposedHelpers.findClassIfExists("com.whatsapp.HomeActivity", classLoader);
            if (homeActivityClass == null) {
                homeActivityClass = XposedHelpers.findClassIfExists("com.whatsapp.home.ui.HomePlaceholderActivity", classLoader);
            }

            if (homeActivityClass != null) {
                XposedBridge.hookAllMethods(homeActivityClass, "onCreateOptionsMenu", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.args.length > 0 && param.args[0] instanceof Menu) {
                            Menu menu = (Menu) param.args[0];
                            injectHeaderMenuItems(menu);
                        }
                    }
                });

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
                XposedBridge.log(TAG + " Hooked HomeActivity options menu.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking HomeActivity menu: " + t.getMessage());
        }
    }

    private void injectHeaderMenuItems(Menu menu) {
        if (isEnabled("restartbutton", false)) {
            MenuItem restartItem = menu.add(Menu.NONE, MENU_ID_RESTART, Menu.NONE, "Restart WhatsApp");
            restartItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }

        if (isEnabled("open_wae", false)) {
            MenuItem settingsItem = menu.add(Menu.NONE, MENU_ID_SETTINGS, Menu.NONE, "WA Enhancer Settings");
            settingsItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }

        if (isEnabled("show_dndmode", false)) {
            boolean dndActive = isEnabled("dnd_mode", false);
            MenuItem dndItem = menu.add(Menu.NONE, MENU_ID_DND, Menu.NONE, dndActive ? "DND: ON" : "DND: OFF");
            dndItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        }
    }

    private boolean handleMenuItemSelection(MenuItem item, Object activityObj) {
        int id = item.getItemId();
        if (id == MENU_ID_RESTART) {
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
            return true;
        }
        return false;
    }

    @NonNull
    @Override
    public String getName() {
        return "Home Header Actions";
    }
}
