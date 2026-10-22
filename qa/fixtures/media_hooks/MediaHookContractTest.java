import android.content.Context;
import android.content.SharedPreferences;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.FeatureRegistry;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.media.MediaQualityBypassHook;
import com.waenhancer.xposed.features.privacy.AntiViewOnceHook;
import com.whatsapp.infra.core.jid.Jid;
import com.whatsapp.infra.core.jid.UserJid;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.*;
import java.lang.reflect.*;
import java.util.*;

/** Fixture metadata describes host relationships; production hooks decide what to bind. */
public final class MediaHookContractTest {
    private static int checks, failed;
    private static final Context CONTEXT = new Context();
    private static final ClassLoader LOADER = MediaHookContractTest.class.getClassLoader();

    static final class Preferences implements SharedPreferences {
        final Map<String, Boolean> values = new HashMap<>();
        @Override public boolean getBoolean(String k, boolean fallback) { return values.getOrDefault(k, fallback); }
        void put(String k, boolean enabled) { values.put(k, enabled); }
    }
    public interface ViewState { int getState(); void setState(int value); }
    public interface AmbiguousState { int getState(); void first(int value); void second(int value); }
    public static final class Key {
        final String id;
        final boolean fromMe;
        final Jid chat;
        Key(boolean own) { id = "fixture-id"; fromMe = own; chat = new Jid(); }
    }
    public static class Message {
        final Key primary;
        Key mutableDecoy;
        final Key otherFinal;
        Message(Key key) { primary = key; mutableDecoy = new Key(true); otherFinal = new Key(true); }
        public UserJid sender() { return new UserJid(); }
    }
    public static class Photo extends Message implements ViewState {
        int state = -1, unrelated = -1;
        Photo(Key key) { super(key); }
        public int getState() { return state; }
        public void setState(int value) { state = value; }
        public void setUnrelated(int value) { unrelated = value; }
    }
    public static class InheritedPhoto extends Photo { InheritedPhoto(Key key) { super(key); } }
    public static class Video extends Message implements ViewState {
        int state = -1;
        Video(Key key) { super(key); }
        public int getState() { return state; }
        public void setState(int value) { state = value; }
    }
    public static class NotMessage implements ViewState {
        int state = -1;
        public int getState() { return state; }
        public void setState(int value) { state = value; }
    }
    public static class AmbiguousPhoto extends Message implements AmbiguousState {
        AmbiguousPhoto(Key key) { super(key); }
        public int getState() { return 1; }
        public void first(int value) {}
        public void second(int value) {}
    }
    public static class DirectPhoto extends Message {
        int state = -1;
        DirectPhoto(Key key) { super(key); }
        public void directState(int value) { state = value; }
        public void otherDirectState(int value) { state = value; }
        public static void staticState(int value) {}
    }
    public static class Sql {
        public void unrelated() {}
        public void savePhoto() {}
        public void saveVideo() {}
    }
    public static class Encoder {
        int quality;
        int fps = 30;
        Integer boxed = 30;
        boolean hd, fail;
        final RuntimeException failure = new IllegalStateException("fixture-private-media-value");
        public void image(int value) { quality = value; }
        public void otherImage(int value) { quality = value; }
        public void wrongImage(String value) {}
        public int frames() { if (fail) throw failure; return fps; }
        public Integer boxedFrames() { if (fail) throw failure; return boxed; }
        public int otherFrames() { return 24; }
        public boolean qualityTool() { if (fail) throw failure; return hd; }
        public boolean qualityToggle() { if (fail) throw failure; return hd; }
        public Boolean boxedToggle() { return Boolean.FALSE; }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) { failed++; System.out.println("FAIL " + label); }
    }
    private static String unavailableMessage(Class<?> type) {
        return type == MediaQualityBypassHook.class
                ? "Media quality contract unavailable" : "View-once contract unavailable";
    }
    private static void expectUnavailable(BaseFeature hook, String label) {
        Throwable failure = null;
        try { hook.hook(); } catch (Throwable unavailable) { failure = unavailable; }
        check(failure instanceof IllegalStateException, label + "/missing contract is reported");
        check(failure != null && unavailableMessage(hook.getClass()).equals(failure.getMessage()),
                label + "/unavailable diagnostic is generic");
    }
    private static void repeatPartialInstallation(BaseFeature hook, String label) {
        boolean returned = true;
        try { hook.hook(); } catch (Throwable unavailable) { returned = false; }
        check(returned && XposedBridge.registrations() == 1, label);
    }
    @SuppressWarnings("unchecked")
    private static FeatureRegistry.FeatureEntry registryState(Preferences prefs, Class<?> type,
                                                              boolean unavailable, String label) throws Exception {
        FeatureRegistry registry = new FeatureRegistry(CONTEXT, LOADER, prefs);
        Field field = FeatureRegistry.class.getDeclaredField("features"); field.setAccessible(true);
        List<FeatureRegistry.FeatureEntry> entries = (List<FeatureRegistry.FeatureEntry>) field.get(registry);
        // Keep the real factory and status logic; unrelated hooks are never executed.
        entries.removeIf(entry -> !type.getSimpleName().equals(entry.className));
        if (entries.size() != 1) throw new IllegalStateException("Fixture registry target missing");
        registry.initializeAll();
        FeatureRegistry.FeatureEntry entry = entries.get(0);
        check((unavailable ? "FAILED" : "ACTIVE").equals(entry.status), label + "/registry status");
        check(registry.hasFailures() == unavailable, label + "/registry failure signal");
        check(unavailable ? unavailableMessage(type).equals(entry.error) : entry.error == null,
                label + "/registry error contract");
        check(XposedBridge.logs.stream().noneMatch(value -> value.contains("fixture-private-")),
                label + "/private failure text is not reported");
        return entry;
    }
    private static Method method(Class<?> c, String name, Class<?>... args) throws Exception {
        return c.getMethod(name, args);
    }
    private static MethodData metadata(DexKitBridge b, Class<?> c, String name, String anchor, Class<?>... args)
            throws Exception {
        MethodData value = new MethodData(method(c, name, args), anchor);
        b.methods.add(value);
        return value;
    }
    private static DexKitBridge reset() {
        XposedBridge.hooks.clear(); XposedBridge.logs.clear(); XposedBridge.rejected.clear();
        DexSearchEngine.initializes = 0; DexSearchEngine.failure = null;
        DexKitBridge b = new DexKitBridge(); DexSearchEngine.bridge = b; return b;
    }
    private static void identity(DexKitBridge b, boolean ambiguous) throws Exception {
        MethodData sender = metadata(b, Message.class, "sender", "FMessage/getSenderUserJid/key.id=");
        sender.fields.add(new UsingFieldData(Message.class.getDeclaredField("primary")));
        sender.fields.add(new UsingFieldData(Message.class.getDeclaredField("mutableDecoy")));
        if (ambiguous) sender.fields.add(new UsingFieldData(Message.class.getDeclaredField("otherFinal")));
    }
    private static DexKitBridge viewFixture(boolean decoyFirst, boolean ambiguousKey) throws Exception {
        DexKitBridge b = reset(); identity(b, ambiguousKey);
        if (decoyFirst) metadata(b, Sql.class, "unrelated", "INSERT_VIEW_ONCE_SQL");
        MethodData caller = metadata(b, Sql.class, "savePhoto", "INSERT_VIEW_ONCE_SQL");
        // Storage reads state: the setter is the other method in this exact interface.
        caller.invokes.add(new MethodData(method(ViewState.class, "getState")));
        caller.invokes.add(new MethodData(method(ViewState.class, "getState")));
        MethodData second = metadata(b, Sql.class, "saveVideo", "INSERT_VIEW_ONCE_SQL");
        second.invokes.add(new MethodData(method(ViewState.class, "getState")));
        for (Class<?> type : new Class<?>[]{Photo.class, Video.class, NotMessage.class,
                                          InheritedPhoto.class, Photo.class}) {
            b.classes.add(new ClassData(type));
        }
        return b;
    }
    private static void setState(Object target, int input) throws Throwable {
        XposedBridge.invoke(method(target.getClass(), "setState", int.class), target, input);
    }
    private static void viewOnceTests() throws Throwable {
        Preferences p = new Preferences(); p.put("anti_view_once", true);
        DexKitBridge b = viewFixture(false, false);
        AntiViewOnceHook hook = new AntiViewOnceHook(CONTEXT, LOADER, p); hook.hook();
        Photo incoming = new Photo(new Key(false)); setState(incoming, 1);
        check(incoming.state == 0, "viewonce/incoming state 1 becomes standard");
        Photo outgoing = new Photo(new Key(true)); setState(outgoing, 1);
        check(outgoing.state == 1, "viewonce/outgoing media remains view-once");
        Photo unknown = new Photo(null); setState(unknown, 1);
        check(unknown.state == 1, "viewonce/missing identity preserves host state");
        NotMessage unrelated = new NotMessage(); setState(unrelated, 1);
        check(unrelated.state == 1, "viewonce/non-message implementor untouched");
        XposedBridge.invoke(method(Photo.class, "setUnrelated", int.class), incoming, 1);
        check(incoming.unrelated == 1, "viewonce/unrelated integer setter untouched");
        check(XposedBridge.registrations() == 2, "viewonce/exactly two concrete setters, no duplicate hooks");
        System.out.println("METRIC viewonce initial_queries=" + b.queries
                + " initial_registrations=" + XposedBridge.registrations()
                + " callbacks_per_photo_set=" + XposedBridge.hooks.getOrDefault(
                        method(Photo.class, "setState", int.class), Collections.emptyList()).size());
        int installed = XposedBridge.registrations(); hook.hook();
        check(XposedBridge.registrations() == installed, "viewonce/repeated installation is idempotent");
        for (int value : new int[]{-1, 0, 2, Integer.MAX_VALUE}) {
            setState(incoming, value); check(incoming.state == value, "viewonce/preserve state " + value);
        }
        InheritedPhoto inherited = new InheritedPhoto(new Key(false)); setState(inherited, 1);
        check(inherited.state == 0, "viewonce/inherited concrete setter works");
        p.put("anti_view_once", false); setState(incoming, 1);
        check(incoming.state == 1, "viewonce/live disable");
        p.put("viewonce", true); setState(incoming, 1);
        check(incoming.state == 0, "viewonce/legacy live enable");
        int scans = b.queries;
        for (int i = 0; i < 500; i++) setState(incoming, 1);
        check(b.queries == scans, "viewonce/no DexKit scan from callbacks");

        viewFixture(true, false); new AntiViewOnceHook(CONTEXT, LOADER, p).hook();
        Photo later = new Photo(new Key(false)); setState(later, 1);
        check(later.state == 0, "viewonce/valid SQL caller after unrelated first result");

        viewFixture(false, true);
        expectUnavailable(new AntiViewOnceHook(CONTEXT, LOADER, p), "viewonce/ambiguous immutable message key");
        check(XposedBridge.hooks.isEmpty(), "viewonce/ambiguous immutable message key fails open");

        b = reset(); identity(b, false);
        MethodData caller = metadata(b, Sql.class, "savePhoto", "INSERT_VIEW_ONCE_SQL");
        caller.invokes.add(new MethodData(method(AmbiguousState.class, "getState")));
        b.classes.add(new ClassData(AmbiguousPhoto.class));
        expectUnavailable(new AntiViewOnceHook(CONTEXT, LOADER, p), "viewonce/ambiguous state interface");
        check(XposedBridge.hooks.isEmpty(), "viewonce/ambiguous state interface fails open");

        b = reset(); identity(b, false);
        metadata(b, DirectPhoto.class, "directState", "view_once", int.class);
        metadata(b, DirectPhoto.class, "staticState", "view_once", int.class);
        metadata(b, NotMessage.class, "setState", "view_once", int.class);
        new AntiViewOnceHook(CONTEXT, LOADER, p).hook();
        check(XposedBridge.registrations() == 1, "viewonce/direct fallback only concrete message setter");
        DirectPhoto direct = new DirectPhoto(new Key(false));
        XposedBridge.invoke(method(DirectPhoto.class, "directState", int.class), direct, 1);
        check(direct.state == 0, "viewonce/direct fallback incoming state");
        direct = new DirectPhoto(new Key(true));
        XposedBridge.invoke(method(DirectPhoto.class, "directState", int.class), direct, 1);
        check(direct.state == 1, "viewonce/direct fallback outgoing state");

        b = reset(); identity(b, false);
        metadata(b, DirectPhoto.class, "directState", "view_once", int.class);
        metadata(b, DirectPhoto.class, "otherDirectState", "view_once", int.class);
        expectUnavailable(new AntiViewOnceHook(CONTEXT, LOADER, p), "viewonce/ambiguous direct setters");
        check(XposedBridge.hooks.isEmpty(), "viewonce/ambiguous direct setters fail open");

        b = viewFixture(false, false); Preferences initiallyOff = new Preferences();
        new AntiViewOnceHook(CONTEXT, LOADER, initiallyOff).hook();
        initiallyOff.put("anti_view_once", true); Photo live = new Photo(new Key(false)); setState(live, 1);
        check(live.state == 0, "viewonce/install while off keeps existing live-enable contract");
    }

    private static void imageTests() throws Throwable {
        Preferences p = new Preferences(); p.put("imagequality", true);
        DexKitBridge b = reset(); metadata(b, Encoder.class, "image", "image/compress quality", int.class);
        new MediaQualityBypassHook(CONTEXT, LOADER, p).hook();
        Encoder encoder = new Encoder(); Method image = method(Encoder.class, "image", int.class);
        for (int quality : new int[]{0, 75, 100}) {
            XposedBridge.invoke(image, encoder, quality);
            check(encoder.quality == 100, "media/image quality " + quality);
        }
        for (int sentinel : new int[]{-1, 101, Integer.MAX_VALUE}) {
            XposedBridge.invoke(image, encoder, sentinel);
            check(encoder.quality == sentinel, "media/preserve non-quality integer " + sentinel);
        }
        XC_MethodHook.MethodHookParam malformed = new XC_MethodHook.MethodHookParam(); malformed.args = null;
        try {
            for (XC_MethodHook callback : XposedBridge.hooks.getOrDefault(image, Collections.emptyList())) callback.before(malformed);
            check(true, "media/null argument envelope safe");
        } catch (NullPointerException failure) { check(false, "media/null argument envelope safe"); }
        p.put("imagequality", false); XposedBridge.invoke(image, encoder, 70);
        check(encoder.quality == 70, "media/image live disable");
        p.put("imagequality", true); XposedBridge.invoke(image, encoder, 70);
        check(encoder.quality == 100, "media/image live re-enable");

        b = reset(); metadata(b, Encoder.class, "image", "image/compress quality", int.class);
        metadata(b, Encoder.class, "otherImage", "image/compress quality", int.class);
        expectUnavailable(new MediaQualityBypassHook(CONTEXT, LOADER, p), "media/ambiguous image methods");
        check(XposedBridge.hooks.isEmpty(), "media/ambiguous image methods fail open");
    }
    private static void fpsTests() throws Throwable {
        Preferences p = new Preferences(); p.put("video_maxfps", true);
        DexKitBridge b = reset(); metadata(b, Encoder.class, "frames", "video/encoder fps");
        new MediaQualityBypassHook(CONTEXT, LOADER, p).hook();
        Encoder encoder = new Encoder(); Method frames = method(Encoder.class, "frames");
        check(Integer.valueOf(60).equals(XposedBridge.invoke(frames, encoder).getResult()), "media/valid FPS becomes 60");
        encoder.fps = 120;
        check(Integer.valueOf(120).equals(XposedBridge.invoke(frames, encoder).getResult()), "media/higher FPS not reduced");
        for (int value : new int[]{0, -1}) {
            encoder.fps = value;
            check(Integer.valueOf(value).equals(XposedBridge.invoke(frames, encoder).getResult()), "media/FPS sentinel " + value);
        }
        encoder.fail = true; XC_MethodHook.MethodHookParam failed = XposedBridge.invoke(frames, encoder);
        check(failed.getThrowable() == encoder.failure, "media/original codec exception preserved");
        encoder.fail = false; encoder.fps = 24; p.put("video_maxfps", false);
        check(Integer.valueOf(24).equals(XposedBridge.invoke(frames, encoder).getResult()), "media/FPS live disable");
        p.put("video_maxfps", true);
        check(Integer.valueOf(60).equals(XposedBridge.invoke(frames, encoder).getResult()), "media/FPS live re-enable");
        int scans = b.queries;
        for (int i = 0; i < 500; i++) XposedBridge.invoke(frames, encoder);
        check(b.queries == scans, "media/no DexKit scan from FPS callbacks");

        b = reset(); metadata(b, Encoder.class, "boxedFrames", "video/encoder fps");
        new MediaQualityBypassHook(CONTEXT, LOADER, p).hook(); encoder.boxed = null;
        check(XposedBridge.invoke(method(Encoder.class, "boxedFrames"), encoder).getResult() == null,
                "media/nullable FPS remains null");

        b = reset(); metadata(b, Encoder.class, "frames", "video/encoder fps");
        metadata(b, Encoder.class, "otherFrames", "video/encoder fps");
        expectUnavailable(new MediaQualityBypassHook(CONTEXT, LOADER, p), "media/ambiguous FPS methods");
        check(XposedBridge.hooks.isEmpty(), "media/ambiguous FPS methods fail open");
    }
    private static void selectorTests() throws Throwable {
        Preferences p = new Preferences();
        for (String anchor : new String[]{"enable_media_quality_tool", "show_media_quality_toggle"}) {
            DexKitBridge b = reset(); metadata(b, Encoder.class, "qualityTool", anchor);
            MediaQualityBypassHook hook = new MediaQualityBypassHook(CONTEXT, LOADER, p); hook.hook();
            Encoder encoder = new Encoder(); Method selector = method(Encoder.class, "qualityTool");
            p.values.clear();
            check(Boolean.FALSE.equals(XposedBridge.invoke(selector, encoder).getResult()), "media/HD tool disabled without preset " + anchor);
            p.put("imagequality", true); p.put("video_maxfps", true);
            check(Boolean.TRUE.equals(XposedBridge.invoke(selector, encoder).getResult()), "media/HD tool enabled for exact anchor " + anchor);
            p.put("video_maxfps", false);
            check(Boolean.TRUE.equals(XposedBridge.invoke(selector, encoder).getResult()), "media/HD control available for image-only setting " + anchor);
            p.put("imagequality", false); p.put("video_maxfps", true);
            check(Boolean.TRUE.equals(XposedBridge.invoke(selector, encoder).getResult()), "media/HD control available for video-only setting " + anchor);
            encoder.fail = true;
            check(XposedBridge.invoke(selector, encoder).getThrowable() == encoder.failure, "media/HD tool exception preserved " + anchor);
            encoder.fail = false; p.put("imagequality", false); p.put("video_maxfps", false);
            check(Boolean.FALSE.equals(XposedBridge.invoke(selector, encoder).getResult()), "media/HD tool live disable " + anchor);
            int registrations = XposedBridge.registrations(); hook.hook();
            check(XposedBridge.registrations() == registrations, "media/repeated installation no duplicate selectors " + anchor);
        }
        p.put("imagequality", true); p.put("video_maxfps", true);
        DexKitBridge b = reset(); metadata(b, Encoder.class, "qualityTool", "enable_media_quality_tool");
        metadata(b, Encoder.class, "qualityToggle", "enable_media_quality_tool");
        expectUnavailable(new MediaQualityBypassHook(CONTEXT, LOADER, p), "media/ambiguous HD selector");
        check(XposedBridge.hooks.isEmpty(), "media/ambiguous HD selector fails open");
        b = reset(); metadata(b, Encoder.class, "qualityTool", "prefix enable_media_quality_tool unrelated");
        metadata(b, Encoder.class, "boxedToggle", "show_media_quality_toggle");
        expectUnavailable(new MediaQualityBypassHook(CONTEXT, LOADER, p), "media/unsupported HD selector");
        check(XposedBridge.hooks.isEmpty(), "media/HD selector requires exact literal and primitive boolean");
        b = reset(); metadata(b, Encoder.class, "qualityTool", "enable_media_quality_tool");
        b.methods.get(0).anchors.add("show_media_quality_toggle");
        new MediaQualityBypassHook(CONTEXT, LOADER, p).hook();
        check(XposedBridge.registrations() == 1, "media/shared HD selector registered once");
    }

    private static void installationStatusTests() throws Throwable {
        for (String key : new String[]{"imagequality", "video_maxfps", "anti_view_once", "viewonce"}) {
            Class<?> type = key.equals("imagequality") || key.equals("video_maxfps")
                    ? MediaQualityBypassHook.class : AntiViewOnceHook.class;
            Preferences prefs = new Preferences(); prefs.put(key, true);
            reset(); registryState(prefs, type, true, "status/absent contract/" + key);
            check(XposedBridge.registrations() == 0, "status/absent contract keeps host unchanged/" + key);
            reset(); DexSearchEngine.bridge = null;
            registryState(prefs, type, true, "status/absent bridge/" + key);
            check(XposedBridge.registrations() == 0, "status/absent bridge installs no callback/" + key);
            reset(); DexSearchEngine.failure = new IllegalStateException("fixture-private-discovery-value");
            registryState(prefs, type, true, "status/discovery error/" + key);
            check(XposedBridge.registrations() == 0, "status/discovery error installs no callback/" + key);
            prefs.put(key, false); reset();
            registryState(prefs, type, false, "status/off without contract/" + key);
            check(XposedBridge.registrations() == 0, "status/off missing contract keeps host unchanged/" + key);
            reset(); DexSearchEngine.bridge = null;
            registryState(prefs, type, false, "status/off without bridge/" + key);
            check(XposedBridge.registrations() == 0, "status/off missing bridge installs no callback/" + key);
        }

        Preferences media = new Preferences(); media.put("imagequality", true); media.put("video_maxfps", true);
        DexKitBridge bridge = reset(); metadata(bridge, Encoder.class, "image", "image/compress quality", int.class);
        Method image = method(Encoder.class, "image", int.class), frames = method(Encoder.class, "frames");
        XposedBridge.rejected.add(image);
        registryState(media, MediaQualityBypassHook.class, true, "status/media registration error");
        check(XposedBridge.registrations() == 0, "status/media failed registration is not counted");

        bridge = reset(); metadata(bridge, Encoder.class, "image", "image/compress quality", int.class);
        metadata(bridge, Encoder.class, "frames", "video/encoder fps"); XposedBridge.rejected.add(frames);
        FeatureRegistry.FeatureEntry partialMedia = registryState(media, MediaQualityBypassHook.class, false,
                "status/media partial registration");
        check(XposedBridge.registrations() == 1, "status/media keeps successful partial registration");
        Encoder encoder = new Encoder(); XposedBridge.invoke(image, encoder, 75);
        check(encoder.quality == 100, "status/media partial image callback remains usable");
        encoder.fps = 24;
        check(Integer.valueOf(24).equals(XposedBridge.invoke(frames, encoder).getResult()),
                "status/media ACTIVE does not prove missing FPS suboption");
        DexSearchEngine.bridge = null;
        repeatPartialInstallation(partialMedia.feature, "status/media retained callback survives later discovery loss");

        Preferences view = new Preferences(); view.put("anti_view_once", true);
        viewFixture(false, false);
        Method photo = method(Photo.class, "setState", int.class), video = method(Video.class, "setState", int.class);
        XposedBridge.rejected.add(photo); XposedBridge.rejected.add(video);
        registryState(view, AntiViewOnceHook.class, true, "status/viewonce registration error");
        check(XposedBridge.registrations() == 0, "status/viewonce failed registrations are not counted");

        viewFixture(false, false); XposedBridge.rejected.add(video);
        FeatureRegistry.FeatureEntry partialView = registryState(view, AntiViewOnceHook.class, false,
                "status/viewonce partial registration");
        check(XposedBridge.registrations() == 1, "status/viewonce keeps successful partial registration");
        Photo incoming = new Photo(new Key(false)); setState(incoming, 1);
        check(incoming.state == 0, "status/viewonce partial photo callback remains usable");
        Video unsupported = new Video(new Key(false)); setState(unsupported, 1);
        check(unsupported.state == 1, "status/viewonce ACTIVE does not prove every message subtype");
        DexSearchEngine.bridge = null;
        repeatPartialInstallation(partialView.feature, "status/viewonce retained callback survives later discovery loss");
    }

    public static void main(String[] args) throws Throwable {
        String suite = args.length == 0 ? "all" : args[0];
        if (suite.equals("all") || suite.equals("viewonce")) viewOnceTests();
        if (suite.equals("all") || suite.equals("media")) { imageTests(); fpsTests(); selectorTests(); }
        if (suite.equals("all") || suite.equals("status")) installationStatusTests();
        System.out.println("RESULT checks=" + checks + " failures=" + failed + " suite=" + suite);
        if (failed != 0) throw new AssertionError(failed + " media/view-once contract failures");
        System.out.println("PASS full production hooks; synthetic host metadata + Xposed boundary doubles");
    }
}
