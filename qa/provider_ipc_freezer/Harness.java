import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.RemoteException;
import com.waenhancer.xposed.bridge.client.HookProviderIpc;

public final class Harness {
    private static int passed;

    private static void verify(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++;
    }

    private static Context enqueue(ContentResolver resolver, ContentProviderClient client) {
        if (client != null) resolver.clients.add(client);
        return new Context(resolver);
    }

    private static void acquiredUnstable(ContentResolver resolver, ContentProviderClient client) {
        verify(resolver.unstableAcquires == 1, "unstable acquisition required");
        verify(resolver.stableAcquires == 0, "stable provider would couple WhatsApp liveness");
        verify(resolver.legacyCalls == 0, "legacy stable ContentResolver.call forbidden");
        verify("content://com.waenhancer.hookprovider".equals(resolver.lastAuthority), "authority");
        verify(client.closes == 1, "provider must close exactly once");
    }

    public static void main(String[] args) {
        ContentResolver successResolver = new ContentResolver();
        ContentProviderClient success = new ContentProviderClient();
        Bundle request = new Bundle("request");
        Bundle answer = new Bundle("answer");
        success.answer = answer;
        verify(HookProviderIpc.call(enqueue(successResolver, success), "get_all_preferences", "scope", request) == answer,
                "preserve successful Bundle identity");
        verify("get_all_preferences".equals(success.lastMethod), "method forwarded");
        verify("scope".equals(success.lastArgument), "argument forwarded");
        verify(success.lastExtras == request, "extras forwarded");
        verify(success.calls == 1, "exactly one operation");
        acquiredUnstable(successResolver, success);

        ContentResolver nullResolver = new ContentResolver();
        ContentProviderClient empty = new ContentProviderClient();
        verify(HookProviderIpc.call(enqueue(nullResolver, empty), "get_all_preferences", null, null) == null,
                "null provider reply retained");
        acquiredUnstable(nullResolver, empty);

        ContentResolver deadResolver = new ContentResolver();
        ContentProviderClient dead = new ContentProviderClient();
        ContentProviderClient unused = new ContentProviderClient();
        dead.failure = new DeadObjectException("binder died");
        deadResolver.clients.add(dead);
        deadResolver.clients.add(unused);
        verify(HookProviderIpc.call(new Context(deadResolver), "record_preserved_message", null, request) == null,
                "DeadObjectException must match ContentResolver null semantics");
        verify(dead.calls == 1 && deadResolver.clients.size() == 1 && unused.calls == 0,
                "no retry of potentially non-idempotent operation");
        acquiredUnstable(deadResolver, dead);

        ContentResolver exceptionResolver = new ContentResolver();
        ContentProviderClient exception = new ContentProviderClient();
        exception.failure = new RemoteException("remote unavailable");
        verify(HookProviderIpc.call(enqueue(exceptionResolver, exception), "apply_preferences", null, request) == null,
                "RemoteException becomes null response");
        acquiredUnstable(exceptionResolver, exception);

        ContentResolver deniedResolver = new ContentResolver();
        ContentProviderClient denied = new ContentProviderClient();
        denied.failure = new SecurityException("outside scope");
        try {
            HookProviderIpc.call(enqueue(deniedResolver, denied), "report_runtime_health", null, request);
            throw new AssertionError("SecurityException must not be hidden");
        } catch (SecurityException expected) {
            verify("outside scope".equals(expected.getMessage()), "authorization error preserved");
        }
        acquiredUnstable(deniedResolver, denied);

        ContentResolver missingResolver = new ContentResolver();
        try {
            HookProviderIpc.call(new Context(missingResolver), "get_all_preferences", null, null);
            throw new AssertionError("unknown authority must not fake success");
        } catch (IllegalArgumentException expected) {
            verify(expected.getMessage().contains("com.waenhancer.hookprovider"), "unknown URI reported");
        }
        verify(missingResolver.unstableAcquires == 1 && missingResolver.stableAcquires == 0,
                "missing provider uses unstable path");

        ContentResolver invalidResolver = new ContentResolver();
        try {
            HookProviderIpc.call(new Context(invalidResolver), null, null, null);
            throw new AssertionError("null method must fail");
        } catch (NullPointerException expected) {
            verify(invalidResolver.unstableAcquires == 0, "validation before acquisition");
        }
        try {
            HookProviderIpc.call(null, "get_all_preferences", null, null);
            throw new AssertionError("null context must fail");
        } catch (NullPointerException expected) {
            verify(true, "null context rejected");
        }

        ContentResolver workResolver = new ContentResolver();
        ContentProviderClient work = new ContentProviderClient();
        verify(HookProviderIpc.call(enqueue(workResolver, work), "report_runtime_health", "com.whatsapp", null) == null,
                "independent work-profile context response");
        acquiredUnstable(workResolver, work);
        verify(successResolver.unstableAcquires == 1 && success.closes == 1,
                "work context does not reuse personal provider reference");

        System.out.println("PASS " + passed + " provider-contract assertions");
    }
}
