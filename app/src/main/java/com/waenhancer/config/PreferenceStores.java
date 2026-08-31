package com.waenhancer.config;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The preference files the module keeps, and the rule for which one a key belongs to.
 *
 * <p><strong>public_config ("waex_prefs")</strong> is the world-readable preference file that
 * Xposed hooks in the WhatsApp process read.</p>
 *
 * <p><strong>private_config</strong> is a {@code MODE_PRIVATE} file reachable only by the
 * module's own UID. It holds secrets (API keys), internal settings, and runtime state.</p>
 */
public final class PreferenceStores {

    /** Name of the public world-readable store for Xposed hooks. */
    public static final String PUBLIC_NAME = "waex_prefs";

    /** Name of the private store. Never world-readable. */
    public static final String PRIVATE_NAME = "private_config";

    // Dedicated cache and isolated preference stores
    public static final String GITHUB_API_CACHE = "github_api_cache";
    public static final String GITHUB_USER_CACHE = "github_user_cache";
    public static final String PLANS_CACHE = "waex_plans_cache";
    public static final String APK_MIRROR_CACHE = "ApkMirrorCache";
    public static final String UPDATE_IGNORED = "wae_update_ignored";
    public static final String DEX_CACHE = "waex_dex_cache";
    public static final String STARTUP_PREFS = "startup_prefs";

    private PreferenceStores() {
    }

    /** The world-readable store the hooked WhatsApp process reads. */
    public static SharedPreferences publicStore(Context context) {
        return context.getSharedPreferences(PUBLIC_NAME, Context.MODE_PRIVATE);
    }

    /** The module-private store. Never world-readable, never read across the process boundary. */
    public static SharedPreferences privateStore(Context context) {
        return context.getSharedPreferences(PRIVATE_NAME, Context.MODE_PRIVATE);
    }

    /** Get a dedicated isolated cache store. */
    public static SharedPreferences cacheStore(Context context, String storeName) {
        return context.getSharedPreferences(storeName, Context.MODE_PRIVATE);
    }

    /** Which store a key belongs to according to the schema; public when the key is unknown. */
    public static SharedPreferences storeFor(Context context, String key) {
        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        if (entry != null && entry.store == PreferenceSchema.Store.PRIVATE) {
            return privateStore(context);
        }
        return publicStore(context);
    }

    /**
     * Reads a key from the store the schema assigns it, falling back to the other store.
     */
    public static Object read(Context context, String key) {
        PreferenceSchema.Entry entry = PreferenceSchema.entry(key);
        boolean privateFirst = entry != null && entry.store == PreferenceSchema.Store.PRIVATE;
        SharedPreferences first = privateFirst ? privateStore(context) : publicStore(context);
        SharedPreferences second = privateFirst ? publicStore(context) : privateStore(context);
        Object value = first.getAll().get(key);
        return value != null ? value : second.getAll().get(key);
    }
}
