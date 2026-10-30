package com.waenhancer.xposed.features.customization;


import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.SparseIntArray;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import android.app.Activity;
import com.waenhancer.xposed.utils.ActivityTracker;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Adds a dedicated Groups home tab and filters the Chats/Groups fragment data sets. */
public final class SeparateGroupsHook extends BaseFeature {
    private static final String TAG = "[WAEX][SeparateGroups]";
    private static final int CHATS = 200;
    private static final int GROUPS = 500;

    private static final ArrayList<Integer> tabs = new ArrayList<>();
    private static final WeakHashMap<Object, Integer> fragmentTabs = new WeakHashMap<>();
    private static final WeakHashMap<Object, int[]> unreadCounts = new WeakHashMap<>();
    private static final WeakHashMap<Object, Object> emptyBadges = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> hookedBadgeRenderers = new WeakHashMap<>();

    /** Returns the active conversation pager tab, or -1 while a non-conversation tab is resumed. */
    public static int getResumedConversationTab() {
        synchronized (fragmentTabs) {
            for (Map.Entry<Object, Integer> entry : fragmentTabs.entrySet()) {
                Object fragment = entry.getKey();
                if (fragment instanceof Fragment && ((Fragment) fragment).isResumed()) {
                    return entry.getValue();
                }
            }
        }
        return -1;
    }

    public SeparateGroupsHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                              @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Separate Chats and Groups"; }

