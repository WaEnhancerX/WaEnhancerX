package com.waenhancer.xposed.features.media;

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import com.waenhancer.config.PreferenceSchema;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.core.preferences.SettingsBackupCodec;
import com.waenhancer.core.preferences.WaexPreferenceManager;
import com.whatsapp.calling.infra.voipcalling.CallInfo;
import com.whatsapp.calling.infra.voipcalling.CallState;
import com.whatsapp.calling.service.VoiceServiceEventCallback;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.PriorityQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableScheduledFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowMediaRecorder;
import static org.junit.Assert.*;

/** Android preferences/IPC descriptors are real framework objects; no audio is captured. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE, application = Application.class,
        shadows = CallRecordingAudioAndroidTest.RecorderShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class CallRecordingAudioAndroidTest {
    private static final String ENABLED = "call_recording_enabled";
    private static final String CALL = "call_recording_use_root";
    private static final String FALLBACK = "call_recording_allow_mic_fallback";
    private Application app;
    private SharedPreferences prefs;
    private CallRecordingHook hook;
    private VoiceServiceEventCallback host;
    private Scheduler worker;
    private StorageProvider storage;

    /** Only the native recorder boundary is controlled; start does not generate sound or bytes. */
    @Implements(MediaRecorder.class)
    public static class RecorderShadow extends ShadowMediaRecorder {
        static final List<RecorderShadow> attempts = new ArrayList<>();
        static final Map<Integer, RuntimeException> sourceFailures = new HashMap<>();
        static int failedPrepareSource = -1, failedStartSource = -1;
        static Error fatal;
        static Runnable duringPrepare;
        int source, prepares, starts, stops, releases;
        @Implementation protected void setAudioSource(int value) {
            source = value; attempts.add(this);
            if (fatal != null) throw fatal;
            if (sourceFailures.containsKey(value)) throw sourceFailures.get(value);
            super.setAudioSource(value);
        }
        @Implementation protected void prepare() {
            prepares++;
            if (duringPrepare != null) duringPrepare.run();
            if (source == failedPrepareSource) throw new IllegalStateException("private prepare detail");
            super.prepare();
        }
        @Implementation protected void start() {
            starts++;
            if (source == failedStartSource) throw new IllegalStateException("private start detail");
            super.start();
        }
        @Implementation protected void stop() { stops++; super.stop(); }
        @Implementation protected void release() { releases++; super.release(); }
        static void resetFixture() {
            attempts.clear(); sourceFailures.clear(); failedPrepareSource = failedStartSource = -1;
            fatal = null; duringPrepare = null;
        }
    }

    /** Storage authorization is outside this fixture; actual Bundle, PFD and file lifetime are used. */
    public static class StorageProvider extends ContentProvider {
        final List<ParcelFileDescriptor> descriptors = new ArrayList<>();
        final List<File> outputs = new ArrayList<>();
        final List<String> requestedNames = new ArrayList<>();
        int creates, deletes;
        boolean unavailable;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle request) {
            try {
                if ("create_call_recording".equals(method)) {
                    creates++;
                    if (unavailable) return null;
                    File output = File.createTempFile("fixture_", ".audio", getContext().getFilesDir());
                    ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(output,
                            ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_TRUNCATE);
                    outputs.add(output); descriptors.add(descriptor); requestedNames.add(request.getString("name"));
                    Bundle result = new Bundle(); result.putParcelable("descriptor", descriptor);
                    result.putString("path", output.getAbsolutePath()); return result;
                }
                if ("delete_call_recording".equals(method)) {
                    deletes++;
                    for (int i = outputs.size() - 1; i >= 0; i--) {
                        if (requestedNames.get(i).equals(request.getString("name"))) {
                            outputs.get(i).delete(); break;
                        }
                    }
                    return new Bundle();
                }
                throw new AssertionError("Unexpected fixture storage operation");
            } catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        @Override public String getType(Uri u) { return null; }
        @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    }

    /** Virtual two-second delay; tasks are executed synchronously to inspect exact callback effects. */
    private static class Scheduler extends ScheduledThreadPoolExecutor {
        long sequence, now;
        final PriorityQueue<Ticket> tasks = new PriorityQueue<>();
        Scheduler() { super(1); setRemoveOnCancelPolicy(true); }
        final class Ticket extends FutureTask<Object> implements RunnableScheduledFuture<Object> {
            final long due, order;
            Ticket(Runnable task, long delay) { super(task, null); due = now + delay; order = sequence++; }
            public long getDelay(TimeUnit unit) { return unit.convert(due - now, TimeUnit.NANOSECONDS); }
            public int compareTo(Delayed other) {
                Ticket value = (Ticket) other;
                int compared = Long.compare(due, value.due);
                return compared == 0 ? Long.compare(order, value.order) : compared;
            }
            public boolean isPeriodic() { return false; }
            public boolean cancel(boolean interrupt) { boolean value = super.cancel(interrupt); tasks.remove(this); return value; }
        }
        public ScheduledFuture<?> schedule(Runnable task, long delay, TimeUnit unit) {
            Ticket value = new Ticket(task, unit.toNanos(delay)); tasks.add(value); return value;
        }
        public void execute(Runnable task) { schedule(task, 0, TimeUnit.NANOSECONDS); }
        void drain() throws Exception {
            int count = 0;
            while (!tasks.isEmpty()) {
                Ticket value = tasks.remove(); now = value.due; value.run();
                if (!value.isCancelled()) value.get();
                if (++count > 100) throw new AssertionError("Unbounded recorder tasks");
            }
        }
    }

    @Before public void setup() throws Exception {
        app = RuntimeEnvironment.getApplication();
        prefs = app.getSharedPreferences("waex_audio_fixture", Context.MODE_PRIVATE);
        assertTrue(prefs.edit().clear().putBoolean(ENABLED, true).commit());
        XposedBridge.reset(); RecorderShadow.resetFixture();
        host = new VoiceServiceEventCallback();
        storage = new StorageProvider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "com.waenhancer.hook";
        storage.attachInfo(app, info);
        ShadowContentResolver.registerProviderInternal(info.authority, storage);
        hook = new CallRecordingHook(app, getClass().getClassLoader(), prefs);
        Field field = CallRecordingHook.class.getDeclaredField("worker"); field.setAccessible(true);
        ((ScheduledThreadPoolExecutor) field.get(hook)).shutdownNow();
        worker = new Scheduler(); field.set(hook, worker); hook.hook();
    }

    @After public void cleanup() throws Exception {
        RecorderShadow.duringPrepare = null;
        if (prefs != null) prefs.edit().putBoolean(ENABLED, false).commit();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        if (worker != null) { try { worker.drain(); } finally { worker.shutdownNow(); } }
        if (storage != null) {
            for (ParcelFileDescriptor value : storage.descriptors) value.close();
            for (File value : storage.outputs) value.delete();
        }
    }

    private void mode(boolean call, Boolean fallback) {
        SharedPreferences.Editor editor = prefs.edit().putBoolean(CALL, call);
        if (fallback == null) editor.remove(FALLBACK); else editor.putBoolean(FALLBACK, fallback);
        assertTrue(editor.commit()); Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void active(String id) throws Throwable {
        host.current = new CallInfo(id, CallState.ACTIVE);
        XposedBridge.fire(host, "callStateChanged", new Class<?>[]{CallState.class, CallInfo.class},
                CallState.CALLING, host.current);
    }
    private void start() throws Throwable { active("private-call-id"); worker.drain(); }
    private void end() throws Throwable {
        CallInfo ended = new CallInfo(host.current.callId, CallState.NONE);
        XposedBridge.fire(host, "callStateChanged", new Class<?>[]{CallState.class, CallInfo.class},
                CallState.ACTIVE, ended); worker.drain();
    }
    private boolean recording() throws Exception {
        Field field = CallRecordingHook.class.getDeclaredField("recording"); field.setAccessible(true);
        return ((AtomicBoolean) field.get(hook)).get();
    }
    private List<Integer> sources() {
        List<Integer> values = new ArrayList<>();
        for (RecorderShadow value : RecorderShadow.attempts) values.add(value.source);
        return values;
    }
    private void reject(int... values) {
        for (int value : values) RecorderShadow.sourceFailures.put(value,
                new SecurityException("private audio routing payload"));
    }
    private long logs(String text) { return XposedBridge.logs.stream().filter(x -> x.contains(text)).count(); }
    private void failedOutput() throws Exception {
        assertFalse(recording()); assertEquals(1, storage.deletes);
        assertFalse(storage.outputs.get(0).exists());
        assertFalse(storage.descriptors.get(0).getFileDescriptor().valid());
        assertTrue(RecorderShadow.attempts.stream().allMatch(x -> x.releases == 1));
    }

    @Test public void standardDefaultStartsCommunicationMicrophone() throws Throwable {
        start(); assertEquals(Arrays.asList(7), sources()); assertTrue(recording());
        assertEquals(1, logs("started; source=VOICE_COMMUNICATION; fallback=false; remote_audio=unverified"));
    }
    @Test public void standardMicFallbackRemainsAvailable() throws Throwable {
        mode(false, false); reject(7); start(); assertEquals(Arrays.asList(7, 1), sources());
        assertEquals(1, logs("started; source=MIC; fallback=true; remote_audio=unverified"));
        assertEquals(1, RecorderShadow.attempts.get(0).releases);
    }
    @Test public void standardFinalCallSourcePreservesExistingOrder() throws Throwable {
        mode(false, false); reject(7, 1); start(); assertEquals(Arrays.asList(7, 1, 4), sources());
        assertTrue(recording()); assertEquals(1, logs("started; source=VOICE_CALL; fallback=true; remote_audio=unverified"));
    }
    @Test public void absentFallbackPreferencePreservesCallModeCompatibility() throws Throwable {
        mode(true, null); reject(4); start(); assertEquals(Arrays.asList(4, 7), sources());
        assertTrue(recording()); assertEquals(1, logs("started; source=VOICE_COMMUNICATION; fallback=true; remote_audio=unverified"));
    }
    @Test public void explicitFallbackAllowsFinalMicrophoneSource() throws Throwable {
        mode(true, true); reject(4, 7); start(); assertEquals(Arrays.asList(4, 7, 1), sources());
        assertTrue(recording()); assertEquals(1, logs("started; source=MIC; fallback=true; remote_audio=unverified"));
    }
    @Test public void strictCallModeStopsOnSourceRejectionAndRemovesOutput() throws Throwable {
        mode(true, false); reject(4); start(); assertEquals(Arrays.asList(4), sources()); failedOutput();
        assertEquals(0, logs("started;"));
    }
    @Test public void strictCallModeStopsOnPrepareFailureAndRemovesOutput() throws Throwable {
        mode(true, false); RecorderShadow.failedPrepareSource = 4; start();
        assertEquals(Arrays.asList(4), sources()); failedOutput(); assertEquals(0, RecorderShadow.attempts.get(0).starts);
    }
    @Test public void strictCallModeStopsOnStartFailureAndRemovesOutput() throws Throwable {
        mode(true, false); RecorderShadow.failedStartSource = 4; start();
        assertEquals(Arrays.asList(4), sources()); failedOutput(); assertEquals(0, logs("started;"));
    }
    @Test public void acceptedCallSourceDoesNotClaimRemoteAudioSuccess() throws Throwable {
        mode(true, false); start(); assertEquals(Arrays.asList(4), sources()); assertTrue(recording());
        assertEquals(1, logs("started; source=VOICE_CALL; fallback=false; remote_audio=unverified"));
        assertEquals(0, logs("remote_audio=verified")); assertEquals(0, logs("both participants"));
    }
    @Test public void allRejectedSourcesReleaseEachAttemptAndDeleteOutput() throws Throwable {
        mode(true, true); reject(4, 7, 1); start(); failedOutput();
        assertEquals(Arrays.asList(4, 7, 1), sources()); assertEquals(3, logs("source failed;"));
        assertEquals(1, logs("start failed:"));
    }
    @Test public void cancellationBeforeDelayDoesNotCreateAudioResources() throws Throwable {
        active("private-call-id"); end(); assertTrue(RecorderShadow.attempts.isEmpty());
        assertEquals(0, storage.creates); assertEquals(0, logs("started;"));
    }
    @Test public void cancellationDuringPrepareDoesNotFallBack() throws Throwable {
        mode(true, true);
        RecorderShadow.duringPrepare = () -> {
            prefs.edit().putBoolean(ENABLED, false).commit(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        };
        start(); assertEquals(Arrays.asList(4), sources()); failedOutput(); assertEquals(0, logs("started;"));
    }
    @Test public void disableDuringCaptureStopsAndClosesDescriptorOnce() throws Throwable {
        start(); RecorderShadow value = RecorderShadow.attempts.get(0);
        prefs.edit().putBoolean(ENABLED, false).commit(); Shadows.shadowOf(Looper.getMainLooper()).idle(); worker.drain();
        assertFalse(recording()); assertEquals(1, value.stops); assertEquals(1, value.releases);
        assertFalse(storage.descriptors.get(0).getFileDescriptor().valid()); assertEquals(0, storage.deletes);
    }
    @Test public void sourcePreferenceChangeAffectsNextAttemptOnly() throws Throwable {
        mode(true, true); reject(4); start(); RecorderShadow value = RecorderShadow.attempts.get(1);
        mode(true, false); assertTrue(recording()); assertEquals(0, value.stops); end();
        active("private-next-call"); worker.drain(); assertEquals(Arrays.asList(4, 7, 4), sources());
        assertFalse(recording()); assertEquals(1, storage.deletes);
    }
    @Test public void fatalVmErrorPropagatesWithoutMicrophoneFallback() throws Throwable {
        mode(true, true); Error fatal = new OutOfMemoryError("private fatal payload"); RecorderShadow.fatal = fatal;
        active("private-call-id");
        try { worker.drain(); fail("Fatal error was swallowed"); }
        catch (ExecutionException error) { assertSame(fatal, error.getCause()); }
        assertEquals(Arrays.asList(4), sources()); failedOutput(); assertEquals(0, logs("started;"));
    }
    @Test public void repeatedFailuresDeduplicateSourceDiagnosticsForTheSession() throws Throwable {
        mode(true, true); reject(4, 7, 1);
        for (int i = 0; i < 20; i++) { active("private-call-id"); worker.drain(); }
        assertEquals(60, RecorderShadow.attempts.size()); assertEquals(3, logs("source failed;"));
        assertEquals(1, logs("start failed:")); assertEquals(1, logs("hooks installed;"));
    }
    @Test public void repeatedActiveEventsDoNotRepeatSuccessfulSourceLogsOrAllocateOutputs() throws Throwable {
        for (int i = 0; i < 10000; i++) active("private-call-id");
        assertEquals(1, worker.tasks.size()); worker.drain();
        for (int i = 0; i < 10000; i++) active("private-call-id");
        assertEquals(1, storage.creates); assertEquals(1, logs("started;")); assertTrue(worker.tasks.isEmpty());
    }
    @Test public void successfulStopPreservesFileAndReleasesRecorder() throws Throwable {
        XposedBridge.failLog = true;
        start(); RecorderShadow value = RecorderShadow.attempts.get(0); end();
        assertEquals(1, RecorderShadow.attempts.size());
        assertFalse(recording()); assertEquals(1, value.stops); assertEquals(1, value.releases);
        assertEquals(0, storage.deletes); assertTrue(storage.outputs.get(0).exists());
        assertFalse(storage.descriptors.get(0).getFileDescriptor().valid());
    }
    @Test public void aacFormatSelectionKeepsSourceChoiceIndependent() throws Throwable {
        mode(true, false); prefs.edit().putString("call_recording_format", "m4a").commit(); start();
        RecorderShadow value = RecorderShadow.attempts.get(0); assertEquals(4, value.source);
        assertEquals(MediaRecorder.AudioEncoder.AAC, value.getAudioEncoder());
        assertEquals(MediaRecorder.OutputFormat.MPEG_4, value.getOutputFormat());
        assertTrue(storage.requestedNames.get(0).endsWith(".m4a"));
    }
    @Test public void opusFormatUsesSupportedApiAndKeepsSourceChoiceIndependent() throws Throwable {
        mode(true, false); prefs.edit().putString("call_recording_format", "opus").commit(); start();
        RecorderShadow value = RecorderShadow.attempts.get(0); assertEquals(4, value.source);
        boolean opus = Build.VERSION.SDK_INT >= 29;
        assertEquals(opus ? MediaRecorder.AudioEncoder.OPUS : MediaRecorder.AudioEncoder.AAC, value.getAudioEncoder());
        assertEquals(opus ? MediaRecorder.OutputFormat.OGG : MediaRecorder.OutputFormat.MPEG_4, value.getOutputFormat());
        assertTrue(storage.requestedNames.get(0).endsWith(opus ? ".opus" : ".m4a"));
    }
    @Test public void fallbackIsPublicBooleanAndBothValuesPersistInPreferences() {
        PreferenceSchema.Entry entry = PreferenceSchema.entry(FALLBACK);
        assertNotNull(entry); assertEquals(PreferenceSchema.Type.BOOLEAN, entry.type);
        assertEquals(PreferenceSchema.Store.PUBLIC, entry.store);
        assertEquals(PreferenceSchema.Sensitivity.PUBLIC_SETTING, entry.sensitivity);
        for (boolean value : new boolean[]{false, true}) {
            assertTrue(prefs.edit().putBoolean(FALLBACK, value).commit());
            SharedPreferences reread = app.getSharedPreferences("waex_audio_fixture", Context.MODE_PRIVATE);
            assertTrue(reread.contains(FALLBACK)); assertEquals(value, reread.getBoolean(FALLBACK, !value));
        }
    }
    @Test public void nextCallRetainsIndependentDiagnosticsWithoutContactIdentity() throws Throwable {
        mode(true, false); reject(4); start(); end(); active("private-next-call"); worker.drain();
        assertEquals(2, logs("source failed; source=VOICE_CALL; error=SecurityException"));
        assertEquals(2, logs("start failed:")); assertEquals(0, logs("private"));
    }
    @Test public void exceptionMessagesAndOutputPathsNeverEnterDiagnostics() throws Throwable {
        mode(true, true); reject(4, 7); start();
        for (String value : XposedBridge.logs) {
            assertFalse(value.contains("private")); assertFalse(value.contains("WhatsApp_Call_"));
            assertFalse(value.contains(storage.outputs.get(0).getAbsolutePath()));
            assertFalse(value.contains("@s.whatsapp.net"));
        }
        assertEquals(1, logs("source failed; source=VOICE_CALL; error=SecurityException"));
    }
    @Test public void failedPrepareCanFallBackWithoutLeakingFirstRecorder() throws Throwable {
        mode(true, true); RecorderShadow.failedPrepareSource = 4; start();
        assertEquals(Arrays.asList(4, 7), sources()); assertEquals(1, RecorderShadow.attempts.get(0).releases);
        assertEquals(0, RecorderShadow.attempts.get(0).starts); assertEquals(0, RecorderShadow.attempts.get(1).releases);
        assertTrue(recording()); assertEquals(1, logs("started; source=VOICE_COMMUNICATION; fallback=true; remote_audio=unverified"));
    }
    @Test public void preferenceManagerAndBackupCodecRetainBothFallbackValues() throws Exception {
        WaexPreferenceManager manager = new WaexPreferenceManager(app);
        SharedPreferences publicPrefs = PreferenceStores.publicStore(app);
        assertTrue(publicPrefs.edit().clear().commit());
        assertTrue(PreferenceStores.privateStore(app).edit().clear().commit());
        assertTrue(manager.getBoolean(FALLBACK, true));
        for (boolean value : new boolean[]{false, true}) {
            assertTrue(manager.putBooleans(Collections.singletonMap(FALLBACK, value)));
            assertEquals(value, new WaexPreferenceManager(app).getBoolean(FALLBACK, !value));
            assertFalse(PreferenceStores.privateStore(app).contains(FALLBACK));
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            assertEquals(1, SettingsBackupCodec.INSTANCE.encode(app, encoded));
            assertTrue(publicPrefs.edit().remove(FALLBACK).commit());
            assertEquals(1, SettingsBackupCodec.INSTANCE.decode(app,
                    new ByteArrayInputStream(encoded.toByteArray())));
            assertEquals(value, manager.getBoolean(FALLBACK, !value));
        }
    }
    @Test public void failedPreferenceCommitIsNotAcknowledgedAsSaved() {
        SharedPreferences real = PreferenceStores.publicStore(app);
        assertTrue(real.edit().clear().putBoolean(FALLBACK, true).commit());
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(real, args);
                    SharedPreferences.Editor editor = real.edit();
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (self, edit, values) -> {
                                if (edit.getName().equals("commit")) return false;
                                Object result = edit.invoke(editor, values);
                                return result == editor ? self : result;
                            });
                });
        Context failedContext = new ContextWrapper(app) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return PreferenceStores.PUBLIC_NAME.equals(name) ? failing : super.getSharedPreferences(name, mode);
            }
        };
        WaexPreferenceManager manager = new WaexPreferenceManager(failedContext);
        assertFalse(manager.putBooleans(Collections.singletonMap(FALLBACK, false)));
        assertTrue(real.getBoolean(FALLBACK, false));
    }
}
