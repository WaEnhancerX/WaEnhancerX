package com.waenhancer.xposed.features.privacy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Pure identity policy for removing locked contacts without depending on host wrapper types. */
final class LockedContactFilterPolicy {
    private LockedContactFilterPolicy() {}

    static Set<String> collectIds(Collection<?> locked, Function<Object, Collection<String>> ids) {
        if (locked == null || locked.isEmpty()) return Collections.emptySet();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (Object value : locked) addNormalized(result, ids.apply(value));
        return result;
    }

    static <T> List<T> withoutLockedContacts(
            Collection<T> source,
            Set<String> lockedIds,
            Function<T, Collection<String>> contactIds
    ) {
        if (source == null) return Collections.emptyList();
        ArrayList<T> result = new ArrayList<>(source.size());
        for (T item : source) {
            Collection<String> ids = contactIds.apply(item);
            // Unknown host entries are not contacts and must be preserved.
            if (ids == null || ids.isEmpty() || Collections.disjoint(normalized(ids), lockedIds)) {
                result.add(item);
            }
        }
        return result;
    }

    private static Set<String> normalized(Collection<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        addNormalized(result, values);
        return result;
    }

    private static void addNormalized(Set<String> target, Collection<String> values) {
        if (values == null) return;
        for (String value : values) {
            if (value == null) continue;
            String clean = value.trim().toLowerCase(java.util.Locale.ROOT);
            if (!clean.isEmpty()) target.add(clean);
        }
    }
}
