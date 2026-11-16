package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.db.MessageStore;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.utils.HookErrors;
import com.waenhancer.xposed.utils.HostFields;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Locked Chats Enhancer Hook:
 * Enhances WhatsApp's native locked chats vault by:
 * 1. Suppressing notification previews/alerts for locked chats.
 * 2. Hiding locked contacts from appearing in contact pickers.
 */
public class LockedChatsEnhancerHook extends BaseFeature {

    private static final String TAG = "[WAEX][LockedChats]";
    private static final String PREF_KEY_1 = "locked_chats_enhancer";
    private static final String PREF_KEY_2 = "lockedchats_enhancer";
    private static final String CONTACT_PICKER_PACKAGE = "com.whatsapp.contact.ui.picker";
    private static final String PICKER_SINKS_CACHE_KEY = "waex_contact_picker_data_sinks_v1";
    /** Log tags the picker emits when contact data is delivered; the message text is unobfuscated. */
    private static final String[] PICKER_RESULT_LOG_TAGS = {
            "ContactPickerFragment/onContactsLoadResult",
            "ContactPickerFragment/onContactsLoaded",
            "ContactPickerFragment/onContactsFilterResult"};
    /** A results holder distributes many lists; this keeps the structural fallback specific. */
    private static final int MIN_HOLDER_LISTS = 3;

    private final ThreadLocal<Boolean> suppressNotificationQuery = ThreadLocal.withInitial(() -> false);
    private final AtomicBoolean loggedEmptyLockedSet = new AtomicBoolean();
    private final AtomicBoolean warnedUnidentifiableRows = new AtomicBoolean();
    private final AtomicInteger scrubLogBudget = new AtomicInteger(5);
    private volatile Object chatCacheInstance = null;
    private volatile Field lockedChatsField = null;
    private final LockedChatIds lockedChatIds = new LockedChatIds(
            () -> LockedChatIds.overDatabase(() -> MessageStore.getInstance(context).getDatabase()),
            System::currentTimeMillis);

    public LockedChatsEnhancerHook(@NonNull Context context,
                                   @NonNull ClassLoader classLoader,
                                   @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Locked Chats Enhancer";
    }

    private boolean isFeatureEnabled() {
        return isEnabled(PREF_KEY_1, false) || isEnabled(PREF_KEY_2, false);
    }

    @Override
    public void hook() throws Throwable {
        try {
            hookNotificationSuppression();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking notification suppression: " + t.getMessage());
        }

        try {
            hookContactPickerSuppression();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking contact picker suppression: " + t.getMessage());
        }
    }

