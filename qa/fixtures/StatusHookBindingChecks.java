import com.waenhancer.xposed.features.privacy.StatusHookContract;
import java.lang.reflect.*;
import java.util.*;

/**
 * This harness receives the verbatim production initialization/revoke methods.
 * DexKit discovery, Xposed dispatch, key extraction, persistence and UI are explicit doubles.
 */
public final class StatusHookBindingChecks {
    @interface Nullable {}
    @interface NonNull {}
    static final String TAG = "[fixture]";
    final Object context = new Object();
    final ClassLoader classLoader = getClass().getClassLoader();
    Class<?> fMessageClass, messageKeyClass, statusKeyClass, statusModelClass;
    Method statusToMessageMethod;
    Object statusToMessageMapper;
    boolean enabled = true;
    static final Map<String, Long> REVOKED_MESSAGES = new HashMap<>();
    static int checks;

    enum StoreResult { STORED, STORED_ORPHAN, FAILED_TO_STORE, DIDNT_STORE }
    static class Key {
        final String id;
        final boolean fromMe;
        Key(String id, boolean fromMe) { this.id = id; this.fromMe = fromMe; }
    }
    static class OtherKey { String id; boolean fromMe; }
    static class Manager {
        StoreResult revoke(Key metadata, Key target, boolean incoming) { return StoreResult.DIDNT_STORE; }
    }
    static class OtherManager {
        StoreResult second(Key metadata, Key target, boolean incoming) { return StoreResult.DIDNT_STORE; }
    }
    static class UnsupportedManager {
        StoreResult unrelated(Key metadata, Object target, boolean incoming) { return StoreResult.DIDNT_STORE; }
    }
    static class Message { void marker() {} }
    static class SpecificMessage extends Message {}
    static class Status {}
    interface Projection<T extends Message> { T convert(Status status); }
    static class SyntheticMapper implements Projection<SpecificMessage> {
        public SpecificMessage convert(Status status) { return null; }
    }
    static class Mapper { Message convert(Status status) { return new Message(); } }
    static class OtherMapper { Message convert(Status status) { return new Message(); } }

    private boolean isAntiRevokeStatusEnabled() { return enabled; }
    private Boolean extractIsFromMe(Object key) { return key instanceof Key ? ((Key) key).fromMe : null; }
    private String extractKeyIdFromFMessage(Object key) { return key instanceof Key ? ((Key) key).id : null; }
    private String extractSenderJid(Object key) { return "fixture"; }
    private void showDeletedMessageToast(String sender) {}
    private String resolveStatusSenderJid(Object target, Object metadata) { return "fixture"; }
    private void showDeletedStatusToast(String sender) {}

    /** Production flag set once the status revoke manager is hooked. */
    volatile boolean statusRevokeHooked;

    /* PRODUCTION_METHODS */

