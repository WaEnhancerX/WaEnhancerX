package com.waenhancer.xposed.features.privacy;

import com.waenhancer.xposed.utils.HookErrors;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Removes locked contacts from whatever contact data a host method receives, without knowing
 * which argument or which field carries which list.
 *
 * A host "results" object distributes many lists (all contacts, top contacts, groups, frequent,
 * recent, search sources...) and the UI reads whichever one it needs. Cleaning one hand-picked
 * list leaves the contact visible through the others, so every {@link List} field of the object
 * is cleaned, and so is a bare {@link List} argument.
 */
final class ContactListSanitizer {
    private static final Field[] NO_FIELDS = new Field[0];
    private static final Map<Class<?>, Field[]> LIST_FIELDS = new ConcurrentHashMap<>();

    private ContactListSanitizer() {}

    /** What one {@link #scrub} call changed. */
    static final class Result {
        int removedContacts;
        int cleanedLists;
    }

    /**
     * Cleans {@code args} in place: a {@link List} argument is replaced by a filtered copy, and
     * the {@link List} fields of any other host object are replaced on that object.
     */
    static Result scrub(Object[] args, Set<String> lockedIds,
                        Function<Object, Collection<String>> identities) {
        Result result = new Result();
        if (args == null || lockedIds == null || lockedIds.isEmpty()) return result;
        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];
            if (arg instanceof List) {
                List<?> filtered = filtered((List<?>) arg, lockedIds, identities, result);
                if (filtered != null) args[i] = filtered;
            } else if (arg != null) {
                scrubFields(arg, lockedIds, identities, result);
            }
        }
        return result;
    }

    /** Whether {@code type} looks like a results holder: a host class with several list fields. */
    static boolean isResultsHolder(Class<?> type, int minimumLists) {
        return !ContactIdentityExtractor.isPlatform(type) && listFields(type).length >= minimumLists;
    }

    private static void scrubFields(Object holder, Set<String> lockedIds,
                                    Function<Object, Collection<String>> identities, Result result) {
        for (Field field : listFields(holder.getClass())) {
            try {
                Object value = field.get(holder);
                if (!(value instanceof List)) continue;
                List<?> filtered = filtered((List<?>) value, lockedIds, identities, result);
                if (filtered != null) field.set(holder, filtered);
            } catch (IllegalAccessException | RuntimeException failure) {
                HookErrors.swallowed("ContactListSanitizer", failure);
            }
        }
    }

    /** A filtered copy, or null when the list holds no locked contact (it is left untouched). */
    private static List<?> filtered(List<?> source, Set<String> lockedIds,
                                    Function<Object, Collection<String>> identities, Result result) {
        if (source.isEmpty()) return null;
        List<Object> kept = LockedContactFilterPolicy.withoutLockedContacts(
                castToObjects(source), lockedIds, identities);
        int removed = source.size() - kept.size();
        if (removed <= 0) return null;
        result.removedContacts += removed;
        result.cleanedLists++;
        return kept;
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> castToObjects(List<?> source) {
        return (Collection<Object>) source;
    }

    private static Field[] listFields(Class<?> type) {
        Field[] cached = LIST_FIELDS.get(type);
        if (cached != null) return cached;
        ArrayList<Field> fields = new ArrayList<>();
        for (Class<?> current = type; current != null && !ContactIdentityExtractor.isPlatform(current);
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !List.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (RuntimeException failure) {
                    HookErrors.swallowed("ContactListSanitizer", failure);
                }
            }
        }
        Field[] created = fields.isEmpty() ? NO_FIELDS : fields.toArray(NO_FIELDS);
        Field[] raced = LIST_FIELDS.putIfAbsent(type, created);
        return raced != null ? raced : created;
    }
}
