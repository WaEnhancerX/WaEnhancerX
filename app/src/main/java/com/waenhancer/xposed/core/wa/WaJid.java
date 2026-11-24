package com.waenhancer.xposed.core.wa;

import android.content.Context;
import android.os.SystemClock;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.utils.ContactNameResolver;
import com.waenhancer.xposed.utils.HookErrors;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * One place that turns WhatsApp Jid objects into normalized raw strings and back.
 *
 * Every privacy hook used to re-implement "find the JID and call getRawString" with its own
 * heuristics. WhatsApp's public Jid API ({@code getRawString}) is a stable, non-obfuscated
 * method, so it is the only contract used for reading. Creating a typed Jid from a string uses
 * the Jid class's own {@code (String) -> Jid} factory, found by signature.
 */
public final class WaJid {

    private static final String TAG = "WaJid";
    private static final Pattern DEVICE_SUFFIX = Pattern.compile("[.:][\\d:]+@");
    private static final long MISS_TTL_MS = 60_000L;

    private static final ClassValue<Method> RAW_STRING = new ClassValue<Method>() {
        @Override
        protected Method computeValue(Class<?> type) {
            try {
                Method method = type.getMethod("getRawString");
                method.setAccessible(true);
                return method.getReturnType() == String.class ? method : null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    };

    private static final IdentityMap LID_TO_PHONE = new IdentityMap(
            (context, lid) -> {
                String digits = ContactNameResolver.INSTANCE.resolveLidToPhone(context, lid);
                return digits == null || digits.isEmpty() ? null : digits + "@s.whatsapp.net";
            });
    private static final IdentityMap PHONE_TO_LID = new IdentityMap(
            (context, phone) -> ContactNameResolver.INSTANCE.resolvePhoneToLid(context, phone));

    private static volatile boolean factoryResolved;
    private static volatile Class<?> jidClass;
    private static volatile Method factory;
    private static volatile Object factoryReceiver;

    private WaJid() {}

    // ─── Reading ─────────────────────────────────────────────────────────

    /** Normalized raw JID ("123@s.whatsapp.net", "456@lid", "1-2@g.us") or null. */
    @Nullable
    public static String raw(@Nullable Object jid) {
        if (jid == null) return null;
        String value;
        if (jid instanceof String) {
            value = (String) jid;
        } else {
            Method method = RAW_STRING.get(jid.getClass());
            if (method == null) return null;
            try {
                value = (String) method.invoke(jid);
            } catch (Throwable t) {
                HookErrors.swallowed(TAG, t);
                return null;
            }
        }
        if (value == null || value.isEmpty() || value.indexOf('@') < 0) return null;
        return DEVICE_SUFFIX.matcher(value).replaceFirst("@");
    }

    /** True when the object exposes WhatsApp's Jid contract. */
    public static boolean isJid(@Nullable Object value) {
        return value != null && !(value instanceof String) && RAW_STRING.get(value.getClass()) != null;
    }

    public static boolean isGroup(@Nullable String raw) {
        return raw != null && raw.endsWith("@g.us");
    }

    public static boolean isStatus(@Nullable String raw) {
        return "status@broadcast".equals(raw);
    }

    public static boolean isLid(@Nullable String raw) {
        return raw != null && raw.endsWith("@lid");
    }

    public static boolean isPhone(@Nullable String raw) {
        return raw != null && raw.endsWith("@s.whatsapp.net");
    }

    public static boolean isNewsletter(@Nullable String raw) {
        return raw != null && raw.endsWith("@newsletter");
    }

    // ─── LID <-> phone ───────────────────────────────────────────────────

    /** Resolve a LID to its phone JID through WhatsApp's own jid_map. Cached; never throws. */
    @Nullable
    public static String phoneJidFor(@NonNull Context context, @Nullable String raw) {
        return isLid(raw) ? LID_TO_PHONE.resolve(context, raw) : null;
    }

    /** Resolve a phone JID to its LID through WhatsApp's own jid_map. Cached; never throws. */
    @Nullable
    public static String lidFor(@NonNull Context context, @Nullable String raw) {
        return isPhone(raw) ? PHONE_TO_LID.resolve(context, raw) : null;
    }

    /** Every identity a per-contact rule may have been saved under: the JID, then its phone JID. */
    @NonNull
    public static List<String> lookupIds(@NonNull Context context, @Nullable String raw) {
        if (raw == null) return Collections.emptyList();
        List<String> ids = new ArrayList<>(2);
        ids.add(raw);
        String phone = phoneJidFor(context, raw);
        if (phone != null && !phone.equals(raw)) ids.add(phone);
        return ids;
    }

    /** The JID and its counterpart in the other namespace (LID <-> phone), when WhatsApp maps one. */
    @NonNull
    public static List<String> aliases(@NonNull Context context, @Nullable String raw) {
        if (raw == null) return Collections.emptyList();
        List<String> ids = new ArrayList<>(2);
        ids.add(raw);
        String other = isLid(raw) ? phoneJidFor(context, raw) : lidFor(context, raw);
        if (other != null && !other.equals(raw)) ids.add(other);
        return ids;
    }

    // ─── Creating typed Jids ─────────────────────────────────────────────

    /** WhatsApp's base Jid class, or null if unresolved. */
    @Nullable
    public static Class<?> jidClass(@NonNull Context context, @NonNull ClassLoader loader) {
        resolveFactory(context, loader);
        return jidClass;
    }

    /** WhatsApp's own typed Jid for a raw string, or null when it cannot be created safely. */
    @Nullable
    public static Object create(@NonNull Context context, @NonNull ClassLoader loader, @Nullable String raw) {
        if (raw == null) return null;
        resolveFactory(context, loader);
        Method method = factory;
        if (method == null) return null;
        try {
            Object jid = method.invoke(factoryReceiver, raw);
            return raw.equals(raw(jid)) ? jid : null;
        } catch (Throwable t) {
            HookErrors.swallowed(TAG, t);
            return null;
        }
    }

    private static synchronized void resolveFactory(@NonNull Context context, @NonNull ClassLoader loader) {
        if (factoryResolved) return;
        factoryResolved = true;
        Class<?> base = WaTargets.jidClass(context, loader);
        if (base == null) return;
        jidClass = base;
        // The Jid class, or a helper held in one of its static fields (its Kotlin Companion,
        // a JID factory), exposes a (String) -> Jid parser. Those helper classes are obfuscated
        // (e.g. X.0CZ), so match by field and method signature, never by class name.
        List<Object[]> candidates = new ArrayList<>();
        collectFactories(base, null, base, candidates);
        for (Field field : base.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (!Modifier.isStatic(field.getModifiers()) || type.isPrimitive() || type.isArray()
                    || type == base || type.getName().startsWith("java.")
                    || type.getName().startsWith("android.")) continue;
            try {
                field.setAccessible(true);
                collectFactories(field.getType(), field.get(null), base, candidates);
            } catch (Throwable ignored) { HookErrors.swallowed(TAG, ignored); }
        }
        String probe = "status@broadcast";
        for (Object[] candidate : candidates) {
            Method method = (Method) candidate[0];
            try {
                Object jid = method.invoke(candidate[1], probe);
                if (probe.equals(raw(jid))) {
                    factory = method;
                    factoryReceiver = candidate[1];
                    return;
                }
            } catch (Throwable ignored) { HookErrors.swallowed(TAG, ignored); }
        }
    }

    private static void collectFactories(Class<?> owner, @Nullable Object instance, Class<?> base,
                                         List<Object[]> out) {
        for (Method m : owner.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length != 1 || params[0] != String.class) continue;
            if (!base.isAssignableFrom(m.getReturnType())) continue;
            boolean isStatic = Modifier.isStatic(m.getModifiers());
            if (!isStatic && instance == null) continue;
            m.setAccessible(true);
            out.add(new Object[]{m, isStatic ? null : instance});
        }
    }

    /** Cached one-way identity mapping with a short negative cache. */
    private static final class IdentityMap {
        private final LruCache<String, String> hits = new LruCache<>(512);
        private final LruCache<String, Long> missesAt = new LruCache<>(512);
        private final BiFunction<Context, String, String> resolver;

        IdentityMap(BiFunction<Context, String, String> resolver) {
            this.resolver = resolver;
        }

        @Nullable
        String resolve(@NonNull Context context, @NonNull String key) {
            synchronized (this) {
                String cached = hits.get(key);
                if (cached != null) return cached;
                Long missAt = missesAt.get(key);
                if (missAt != null && SystemClock.elapsedRealtime() - missAt < MISS_TTL_MS) return null;
            }
            String value = null;
            try {
                value = resolver.apply(context, key);
            } catch (Throwable t) {
                HookErrors.swallowed(TAG, t);
            }
            synchronized (this) {
                if (value != null) {
                    hits.put(key, value);
                    missesAt.remove(key);
                } else {
                    missesAt.put(key, SystemClock.elapsedRealtime());
                }
            }
            return value;
        }
    }
}
