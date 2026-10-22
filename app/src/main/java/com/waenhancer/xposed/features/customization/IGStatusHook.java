package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.ListView;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.views.IGStatusAdapter;
import com.waenhancer.xposed.views.IGStatusView;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

public class IGStatusHook extends BaseFeature {

    private static final String TAG = "[WAEX][IGStatus]";

    public static final CopyOnWriteArrayList<Object> items = new CopyOnWriteArrayList<>();
    private static final List<WeakReference<IGStatusView>> statusContainers = new ArrayList<>();

    public IGStatusHook(@NonNull Context context,
                        @NonNull ClassLoader classLoader,
                        @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Enable IGStatus on Home Screen";
    }

    @NonNull
    @Override
    public String getCategory() {
        return "Customization";
    }

    @Override
    public void hook() throws Throwable {
        boolean enabled = prefs.getBoolean("igstatus", false);
        if (!enabled) {
            return;
        }

        DexSearchEngine engine = DexSearchEngine.getInstance();
        engine.initialize(context);

        // 1. Resolve classes and methods
        Class<?> conversationsFragClass = resolveConversationsFragmentClass(engine);
        Class<?> archivedFragClass = resolveArchivedFragmentClass(engine);
        Class<?> folderFragClass = resolveFolderFragmentClass(engine);

        Method fabIntMethod = resolveFabIntMethod(engine, conversationsFragClass);
        Method onCreateViewMethod = resolveOnCreateViewMethod(conversationsFragClass);

        Class<?> statusInfoClass = resolveStatusInfoClass(engine);
        Class<?> contactStatusThumbnailClass = resolveContactStatusThumbnailClass(engine);
        Class<?> statusListUpdatesClass = resolveStatusListUpdatesClass(engine);

        // 2. Hook ConversationsFragment onCreateView to inject IGStatusView at top of chat list
        if (onCreateViewMethod != null) {
            XposedBridge.hookMethod(onCreateViewMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object fragment = param.thisObject;
                        if (archivedFragClass != null && archivedFragClass.isInstance(fragment)) return;
                        if (folderFragClass != null && folderFragClass.isInstance(fragment)) return;

                        View rootView = (View) param.getResult();
                        if (!(rootView instanceof ViewGroup)) return;

                        ViewGroup view = (ViewGroup) rootView;
                        View listChild = view.findViewById(android.R.id.list);
                        if (listChild == null) {
                            int listId = context.getResources().getIdentifier("list", "id", context.getPackageName());
                            if (listId != 0) {
                                listChild = view.findViewById(listId);
                            }
                        }
                        if (listChild == null) return;

                        Activity activity = fragment instanceof androidx.fragment.app.Fragment
                                ? ((androidx.fragment.app.Fragment) fragment).getActivity()
                                : null;
                        if (activity == null && listChild.getContext() instanceof Activity) {
                            activity = (Activity) listChild.getContext();
                        }
                        if (activity == null) return;

                        IGStatusView statusContainer = new IGStatusView(activity);
                        int heightPx = (int) (84f * activity.getResources().getDisplayMetrics().density + 0.5f);

                        if (listChild instanceof ListView) {
                            ListView listView = (ListView) listChild;
                            listView.setNestedScrollingEnabled(true);
                            AbsListView.LayoutParams layoutParams = new AbsListView.LayoutParams(
                                    AbsListView.LayoutParams.MATCH_PARENT, heightPx);
                            statusContainer.setLayoutParams(layoutParams);
                            listView.addHeaderView(statusContainer);
                        } else {
                            statusContainer.setLayoutParams(new ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT, heightPx));
                            attachRecyclerHeaderWhenReady(fragment, listChild, statusContainer);
                        }

                        int id = 0;
                        if (fabIntMethod != null) {
                            try {
                                Object ret = fabIntMethod.invoke(fragment);
                                if (ret instanceof Integer) {
                                    id = (Integer) ret;
                                }
                            } catch (Throwable ignored) {}
                        }

                        statusContainer.fragmentId = id;
                        trackStatusContainer(statusContainer);

                        // Attach initial adapter
                        Class<?> targetStatusInfo = statusInfoClass != null ? statusInfoClass : Object.class;
                        IGStatusAdapter adapter = new IGStatusAdapter(activity, targetStatusInfo, contactStatusThumbnailClass);
                        statusContainer.setAdapter(adapter);
                        statusContainer.updateList();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " error injecting IGStatusView: " + t.getMessage());
                    }
                }
            });
        }

        // 3. Hook UpdatesViewModel constructor / refresh
        Method onUpdateStatusChanged = resolveOnUpdateStatusChanged(engine);
        if (onUpdateStatusChanged != null) {
            Class<?> updatesViewModelClass = onUpdateStatusChanged.getDeclaringClass();
            for (Constructor<?> ctor : updatesViewModelClass.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ensureMyStatusHeader();
                        updateAllContainers();
                    }
                });
            }

            Field invokeField = resolveInvokeField(onUpdateStatusChanged);
            XposedBridge.hookMethod(onUpdateStatusChanged, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (invokeField == null || param.args == null || param.args.length == 0) return;
                        Object obj = invokeField.get(param.args[0]);
                        if (obj == null) return;

                        Method dataMethod = null;
                        for (Method m : obj.getClass().getDeclaredMethods()) {
                            if (m.getParameterTypes().length == 0 && m.getReturnType() != void.class) {
                                dataMethod = m;
                                break;
                            }
                        }
                        if (dataMethod == null) return;
                        dataMethod.setAccessible(true);
                        Object statusListUpdates = dataMethod.invoke(obj);
                        if (statusListUpdates == null) return;

                        List<Field> listFields = new ArrayList<>();
                        for (Field f : statusListUpdates.getClass().getDeclaredFields()) {
                            if (List.class.isAssignableFrom(f.getType())) {
                                f.setAccessible(true);
                                listFields.add(f);
                            }
                        }
                        if (listFields.size() < 2) return;

                        List<?> list1 = (List<?>) listFields.get(0).get(statusListUpdates);
                        List<?> list2 = (List<?>) listFields.get(1).get(statusListUpdates);

                        ArrayList<Object> newList = new ArrayList<>();
                        newList.add(null); // My Status header
                        if (list1 != null) newList.addAll(list1);
                        if (list2 != null) newList.addAll(list2);

                        items.clear();
                        items.addAll(newList);
                        updateAllContainers();
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " onUpdateStatusChanged hook error: " + t.getMessage());
                    }
                }
            });
        }

        // 4. Hook status list updates class constructors
        if (statusListUpdatesClass != null) {
            for (Constructor<?> ctor : statusListUpdatesClass.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            if (param.args == null) return;
                            List<List<?>> lists = new ArrayList<>();
                            for (Object arg : param.args) {
                                if (arg instanceof List) {
                                    lists.add((List<?>) arg);
                                }
                            }
                            if (lists.size() >= 2) {
                                ArrayList<Object> newList = new ArrayList<>();
                                newList.add(null);
                                newList.addAll(lists.get(0));
                                newList.addAll(lists.get(1));
                                items.clear();
                                items.addAll(newList);
                                updateAllContainers();
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " status updates constructor hook error: " + t.getMessage());
                        }
                    }
                });
            }
        }
    }

    private static void ensureMyStatusHeader() {
        if (items.isEmpty() || items.get(0) != null) {
            items.add(0, null);
        }
    }

    private static void updateAllContainers() {
        List<IGStatusView> attachedContainers = new ArrayList<>();
        synchronized (statusContainers) {
            for (Iterator<WeakReference<IGStatusView>> iterator = statusContainers.iterator(); iterator.hasNext();) {
                IGStatusView view = iterator.next().get();
                if (view == null) {
                    iterator.remove();
                } else if (view.isAttachedToWindow()) {
                    attachedContainers.add(view);
                }
            }
        }
        for (IGStatusView view : attachedContainers) view.updateList();
    }

    private static void trackStatusContainer(IGStatusView view) {
        registerStatusContainer(view);
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View attached) {
                registerStatusContainer((IGStatusView) attached);
            }
            @Override public void onViewDetachedFromWindow(View detached) {
                synchronized (statusContainers) {
                    statusContainers.removeIf(reference -> reference.get() == null || reference.get() == detached);
                }
            }
        });
    }

    private static void registerStatusContainer(IGStatusView view) {
        synchronized (statusContainers) {
            statusContainers.removeIf(reference -> {
                IGStatusView previous = reference.get();
                return previous == null || previous == view || previous.fragmentId == view.fragmentId;
            });
            statusContainers.add(new WeakReference<>(view));
        }
    }

    private static void attachRecyclerHeaderWhenReady(
            Object fragment, View scrollingView, IGStatusView header) {
        RecyclerHeaderAttachment attachment = new RecyclerHeaderAttachment(fragment, scrollingView, header);
        scrollingView.addOnAttachStateChangeListener(attachment);
        if (scrollingView.isAttachedToWindow()) attachment.onViewAttachedToWindow(scrollingView);
    }

    /** Retries follow the scrolling View's attachment lifecycle. */
    private static final class RecyclerHeaderAttachment implements Runnable, View.OnAttachStateChangeListener {
        private final WeakReference<Object> fragment;
        private final WeakReference<View> scrollingView;
        // Keep a not-yet-inserted header alive while its owner View is alive.
        private IGStatusView header;
        private int attempts;
        private boolean posted;

        RecyclerHeaderAttachment(Object fragment, View scrollingView, IGStatusView header) {
            this.fragment = new WeakReference<>(fragment);
            this.scrollingView = new WeakReference<>(scrollingView);
            this.header = header;
        }

        @Override public void onViewAttachedToWindow(View view) {
            attempts = 0;
            schedule(view, 0L);
        }

        @Override public void onViewDetachedFromWindow(View view) {
            view.removeCallbacks(this);
            posted = false;
        }

        private void schedule(View view, long delay) {
            if (header == null || posted) return;
            posted = true;
            if (!view.postDelayed(this, delay)) posted = false;
        }

        private void finish(View view) {
            view.removeCallbacks(this);
            view.removeOnAttachStateChangeListener(this);
            posted = false;
            header = null;
            fragment.clear();
        }

        @Override public void run() {
            posted = false;
            View view = scrollingView.get();
            Object owner = fragment.get();
            if (view == null || header == null) return;
            Activity activity = header.getContext() instanceof Activity ? (Activity) header.getContext() : null;
            if (owner == null || (activity != null && (activity.isFinishing() || activity.isDestroyed()))) {
                finish(view);
                return;
            }
            if (!view.isAttachedToWindow()) return;
            if (header.isAttachedToWindow() || tryAttachRecyclerHeader(owner, view, header)) {
                finish(view);
            } else if (++attempts < 31) {
                schedule(view, 100L);
            } else {
                finish(view);
            }
        }
    }

    /** Adds the stories row through WhatsApp's HeaderFooterRecyclerViewAdapter controller. */
    private static boolean tryAttachRecyclerHeader(Object fragment, View scrollingView, View header) {
        for (Class<?> type = fragment.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object controller = field.get(fragment);
                    if (controller == null || !ownsScrollingView(controller, scrollingView)) continue;

                    for (Method method : controller.getClass().getDeclaredMethods()) {
                        Class<?>[] parameters = method.getParameterTypes();
                        if (parameters.length == 2
                                && View.class.isAssignableFrom(parameters[0])
                                && parameters[1] == Integer.class
                                && method.getReturnType() == void.class) {
                            method.setAccessible(true);
                            method.invoke(controller, header, null);
                            return true;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static boolean ownsScrollingView(Object controller, View scrollingView) {
        for (Class<?> type = controller.getClass(); type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().contains("RecyclerView")) continue;
                try {
                    field.setAccessible(true);
                    if (field.get(controller) == scrollingView) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private Class<?> resolveConversationsFragmentClass(DexSearchEngine engine) {
        Class<?> clazz = XposedHelpers.findClassIfExists(
                "com.whatsapp.conversationslist.ConversationsFragment", classLoader);
        if (clazz != null) return clazz;
        return engine.findClassWithCache(context, classLoader, "ig_conversations_fragment",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().className(".ConversationsFragment", StringMatchType.EndsWith))).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                });
    }

    private Class<?> resolveArchivedFragmentClass(DexSearchEngine engine) {
        String[] candidates = {
                "com.whatsapp.conversation.conversationslist.ArchivedConversationsFragment",
                "com.whatsapp.conversationslist.ArchivedConversationsFragment"
        };
        for (String c : candidates) {
            Class<?> clazz = XposedHelpers.findClassIfExists(c, classLoader);
            if (clazz != null) return clazz;
        }
        return engine.findClassWithCache(context, classLoader, "ig_archived_fragment",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().className("ArchivedConversationsFragment", StringMatchType.EndsWith))).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                });
    }

    private Class<?> resolveFolderFragmentClass(DexSearchEngine engine) {
        String[] candidates = {
                "com.whatsapp.conversation.conversationslist.FolderConversationsFragment",
                "com.whatsapp.conversation.conversationslist.BusinessFolderConversationsFragment",
                "com.whatsapp.conversationslist.FolderConversationsFragment"
        };
        for (String c : candidates) {
            Class<?> clazz = XposedHelpers.findClassIfExists(c, classLoader);
            if (clazz != null) return clazz;
        }
        return engine.findClassWithCache(context, classLoader, "ig_folder_fragment",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().className("FolderConversationsFragment", StringMatchType.EndsWith))).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                });
    }

    private Method resolveFabIntMethod(DexSearchEngine engine, Class<?> conversationsFragClass) {
        if (conversationsFragClass != null) {
            for (Class<?> c = conversationsFragClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 0 && m.getReturnType() == int.class) {
                        try {
                            m.setAccessible(true);
                            return m;
                        } catch (Throwable ignored) {}
                    }
                }
            }
        }
        return engine.findMethodWithCache(context, classLoader, "ig_fab_method",
                (bridge, loader) -> {
                    ClassData cd = bridge.getClassData("com.whatsapp.conversationslist.ConversationsFragment");
                    if (cd != null) {
                        for (ClassData curr : new ClassData[]{cd, cd.getSuperClass()}) {
                            if (curr == null) continue;
                            MethodData md = curr.findMethod(FindMethod.create().matcher(
                                    MethodMatcher.create().paramCount(0).addUsingNumber(200).returnType(int.class))).firstOrNull();
                            if (md != null) return md.getMethodInstance(loader);
                        }
                    }
                    return null;
                });
    }

    private Method resolveOnCreateViewMethod(Class<?> conversationsFragClass) {
        if (conversationsFragClass == null) return null;
        for (Class<?> c = conversationsFragClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] methodParameters = m.getParameterTypes();
                if (methodParameters.length == 3 && m.getReturnType() == View.class
                        && methodParameters[0] == LayoutInflater.class
                        && methodParameters[1] == ViewGroup.class) {
                    m.setAccessible(true);
                    return m;
                }
            }
        }
        return null;
    }

    private Class<?> resolveStatusInfoClass(DexSearchEngine engine) {
        return engine.findClassWithCache(context, classLoader, "ig_status_info_class",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().addUsingString("ContactStatusDataItem(statusInfo=", StringMatchType.Contains))).firstOrNull();
                    if (cd == null) {
                        cd = bridge.findClass(FindClass.create().matcher(
                                ClassMatcher.create().addUsingString("StatusInfo/status-create/", StringMatchType.Contains))).firstOrNull();
                    }
                    return cd != null ? cd.getInstance(loader) : null;
                });
    }

    private Class<?> resolveContactStatusThumbnailClass(DexSearchEngine engine) {
        Class<?> direct = XposedHelpers.findClassIfExists("com.whatsapp.status.api.ContactStatusThumbnail", classLoader);
        if (direct != null) return direct;
        return engine.findClassWithCache(context, classLoader, "ig_contact_status_thumbnail_class",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().className(".ContactStatusThumbnail", StringMatchType.EndsWith))).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                });
    }

    private Class<?> resolveStatusListUpdatesClass(DexSearchEngine engine) {
        return engine.findClassWithCache(context, classLoader, "ig_status_list_updates_class",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().addUsingString(", newUpdates=", StringMatchType.Contains))).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                });
    }

    private Method resolveOnUpdateStatusChanged(DexSearchEngine engine) {
        return engine.findMethodWithCache(context, classLoader, "ig_on_update_status_changed_method",
                (bridge, loader) -> {
                    MethodData md = bridge.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString("UpdatesViewModel/Scheduled updates list refresh", StringMatchType.Contains))).firstOrNull();
                    return md != null && md.isMethod() ? md.getMethodInstance(loader) : null;
                });
    }

    private Field resolveInvokeField(Method onUpdateStatusChanged) {
        if (onUpdateStatusChanged == null) return null;
        for (Field f : onUpdateStatusChanged.getDeclaringClass().getDeclaredFields()) {
            if (f.getType().getName().contains("Jg1") || f.getType().getName().contains("StatusListUpdates")) {
                f.setAccessible(true);
                return f;
            }
        }
        for (Field f : onUpdateStatusChanged.getDeclaringClass().getDeclaredFields()) {
            f.setAccessible(true);
            return f;
        }
        return null;
    }
}
