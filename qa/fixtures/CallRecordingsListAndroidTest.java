package com.waenhancer.qa.recordings;

import android.Manifest;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadows.ShadowEnvironment;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.ShadowToast;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import static org.junit.Assert.*;

/** Real Android Context/Core/PFD and disk; Compose/lifecycle/Binder are explicit adapters. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {24, 28, 34}, manifest = Config.NONE, application = Application.class,
        shadows = CallRecordingsListAndroidTest.ControlledPlayer.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class CallRecordingsListAndroidTest {
    private static final String AUTHORITY = "com.waenhancer.qa.recordings.files";
    private Application app;
    private StorageContext context;
    private final List<File> created = new ArrayList<>();
    private String stem;

    /** Native decoder/playback boundary only: placeholder files are never decoded or played. */
    @Implements(MediaPlayer.class)
    public static class ControlledPlayer extends ShadowMediaPlayer {
        static final List<ControlledPlayer> attempts = new ArrayList<>();
        static String failAt = "";
        static boolean stopFails, releaseFails;
        static Runnable duringStop;
        @RealObject MediaPlayer realPlayer;
        int prepares, starts, stops, releases;
        MediaPlayer.OnCompletionListener completion;
        @Implementation protected void setDataSource(String path) throws IOException {
            attempts.add(this);
            if ("data".equals(failAt)) throw new IOException("controlled source failure");
        }
        @Implementation protected void prepare() {
            prepares++;
            if ("prepare".equals(failAt)) throw new IllegalStateException("controlled prepare failure");
        }
        @Implementation protected void start() {
            starts++;
            if ("start".equals(failAt)) throw new IllegalStateException("controlled start failure");
        }
        @Implementation protected void setOnCompletionListener(MediaPlayer.OnCompletionListener listener) {
            completion = listener;
        }
        @Implementation protected void stop() {
            stops++;
            Runnable reentry = duringStop; duringStop = null;
            if (reentry != null) reentry.run();
            if (stopFails) throw new IllegalStateException("controlled stop failure");
        }
        @Implementation protected void release() {
            releases++;
            if (releaseFails) throw new IllegalStateException("controlled release failure");
        }
        static void resetFixture() {
            attempts.clear(); failAt = ""; stopFails = releaseFails = false; duringStop = null;
        }
    }

    /** Simulates external-volume availability and permission answers, not file contents. */
    private static final class StorageContext extends ContextWrapper {
        boolean externalAvailable = true;
        final Map<String, Integer> permissions = new HashMap<>();
        final List<String> ioThreads = Collections.synchronizedList(new ArrayList<>());
        StorageContext(Context context) { super(context); }
        @Override public int checkPermission(String permission, int pid, int uid) {
            return permissions.getOrDefault(permission, PackageManager.PERMISSION_DENIED);
        }
        @Override public File getFilesDir() {
            ioThreads.add(Thread.currentThread().getName());
            return super.getFilesDir();
        }
        @Override public File getExternalFilesDir(String type) {
            ioThreads.add(Thread.currentThread().getName());
            return externalAvailable ? super.getExternalFilesDir(type) : null;
        }
    }

    @Before public void setup() {
        app = RuntimeEnvironment.getApplication();
        // Robolectric defaults these directory APIs to separate roots. Model one
        // external volume so the public Recordings path matches external-path XML.
        ShadowEnvironment.setExternalStoragePublicDirectory(Environment.getExternalStorageDirectory().toPath());
        context = new StorageContext(app);
        stem = "fixture_" + UUID.randomUUID().toString().replace("-", "");
        ControlledPlayer.resetFixture(); ShadowToast.reset();
    }
    @After public void cleanup() throws Exception {
        ControlledPlayer.stopFails = ControlledPlayer.releaseFails = false;
        ControlledPlayer.duringStop = null;
        for (ControlledPlayer player : ControlledPlayer.attempts) {
            if (player.releases == 0) player.realPlayer.release();
        }
        for (File file : created) assertTrue(!file.exists() || file.delete());
        Field cache = FileProvider.class.getDeclaredField("sCache"); cache.setAccessible(true);
        ((Map<?, ?>) cache.get(null)).clear();
    }
    private void grantLegacy() {
        context.permissions.put(Manifest.permission.READ_EXTERNAL_STORAGE, PackageManager.PERMISSION_GRANTED);
        context.permissions.put(Manifest.permission.READ_MEDIA_AUDIO, PackageManager.PERMISSION_GRANTED);
    }
    private Controller probe() { return CallRecordingsListProbe.create(context, "recordings"); }
    private File storedByProvider() throws Exception {
        Bundle result = ProviderStorageProbe.create(context, stem + ".m4a");
        assertNotNull("Provider branch could not create controlled output", result);
        ParcelFileDescriptor descriptor = result.getParcelable("descriptor");
        assertNotNull(descriptor);
        File output = new File(result.getString("path")); created.add(output);
        try (ParcelFileDescriptor.AutoCloseOutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
            stream.write("controlled-audio-placeholder".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue(output.isFile()); assertTrue(output.length() > 0);
        assertFalse(descriptor.getFileDescriptor().valid());
        return output;
    }
    private File create(File directory, String extension, long modified) throws Exception {
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File file = new File(directory, stem + "_" + created.size() + extension);
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(new byte[]{1, 2, 3}); }
        assertTrue(file.setLastModified(modified)); created.add(file); return file;
    }
    private void assertListed(Controller value, File expected) {
        assertTrue("Readable provider/legacy output missing from CallsHub: " + expected.getName(),
                Arrays.asList(value.paths()).contains(expected.getAbsolutePath()));
    }
    private void assertAbsent(Controller value, File expected) {
        assertFalse("Disallowed or unrelated output entered CallsHub", Arrays.asList(value.paths()).contains(expected.getAbsolutePath()));
    }

    @Test public void ownExternalRecordingRemainsVisibleWhenLegacyPermissionDenied() throws Exception {
        File output = storedByProvider(); Controller value = probe(); value.refresh(); assertListed(value, output);
    }
    @Test public void internalProviderFallbackIsListedWithExternalVolumeUnavailable() throws Exception {
        grantLegacy(); context.externalAvailable = false;
        File output = storedByProvider(); Controller value = probe(); value.refresh(); assertListed(value, output);
    }
    @Test public void earlierInternalFallbackRemainsVisibleAfterExternalVolumeReturns() throws Exception {
        grantLegacy(); context.externalAvailable = false; File output = storedByProvider();
        context.externalAvailable = true; Controller value = probe(); value.refresh(); assertListed(value, output);
    }
    @Test public void oldPrivateDirectoryRemainsVisibleWithoutSharedPermission() throws Exception {
        File output = create(new File(app.getFilesDir(), "call_recordings"), ".opus", 1000);
        Controller value = probe(); value.refresh(); assertListed(value, output);
    }
    @Test public void deniedLegacyPermissionDoesNotListPublicDirectories() throws Exception {
        File output = create(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WAEX_Calls"), ".wav", 1000);
        Controller value = probe(); value.refresh(); assertAbsent(value, output);
    }
    @Test public void grantedPermissionRetainsAllThreeLegacyPublicDirectories() throws Exception {
        grantLegacy();
        List<File> outputs = Arrays.asList(
                create(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WAEX_Calls"), ".mp3", 1000),
                create(new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "WaEnhancer/Recordings"), ".aac", 2000),
                create(new File(Environment.getExternalStorageDirectory(), "Recordings/WAEX_Calls"), ".wav", 3000));
        Controller value = probe(); value.refresh(); for (File output : outputs) assertListed(value, output);
    }
    @Test public void storagePermissionMatchesAudioApiAndDenialIsRepresented() {
        Controller value = probe();
        assertEquals(Build.VERSION.SDK_INT >= 33 ? Manifest.permission.READ_MEDIA_AUDIO : Manifest.permission.READ_EXTERNAL_STORAGE,
                value.requestedPermission());
        assertFalse("Android 13+ must not assume the audio permission is granted", value.permissionGranted());
        assertTrue(value.permissionRequestEnabled());
    }
    @Test public void configurationsTabDoesNotRequestOrScanRecordingStorage() {
        Controller value = CallRecordingsListProbe.create(context, "configs"); context.ioThreads.clear();
        assertFalse(value.permissionRequestEnabled()); value.refresh(); assertTrue(context.ioThreads.isEmpty());
    }
    @Test public void resumeRechecksPermissionAndDisposalReleasesObserver() {
        Controller value = probe(); assertEquals(1, value.observerCount());
        grantLegacy(); value.resume(); assertTrue(value.permissionGranted());
        context.permissions.clear(); value.resume(); assertFalse(value.permissionGranted());
        value.dispose(); assertEquals(0, value.observerCount());
    }
    @Test public void diskEnumerationDoesNotRunOnTheCallerThread() throws Exception {
        grantLegacy(); File output = storedByProvider(); Controller value = probe(); context.ioThreads.clear();
        String caller = Thread.currentThread().getName(); value.refresh(); assertListed(value, output);
        assertFalse(context.ioThreads.isEmpty());
        for (String thread : context.ioThreads) assertNotEquals("Directory enumeration stayed on the UI caller", caller, thread);
        assertFalse(value.isLoading());
    }
    @Test public void fileProviderCanShareInternalProviderFallback() throws Exception {
        context.externalAvailable = false; File output = storedByProvider(); installDeclaredFileProviderRoots();
        try {
            Uri uri = FileProvider.getUriForFile(context, AUTHORITY, output);
            assertEquals("content", uri.getScheme()); assertEquals(AUTHORITY, uri.getAuthority());
        } catch (IllegalArgumentException mismatch) {
            throw new AssertionError("Provider-created internal fallback is outside declared sharing roots", mismatch);
        }
    }
    @Test public void fileProviderKeepsExistingExternalAndOldInternalSharingRoots() throws Exception {
        File external = storedByProvider();
        File old = create(new File(app.getFilesDir(), "call_recordings"), ".m4a", 1000);
        installDeclaredFileProviderRoots();
        assertNotNull(FileProvider.getUriForFile(context, AUTHORITY, external));
        assertNotNull(FileProvider.getUriForFile(context, AUTHORITY, old));
    }
    @Test public void fileProviderDoesNotExposeOtherInternalFiles() throws Exception {
        File other = create(new File(app.getFilesDir(), "unrelated_private"), ".m4a", 1000);
        installDeclaredFileProviderRoots();
        assertThrows(IllegalArgumentException.class, () -> FileProvider.getUriForFile(context, AUTHORITY, other));
    }
    @Test public void audioExtensionCaseAndNewestFirstOrderingAreStable() throws Exception {
        grantLegacy(); File directory = new File(app.getExternalFilesDir(null), "CallRecordings");
        File older = create(directory, ".m4a", 1000);
        File newer = create(directory, ".OPUS", 2000);
        File other = create(directory, ".txt", 3000);
        Controller value = probe(); value.refresh(); assertListed(value, older); assertListed(value, newer); assertAbsent(value, other);
        List<String> paths = Arrays.asList(value.paths()); assertTrue(paths.indexOf(newer.getAbsolutePath()) < paths.indexOf(older.getAbsolutePath()));
    }

    private RecordedCallItem playerItem(Controller value) throws Exception {
        return value.addFile(create(new File(app.getFilesDir(), "call_recordings"), ".m4a", 1000));
    }
    private void assertFailedPlayerReleased(String operation) throws Exception {
        Controller value = probe(); RecordedCallItem item = playerItem(value);
        ControlledPlayer.failAt = operation;
        // Invalid/failed media states may reject stop; release must still be attempted.
        ControlledPlayer.stopFails = true;
        value.play(item);
        assertEquals(1, ControlledPlayer.attempts.size());
        assertEquals("Failed local MediaPlayer lost its cleanup owner", 1, ControlledPlayer.attempts.get(0).releases);
        assertNull(value.activePlayer()); assertNull(value.playingPath());
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Playback error"));
    }
    @Test public void dataSourceFailureReleasesThePlayerCandidate() throws Exception {
        assertFailedPlayerReleased("data");
    }
    @Test public void preparationFailureReleasesThePlayerCandidate() throws Exception {
        assertFailedPlayerReleased("prepare");
    }
    @Test public void startFailureReleasesThePlayerCandidate() throws Exception {
        assertFailedPlayerReleased("start");
    }
    @Test public void stopFailureStillReleasesAndClearsOwnership() throws Exception {
        Controller value = probe(); value.play(playerItem(value));
        ControlledPlayer player = ControlledPlayer.attempts.get(0); ControlledPlayer.stopFails = true;
        value.stop(); assertEquals(1, player.stops); assertEquals(1, player.releases);
        assertNull(value.activePlayer()); assertNull(value.playingPath());
    }
    @Test public void successfulStopIsIdempotentAndClearsPlayingState() throws Exception {
        Controller value = probe(); RecordedCallItem item = playerItem(value); value.play(item);
        assertNotNull(value.activePlayer()); assertEquals(item.getFile().getAbsolutePath(), value.playingPath());
        ControlledPlayer player = ControlledPlayer.attempts.get(0);
        value.stop(); value.stop(); assertEquals(1, player.stops); assertEquals(1, player.releases);
        assertNull(value.activePlayer()); assertNull(value.playingPath());
    }
    @Test public void releaseFailureDoesNotRetainOrRetryTheOldPlayer() throws Exception {
        Controller value = probe(); value.play(playerItem(value));
        ControlledPlayer player = ControlledPlayer.attempts.get(0); ControlledPlayer.releaseFails = true;
        value.stop(); value.stop(); assertEquals(1, player.releases);
        assertNull(value.activePlayer()); assertNull(value.playingPath());
    }
    @Test public void disposingTheScreenReleasesPlaybackAndItsObserver() throws Exception {
        Controller value = probe(); value.play(playerItem(value)); ControlledPlayer player = ControlledPlayer.attempts.get(0);
        value.dispose(); assertEquals(1, player.releases); assertNull(value.activePlayer());
        assertEquals(0, value.observerCount());
    }
    @Test public void refusedFileDeletionKeepsTheRowAndReportsFailure() throws Exception {
        Controller value = probe(); File original = create(new File(app.getFilesDir(), "call_recordings"), ".m4a", 1000);
        File refused = new File(original.getAbsolutePath()) { @Override public boolean delete() { return false; } };
        RecordedCallItem item = value.addFile(refused); value.delete(item);
        assertTrue(original.isFile()); assertListed(value, original);
        assertEquals("Couldn't delete this recording", ShadowToast.getTextOfLatestToast());
    }
    @Test public void throwingFileDeletionKeepsTheRowAndReportsFailure() throws Exception {
        Controller value = probe(); File original = create(new File(app.getFilesDir(), "call_recordings"), ".m4a", 1000);
        File refused = new File(original.getAbsolutePath()) { @Override public boolean delete() { throw new SecurityException("controlled denial"); } };
        RecordedCallItem item = value.addFile(refused); value.delete(item);
        assertTrue(original.isFile()); assertListed(value, original);
        assertEquals("Couldn't delete this recording", ShadowToast.getTextOfLatestToast());
    }
    @Test public void confirmedFileDeletionRemovesFileAndRowAndStopsPlayback() throws Exception {
        Controller value = probe(); RecordedCallItem item = playerItem(value); value.play(item);
        ControlledPlayer player = ControlledPlayer.attempts.get(0); value.delete(item);
        assertFalse(item.getFile().exists()); assertAbsent(value, item.getFile()); assertEquals(1, player.releases);
        assertNull(value.activePlayer()); assertEquals("Recording deleted", ShadowToast.getTextOfLatestToast());
    }
    @Test public void repeatedFailedPreparationDoesNotAccumulatePlayerOwnership() throws Exception {
        Controller value = probe(); RecordedCallItem item = playerItem(value); ControlledPlayer.failAt = "prepare";
        for (int index = 0; index < 30; index++) value.play(item);
        assertEquals(30, ControlledPlayer.attempts.size());
        for (ControlledPlayer player : ControlledPlayer.attempts) assertEquals(1, player.releases);
        assertNull(value.activePlayer()); assertNull(value.playingPath());
    }
    @Test public void replacementPlaybackReleasesOnlyThePreviousOwner() throws Exception {
        Controller value = probe(); RecordedCallItem first = playerItem(value), next = playerItem(value);
        value.play(first); ControlledPlayer previous = ControlledPlayer.attempts.get(0);
        value.play(next); ControlledPlayer current = ControlledPlayer.attempts.get(1);
        assertEquals(1, previous.releases); assertEquals(0, current.releases);
        assertEquals(next.getFile().getAbsolutePath(), value.playingPath());
    }
    @Test public void staleCompletionDoesNotStopReplacementPlayback() throws Exception {
        Controller value = probe(); RecordedCallItem first = playerItem(value), next = playerItem(value);
        value.play(first); ControlledPlayer previous = ControlledPlayer.attempts.get(0);
        value.play(next); ControlledPlayer current = ControlledPlayer.attempts.get(1);
        assertNotNull(previous.completion); previous.completion.onCompletion(previous.realPlayer);
        assertEquals(0, current.stops); assertEquals(0, current.releases);
        assertEquals(next.getFile().getAbsolutePath(), value.playingPath());
    }
    @Test public void reentrantStopCannotClaimTheSamePlayerTwice() throws Exception {
        Controller value = probe(); value.play(playerItem(value)); ControlledPlayer player = ControlledPlayer.attempts.get(0);
        ControlledPlayer.duringStop = value::stop;
        value.stop(); assertEquals(1, player.stops); assertEquals(1, player.releases);
        assertNull(value.activePlayer()); assertNull(value.playingPath());
    }

    /** XML inflation is a boundary; actual declared roots feed Core's unchanged canonical path strategy. */
    private void installDeclaredFileProviderRoots() throws Exception {
        Class<?> strategyType = Class.forName("androidx.core.content.FileProvider$SimplePathStrategy");
        Constructor<?> constructor = strategyType.getDeclaredConstructor(String.class); constructor.setAccessible(true);
        Object strategy = constructor.newInstance(AUTHORITY);
        Method addRoot = strategyType.getDeclaredMethod("addRoot", String.class, File.class); addRoot.setAccessible(true);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Element root = factory.newDocumentBuilder().parse(new File(System.getProperty("waex.recording.paths"))).getDocumentElement();
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element)) continue;
            Element value = (Element) node;
            File base;
            switch (value.getTagName()) {
                case "files-path": base = app.getFilesDir(); break;
                case "cache-path": base = app.getCacheDir(); break;
                case "external-files-path": base = context.getExternalFilesDir(null); break;
                case "external-path": base = Environment.getExternalStorageDirectory(); break;
                default: throw new AssertionError("Unreviewed FileProvider root type");
            }
            if (base != null) addRoot.invoke(strategy, value.getAttribute("name"), new File(base, value.getAttribute("path")));
        }
        Field cache = FileProvider.class.getDeclaredField("sCache"); cache.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> roots = (Map<String, Object>) cache.get(null);
        roots.put(AUTHORITY, strategy);
    }
}