    private void hookNotificationSuppression() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        Method notificationMethod = engine.findMethodWithCache(
                context,
                classLoader,
                "waex_locked_chats_notification_query",
                (bridge, loader) -> {
                    MethodData md = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .addUsingString("LastMessageStore/getLastMessagesForNotificationAfterReply", StringMatchType.Contains)
                            )
                    ).firstOrNull();
                    return md != null ? md.getMethodInstance(loader) : null;
                }
        );

        if (notificationMethod != null) {
            XposedBridge.hookMethod(notificationMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isFeatureEnabled()) {
                        suppressNotificationQuery.set(true);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    suppressNotificationQuery.remove();
                }
            });
        }

        Method lockedChatsQueryMethod = engine.findMethodWithCache(
                context,
                classLoader,
                "waex_locked_chats_query_list",
                (bridge, loader) -> {
                    ClassData convMgrClass = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .addUsingString("conversationsmgr/replacecontact", StringMatchType.Contains)
                            )
                    ).firstOrNull();

                    if (convMgrClass != null && notificationMethod != null) {
                        MethodData invokedMethod = bridge.getMethodData(notificationMethod);
                        if (invokedMethod != null) {
                            for (MethodData invoke : invokedMethod.getInvokes()) {
                                if (invoke.isMethod() && convMgrClass.getName().equals(invoke.getClassName())
                                        && ArrayList.class.getName().equals(invoke.getReturnType())) {
                                    return invoke.getMethodInstance(loader);
                                }
                            }
                        }
                    }
                    return null;
                }
        );

        if (lockedChatsQueryMethod != null) {
            XposedBridge.hookMethod(lockedChatsQueryMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (isFeatureEnabled() && Boolean.TRUE.equals(suppressNotificationQuery.get())) {
                        param.setResult(new ArrayList<>());
                    }
                }
            });
        }
    }

    private void hookContactPickerSuppression() {
        DexSearchEngine engine = DexSearchEngine.getInstance();

        // Locked chats are read from the database. The chat cache is only a fallback for a
        // database that cannot be read, so its absence must not stop the picker hooks.
        try {
            installChatCacheFallback(engine);
        } catch (Throwable error) {
            HookErrors.swallowed("LockedChatsEnhancerHook", error);
        }

        Method[] sinks = engine.findMethodsWithCache(
                context, classLoader, PICKER_SINKS_CACHE_KEY,
                LockedChatsEnhancerHook::resolvePickerDataSinks);
        if (sinks == null || sinks.length == 0) {
            XposedBridge.log(TAG + " contact picker data methods not found; picker stays unfiltered");
            return;
        }
        XC_MethodHook scrubber = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                scrubPickerData(param.args);
            }
        };
        for (Method sink : sinks) {
            XposedBridge.hookMethod(sink, scrubber);
            // Obfuscated host names only; this is what to report when the picker is not filtered.
            XposedBridge.log(TAG + " hooked " + sink.getDeclaringClass().getName() + "#" + sink.getName()
                    + "/" + sink.getParameterTypes().length);
        }
    }

    /**
     * Finds the host methods that receive contact data for the picker, without relying on any
     * obfuscated name, parameter list or A/B-flag constant. In order of preference:
     * <ol>
     *   <li>methods that log one of the picker's own result tags (log text survives
     *       obfuscation, and the picker class itself is XML-referenced so it keeps its name);</li>
     *   <li>one-argument methods declared in the picker package whose argument is a results
     *       holder, i.e. a host class with several {@link java.util.List} fields.</li>
     * </ol>
     * Every returned method is hooked generically, so the argument shape does not matter.
     */
    private static Method[] resolvePickerDataSinks(DexKitBridge bridge, ClassLoader loader) {
        LinkedHashSet<Method> found = new LinkedHashSet<>();
        for (String tag : PICKER_RESULT_LOG_TAGS) {
            for (MethodData method : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().addUsingString(tag, StringMatchType.Contains)))) {
                addResolved(found, method, loader);
            }
        }
        if (found.isEmpty()) {
            for (MethodData method : bridge.findMethod(FindMethod.create()
                    .searchPackages(CONTACT_PICKER_PACKAGE)
                    .matcher(MethodMatcher.create().paramCount(1)))) {
                Method resolved = resolveOrNull(method, loader);
                if (resolved != null
                        && ContactListSanitizer.isResultsHolder(resolved.getParameterTypes()[0], MIN_HOLDER_LISTS)) {
                    found.add(resolved);
                }
            }
        }
        return found.isEmpty() ? null : found.toArray(new Method[0]);
    }

    private static void addResolved(Set<Method> into, MethodData data, ClassLoader loader) {
        Method method = resolveOrNull(data, loader);
        if (method != null) into.add(method);
    }

    private static Method resolveOrNull(MethodData data, ClassLoader loader) {
        try {
            Method method = data.getMethodInstance(loader);
            return Modifier.isAbstract(method.getModifiers()) ? null : method;
        } catch (Throwable unresolvable) {
            HookErrors.swallowed("LockedChatsEnhancerHook", unresolvable);
            return null;
        }
    }

    private void scrubPickerData(Object[] args) {
        if (args == null || args.length == 0 || !isFeatureEnabled()) return;
        try {
            Set<String> locked = lockedIds();
            if (locked.isEmpty()) {
                if (loggedEmptyLockedSet.compareAndSet(false, true)) {
                    XposedBridge.log(TAG + " no locked chats known yet; picker data left untouched");
                }
                return;
            }
            ContactListSanitizer.Result result = ContactListSanitizer.scrub(
                    args, locked, ContactIdentityExtractor::identities);
            if (result.rowsSeen > 0 && result.rowsIdentified == 0
                    && warnedUnidentifiableRows.compareAndSet(false, true)) {
                // Locked chats exist but no picker row yielded an identity: the host's JID
                // access has probably changed. Say so once instead of failing silently.
                XposedBridge.log(TAG + " WARNING: " + result.rowsSeen
                        + " picker rows seen but none identifiable; JID access may have changed");
            }
            if (result.removedContacts > 0 && scrubLogBudget.getAndDecrement() > 0) {
                XposedBridge.log(TAG + " removed " + result.removedContacts
                        + " locked contact(s) from " + result.cleanedLists + " list(s)");
            }
        } catch (Throwable error) {
            HookErrors.swallowed("LockedChatsEnhancerHook", error);
        }
    }

    private void installChatCacheFallback(DexSearchEngine engine) {
        Class<?> chatCacheClass = engine.findClassWithCache(
                context,
                classLoader,
                "waex_chat_cache_class",
                (bridge, loader) -> {
                    ClassData cd = bridge.findClass(FindClass.create()
                            .matcher(ClassMatcher.create()
                                    .addUsingString("Chatscache/", StringMatchType.StartsWith)
                            )
                    ).firstOrNull();
                    return cd != null ? cd.getInstance(loader) : null;
                }
        );
        if (chatCacheClass == null) return;
        Field field = selectLockedChatsField(chatCacheClass);
        if (field == null) {
            XposedBridge.log(TAG + " chat cache has no single locked-set candidate; fallback disabled");
            return;
        }
        lockedChatsField = field;
        XposedBridge.hookAllConstructors(chatCacheClass, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                chatCacheInstance = param.thisObject;
            }
        });
    }

    /** Locked identities from WhatsApp's database, else from the chat cache, else none. */
    private Set<String> lockedIds() throws IllegalAccessException {
        Set<String> fromDatabase = lockedChatIds.current();
        if (fromDatabase != null) return fromDatabase;
        Object cache = chatCacheInstance;
        Field field = lockedChatsField;
        if (cache == null || field == null) return Collections.emptySet();
        Object lockedChats = field.get(cache);
        if (!(lockedChats instanceof Collection) || ((Collection<?>) lockedChats).isEmpty()) {
            return Collections.emptySet();
        }
        return LockedContactFilterPolicy.collectIds(
                (Collection<?>) lockedChats, ContactIdentityExtractor::identities);
    }

    /**
     * The chat cache's locked-chat set, but only when it is unambiguous. With several candidate
     * collections nothing marks which one is "locked", and guessing would hide the wrong
     * contacts, so the fallback stays off instead.
     */
    private static Field selectLockedChatsField(Class<?> type) {
        List<Field> candidates = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : HostFields.declared(current)) {
                if (!Modifier.isStatic(field.getModifiers()) && Set.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    candidates.add(field);
                }
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }
}
