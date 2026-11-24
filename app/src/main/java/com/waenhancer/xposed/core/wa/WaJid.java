package com.waenhancer.xposed.core.wa;

import android.content.Context;
import android.os.SystemClock;
import android.util.LruCache;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.utils.ContactNameResolver;
import com.waenhancer.xposed.utils.HookErrors;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * One place that turns WhatsApp Jid objects into normalized raw strings.
 *
 * Every privacy hook used to re-implement "find the JID and call getRawString" with its own
 * heuristics. WhatsApp's public Jid API ({@code getRawString}) is a stable, non-obfuscated
 * method, so it is the only contract used here.
 */
public final class WaJid {

    private static final String TAG = "WaJid";
    private static final Pattern DEVICE_SUFFIX = Pattern.compile("[.:][\\d:]+@");
    private static final long LID_MISS_TTL_MS = 60_000L;

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

    /** LID raw string -> phone JID (or "" for a recent miss). */
    private static final LruCache<String, String> LID_TO_PHONE = new LruCache<>(512);
    private static final LruCache<String, Long> LID_MISS_AT = new LruCache<>(512);

    private WaJid() {}

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

    public static boolean isNewsletter(@Nullable String raw) {
        return raw != null && raw.endsWith("@newsletter");
    }

    /** Resolve a LID to its phone JID through WhatsApp's own jid_map. Cached; never throws. */
    @Nullable
    public static String phoneJidFor(@NonNull Context context, @Nullable String raw) {
        if (!isLid(raw)) return null;
        synchronized (LID_TO_PHONE) {
            String cached = LID_TO_PHONE.get(raw);
            if (cached != null) return cached.isEmpty() ? null : cached;
            Long missAt = LID_MISS_AT.get(raw);
            if (missAt != null && SystemClock.elapsedRealtime() - missAt < LID_MISS_TTL_MS) return null;
        }
        String phone = null;
        try {
            String digits = ContactNameResolver.INSTANCE.resolveLidToPhone(context, raw);
            if (digits != null && !digits.isEmpty()) phone = digits + "@s.whatsapp.net";
        } catch (Throwable t) {
            HookErrors.swallowed(TAG, t);
        }
        synchronized (LID_TO_PHONE) {
            if (phone != null) {
                LID_TO_PHONE.put(raw, phone);
                LID_MISS_AT.remove(raw);
            } else {
                LID_MISS_AT.put(raw, SystemClock.elapsedRealtime());
            }
        }
        return phone;
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
}
