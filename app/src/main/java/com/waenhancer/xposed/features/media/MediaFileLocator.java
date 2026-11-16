package com.waenhancer.xposed.features.media;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.utils.HookErrors;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Finds the media {@link File} that WhatsApp keeps somewhere inside an obfuscated object graph
 * (FMessage, MediaData, status items, an Activity, ...). Reflection lookups are cached per class,
 * so repeated lookups on the same host classes only pay for reading field values.
 */
final class MediaFileLocator {
    /** SHALLOW: fields only, stops at Context. DEEP: also strings, collections and File getters. */
    enum Mode { SHALLOW, DEEP }

    private static final Map<Class<?>, Field[]> SHALLOW_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> DEEP_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method[]> FILE_GETTERS = new ConcurrentHashMap<>();

    private MediaFileLocator() {}

    /**
     * Returns the largest readable, non-empty file reachable from {@code root}, or null.
     *
     * @param accept optional extra filter applied to each candidate; a throwing filter rejects it
     */
    @Nullable
    static File find(@Nullable Object root, int maxDepth, @NonNull Mode mode,
                     @Nullable Predicate<File> accept) {
        if (root == null) return null;
        List<File> candidates = new ArrayList<>();
        collect(root, mode, Collections.newSetFromMap(new IdentityHashMap<>()), candidates, 0, maxDepth);
        File best = null;
        for (File candidate : candidates) {
            if (best != null && candidate.length() <= best.length()) continue;
            try {
                if (accept == null || accept.test(candidate)) best = candidate;
            } catch (Throwable ignored) { HookErrors.swallowed("MediaFileLocator", ignored); }
        }
        return best;
    }

    private static void collect(Object obj, Mode mode, Set<Object> visited, List<File> out,
                                int depth, int maxDepth) {
        if (obj == null || depth > maxDepth || !visited.add(obj)) return;

        Class<?> clazz = obj.getClass();
        String className = clazz.getName();
        boolean isString = obj instanceof String;
        if (className.startsWith("java.lang.") && !isString) return;
        if (className.startsWith("android.graphics.")
                || className.startsWith("android.view.ViewGroup$LayoutParams")) return;

        if (obj instanceof File || obj instanceof Uri) {
            addFile(obj, out);
            return;
        }
        boolean deep = mode == Mode.DEEP;
        if (isString) {
            if (deep) addPath((String) obj, out);
            return;
        }
        if (deep) {
            if (obj instanceof Iterable) {
                for (Object item : (Iterable<?>) obj) collect(item, mode, visited, out, depth + 1, maxDepth);
                return;
            }
            if (obj instanceof Object[]) {
                for (Object item : (Object[]) obj) collect(item, mode, visited, out, depth + 1, maxDepth);
                return;
            }
            if (obj instanceof Map) {
                for (Object item : ((Map<?, ?>) obj).values()) collect(item, mode, visited, out, depth + 1, maxDepth);
                return;
            }
            for (Method getter : fileGetters(clazz)) {
                try {
                    addFile(getter.invoke(obj), out);
                } catch (Throwable ignored) { HookErrors.swallowed("MediaFileLocator", ignored); }
            }
        }
        for (Field field : fields(clazz, mode)) {
            try {
                Object value = field.get(obj);
                if (value == null) continue;
                if (deep) {
                    collect(value, mode, visited, out, depth + 1, maxDepth);
                } else if (value instanceof File || value instanceof Uri) {
                    addFile(value, out);
                } else if (!field.getType().getName().startsWith("java.lang.")) {
                    collect(value, mode, visited, out, depth + 1, maxDepth);
                }
            } catch (Throwable ignored) { HookErrors.swallowed("MediaFileLocator", ignored); }
        }
    }

    private static void addFile(@Nullable Object value, List<File> out) {
        File file = null;
        if (value instanceof File) {
            file = (File) value;
        } else if (value instanceof Uri) {
            Uri uri = (Uri) value;
            String path = uri.getPath();
            if ("file".equalsIgnoreCase(uri.getScheme()) && path != null) file = new File(path);
        }
        if (file != null && file.isFile() && file.length() > 0) out.add(file);
    }

    private static void addPath(String text, List<File> out) {
        if (text.startsWith("/")) {
            addFile(new File(text), out);
        } else if (text.startsWith("file://")) {
            addFile(new File(text.substring(7)), out);
        }
    }

    private static Field[] fields(Class<?> clazz, Mode mode) {
        Map<Class<?>, Field[]> cache = mode == Mode.DEEP ? DEEP_FIELDS : SHALLOW_FIELDS;
        Field[] cached = cache.get(clazz);
        if (cached != null) return cached;
        List<Field> list = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            // SHALLOW never walks into Context internals; DEEP walks the whole hierarchy.
            if (mode == Mode.SHALLOW && c.getName().startsWith("android.content.Context")) break;
            Field[] declared;
            try {
                declared = c.getDeclaredFields();
            } catch (Throwable t) {
                break;
            }
            for (Field f : declared) {
                if (f.getType().isPrimitive()) continue;
                // DEEP skips statics unless they hold WhatsApp types (singletons that own media state).
                if (mode == Mode.DEEP && Modifier.isStatic(f.getModifiers())
                        && !f.getType().getName().startsWith("com.whatsapp")) continue;
                try {
                    f.setAccessible(true);
                    list.add(f);
                } catch (Throwable ignored) { HookErrors.swallowed("MediaFileLocator", ignored); }
            }
        }
        Field[] result = list.toArray(new Field[0]);
        cache.put(clazz, result);
        return result;
    }

    private static Method[] fileGetters(Class<?> clazz) {
        Method[] cached = FILE_GETTERS.get(clazz);
        if (cached != null) return cached;
        List<Method> list = new ArrayList<>();
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0 || m.getReturnType() != File.class) continue;
                try {
                    m.setAccessible(true);
                    list.add(m);
                } catch (Throwable ignored) { HookErrors.swallowed("MediaFileLocator", ignored); }
            }
        } catch (Throwable ignored) { HookErrors.swallowed("MediaFileLocator", ignored); }
        Method[] result = list.toArray(new Method[0]);
        FILE_GETTERS.put(clazz, result);
        return result;
    }
}
