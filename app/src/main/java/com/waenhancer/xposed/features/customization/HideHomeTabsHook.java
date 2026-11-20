package com.waenhancer.xposed.features.customization;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Set;
import android.app.Activity;
import com.waenhancer.xposed.utils.ActivityTracker;
import java.util.Locale;

/** Removes configured WhatsApp home tabs from both its model and visible menu. */
public final class HideHomeTabsHook extends BaseFeature {
    private static final String TAG = "[WAEX][HideTabs]";

    public HideHomeTabsHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                            @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Hide Home Tabs"; }

    @Override public void hook() throws Throwable {
        Set<String> stored = prefs.getStringSet("hidetabs", null);
        Set<Integer> hidden = TypedStyleHookPolicy.parseHiddenTabIds(stored);
        if (hidden.isEmpty()) return;

        // Native IGStatus shows stories in Chats; it must not silently undo an
        // explicit request to hide the separate Updates navigation tab.

        DexSearchEngine engine = DexSearchEngine.getInstance();
        engine.initialize(context);
        if (engine.getBridge() == null) throw new IllegalStateException("DexKit unavailable");

        Method tabList = engine.findMethodWithCache(context, classLoader,
                "hide_tabs.tab_list", (bridge, loader) -> {
                    var methods = bridge.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().addUsingNumber(200).addUsingNumber(300)
                                    .returnType(ArrayList.class)));
                    return methods.isEmpty() ? null : methods.get(0).getMethodInstance(loader);
                });
        if (tabList == null) throw new IllegalStateException("Home tab list method not found");
        XposedBridge.hookMethod(tabList, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof ArrayList)) return;
                @SuppressWarnings("unchecked") ArrayList<Object> tabs =
                        (ArrayList<Object>) param.getResult();
                TypedStyleHookPolicy.filterTabIds(tabs, hidden);
            }
        });

        try {
            var builders = engine.getBridge().findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().addUsingString(
                            "Maximum number of items supported by", StringMatchType.Contains)));
            for (var data : builders) {
                Method method = data.getMethodInstance(classLoader);
                if (!MenuItem.class.isAssignableFrom(method.getReturnType())) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        MenuItem item = (MenuItem) param.getResult();
                        if (item != null && hidden.contains(item.getItemId())) item.setVisible(false);
                    }
                });
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Menu-item visibility hook unavailable: " + error);
        }

        refreshCurrentHomeActivity();
    }

    private void refreshCurrentHomeActivity() {
        Activity activity = ActivityTracker.getCurrentActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String name = activity.getClass().getName().toLowerCase(Locale.ROOT);
        if (!name.contains("home") && !name.contains("main")) return;
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.recreate();
            }
        });
    }
}
