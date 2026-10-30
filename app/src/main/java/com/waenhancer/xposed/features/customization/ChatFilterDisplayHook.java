package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Controls whether chat search is hidden, shown as an icon, or shown as a full bar. */
public final class ChatFilterDisplayHook extends BaseFeature {
    private static final String TAG = "[WAEX][ChatFilterDisplay]";

    public ChatFilterDisplayHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                 @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Chat Filter Display"; }

    @Override public void hook() throws Throwable {
        String mode = prefs.getString("chatfilter", "2");
        if (!"0".equals(mode) && !"1".equals(mode)) return;

        DexSearchEngine engine = DexSearchEngine.getInstance();
        engine.initialize(context);
        if (engine.getBridge() == null) throw new IllegalStateException("DexKit unavailable");
        int searchBarId = context.getResources().getIdentifier(
                "my_search_bar", "id", context.getPackageName());

        hookHeaderInsertion(engine, searchBarId);
        hookSearchBarRenderer(engine);
        hookSearchMenu(engine, mode);
    }

    private void hookHeaderInsertion(DexSearchEngine engine, int searchBarId) {
        for (String marker : new String[] {
                "HeaderFooterRecyclerViewAdapter/addHeaderViewItemIfNeeded",
                "HeaderFooterRecyclerViewAdapter/addFooterViewItemAtPositionIfNeeded"
        }) {
            try {
                var results = engine.getBridge().findMethod(FindMethod.create().matcher(
                        MethodMatcher.create().addUsingString(marker, StringMatchType.Contains)));
                for (var data : results) {
                    Method method = data.getMethodInstance(classLoader);
                    XposedBridge.hookMethod(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            View view = firstView(param.args);
                            if (view == null || searchBarId == 0) return;
                            if (view.getId() == searchBarId
                                    || view.findViewById(searchBarId) != null) {
                                if (method.getReturnType() == void.class) param.setResult(null);
                                else if (!method.getReturnType().isPrimitive()) param.setResult(null);
                            }
                        }
                    });
                }
                if (!results.isEmpty()) return;
            } catch (Throwable ignored) {}
        }
        XposedBridge.log(TAG + " Search-bar insertion method not found");
    }

    private void hookSearchBarRenderer(DexSearchEngine engine) {
        try {
            var results = engine.getBridge().findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().addUsingString(
                            "search_bar_render_start", StringMatchType.EndsWith)));
            for (var data : results) {
                Method method = data.getMethodInstance(classLoader);
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (method.getReturnType() == void.class
                                || !method.getReturnType().isPrimitive()) param.setResult(null);
                    }
                });
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Search renderer hook unavailable: " + error);
        }
    }

    private void hookSearchMenu(DexSearchEngine engine, String mode) {
        Class<?> home = XposedHelpers.findClassIfExists(
                "com.whatsapp.home.ui.HomeActivity", classLoader);
        if (home == null) home = XposedHelpers.findClassIfExists("com.whatsapp.HomeActivity", classLoader);
        if (home == null) return;
        final Class<?> homeActivity = home;
        int searchItemId = context.getResources().getIdentifier(
                "menuitem_search", "id", context.getPackageName());
        if (searchItemId == 0) return;

        // WhatsApp only creates the search item on a specific home page. Merely making an
        // already-created MenuItem visible is therefore insufficient on current Business builds.
        // Match the upstream implementation: while WhatsApp builds this menu, temporarily report
        // the page which supports search so the item is actually added.
        if ("1".equals(mode)) hookSearchMenuCreation(engine, home, searchItemId);

        // Keep this visibility hook as a fallback for versions where the construction method or
        // current-page field cannot be resolved, and to guarantee that mode 0 remains hidden.
        Method prepareMenu = findHierarchyMethod(home, "onPrepareOptionsMenu", Menu.class);
        if (prepareMenu == null) {
            XposedBridge.log(TAG + " Home menu preparation callback not found");
            return;
        }
        XposedBridge.hookMethod(prepareMenu, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!homeActivity.isInstance(param.thisObject)) return;
                if (param.args.length == 0 || !(param.args[0] instanceof Menu)) return;
                MenuItem item = ((Menu) param.args[0]).findItem(searchItemId);
                if (item != null) item.setVisible("1".equals(mode));
            }
        });
    }

    private void hookSearchMenuCreation(DexSearchEngine engine, Class<?> home, int searchItemId) {
        try {
            MethodData target = null;
            var results = engine.getBridge().findMethod(FindMethod.create().matcher(
                    MethodMatcher.create()
                            .addUsingNumber(searchItemId)
                            .addUsingNumber(200)
                            .paramCount(1)
                            .addParamType(Menu.class)));
            for (MethodData candidate : results) {
                Method candidateMethod = candidate.getMethodInstance(classLoader);
                if (candidateMethod.getDeclaringClass().isAssignableFrom(home)) {
                    target = candidate;
                    break;
                }
            }
            if (target == null) {
                XposedBridge.log(TAG + " Search-menu construction method not found");
                return;
            }

            Method method = target.getMethodInstance(classLoader);
            Field currentPage = findCurrentPageField(target, method.getDeclaringClass());
            if (currentPage == null) {
                XposedBridge.log(TAG + " Current home-page field not found");
                return;
            }
            currentPage.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object owner = Modifier.isStatic(method.getModifiers())
                            ? firstAssignable(param.args, currentPage.getDeclaringClass())
                            : param.thisObject;
                    if (owner == null || currentPage.getType() != int.class) return;
                    int original = currentPage.getInt(owner);
                    param.setObjectExtra("waex_search_page_owner", owner);
                    param.setObjectExtra("waex_search_page_original", original);
                    currentPage.setInt(owner, 1);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object owner = param.getObjectExtra("waex_search_page_owner");
                    Object original = param.getObjectExtra("waex_search_page_original");
                    if (owner != null && original instanceof Integer) {
                        currentPage.setInt(owner, (Integer) original);
                    }
                }
            });
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Search-menu creation hook unavailable: " + error);
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

    private Field findCurrentPageField(MethodData method, Class<?> declaringClass) {
        try {
            for (UsingFieldData use : method.getUsingFields()) {
                FieldData data = use.getField();
                if (!"int".equals(data.getTypeName())) continue;
                Field field = data.getFieldInstance(classLoader);
                if (field.getDeclaringClass() == declaringClass) return field;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object firstAssignable(Object[] args, Class<?> type) {
        if (args == null) return null;
        for (Object arg : args) if (arg != null && type.isInstance(arg)) return arg;
        return null;
    }

    private static View firstView(Object[] args) {
        if (args == null) return null;
        for (Object arg : args) if (arg instanceof View) return (View) arg;
        return null;
    }
}
