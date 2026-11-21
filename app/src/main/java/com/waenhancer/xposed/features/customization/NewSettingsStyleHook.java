package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Menu;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.utils.ActivityTracker;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import java.lang.reflect.Method;


public class NewSettingsStyleHook extends BaseFeature {

    private static final String TAG = "[WAEX][NewSettingsStyle]";

    private static final int WHATS_HAPPENING_SENDING_ENABLED_CODE = 14862;
    private static final int ME_TAB_V2_VARIANTS_CODE = 18564;

    public NewSettingsStyleHook(@NonNull Context context,
                                @NonNull ClassLoader classLoader,
                                @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "New Settings Style";
    }

    @NonNull
    @Override
    public String getCategory() {
        return "Customization";
    }

    @Override
    public void hook() throws Throwable {
        boolean enabled = prefs.getBoolean("novaconfig", false);
        if (!enabled) {
            return;
        }

        // WhatsApp New Settings variant 2: profile photo on toolbar + new settings UI
        int newSettings = 2;
        PropsOverrideHook.BOOLEANS.put(WHATS_HAPPENING_SENDING_ENABLED_CODE, true);
        PropsOverrideHook.INTEGERS.put(ME_TAB_V2_VARIANTS_CODE, newSettings);

        hookHomeMenu();
    }

    private void hookHomeMenu() {
        try {
            Class<?> homeActivityClass = resolveHomeActivityClass();
            if (homeActivityClass == null) {
                return;
            }

            int meTabMenuItemId = context.getResources().getIdentifier(
                    "me_tab_menu_item", "id", context.getPackageName());

            Method createMenu = findHierarchyMethod(homeActivityClass, "onCreateOptionsMenu", Menu.class);
            if (createMenu == null) return;
            XposedBridge.hookMethod(createMenu, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!homeActivityClass.isInstance(param.thisObject)) return;
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Menu) {
                        Menu menu = (Menu) param.args[0];
                        if (meTabMenuItemId != 0) {
                            MenuItem item = menu.findItem(meTabMenuItemId);
                            if (item != null) {
                                item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
                            }
                        }
                    }
                }
            });
            refreshCurrentHomeMenu(homeActivityClass);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook HomeActivity menu for me_tab_menu_item: " + t.getMessage());
        }
    }

    private static Method findHierarchyMethod(Class<?> leaf, String name, Class<?>... parameters) {
        for (Class<?> current = leaf; current != null && current != Object.class;
             current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, parameters);
            } catch (NoSuchMethodException ignored) {}
        }
        return null;
    }

    private void refreshCurrentHomeMenu(@NonNull Class<?> homeActivityClass) {
        Activity activity = ActivityTracker.getCurrentActivity();
        if (activity == null || !homeActivityClass.isInstance(activity)) return;
        activity.runOnUiThread(activity::invalidateOptionsMenu);
    }

    private Class<?> resolveHomeActivityClass() {
        try {
            return DexSearchEngine.getInstance().findClassWithCache(
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
        } catch (Throwable ignored) {
            return null;
        }
    }
}
