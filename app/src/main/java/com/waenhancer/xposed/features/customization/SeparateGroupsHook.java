package com.waenhancer.xposed.features.customization;

import android.content.Context;
import android.content.SharedPreferences;
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

/** Adds a dedicated Groups home tab and filters the Chats/Groups fragment data sets. */
public final class SeparateGroupsHook extends BaseFeature {
    private static final String TAG = "[WAEX][SeparateGroups]";
    private static final int CHATS = 200;
    private static final int GROUPS = 500;

    private static final ArrayList<Integer> tabs = new ArrayList<>();
    private static final WeakHashMap<Object, Integer> fragmentTabs = new WeakHashMap<>();

    /** Returns the active conversation pager tab, or -1 while a non-conversation tab is resumed. */
    public static int getResumedConversationTab() {
        synchronized (fragmentTabs) {
            for (java.util.Map.Entry<Object, Integer> entry : fragmentTabs.entrySet()) {
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
        XposedBridge.log(TAG + " enabled");
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
                if (method.getParameterCount() > 0 && method.getParameterTypes()[0] == int.class) return method;
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
                @SuppressWarnings("unchecked") ArrayList<Integer> result = (ArrayList<Integer>) param.getResult();
                if (!result.contains(GROUPS)) result.add(Math.min(1, result.size()), GROUPS);
                synchronized (tabs) { tabs.clear(); tabs.addAll(result); }
            }
        });
    }

    private void hookFragments(Method getTab, Class<?> conversations) throws Throwable {
        if (getTab == null) throw new IllegalStateException("Home tab factory not found");
        Constructor<?> emptyConstructor = null;
        for (Constructor<?> constructor : conversations.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 0) {
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
            if (method.getParameterCount() != 0 || !List.class.isAssignableFrom(method.getReturnType())) continue;
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.getResult() instanceof List)) return;
                    Integer tab = fragmentTabs.get(param.thisObject);
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
                if (method.getParameterCount() != 1 || method.getParameterTypes()[0] != int.class
                        || !CharSequence.class.isAssignableFrom(method.getReturnType())) continue;
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if ((int) param.args[0] == GROUPS) param.setResult("Groups");
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
                            item.setTitle("Groups");
                            item.setIcon(communities);
                            item.setCheckable(true);
                        }
                    }
                });
            }
        } catch (Throwable t) { XposedBridge.log(TAG + " icon hook unavailable: " + t); }
    }
}
