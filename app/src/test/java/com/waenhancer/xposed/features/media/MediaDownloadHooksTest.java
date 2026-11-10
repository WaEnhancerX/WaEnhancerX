package com.waenhancer.xposed.features.media;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.Proxy;

public class MediaDownloadHooksTest {

    @SuppressWarnings("unchecked")
    private static <T> T createInterfaceProxy(Class<T> clazz) {
        return (T) Proxy.newProxyInstance(
                clazz.getClassLoader(),
                new Class<?>[]{clazz},
                (proxy, method, args) -> {
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) return false;
                    if (ret == int.class) return 0;
                    if (ret == long.class) return 0L;
                    if (ret == float.class) return 0f;
                    return null;
                }
        );
    }

    @Test
    public void testProfilePhotoDownloadHookInitialization() {
        Context context = new ContextWrapper(null);
        ClassLoader classLoader = getClass().getClassLoader();
        SharedPreferences prefs = createInterfaceProxy(SharedPreferences.class);

        ProfilePhotoDownloadHook hook = new ProfilePhotoDownloadHook(context, classLoader, prefs);
        assertEquals("ProfilePhotoDownload", hook.getName());
    }

    @Test
    public void testDownloadViewOnceHookInitialization() {
        Context context = new ContextWrapper(null);
        ClassLoader classLoader = getClass().getClassLoader();
        SharedPreferences prefs = createInterfaceProxy(SharedPreferences.class);

        DownloadViewOnceHook hook = new DownloadViewOnceHook(context, classLoader, prefs);
        assertEquals("Download View Once", hook.getName());
    }
}
