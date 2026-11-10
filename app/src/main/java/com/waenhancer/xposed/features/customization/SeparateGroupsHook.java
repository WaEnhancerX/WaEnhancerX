package com.waenhancer.xposed.features.customization;


import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.SparseIntArray;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    private static Context sAppContext;
    private static SharedPreferences sPrefs;
    private static Method sTabBadgeMethod;
    private static Class<?> sEmptyBadgeClass;
    private static Constructor<?> sBadgeWrapperConstructor;
    private static Constructor<?> sBadgeItemConstructor;
    private static int sIntIndex = -1;
    private static int sBadgeIndex = -1;
    private static int sContextIndex = -1;

    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());
    private static final ExecutorService sBadgeExecutor = Executors.newSingleThreadExecutor();
    private static final AtomicLong sBadgeUpdateSeq = new AtomicLong(0);
    private static final AtomicReference<WeakReference<Object>> sLastBadgeTarget =
            new AtomicReference<>(new WeakReference<>(null));
    private static final AtomicReference<WeakReference<Context>> sLastBadgeContext =
            new AtomicReference<>(new WeakReference<>(null));

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
        sAppContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        sPrefs = prefs;
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
                    refreshBadges();
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

    private static final Map<Class<?>, Field> JID_FIELD_CACHE = new ConcurrentHashMap<>();

    private Object findJid(Object chat) {
        if (chat == null) return null;
        Class<?> chatClass = chat.getClass();
        Field cached = JID_FIELD_CACHE.get(chatClass);
        if (cached != null) {
            try {
                return cached.get(chat);
            } catch (Throwable ignored) {}
        }
        Class<?> type = chatClass;
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(chat);
                    if (value == null) continue;
                    if (XposedHelpers.findMethodExactIfExists(value.getClass(), "getServer") != null
                            || value.getClass().getName().contains("jid.")) {
                        JID_FIELD_CACHE.put(chatClass, field);
                        return value;
                    }
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
     * Resolves the tab badge controller method and badge model classes using DexKit,
     * then intercepts the combined conversation tab badge to asynchronously compute and
     * apply separate unread badges for the Chats tab and Groups tab.
     */
    private void hookUnreadBadges(DexSearchEngine engine) {
        try {
            Method tabBadgeMethod = engine.findMethodWithCache(context, classLoader,
                    "separate_groups.tab_badge_method", (bridge, loader) -> {
                        var result = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                                .addUsingString("Tried to set badge for invalid", StringMatchType.Contains)));
                        for (var data : result) {
                            if (data.isMethod()) {
                                Method m = data.getMethodInstance(loader);
                                Class<?>[] params = m.getParameterTypes();
                                boolean hasInt = false;
                                boolean hasObject = false;
                                for (Class<?> p : params) {
                                    if (p == int.class) hasInt = true;
                                    else if (!p.isPrimitive() && !Context.class.isAssignableFrom(p)) hasObject = true;
                                }
                                if (hasInt && hasObject) {
                                    return m;
                                }
                            }
                        }
                        return null;
                    });

            if (tabBadgeMethod == null) {
                XposedBridge.log(TAG + " tab badge method not found");
                return;
            }

            Class<?>[] paramTypes = tabBadgeMethod.getParameterTypes();
            int tmpInt = -1, tmpBadge = -1, tmpCtx = -1;
            for (int i = 0; i < paramTypes.length; i++) {
                if (paramTypes[i] == int.class) {
                    tmpInt = i;
                } else if (Context.class.isAssignableFrom(paramTypes[i])) {
                    tmpCtx = i;
                } else if (!paramTypes[i].isPrimitive()) {
                    tmpBadge = i;
                }
            }

            if (tmpInt < 0 || tmpBadge < 0) {
                XposedBridge.log(TAG + " tab badge method parameters unrecognized");
                return;
            }

            final int intIndex = tmpInt;
            final int badgeIndex = tmpBadge;
            final int contextIndex = tmpCtx;
            final Class<?> badgeBaseClass = paramTypes[badgeIndex];

            Class<?> emptyBadgeClass = engine.findClassWithCache(context, classLoader,
                    "separate_groups.empty_badge_class", (bridge, loader) -> {
                        var subclasses = bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                                .superClass(badgeBaseClass.getName())));
                        for (var data : subclasses) {
                            Class<?> cls = data.getInstance(loader);
                            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                                if (ctor.getParameterCount() == 0) {
                                    return cls;
                                }
                            }
                        }
                        return null;
                    });

            Class<?> badgeWrapperClass = engine.findClassWithCache(context, classLoader,
                    "separate_groups.badge_wrapper_class", (bridge, loader) -> {
                        var subclasses = bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                                .superClass(badgeBaseClass.getName())));
                        for (var data : subclasses) {
                            Class<?> cls = data.getInstance(loader);
                            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                                int count = ctor.getParameterCount();
                                if (count >= 1 && !ctor.getParameterTypes()[0].isPrimitive()) {
                                    return cls;
                                }
                            }
                        }
                        return null;
                    });

            if (emptyBadgeClass == null || badgeWrapperClass == null) {
                XposedBridge.log(TAG + " badge classes could not be resolved");
                return;
            }

            Constructor<?> foundWrapperCtor = null;
            for (Constructor<?> ctor : badgeWrapperClass.getDeclaredConstructors()) {
                int count = ctor.getParameterCount();
                if (count >= 1 && !ctor.getParameterTypes()[0].isPrimitive()) {
                    if (foundWrapperCtor == null || count < foundWrapperCtor.getParameterCount()) {
                        foundWrapperCtor = ctor;
                    }
                }
            }
            if (foundWrapperCtor == null) return;
            foundWrapperCtor.setAccessible(true);
            final Constructor<?> wrapperCtor = foundWrapperCtor;
            final Class<?> badgeItemBaseClass = wrapperCtor.getParameterTypes()[0];

            Class<?> badgeItemClass = engine.findClassWithCache(context, classLoader,
                    "separate_groups.badge_item_class", (bridge, loader) -> {
                        for (Constructor<?> ctor : badgeItemBaseClass.getDeclaredConstructors()) {
                            if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == int.class) {
                                return badgeItemBaseClass;
                            }
                        }
                        var candidates = bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                                .superClass(badgeItemBaseClass.getName())));
                        for (var data : candidates) {
                            Class<?> cls = data.getInstance(loader);
                            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                                if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == int.class) {
                                    return cls;
                                }
                            }
                        }
                        return null;
                    });

            if (badgeItemClass == null) {
                XposedBridge.log(TAG + " badge item class could not be resolved");
                return;
            }

            Constructor<?> foundItemCtor = null;
            for (Constructor<?> ctor : badgeItemClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 1 && ctor.getParameterTypes()[0] == int.class) {
                    ctor.setAccessible(true);
                    foundItemCtor = ctor;
                    break;
                }
            }
            if (foundItemCtor == null) return;
            final Constructor<?> itemCtor = foundItemCtor;

            sTabBadgeMethod = tabBadgeMethod;
            sEmptyBadgeClass = emptyBadgeClass;
            sBadgeWrapperConstructor = wrapperCtor;
            sBadgeItemConstructor = itemCtor;
            sIntIndex = intIndex;
            sBadgeIndex = badgeIndex;
            sContextIndex = contextIndex;

            XposedBridge.hookMethod(tabBadgeMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isSeparateGroupsActive()) return;
                    int renderedIndex = (int) param.args[intIndex];
                    int chatsIndex = tabIndex(CHATS);
                    if (renderedIndex != chatsIndex) {
                        return;
                    }

                    // Cancel default combined badge rendering on Chats tab
                    param.setResult(null);

                    Object target = param.thisObject;
                    sLastBadgeTarget.set(new WeakReference<>(target));
                    if (contextIndex >= 0 && param.args[contextIndex] instanceof Context) {
                        sLastBadgeContext.set(new WeakReference<>((Context) param.args[contextIndex]));
                    }

                    scheduleBadgeUpdate(target, contextIndex >= 0 ? param.args[contextIndex] : null);
                }
            });

            XposedBridge.log(TAG + " successfully hooked tab badge setter");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " unread badge hook unavailable: " + t);
        }
    }

    private static void scheduleBadgeUpdate(Object target, Object ctx) {
        if (target == null || sTabBadgeMethod == null) return;
        final long seq = sBadgeUpdateSeq.incrementAndGet();
        sBadgeExecutor.execute(() -> {
            if (seq < sBadgeUpdateSeq.get()) return;
            UnreadCounts counts = queryUnreadCountsStatic();
            if (seq < sBadgeUpdateSeq.get()) return;

            sMainHandler.post(() -> {
                if (seq < sBadgeUpdateSeq.get()) return;
                try {
                    int cIndex = tabIndex(CHATS);
                    int gIndex = tabIndex(GROUPS);

                    Object emptyBadge = getEmptyBadge(sEmptyBadgeClass);
                    Object chatsBadge = counts.chats > 0
                            ? createCountBadge(sBadgeWrapperConstructor, sBadgeItemConstructor, counts.chats)
                            : emptyBadge;
                    Object groupsBadge = counts.groups > 0
                            ? createCountBadge(sBadgeWrapperConstructor, sBadgeItemConstructor, counts.groups)
                            : emptyBadge;

                    Class<?>[] pTypes = sTabBadgeMethod.getParameterTypes();
                    if (cIndex >= 0 && chatsBadge != null) {
                        Object[] args = new Object[pTypes.length];
                        if (sContextIndex >= 0) args[sContextIndex] = ctx;
                        args[sIntIndex] = cIndex;
                        args[sBadgeIndex] = chatsBadge;
                        de.robv.android.xposed.XposedBridge.invokeOriginalMethod(sTabBadgeMethod, target, args);
                    }
                    if (gIndex >= 0 && groupsBadge != null) {
                        Object[] args = new Object[pTypes.length];
                        if (sContextIndex >= 0) args[sContextIndex] = ctx;
                        args[sIntIndex] = gIndex;
                        args[sBadgeIndex] = groupsBadge;
                        de.robv.android.xposed.XposedBridge.invokeOriginalMethod(sTabBadgeMethod, target, args);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " error applying badges: " + t);
                }
            });
        });
    }

    public static void refreshBadges() {
        Object target = sLastBadgeTarget.get().get();
        Context ctx = sLastBadgeContext.get().get();
        if (target != null && sTabBadgeMethod != null) {
            scheduleBadgeUpdate(target, ctx);
        }
    }

    private static Object getEmptyBadge(Class<?> emptyBadgeClass) {
        if (emptyBadgeClass == null) return null;
        for (Field f : emptyBadgeClass.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && emptyBadgeClass.isAssignableFrom(f.getType())) {
                try {
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) return val;
                } catch (Throwable ignored) {}
            }
        }
        for (Constructor<?> ctor : emptyBadgeClass.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == 0) {
                try {
                    ctor.setAccessible(true);
                    return ctor.newInstance();
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private static Object createCountBadge(Constructor<?> wrapperCtor,
                                           Constructor<?> itemCtor,
                                           int count) {
        try {
            Object item = itemCtor.newInstance(count);
            Class<?>[] pTypes = wrapperCtor.getParameterTypes();
            Object[] args = new Object[pTypes.length];
            args[0] = item;
            for (int i = 1; i < pTypes.length; i++) {
                if (pTypes[i] == boolean.class) args[i] = false;
                else if (pTypes[i] == int.class) args[i] = 0;
                else if (pTypes[i] == long.class) args[i] = 0L;
                else args[i] = null;
            }
            return wrapperCtor.newInstance(args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " createCountBadge failed: " + t);
            return null;
        }
    }

    private static final class UnreadCounts {
        final int chats;
        final int groups;
        UnreadCounts(int chats, int groups) {
            this.chats = chats;
            this.groups = groups;
        }
    }

    private static UnreadCounts queryUnreadCountsStatic() {
        if (sAppContext == null) return new UnreadCounts(0, 0);
        try {
            SQLiteDatabase db = MessageStore.getInstance(sAppContext).getDatabase();
            if (db == null || !db.isOpen()) {
                return new UnreadCounts(0, 0);
            }

            String[] queries = new String[]{
                // Query 1: standard modern WhatsApp schema with chat_lock and group_type
                "SELECT "
                    + "COALESCE(SUM(CASE WHEN jid.server = ? THEN 0 ELSE 1 END), 0) AS chat_count, "
                    + "COALESCE(SUM(CASE WHEN jid.server = ? THEN 1 ELSE 0 END), 0) AS group_count "
                    + "FROM chat INNER JOIN jid ON jid._id = chat.jid_row_id "
                    + "WHERE chat.unseen_message_count <> 0 "
                    + "AND chat.archived = 0 "
                    + "AND chat.chat_lock = 0 "
                    + "AND (chat.group_type = 0 OR chat.group_type = 6)",
                // Query 2: fallback without chat_lock
                "SELECT "
                    + "COALESCE(SUM(CASE WHEN jid.server = ? THEN 0 ELSE 1 END), 0) AS chat_count, "
                    + "COALESCE(SUM(CASE WHEN jid.server = ? THEN 1 ELSE 0 END), 0) AS group_count "
                    + "FROM chat INNER JOIN jid ON jid._id = chat.jid_row_id "
                    + "WHERE chat.unseen_message_count <> 0 "
                    + "AND chat.archived = 0 "
                    + "AND (chat.group_type = 0 OR chat.group_type = 6)",
                // Query 3: universal fallback
                "SELECT "
                    + "COALESCE(SUM(CASE WHEN jid.server = ? THEN 0 ELSE 1 END), 0) AS chat_count, "
                    + "COALESCE(SUM(CASE WHEN jid.server = ? THEN 1 ELSE 0 END), 0) AS group_count "
                    + "FROM chat INNER JOIN jid ON jid._id = chat.jid_row_id "
                    + "WHERE chat.unseen_message_count <> 0 "
                    + "AND chat.archived = 0"
            };

            String[] args = new String[]{"g.us", "g.us"};
            for (String sql : queries) {
                try (Cursor cursor = db.rawQuery(sql, args)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int chatCount = cursor.getInt(cursor.getColumnIndexOrThrow("chat_count"));
                        int groupCount = cursor.getInt(cursor.getColumnIndexOrThrow("group_count"));
                        return new UnreadCounts(chatCount, groupCount);
                    }
                } catch (Throwable ignored) {
                    // Try next fallback query
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " queryUnreadCounts error: " + t);
        }
        return new UnreadCounts(0, 0);
    }

    private static int tabIndex(int tabId) {
        synchronized (tabs) {
            int idx = tabs.indexOf(tabId);
            if (idx >= 0) return idx;
        }
        if (tabId == CHATS) return 0;
        if (tabId == GROUPS) return 1;
        return -1;
    }

    private boolean isSeparateGroupsEnabled() {
        return isSeparateGroupsActive();
    }

    private static boolean isSeparateGroupsActive() {
        if (sPrefs != null) {
            return sPrefs.getBoolean("separate_groups_tabs", false) || sPrefs.getBoolean("separategroups", false);
        }
        return false;
    }
}
