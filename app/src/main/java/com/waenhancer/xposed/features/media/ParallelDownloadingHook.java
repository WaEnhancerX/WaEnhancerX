package com.waenhancer.xposed.features.media;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;

import java.io.InputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/**
 * Parallel Downloading Hook:
 * Multi-threaded chunked downloader for WhatsApp media (photos, videos, documents).
 * Intercepts WhatsApp's media download response and wraps the body stream into a
 * parallel chunked stream using concurrent HTTP Range connections.
 */
public class ParallelDownloadingHook extends BaseFeature {

    private static final String TAG = "[WAEX][ParallelDownload]";
    public static final String PREF_KEY = "parallel_downloading";
    public static final String PREF_CONNECTIONS_KEY = "parallel_downloading_connections";
    public static final int DEFAULT_CONNECTIONS = 4;
    private static final long MIN_PARALLEL_SIZE_BYTES = 6L * 1024L * 1024L; // 6 MB threshold

    public ParallelDownloadingHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "Parallel Downloading";
    }

    @Override
    public void hook() throws Throwable {
        hookTigonDownload();
    }

    private void hookTigonDownload() {
        try {
            Method downloadMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_tigon_media_download_response_method",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("TigonMediaDownload/getDownloadResponse failed; url=", StringMatchType.Contains)))
                                .firstOrNull();
                        return data != null ? data.getMethodInstance(loader) : null;
                    }
            );

            if (downloadMethod == null) {
                XposedBridge.log(TAG + " Tigon media download method not found");
                return;
            }

            XposedBridge.hookMethod(downloadMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    if (!isEnabled(PREF_KEY, false)) return;

                    Object response = param.getResult();
                    if (response == null) return;

                    // Extract download URL and start offset from arguments
                    URL url = null;
                    long startOffset = 0L;
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg instanceof URL && url == null) {
                                url = (URL) arg;
                            } else if (arg instanceof Long && startOffset == 0L) {
                                startOffset = (Long) arg;
                            }
                        }
                    }

                    if (url == null) return;

                    long contentLength = resolveContentLength(response);
                    if (contentLength < MIN_PARALLEL_SIZE_BYTES) {
                        return; // Small files do not need parallel downloading
                    }

                    int connections = readConnectionCount();
                    Object proxy = createResponseProxy(response, contentLength, connections, url, startOffset);
                    if (proxy != null) {
                        param.setResult(proxy);
                    }
                }
            });

            XposedBridge.log(TAG + " Installed parallel downloading hook on: " + downloadMethod.getName());
        } catch (Throwable error) {
            XposedBridge.log(TAG + " Error installing hook: " + error.getMessage());
        }
    }

    private int readConnectionCount() {
        int count = DEFAULT_CONNECTIONS;
        try {
            if (prefs.contains(PREF_CONNECTIONS_KEY)) {
                count = prefs.getInt(PREF_CONNECTIONS_KEY, DEFAULT_CONNECTIONS);
            }
        } catch (ClassCastException e) {
            try {
                String strVal = prefs.getString(PREF_CONNECTIONS_KEY, "4");
                count = Integer.parseInt(strVal);
            } catch (Throwable ignored) {}
        }
        if (count < 2 || count > 16) {
            count = DEFAULT_CONNECTIONS;
        }
        return count;
    }

    private long resolveContentLength(Object response) {
        if (response == null) return -1L;
        try {
            for (Method m : response.getClass().getMethods()) {
                if (m.getParameterCount() == 0) {
                    String name = m.getName().toLowerCase();
                    if (name.contains("contentlength") || name.equals("length") || name.equals("getlength")) {
                        Object res = m.invoke(response);
                        if (res instanceof Number) {
                            return ((Number) res).longValue();
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1L;
    }

    private Object createResponseProxy(
            final Object response,
            final long contentLength,
            final int connections,
            final URL url,
            final long startOffset
    ) {
        Class<?>[] interfaces = response.getClass().getInterfaces();
        if (interfaces.length == 0) return null;

        InvocationHandler handler = new InvocationHandler() {
            private ParallelDownloadingInputStream parallelStream = null;

            @Override
            public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                Object res = method.invoke(response, args != null ? args : new Object[0]);
                if (res instanceof InputStream) {
                    if (parallelStream == null && !(res instanceof ParallelDownloadingInputStream)) {
                        parallelStream = new ParallelDownloadingInputStream(
                                (InputStream) res,
                                contentLength,
                                connections,
                                url,
                                startOffset
                        );
                    }
                    return parallelStream != null ? parallelStream : res;
                }
                return res;
            }
        };

        try {
            return Proxy.newProxyInstance(response.getClass().getClassLoader(), interfaces, handler);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to create proxy: " + t.getMessage());
            return null;
        }
    }
}
