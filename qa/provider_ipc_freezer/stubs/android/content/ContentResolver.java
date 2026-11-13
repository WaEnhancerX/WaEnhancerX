package android.content;

import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayDeque;
import java.util.Queue;

public class ContentResolver {
    public final Queue<ContentProviderClient> clients = new ArrayDeque<>();
    public int unstableAcquires;
    public int stableAcquires;
    public int legacyCalls;
    public String lastAuthority;

    public ContentProviderClient acquireUnstableContentProviderClient(Uri uri) {
        unstableAcquires++;
        lastAuthority = uri.toString();
        return clients.poll();
    }

    public ContentProviderClient acquireContentProviderClient(Uri uri) {
        stableAcquires++;
        lastAuthority = uri.toString();
        return clients.poll();
    }

    public Bundle call(Uri uri, String method, String arg, Bundle extras) {
        legacyCalls++;
        return null;
    }
}
