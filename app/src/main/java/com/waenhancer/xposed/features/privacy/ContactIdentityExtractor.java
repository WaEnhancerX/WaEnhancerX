package com.waenhancer.xposed.features.privacy;

import com.waenhancer.xposed.utils.HookErrors;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
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

    /** All identity strings of {@code value}: its own, or those of its JID-typed fields. */
    static Collection<String> identities(Object value) {
        if (value == null) return Collections.emptyList();
        LinkedHashSet<String> ids = new LinkedHashSet<>(4);
        Plan plan = planFor(value.getClass());
        if (plan.identityMethods.length > 0) {
            collect(ids, value, plan);
        } else {
            for (Field field : plan.wrapperFields) {
                try {
                    Object member = field.get(value);
                    if (member != null) collect(ids, member, planFor(member.getClass()));
                } catch (IllegalAccessException | RuntimeException failure) {
                    HookErrors.swallowed("ContactIdentityExtractor", failure);
                }
            }
        }
        return ids;
    }

    private static void collect(LinkedHashSet<String> ids, Object jid, Plan plan) {
        for (Method method : plan.identityMethods) {
            try {
                Object result = method.invoke(jid);
                if (result instanceof String && !((String) result).isEmpty()) ids.add((String) result);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                HookErrors.swallowed("ContactIdentityExtractor", failure);
            }
        }
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
