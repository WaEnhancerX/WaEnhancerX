package com.waenhancer.xposed.utils;

import androidx.annotation.NonNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class-name tests for hot paths. {@link Class#getSimpleName()} parses and allocates on every call,
 * which adds up when a hook asks "is this the Conversation activity?" for each view attach or
 * adapter bind. The name of a class never changes, so it is computed once.
 */
public final class HostClassNames {
    private static final Map<Class<?>, String> SIMPLE_NAMES = new ConcurrentHashMap<>();

    private HostClassNames() {}

    /** Same result as {@code type.getSimpleName().contains(fragment)}, without the per-call cost. */
    public static boolean simpleNameContains(@NonNull Class<?> type, @NonNull String fragment) {
        String name = SIMPLE_NAMES.get(type);
        if (name == null) {
            name = type.getSimpleName();
            SIMPLE_NAMES.put(type, name);
        }
        return name.contains(fragment);
    }
}
