import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Host contract checks of complete production classes; this is not Android ART. */
public final class TestClassMetadataAndroidCompat {
    private static int cases, checks, failures;
    private static final String WAJID = "com.waenhancer.xposed.core.wa.WaJid";
    private static final String RECEIPTS = "com.waenhancer.xposed.features.privacy.HideReceiptsHook";
    private interface Checked { void run() throws Exception; }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void test(String name, Checked body) {
        cases++;
        try {
            body.run();
            System.out.println("CASE|" + name + "|PASS");
        } catch (Throwable error) {
            failures++;
            System.out.println("CASE|" + name + "|FAIL");
            error.printStackTrace(System.out);
        }
    }
    private static final class AvailabilityLoader extends URLClassLoader {
        private final boolean deny;
        int denied;
        AvailabilityLoader(URL[] urls, boolean deny) {
            super(urls, TestClassMetadataAndroidCompat.class.getClassLoader());
            this.deny = deny;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (deny && name.equals("java.lang.ClassValue")) {
                denied++;
                throw new ClassNotFoundException("java.lang.ClassValue is outside the API24-33 contract");
            }
            return super.loadClass(name, resolve);
        }
    }
    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }
    private static Object field(Class<?> type, String name) throws Exception {
        Field result = type.getDeclaredField(name);
        result.setAccessible(true);
        return result.get(null);
    }
    private static Object call(Method method, Object receiver, Object... arguments) throws Exception {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable error = wrapped.getCause();
            if (error instanceof Error) throw (Error) error;
            if (error instanceof Exception) throw (Exception) error;
            throw wrapped;
        }
    }
    private static ExecutorService worker() {
        return Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "class-metadata-contract");
            thread.setDaemon(true);
            return thread;
        });
    }
    public static class BaseJid {
        public String getRawString() { return "123.4:5@s.whatsapp.net"; }
    }
    public static final class WrongReturn {
        public int getRawString() { return 7; }
    }
    public static final class NoGetter {}
    public static final class NullJid {
        public String getRawString() { return null; }
    }
    public static final class ThrowingJid {
        public String getRawString() { throw new IllegalStateException("getter failure"); }
    }
    public static final class Envelope {
        public Object a;
        public Object b;
    }
    public static class ReceiptToken {
        @Override public String toString() { return "Key(token)"; }
    }
    public static final class DelayedToken extends ReceiptToken {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Override public String toString() {
            entered.countDown();
            try {
                if (!release.await(8, TimeUnit.SECONDS)) throw new AssertionError("token gate timeout");
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
            return "Key(delayed)";
        }
    }
    /** Return-type linkage blocks inside Class.getMethod, before cache publication. */
    private static final class GateLoader extends URLClassLoader {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final boolean gated, failDependency;
        GateLoader(boolean gated, boolean failDependency) {
            super(new URL[]{TestClassMetadataAndroidCompat.class.getProtectionDomain()
                    .getCodeSource().getLocation()}, TestClassMetadataAndroidCompat.class.getClassLoader());
            this.gated = gated;
            this.failDependency = failDependency;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals("CacheGatedJid") && !name.equals("CacheGateDependency")) {
                return super.loadClass(name, resolve);
            }
            synchronized (getClassLoadingLock(name)) {
                Class<?> loaded = findLoadedClass(name);
                if (loaded == null) {
                    if (name.equals("CacheGateDependency") && gated) {
                        entered.countDown();
                        try {
                            if (!release.await(8, TimeUnit.SECONDS)) {
                                throw new ClassNotFoundException("return-type gate timeout");
                            }
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new ClassNotFoundException("interrupted return-type gate", error);
                        }
                        if (failDependency) throw new ClassNotFoundException("controlled return-type failure");
                    }
                    loaded = findClass(name);
                }
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }
    }
    @SuppressWarnings("unchecked")
    private static void contracts(URL[] urls) throws Exception {
        try (AvailabilityLoader loader = new AvailabilityLoader(urls, true)) {
            Class<?> jid = Class.forName(WAJID, true, loader);
            Class<?> receipts = Class.forName(RECEIPTS, true, loader);
            Method raw = method(jid, "raw", Object.class);
            Method isJid = method(jid, "isJid", Object.class);
            Method lookup = method(jid, "rawStringMethod", Class.class);
            Map<Class<?>, WeakReference<Method>> rawCache =
                    (Map<Class<?>, WeakReference<Method>>) field(jid, "RAW_STRING");
            WeakReference<Method> missing = (WeakReference<Method>) field(jid, "NO_RAW_STRING");
            Class<?> context = Class.forName("android.content.Context", true, loader);
            Class<?> prefs = Class.forName("android.content.SharedPreferences", true, loader);
            Object preferenceBoundary = Proxy.newProxyInstance(loader, new Class<?>[]{prefs},
                    (proxy, invoked, values) -> { throw new AssertionError("preferences outside cache contract"); });
            Constructor<?> constructor = receipts.getConstructor(context, ClassLoader.class, prefs);
            Object hook = constructor.newInstance(context.getConstructor().newInstance(), loader, preferenceBoundary);
            Method findKey = method(receipts, "findMessageKey", Object.class);
            Map<Class<?>, WeakReference<Field>> keyCache =
                    (Map<Class<?>, WeakReference<Field>>) field(receipts, "MESSAGE_KEY_FIELDS");

            test("raw-public-contract", () -> {
                check(call(raw, null, new BaseJid()).equals("123@s.whatsapp.net"), "typed normalization");
                check(call(raw, null, "456:9@lid").equals("456@lid"), "string normalization");
                check(call(raw, null, new Object[]{null}) == null, "null raw");
                check(call(raw, null, "missing-domain") == null, "invalid raw");
                check(call(raw, null, new NullJid()) == null, "getter returning null");
                check(Boolean.TRUE.equals(call(isJid, null, new BaseJid())), "typed JID contract");
                check(Boolean.FALSE.equals(call(isJid, null, "123@s.whatsapp.net")), "strings not typed JIDs");
                check(Boolean.FALSE.equals(call(isJid, null, new WrongReturn())), "wrong return type rejected");
                check(Boolean.FALSE.equals(call(isJid, null, new NoGetter())), "missing getter rejected");
                check(call(raw, null, new ThrowingJid()) == null, "getter exception retains existing fallback");
            });
            test("negative-cache-marker", () -> {
                rawCache.clear();
                check(call(lookup, null, NoGetter.class) == null, "first negative");
                check(rawCache.get(NoGetter.class) == missing, "stable negative marker");
                check(call(lookup, null, NoGetter.class) == null, "cached negative");
                check(rawCache.get(NoGetter.class) == missing, "marker not replaced");
                rawCache.put(BaseJid.class, missing);
                check(call(lookup, null, BaseJid.class) == null, "previous negative not recomputed");
                check(rawCache.get(BaseJid.class) == missing, "negative winner retained");
                rawCache.clear();
            });
            test("expired-positive-method-rediscovers", () -> {
                rawCache.clear();
                Method first = (Method) call(lookup, null, BaseJid.class);
                WeakReference<Method> cached = rawCache.get(BaseJid.class);
                check(first != null && cached.get() == first, "positive cached weakly");
                cached.clear(); // Deterministic value-expiration model; no GC timing claim.
                check(cached.get() == null, "reference explicitly expired");
                check(call(lookup, null, BaseJid.class) != null, "expired value rediscovered");
                check(rawCache.get(BaseJid.class) != cached, "new positive wrapper published");
            });
            test("null-class-contract", () -> {
                boolean rejected = false;
                try { call(lookup, null, new Object[]{null}); }
                catch (NullPointerException expected) { rejected = true; }
                check(rejected, "null Class rejected as before");
            });
            test("class-identity-isolation", () -> {
                rawCache.clear();
                try (GateLoader left = new GateLoader(false, false);
                     GateLoader right = new GateLoader(false, false)) {
                    Class<?> a = Class.forName("CacheGatedJid", false, left);
                    Class<?> b = Class.forName("CacheGatedJid", false, right);
                    check(a != b && a.getName().equals(b.getName()), "equal names, distinct Class identities");
                    rawCache.put(a, missing);
                    check(call(lookup, null, a) == null, "left negative retained");
                    check(call(lookup, null, b) != null, "right positive independent");
                    check(rawCache.size() == 2, "two cache identities retained");
                }
            });
            test("positive-winner-before-late-positive", () -> publicationRace(lookup, rawCache, missing, false, false));
            test("positive-winner-before-late-negative", () -> publicationRace(lookup, rawCache, missing, true, false));
            test("negative-winner-before-late-positive", () -> publicationRace(lookup, rawCache, missing, false, true));
            test("linkage-failure-negative-contract", () -> {
                rawCache.clear();
                try (GateLoader gated = new GateLoader(true, true)) {
                    Class<?> type = Class.forName("CacheGatedJid", false, gated);
                    gated.release.countDown();
                    check(call(lookup, null, type) == null, "existing reflection Throwable fallback");
                    check(rawCache.get(type) == missing, "linkage failure cached negative");
                }
            });
            test("message-field-discovery-and-unset-retry", () -> {
                keyCache.clear();
                Envelope value = new Envelope();
                check(call(findKey, hook, value) == null, "empty message");
                check(keyCache.get(Envelope.class) == null, "no permanent negative field");
                ReceiptToken first = new ReceiptToken(), second = new ReceiptToken();
                value.a = first;
                check(call(findKey, hook, value) == first, "initial field");
                check(keyCache.get(Envelope.class).get().getName().equals("a"), "first metadata");
                value.a = null; value.b = second;
                check(call(findKey, hook, value) == second, "unset cached field rescanned");
                check(keyCache.get(Envelope.class).get().getName().equals("b"), "replacement metadata");
                value.b = null;
                check(call(findKey, hook, value) == null, "empty after positive");
                value.a = first;
                check(call(findKey, hook, value) == first, "later populated field retried");
            });
            test("expired-positive-field-rediscovers", () -> {
                keyCache.clear();
                Envelope value = new Envelope(); value.a = new ReceiptToken();
                check(call(findKey, hook, value) == value.a, "field initial discovery");
                WeakReference<Field> cached = keyCache.get(Envelope.class);
                cached.clear();
                check(cached.get() == null, "field reference explicitly expired");
                check(call(findKey, hook, value) == value.a, "field rediscovered");
                check(keyCache.get(Envelope.class) != cached, "new field wrapper published");
            });
            test("message-field-latest-valid-publication", () -> {
                keyCache.clear();
                Envelope slow = new Envelope(), fast = new Envelope();
                DelayedToken delayed = new DelayedToken();
                slow.a = delayed; fast.b = new ReceiptToken();
                ExecutorService executor = worker();
                Future<Object> pending = executor.submit(() -> call(findKey, hook, slow));
                try {
                    check(delayed.entered.await(5, TimeUnit.SECONDS), "first field discovery paused");
                    check(call(findKey, hook, fast) == fast.b, "parallel discovery not blocked by reflection");
                    check(keyCache.get(Envelope.class).get().getName().equals("b"), "fast field published");
                    delayed.release.countDown();
                    check(pending.get(5, TimeUnit.SECONDS) == delayed, "slow discovery returns its own key");
                    check(keyCache.get(Envelope.class).get().getName().equals("a"), "latest valid discovery wins");
                } finally {
                    delayed.release.countDown(); executor.shutdownNow();
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("worker not stopped");
                }
            });
            test("weak-ownership-structure", () -> {
                check(rawCache instanceof WeakHashMap, "raw cache has weak Class keys");
                check(keyCache instanceof WeakHashMap, "field cache has weak Class keys");
                check(missing.get() == null, "negative marker owns no Method/Class");
                rawCache.clear();
                call(lookup, null, BaseJid.class);
                check(rawCache.get(BaseJid.class) instanceof WeakReference, "weak Method value");
                check(keyCache.get(Envelope.class) instanceof WeakReference, "weak Field value");
                check(loader.denied == 0, "production did not request ClassValue");
            });
        }
    }
    private static void publicationRace(Method lookup, Map<Class<?>, WeakReference<Method>> cache,
                                        WeakReference<Method> missing, boolean failed, boolean negative) throws Exception {
        cache.clear();
        ExecutorService executor = worker();
        try (GateLoader gated = new GateLoader(true, failed)) {
            Class<?> type = Class.forName("CacheGatedJid", false, gated);
            Method winner = BaseJid.class.getMethod("getRawString");
            Future<Object> pending = executor.submit(() -> call(lookup, null, type));
            try {
                check(gated.entered.await(5, TimeUnit.SECONDS), "reflection gate reached");
                WeakReference<Method> published = negative ? missing : new WeakReference<>(winner);
                synchronized (cache) { cache.put(type, published); }
                gated.release.countDown();
                check(pending.get(5, TimeUnit.SECONDS) == (negative ? null : winner), "first published result wins");
                check(cache.get(type) == published, "late computation does not overwrite winner");
                check(negative || published.get() == winner, "published Method identity retained");
            } finally { gated.release.countDown(); }
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new AssertionError("worker not stopped");
        }
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("mode and product classpath required");
        List<URL> entries = new ArrayList<>();
        for (int i = 1; i < args.length; i++) entries.add(Path.of(args[i]).toUri().toURL());
        URL[] urls = entries.toArray(new URL[0]);
        if (args[0].equals("availability")) {
            for (boolean deny : new boolean[]{false, true}) {
                for (String target : new String[]{"java.lang.String", WAJID, RECEIPTS}) {
                    test((deny ? "ClassValue-absent:" : "ClassValue-present:") + target, () -> {
                        try (AvailabilityLoader loader = new AvailabilityLoader(urls, deny)) {
                            check(Class.forName(target, true, loader) != null, "complete class initializes");
                        }
                    });
                }
            }
        } else if (args[0].equals("contracts")) {
            contracts(urls);
        } else throw new IllegalArgumentException("unknown mode");
        System.out.println("Class metadata: " + cases + " cases, " + checks + " checks, " + failures + " failed cases");
        if (failures != 0) System.exit(1);
    }
}
class CacheGatedJid extends TestClassMetadataAndroidCompat.BaseJid {
    public CacheGateDependency unrelated() { return null; }
}
class CacheGateDependency {}
