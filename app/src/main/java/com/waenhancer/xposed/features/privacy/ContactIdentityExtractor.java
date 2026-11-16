package com.waenhancer.xposed.features.privacy;

import com.waenhancer.xposed.utils.HookErrors;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the JID strings carried by a host contact or chat object without naming any obfuscated
 * class, field or method.
 *
 * The only host contract it relies on is the public {@code getRawString()} of WhatsApp's JID
 * types, whose names must stay readable because they are serialized and logged. A wrapper that
 * is not itself a JID is searched one level deep for JID-typed fields. Reflection results are
 * cached per class, so no exception is thrown and swallowed per element.
 */
final class ContactIdentityExtractor {
    private static final String RAW_STRING = "getRawString";
    private static final String[] OPTIONAL_STRING_IDENTITIES = {
            "getPhoneNumber", "getUserRawString", "getPhoneRawString"};
    private static final String[] PLATFORM_PREFIXES = {
            "java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.", "dalvik.", "sun."};

    private static final Set<String> CONVERSATION_SERVERS = new HashSet<>(
            Arrays.asList("g.us", "broadcast", "newsletter"));
    private static final Method[] NO_METHODS = new Method[0];
    private static final Field[] NO_FIELDS = new Field[0];
    private static final Plan NOT_A_CONTACT = new Plan(NO_METHODS, NO_FIELDS);
    private static final Map<Class<?>, Plan> PLANS = new ConcurrentHashMap<>();

    private ContactIdentityExtractor() {}

    private static final class Plan {
        /** Non-empty when the class is itself a JID. */
        final Method[] identityMethods;
        /** Candidate fields of a wrapper, searched when the class is not a JID. */
        final Field[] wrapperFields;

        Plan(Method[] identityMethods, Field[] wrapperFields) {
            this.identityMethods = identityMethods;
            this.wrapperFields = wrapperFields;
        }
    }

    /** Wrapper levels searched below an item; real picker rows nest their JID three levels down. */
    private static final int MAX_DEPTH = 4;
    /** Hard cap on visited objects so a pathological graph can never stall the UI thread. */
    private static final int MAX_NODES = 96;

    /** Per-thread work arrays: the search runs once per row, so it must not allocate. */
    private static final class Scratch {
        final Object[] seen = new Object[MAX_NODES];
        Object[] level = new Object[MAX_NODES];
        Object[] next = new Object[MAX_NODES];
        int seenCount;
    }

    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    /**
     * The identity strings of {@code value}: its own when it is a JID, otherwise those of the
     * nearest JIDs reachable through its object fields. "Nearest" keeps an item's own identity
     * apart from anything referenced further away, such as a member of a group row.
     */
    static Collection<String> identities(Object value) {
        if (value == null) return Collections.emptyList();
        Scratch scratch = SCRATCH.get();
        ArrayList<String> ids = null;
        try {
            scratch.seen[0] = value;
            scratch.seenCount = 1;
            scratch.level[0] = value;
            int levelSize = 1;
            for (int depth = 0; depth <= MAX_DEPTH && levelSize > 0; depth++) {
                int nextSize = 0;
                for (int n = 0; n < levelSize; n++) {
                    Object node = scratch.level[n];
                    Plan plan = planFor(node.getClass());
                    if (plan.identityMethods.length > 0) {
                        ids = collect(ids, node, plan);
                        continue;
                    }
                    for (Field field : plan.wrapperFields) {
                        if (scratch.seenCount >= MAX_NODES) break;
                        Object member = read(field, node);
                        if (member != null && !seen(scratch, member)) {
                            scratch.seen[scratch.seenCount++] = member;
                            scratch.next[nextSize++] = member;
                        }
                    }
                }
                // The first level that holds any JID is the item's own; do not look deeper.
                if (ids != null) break;
                Object[] swap = scratch.level;
                scratch.level = scratch.next;
                scratch.next = swap;
                levelSize = nextSize;
            }
        } finally {
            // Never keep host objects alive from a thread-local.
            Arrays.fill(scratch.seen, 0, scratch.seenCount, null);
            Arrays.fill(scratch.level, null);
            Arrays.fill(scratch.next, null);
        }
        return ids == null ? Collections.emptyList() : conversationIdentity(ids);
    }