    static class DelMessageStore {
        static final DelMessageStore instance = new DelMessageStore();
        static final Set<String> recorded = new HashSet<>();
        static DelMessageStore getInstance(Object context) { return instance; }
        void insertMessage(String sender, String id, long timestamp) { recorded.add(id); }
    }
    static class XC_MethodHook {
        protected void beforeHookedMethod(MethodHookParam param) {}
        protected void afterHookedMethod(MethodHookParam param) {}
        static class MethodHookParam {
            Method method;
            Object[] args;
            Object thisObject;
            Object result;
            boolean handled;
            void setResult(Object result) { this.result = result; handled = true; }
        }
    }
    static class XposedBridge {
        static XC_MethodHook hook;
        static Method method;
        static void hookMethod(Method method, XC_MethodHook hook) { XposedBridge.method = method; XposedBridge.hook = hook; }
        static void hookAllConstructors(Class<?> type, XC_MethodHook hook) {}
        static void log(String message) { throw new AssertionError("unexpected hook failure: " + message); }
    }
    enum StringMatchType { Contains }
    static class MethodMatcher {
        String anchor = "";
        static MethodMatcher create() { return new MethodMatcher(); }
        MethodMatcher addUsingString(String value, StringMatchType match) { anchor = value; return this; }
        MethodMatcher name(String name) { return this; }
    }
    static class ClassMatcher {
        String anchor = "";
        static ClassMatcher create() { return new ClassMatcher(); }
        ClassMatcher addUsingString(String value, StringMatchType match) { anchor += value; return this; }
        ClassMatcher fieldCount(int count) { return this; }
        ClassMatcher addMethod(MethodMatcher method) { return this; }
    }
    static class FindClass {
        ClassMatcher matcher;
        static FindClass create() { return new FindClass(); }
        FindClass matcher(ClassMatcher matcher) { this.matcher = matcher; return this; }
    }
    static class FindMethod {
        MethodMatcher matcher;
        static FindMethod create() { return new FindMethod(); }
        FindMethod matcher(MethodMatcher matcher) { this.matcher = matcher; return this; }
    }
    static class ClassData {
        final Class<?> value;
        ClassData(Class<?> value) { this.value = value; }
        Class<?> getInstance(ClassLoader loader) { return value; }
    }
    static class MethodData {
        final Method value;
        MethodData(Method value) { this.value = value; }
        Method getMethodInstance(ClassLoader loader) { return value; }
    }
    static class ClassDataList extends ArrayList<ClassData> {
        ClassData firstOrNull() { return isEmpty() ? null : get(0); }
    }
    static class MethodDataList extends ArrayList<MethodData> {
        MethodData firstOrNull() { return isEmpty() ? null : get(0); }
    }
    static class Bridge {
        List<Class<?>> keys = List.of(Key.class);
        List<Class<?>> managers = List.of(Manager.class);
        List<Method> mappers;
        ClassDataList findClass(FindClass query) {
            List<Class<?>> found = query.matcher.anchor.contains("RevokeStatusManager") ? managers
                    : query.matcher.anchor.contains("Key(id=") ? keys : List.of(Key.class);
            ClassDataList result = new ClassDataList();
            for (Class<?> type : found) result.add(new ClassData(type));
            return result;
        }
        MethodDataList findMethod(FindMethod query) throws Exception {
            List<Method> found = query.matcher.anchor.contains("mapFStatus") ? mappers
                    : List.of(Message.class.getDeclaredMethod("marker"));
            MethodDataList result = new MethodDataList();
            for (Method method : found) result.add(new MethodData(method));
            return result;
        }
    }
    interface MethodQuery { Method execute(Bridge bridge, ClassLoader loader) throws Throwable; }
    interface ClassQuery { Class<?> execute(Bridge bridge, ClassLoader loader) throws Throwable; }
    static class DexSearchEngine {
        static final DexSearchEngine instance = new DexSearchEngine();
        Bridge bridge;
        Method cachedRevoke, cachedMapper;
        static DexSearchEngine getInstance() { return instance; }
        Class<?> findClassWithCache(Object context, ClassLoader loader, String key, ClassQuery query) throws Throwable {
            return query.execute(bridge, loader);
        }
        Method findMethodWithCache(Object context, ClassLoader loader, String key, MethodQuery query) throws Throwable {
            if (key.contains("revoke_status_manager") && cachedRevoke != null) return cachedRevoke;
            if (key.contains("status_to_message_mapper") && cachedMapper != null) return cachedMapper;
            return query.execute(bridge, loader);
        }
    }

    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    static StatusHookBindingChecks reset() throws Exception {
        XposedBridge.hook = null;
        XposedBridge.method = null;
        REVOKED_MESSAGES.clear();
        DelMessageStore.recorded.clear();
        DexSearchEngine.instance.cachedRevoke = null;
        DexSearchEngine.instance.cachedMapper = null;
        DexSearchEngine.instance.bridge = new Bridge();
        Method synthetic = Arrays.stream(SyntheticMapper.class.getDeclaredMethods())
                .filter(Method::isBridge).findFirst().orElseThrow();
        DexSearchEngine.instance.bridge.mappers = List.of(synthetic, Mapper.class.getDeclaredMethod("convert", Status.class));
        return new StatusHookBindingChecks();
    }
    static StatusHookBindingChecks installed() throws Exception {
        StatusHookBindingChecks instance = reset();
        instance.hookStatusBytecodeRevocation();
        check(XposedBridge.hook != null, "verified manager must install");
        return instance;
    }
    static XC_MethodHook.MethodHookParam invoke(Object[] args) {
        XC_MethodHook.MethodHookParam param = new XC_MethodHook.MethodHookParam();
        param.method = XposedBridge.method;
        param.args = args;
        XposedBridge.hook.beforeHookedMethod(param);
        return param;
    }
    static void target() throws Exception {
        installed();
        invoke(new Object[]{new Key("revoke-envelope", false), new Key("target-story", false), true});
        check(REVOKED_MESSAGES.keySet().equals(Set.of("target-story")), "record target key, never the revoke envelope");
        check(DelMessageStore.recorded.equals(Set.of("target-story")), "persist the same target identity");
    }
    static void ack() throws Exception {
        installed();
        XC_MethodHook.MethodHookParam result = invoke(new Object[]{null, new Key("target-story", false), true});
        check(result.handled, "valid incoming revoke should be intercepted");
        // The inspected receiver immediately calls ordinal(); exercise that exact Java precondition.
        int ordinal = ((Enum<?>) result.result).ordinal();
        check(result.result == StoreResult.STORED && ordinal == StoreResult.STORED.ordinal(),
                "return the host's successful consumption constant");
        check(consumeResult((StoreResult) result.result).equals("success"), "preserved revoke reaches receiver success");
        check(result.result == StoreResult.STORED, "preserved revoke qualifies for orphan cleanup");
        check(consumeResult(StoreResult.DIDNT_STORE).equals("no-store"), "no-store is a distinct caller outcome");
        check(consumeResult(StoreResult.FAILED_TO_STORE).equals("error"), "failure is a distinct caller outcome");
    }
    // Abstracted from the inspected receiver's ordinal switch; no host implementation is copied.
    static String consumeResult(StoreResult result) {
        switch (result.ordinal()) {
            case 0:
            case 1: return "success";
            case 2: return "error";
            case 3: return "no-store";
            default: throw new AssertionError("unknown native outcome");
        }
    }
    static void local() throws Exception {
        Object[][] normal = {
                {new Key("metadata", false), new Key("target", false), false},
                {new Key("metadata", false), new Key("own-target", true), true},
                {new Key("metadata", false), null, true},
                {new Key("metadata", false), new Key("", false), true}
        };
        for (Object[] args : normal) {
            installed();
            check(!invoke(args).handled, "local/self/unidentified target must retain host behavior");
            check(REVOKED_MESSAGES.isEmpty() && DelMessageStore.recorded.isEmpty(), "no false deleted marker");
        }
        StatusHookBindingChecks disabled = installed();
        disabled.enabled = false;
        check(!invoke(new Object[]{null, new Key("target", false), true}).handled, "disabled option retains host behavior");
    }
    static void selection() throws Exception {
        StatusHookBindingChecks ambiguous = reset();
        DexSearchEngine.instance.bridge.managers = List.of(Manager.class, OtherManager.class);
        ambiguous.hookStatusBytecodeRevocation();
        check(XposedBridge.hook == null, "ambiguous manager candidates must not install");
        StatusHookBindingChecks unsupported = reset();
        DexSearchEngine.instance.bridge.managers = List.of(UnsupportedManager.class);
        unsupported.hookStatusBytecodeRevocation();
        check(XposedBridge.hook == null, "first-key-only shape is insufficient");
        StatusHookBindingChecks stale = reset();
        DexSearchEngine.instance.cachedRevoke = UnsupportedManager.class.getDeclaredMethod("unrelated", Key.class, Object.class, boolean.class);
        stale.hookStatusBytecodeRevocation();
        check(XposedBridge.hook == null, "cached revoke methods must be revalidated before installing");
        StatusHookBindingChecks keys = reset();
        DexSearchEngine.instance.bridge.keys = List.of(Key.class, OtherKey.class);
        keys.hookStatusBytecodeRevocation();
        check(XposedBridge.hook == null, "ambiguous status-key discovery must not install");
    }
    static void mapper() throws Exception {
        StatusHookBindingChecks mapper = reset();
        mapper.initMessageClasses();
        check(mapper.statusToMessageMethod != null && mapper.statusToMessageMethod.getDeclaringClass() == Mapper.class,
                "synthetic null mapper must not win anchor resolution");
        StatusHookBindingChecks ambiguous = reset();
        DexSearchEngine.instance.bridge.mappers = List.of(Mapper.class.getDeclaredMethod("convert", Status.class),
                OtherMapper.class.getDeclaredMethod("convert", Status.class));
        ambiguous.initMessageClasses();
        check(ambiguous.statusToMessageMethod == null, "multiple real mappers must remain unresolved");
        StatusHookBindingChecks cached = reset();
        DexSearchEngine.instance.cachedMapper = DexSearchEngine.instance.bridge.mappers.get(0);
        cached.initMessageClasses();
        check(cached.statusToMessageMethod == null, "cached synthetic mapper must be rejected");
    }
    public static void main(String[] args) throws Exception {
        String scenario = args.length == 0 ? "all" : args[0];
        if (scenario.equals("all") || scenario.equals("target")) target();
        if (scenario.equals("all") || scenario.equals("ack")) ack();
        if (scenario.equals("all") || scenario.equals("local")) local();
        if (scenario.equals("all") || scenario.equals("selection")) selection();
        if (scenario.equals("all") || scenario.equals("mapper")) mapper();
        System.out.println(checks + " verbatim production status hook binding checks passed (" + scenario + ")");
    }
}
