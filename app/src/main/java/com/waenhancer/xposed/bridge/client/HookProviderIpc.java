package com.waenhancer.xposed.bridge.client;

import android.content.ContentProviderClient;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.RemoteException;

/**
 * One-shot calls from hooked apps to the WAEX manager's provider.
 *
 * <p>Regular ContentResolver.call() obtains a stable provider reference. If Android kills a
 * cached/frozen WAEX provider, a stable dependent WhatsApp process can also be killed. An
 * unstable client lets WhatsApp survive provider death and use its existing failure path.
 * Never retain a client or retry a potentially non-idempotent operation after an ambiguous
 * failure; each attempt closes its provider reference.</p>
 */
public final class HookProviderIpc {
    private static final Uri URI = Uri.parse("content://com.waenhancer.hookprovider");

    private HookProviderIpc() {}

    public static Bundle call(Context context, String method, String arg, Bundle extras) {
        if (context == null) throw new NullPointerException("context == null");
        if (method == null) throw new NullPointerException("method == null");
        try (ContentProviderClient provider =
                     context.getContentResolver().acquireUnstableContentProviderClient(URI)) {
            if (provider == null) throw new IllegalArgumentException("Unknown URI " + URI);
            return provider.call(method, arg, extras);
        } catch (RemoteException ignored) {
            // Like ContentResolver.call(), report Binder death as an unavailable response.
            // ContentProviderClient marks an unstable dead binder before this catch.
            return null;
        }
    }
}