    private static boolean seen(Scratch scratch, Object candidate) {
        for (int i = 0; i < scratch.seenCount; i++) {
            if (scratch.seen[i] == candidate) return true;
        }
        return false;
    }

    private static Object read(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException | RuntimeException failure) {
            HookErrors.swallowed("ContactIdentityExtractor", failure);
            return null;
        }
    }

    /**
     * A row that carries a group, broadcast or newsletter id <em>is</em> that conversation. Any
     * user id beside it (last sender, creator, member) describes someone else, so it must not
     * make the row match a locked person. These server names are part of WhatsApp's wire
     * protocol and do not change with obfuscation.
     */
    private static Collection<String> conversationIdentity(ArrayList<String> ids) {
        ArrayList<String> conversation = null;
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            if (isConversationServer(id)) {
                if (conversation == null) conversation = new ArrayList<>(2);
                conversation.add(id);
            }
        }
        return conversation != null ? conversation : ids;
    }

    private static boolean isConversationServer(String id) {
        int at = id.indexOf('@');
        return at >= 0 && CONVERSATION_SERVERS.contains(id.substring(at + 1));
    }

    private static ArrayList<String> collect(ArrayList<String> ids, Object jid, Plan plan) {
        for (Method method : plan.identityMethods) {
            try {
                Object result = method.invoke(jid);
                if (result instanceof String && !((String) result).isEmpty()) {
                    if (ids == null) ids = new ArrayList<>(3);
                    if (!ids.contains(result)) ids.add((String) result);
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                HookErrors.swallowed("ContactIdentityExtractor", failure);
            }
        }
        return ids;
    }

    private static Plan planFor(Class<?> type) {
        Plan cached = PLANS.get(type);
        if (cached != null) return cached;
        Plan created = buildPlan(type);
        Plan raced = PLANS.putIfAbsent(type, created);
        return raced != null ? raced : created;
    }

    private static Plan buildPlan(Class<?> type) {
        if (isPlatform(type)) return NOT_A_CONTACT;
        Method rawString = stringGetter(type, RAW_STRING);
        if (rawString != null) {
            ArrayList<Method> methods = new ArrayList<>(1 + OPTIONAL_STRING_IDENTITIES.length);
            methods.add(rawString);
            for (String name : OPTIONAL_STRING_IDENTITIES) {
                Method optional = stringGetter(type, name);
                if (optional != null) methods.add(optional);
            }
            return new Plan(methods.toArray(NO_METHODS), NO_FIELDS);
        }
        ArrayList<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && !isPlatform(current); current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (isWrapperCandidate(field)) {
                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (RuntimeException failure) {
                        HookErrors.swallowed("ContactIdentityExtractor", failure);
                    }
                }
            }
        }
        return fields.isEmpty() ? NOT_A_CONTACT : new Plan(NO_METHODS, fields.toArray(NO_FIELDS));
    }

    private static boolean isWrapperCandidate(Field field) {
        if (Modifier.isStatic(field.getModifiers())) return false;
        Class<?> type = field.getType();
        if (type.isPrimitive() || type.isArray() || type == String.class) return false;
        if (Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)) return false;
        // A field typed Object may still hold a JID at runtime; other platform types cannot.
        return type == Object.class || !isPlatform(type);
    }

    private static Method stringGetter(Class<?> type, String name) {
        try {
            Method method = type.getMethod(name);
            if (method.getReturnType() != String.class || Modifier.isStatic(method.getModifiers())) return null;
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException absent) {
            return null; // Expected for non-JID classes; cached by the caller.
        } catch (RuntimeException failure) {
            HookErrors.swallowed("ContactIdentityExtractor", failure);
            return null;
        }
    }

    static boolean isPlatform(Class<?> type) {
        if (type.isPrimitive() || type.getClassLoader() == null) return true;
        String name = type.getName();
        for (String prefix : PLATFORM_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
