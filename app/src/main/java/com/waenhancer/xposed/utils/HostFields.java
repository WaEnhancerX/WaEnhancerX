package com.waenhancer.xposed.utils;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached {@link Class#getDeclaredFields()} for hooks that inspect host objects on every message or
 * row. The platform call copies the field array each time, so inspecting the same obfuscated host
 * class repeatedly while scrolling or receiving messages allocated and re-resolved it every call.
 * Fields are made accessible once. Callers must not modify the returned array.
 */
public final class HostFields {
    private static final Map<Class<?>, Field[]> DECLARED = new ConcurrentHashMap<>();

    private HostFields() {}

    /**
     * Same fields as {@code type.getDeclaredFields()}. Failures (for example a class whose
     * dependencies are missing) propagate to the caller exactly like the platform call and are
     * not cached.
     */
    @NonNull
    public static Field[] declared(@NonNull Class<?> type) {
        Field[] fields = DECLARED.get(type);
        if (fields != null) return fields;
        fields = type.getDeclaredFields();
        for (Field field : fields) {
            try {
                field.setAccessible(true);
            } catch (Throwable ignored) {
                // The caller's own setAccessible reports this in its existing error path.
            }
        }
        DECLARED.put(type, fields);
        return fields;
    }
}