    @Override public void hook() throws Throwable {
        if (!prefs.getBoolean("separate_groups_tabs", false)
                && !prefs.getBoolean("separategroups", false)) return;

        DexSearchEngine engine = DexSearchEngine.getInstance();
        engine.initialize(context);
        if (engine.getBridge() == null) throw new IllegalStateException("DexKit unavailable");

        Method tabList = resolveTabList(engine);
        Method getTab = resolveGetTab(engine);
        Class<?> conversations = XposedHelpers.findClassIfExists(
                "com.whatsapp.conversationslist.ConversationsFragment", classLoader);
        if (conversations == null) conversations = resolveClass(engine, ".ConversationsFragment");
        if (conversations == null) throw new ClassNotFoundException("ConversationsFragment");

        hookTabList(tabList);
        hookFragments(getTab, conversations);
        hookTabName(getTab);
        hookTabIcon(engine);
        hookUnreadBadges(engine);

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

    private Method resolveTabList(DexSearchEngine engine) throws Throwable {
        return engine.findMethodWithCache(context, classLoader, "separate_groups.tab_list", (bridge, loader) -> {
            var result = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .addUsingNumber(200).addUsingNumber(300).returnType(ArrayList.class)));
            if (result.isEmpty()) return null;
            return result.get(0).getMethodInstance(loader);
        });
    }

    private Method resolveGetTab(DexSearchEngine engine) throws Throwable {
        return engine.findMethodWithCache(context, classLoader, "separate_groups.get_tab", (bridge, loader) -> {
            var result = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .addUsingString("No HomeFragment mapping for community tab id:", StringMatchType.Contains)));
            for (var candidate : result) {
                Method method = candidate.getMethodInstance(loader);
                Class<?>[] methodParameters = method.getParameterTypes();
                if (methodParameters.length > 0 && methodParameters[0] == int.class) return method;
            }
            return null;
        });
    }

    private Class<?> resolveClass(DexSearchEngine engine, String suffix) throws Throwable {
        var result = engine.getBridge().findClass(FindClass.create().matcher(
                ClassMatcher.create().className(suffix, StringMatchType.EndsWith)));
        return result.isEmpty() ? null : result.get(0).getInstance(classLoader);
    }

    private void hookTabList(Method method) {
        if (method == null) throw new IllegalStateException("Tab list method not found");
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.getResult() instanceof ArrayList)) return;
                ArrayList<?> raw = (ArrayList<?>) param.getResult();
                for (Object id : raw) if (!(id instanceof Integer)) return;
                @SuppressWarnings("unchecked") ArrayList<Integer> result = (ArrayList<Integer>) raw;
                ArrayList<Integer> configured = SeparateGroupsTabPolicy.rewrite(result);
                if (!result.equals(configured)) {
                    result.clear();
                    result.addAll(configured);
                }
                synchronized (tabs) { tabs.clear(); tabs.addAll(result); }
            }
        });
    }

    private void hookFragments(Method getTab, Class<?> conversations) throws Throwable {
        if (getTab == null) throw new IllegalStateException("Home tab factory not found");
        Constructor<?> emptyConstructor = null;
        for (Constructor<?> constructor : conversations.getDeclaredConstructors()) {
            if (constructor.getParameterTypes().length == 0) {
                constructor.setAccessible(true);
                emptyConstructor = constructor;
                break;
            }
        }
        if (emptyConstructor == null) throw new NoSuchMethodException("ConversationsFragment()");
        Constructor<?> finalConstructor = emptyConstructor;

        XposedBridge.hookMethod(getTab, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int index = (int) param.args[0];
                int tab = tabAt(index);
                if (tab == GROUPS) param.setResult(finalConstructor.newInstance());
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                int index = (int) param.args[0];
                int tab = tabAt(index);
                Object fragment = param.getResult();
                if (fragment != null && (tab == CHATS || tab == GROUPS)) {
                    synchronized (fragmentTabs) { fragmentTabs.put(fragment, tab); }
                }
            }
        });

        for (Method method : conversations.getDeclaredMethods()) {
            if (method.getParameterTypes().length != 0 || !List.class.isAssignableFrom(method.getReturnType())) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.getResult() instanceof List)) return;
                    Integer tab;
                    synchronized (fragmentTabs) { tab = fragmentTabs.get(param.thisObject); }
                    if (tab == null) return;
                    param.setResult(filter((List<?>) param.getResult(), tab == GROUPS));
                }
            });
        }
    }

    private int tabAt(int index) {
        synchronized (tabs) {
            return index >= 0 && index < tabs.size() ? tabs.get(index) : -1;
        }
    }

    private List<Object> filter(Collection<?> source, boolean groups) {
        ArrayList<Object> result = new ArrayList<>();
        for (Object item : source) if (item == null || isGroup(item) == groups) result.add(item);
        return result;
    }

    private boolean isGroup(Object chat) {
        Object jid = findJid(chat);
        if (jid == null) return false;
        try {
            Object server = XposedHelpers.callMethod(jid, "getServer");
            return "g.us".equals(server) || "broadcast".equals(server);
        } catch (Throwable ignored) {
            return jid.toString().contains("@g.us") || jid.toString().contains("@broadcast");
        }
    }

    private Object findJid(Object chat) {
        Class<?> type = chat.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(chat);
                    if (value == null) continue;
                    if (XposedHelpers.findMethodExactIfExists(value.getClass(), "getServer") != null
                            || value.getClass().getName().contains("jid.")) return value;
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private void hookTabName(Method getTab) {
        try {
            for (Method method : getTab.getDeclaringClass().getDeclaredMethods()) {
                Class<?>[] methodParameters = method.getParameterTypes();
                if (methodParameters.length != 1 || methodParameters[0] != int.class
                        || !CharSequence.class.isAssignableFrom(method.getReturnType())) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if ((int) param.args[0] == GROUPS) param.setResult(UiText.get(R.string.waex_ui_groups, "Groups"));
                    }
                });
            }
        } catch (Throwable t) { XposedBridge.log(TAG + " label hook unavailable: " + t); }
    }

    private void hookTabIcon(DexSearchEngine engine) {
        try {
            int communities = context.getResources().getIdentifier(
                    "home_tab_communities_selector", "drawable", context.getPackageName());
            if (communities == 0) return;
            var builders = engine.getBridge().findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().addUsingString(
                            "Maximum number of items supported by", StringMatchType.Contains)));
            for (var data : builders) {
                Method method = data.getMethodInstance(classLoader);
                if (!MenuItem.class.isAssignableFrom(method.getReturnType())) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        MenuItem item = (MenuItem) param.getResult();
                        if (item != null && item.getItemId() == GROUPS) {
                            item.setTitle(UiText.get(R.string.waex_ui_groups, UiText.get(R.string.waex_ui_groups, "Groups")));
                            item.setIcon(communities);
                            item.setCheckable(true);
                        }
                    }
                });
            }
        } catch (Throwable t) { XposedBridge.log(TAG + " icon hook unavailable: " + t); }
    }

    /**
     * WA only publishes one unread counter for home tab 200. Tab 500 is injected by WAEX, so
     * its badge is never calculated by the stock delegate. We retain WA's own unread JID set,
     * split it by server, then reuse the stock badge renderer for both tab positions.
     */
    private void hookUnreadBadges(DexSearchEngine engine) {
        try {
            Method scheduler = engine.findMethodWithCache(context, classLoader,
                    "separate_groups.badge_scheduler", (bridge, loader) -> {
                        var result = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                                .addUsingString("HomeTabBarDelegate/updateNavigationMenuAndBadges skipped", StringMatchType.Contains)));
                        return result.isEmpty() ? null : result.get(0).getMethodInstance(loader);
                    });
            if (scheduler == null) return;
            Class<?> delegate = scheduler.getDeclaringClass();
            Method updateCount = null;
            for (Method method : delegate.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                        && types.length == 3 && types[0] == delegate
                        && types[1] == int.class && types[2] == int.class) {
                    method.setAccessible(true);
                    updateCount = method;
                    break;
                }
            }
            if (updateCount == null) {
                XposedBridge.log(TAG + " unread badge bridge unavailable: update method missing");
                return;
            }
            XposedBridge.hookMethod(updateCount, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if ((int) param.args[1] != CHATS || !isSeparateGroupsEnabled()) return;
                    Object tabDelegate = param.args[0];
                    if (tabDelegate == null) return;
                    int[] counts = splitUnreadCounts(tabDelegate, (int) param.args[2]);
                    synchronized (unreadCounts) { unreadCounts.put(tabDelegate, counts); }
                    XposedBridge.log(TAG + " unread split: chats=" + counts[0] + " groups=" + counts[1]);
                    installBadgeRenderer(tabDelegate);
                }
            });
            XposedBridge.log(TAG + " installed unread badge bridge");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " unread badge bridge unavailable: " + error);
        }
    }

    private void installBadgeRenderer(Object delegate) {
        try {
            Object renderer = null;
            for (Field field : delegate.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(delegate);
                if (value == null) continue;
                for (Method method : value.getClass().getDeclaredMethods()) {
                    Class<?>[] types = method.getParameterTypes();
                    if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                            && types.length == 3 && Context.class.isAssignableFrom(types[0])
                            && types[2] == int.class) {
                        renderer = value;
                        break;
                    }
                }
                if (renderer != null) break;
            }
            if (renderer == null) return;
            synchronized (hookedBadgeRenderers) {
                if (hookedBadgeRenderers.containsKey(renderer)) return;
                hookedBadgeRenderers.put(renderer, true);
            }
            Method render = null;
            for (Method method : renderer.getClass().getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == void.class
                        && types.length == 3 && Context.class.isAssignableFrom(types[0])
                        && types[2] == int.class) {
                    method.setAccessible(true);
                    render = method;
                    break;
                }
            }
            if (render == null) return;
            final Object badgeRenderer = renderer;
            final Method renderMethod = render;
            XposedBridge.hookMethod(render, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isSeparateGroupsEnabled()) return;
                    Object badge = param.args[1];
                    int renderedIndex = (int) param.args[2];
                    int chatsIndex = tabIndex(badgeRenderer, CHATS);
                    int groupsIndex = tabIndex(badgeRenderer, GROUPS);
                    if (renderedIndex != chatsIndex) {
                        if (badge != null && !isNumericBadge(badge)) {
                            synchronized (emptyBadges) { emptyBadges.put(badgeRenderer, badge); }
                        }
                        return;
                    }
                    int[] counts;
                    synchronized (unreadCounts) { counts = unreadCounts.get(delegate); }
                    if (counts == null) return;
                    renderBadge(renderMethod, badgeRenderer, param.args[0], badge, chatsIndex, counts[0]);
                    renderBadge(renderMethod, badgeRenderer, param.args[0], badge, groupsIndex, counts[1]);
                }
            });
            XposedBridge.log(TAG + " installed unread badge renderer");
        } catch (Throwable error) {
            XposedBridge.log(TAG + " unable to install unread renderer: " + error);
        }
    }

    private int[] splitUnreadCounts(Object delegate, int fallbackTotal) {
        int groups = 0;
        int individuals = 0;
        try {
            Object provider = readField(delegate, "A05");
            Method get = provider != null ? provider.getClass().getMethod("BQJ") : null;
            Object manager = get != null ? get.invoke(provider) : null;
            Method unread = manager != null ? manager.getClass().getMethod("A0W") : null;
            Object result = unread != null ? unread.invoke(manager) : null;
            if (result instanceof Set) {
                for (Object jid : (Set<?>) result) {
                    if (isGroupJid(jid)) groups++; else individuals++;
                }
                return new int[]{individuals, groups};
            }
        } catch (Throwable error) {
            XposedBridge.log(TAG + " unread JID split fallback: " + error.getClass().getSimpleName());
        }
        return new int[]{fallbackTotal, 0};
    }

    private boolean isGroupJid(Object jid) {
        if (jid == null) return false;
        try {
            Object server = XposedHelpers.callMethod(jid, "getServer");
            return "g.us".equals(server) || "broadcast".equals(server);
        } catch (Throwable ignored) {
            String value = String.valueOf(jid);
            return value.contains("@g.us") || value.contains("@broadcast");
        }
    }

    private int tabIndex(Object renderer, int tabId) {
        try {
            for (Field field : renderer.getClass().getDeclaredFields()) {
                if (!SparseIntArray.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                SparseIntArray map = (SparseIntArray) field.get(renderer);
                if (map != null) return map.get(tabId, -1);
            }
        } catch (Throwable ignored) { }
        return -1;
    }

    private void renderBadge(Method render, Object renderer, Object context, Object prototype,
                             int index, int count) throws Throwable {
        if (index < 0) return;
        Object badge = count > 0 ? numericBadge(prototype, count) : emptyBadge(renderer);
        if (badge == null) return;
        de.robv.android.xposed.XposedBridge.invokeOriginalMethod(
                render, renderer, new Object[]{context, badge, index});
    }

    private Object numericBadge(Object prototype, int count) {
        try {
            if (!isNumericBadge(prototype)) return null;
            Field innerField = prototype.getClass().getDeclaredFields()[0];
            innerField.setAccessible(true);
            Object inner = innerField.get(prototype);
            Constructor<?> innerConstructor = inner.getClass().getDeclaredConstructor(int.class);
            innerConstructor.setAccessible(true);
            Constructor<?> badgeConstructor = prototype.getClass().getDeclaredConstructors()[0];
            badgeConstructor.setAccessible(true);
            return badgeConstructor.newInstance(innerConstructor.newInstance(count), null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean isNumericBadge(Object badge) {
        if (badge == null) return false;
        try {
            Object inner = readField(badge, "A00");
            return inner != null && inner.getClass().getDeclaredConstructor(int.class) != null;
        } catch (Throwable ignored) { return false; }
    }

    private Object emptyBadge(Object renderer) {
        synchronized (emptyBadges) { return emptyBadges.get(renderer); }
    }

    private Object readField(Object target, String name) throws IllegalAccessException {
        for (Class<?> type = target.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) { }
        }
        return null;
    }

    private boolean isSeparateGroupsEnabled() {
        return prefs.getBoolean("separate_groups_tabs", false) || prefs.getBoolean("separategroups", false);
    }
}
