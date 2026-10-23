package com.waenhancer.xposed.features.media;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import com.waenhancer.core.transcription.TranscriptionClient;
import com.waenhancer.xposed.core.BaseFeature;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Transcribes WhatsApp voice notes, sent and received, through the transcription provider
 * configured in WAEX.
 *
 * <p>WhatsApp writes every voice note as an Ogg/Opus file under "WhatsApp Voice Notes". Watching
 * that folder needs no obfuscated host classes, so it keeps working across WhatsApp updates.
 * The audio goes to the WAEX provider, which holds the API key; the result appears as a
 * notification with Copy, or with a Transcribe button when automatic mode is off.</p>
 */
public final class VoiceNoteTranscriptionHook extends BaseFeature {
    private static final String TAG = "[WAEX][VoiceTranscription]";

    static final String PREF_ENABLED = "voice_transcription";
    static final String PREF_AUTO = "voice_transcription_auto";

    private static final String ACTION_TRANSCRIBE = "com.waenhancer.action.TRANSCRIBE_VOICE_NOTE";
    private static final String ACTION_COPY = "com.waenhancer.action.COPY_VOICE_TRANSCRIPT";
    private static final String EXTRA_PATH = "waex_voice_note_path";
    private static final String CHANNEL_ID = "waex_voice_transcripts";

    /** Let WhatsApp finish writing (and a cancelled recording disappear) before reading. */
    private static final long SETTLE_DELAY_MS = 1_500;
    /** The poll cannot see CLOSE_WRITE, so it waits longer for a paused recording. */
    private static final long POLL_SETTLE_MS = 15_000;
    private static final long FIRST_SCAN_DELAY_MS = 5_000;
    private static final long POLL_INTERVAL_MS = 60_000;
    private static final long DISCOVERY_RETRY_MS = 10 * 60_000;
    private static final int MAX_DISCOVERY_ATTEMPTS = 6;
    private static final int WATCHED_FOLDERS = 3;
    private static final int REMEMBERED_NOTES = 256;

    /** FileObserver stops when garbage-collected, so the instances are kept here. */
    private final List<FileObserver> observers = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> watchedDirs = Collections.synchronizedSet(new HashSet<>());
    /** Path -> size when handled; a later, different size means the note was still growing. */
    private final Map<String, Long> seenNotes = boundedMap();
    private final Map<Integer, String> transcripts = boundedMap();
    private final long startedAt = System.currentTimeMillis();

    private Handler worker;
    /** Set when this feature hit an unexpected error; it then stays off until restart. */
    private final java.util.concurrent.atomic.AtomicBoolean disabled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    @Nullable private File voiceRoot;
    private int discoveryAttempts;

    public VoiceNoteTranscriptionHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                      @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Voice Note Transcription"; }

    @Override
    public void hook() {
        // Nothing here touches the disk or IPC: startup must stay fast.
        VoiceTranscriptionBridge.attach(context);
        worker = VoiceTranscriptionBridge.worker();
        worker.post(safe(this::start));
    }

    private void start() {
        voiceRoot = findVoiceNotesRoot();
        if (voiceRoot == null) {
            if (++discoveryAttempts < MAX_DISCOVERY_ATTEMPTS) {
                worker.postDelayed(safe(this::start), DISCOVERY_RETRY_MS);
            } else {
                XposedBridge.log(TAG + " Voice notes folder not found; transcription inactive");
                VoiceTranscriptionBridge.log("Voice notes folder not found; automatic detection inactive");
            }
            return;
        }
        VoiceTranscriptionBridge.log("Watching voice notes in " + voiceRoot.getPath());
        registerActionReceiver();
        watchRoot(voiceRoot);
        // Catches notes WhatsApp downloaded while this process was starting.
        worker.postDelayed(safe(this::poll), FIRST_SCAN_DELAY_MS);
    }

    // ─── Finding and watching the voice notes folder ────────────────────

    @Nullable
    @SuppressWarnings("deprecation") // Both directory APIs are still the only way to reach this folder.
    private File findVoiceNotesRoot() {
        List<File> bases = new ArrayList<>();
        try {
            File[] mediaDirs = context.getExternalMediaDirs();
            if (mediaDirs != null) bases.addAll(Arrays.asList(mediaDirs));
        } catch (Throwable ignored) {}
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Pre-Android 11 layout. On newer versions this is a stale copy left by migration.
            try {
                bases.add(Environment.getExternalStorageDirectory());
            } catch (Throwable ignored) {}
        }
        for (File base : bases) {
            if (base == null) continue;
            File[] apps = base.listFiles(File::isDirectory);
            if (apps == null) continue;
            for (File app : apps) {
                // e.g. WhatsApp/Media/WhatsApp Voice Notes, WhatsApp Business/Media/WhatsApp Business Voice Notes
                File[] candidates = new File(app, "Media").listFiles(
                        f -> f.isDirectory() && f.getName().endsWith("Voice Notes"));
                if (candidates != null && candidates.length > 0) return candidates[0];
            }
        }
        return null;
    }

    private void watchRoot(File root) {
        // Week folders (e.g. "202642") appear as time passes; watch the root for new ones.
        startObserver(root, true);
        for (File folder : recentFolders(root, WATCHED_FOLDERS)) startObserver(folder, false);
    }

    /** Most recently changed subfolders first; names are not relied on. */
    private static List<File> recentFolders(File root, int limit) {
        File[] folders = root.listFiles(File::isDirectory);
        if (folders == null) return Collections.emptyList();
        Arrays.sort(folders, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return Arrays.asList(folders).subList(0, Math.min(limit, folders.length));
    }

    @SuppressWarnings("deprecation") // FileObserver(File, int) needs API 29; minSdk is 24.
    private void startObserver(File dir, boolean isRoot) {
        if (!watchedDirs.add(dir.getAbsolutePath())) return;
        int mask = FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO
                | (isRoot ? FileObserver.CREATE : 0);
        FileObserver observer = new FileObserver(dir.getAbsolutePath(), mask) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                if (path == null) return;
                File target = new File(dir, path);
                int type = event & FileObserver.ALL_EVENTS;
                if (isRoot && (type == FileObserver.CREATE || type == FileObserver.MOVED_TO)) {
                    worker.post(safe(() -> {
                        if (!target.isDirectory()) return;
                        startObserver(target, false);
                        // A note can land in a new week folder before it is watched.
                        worker.postDelayed(safe(() -> scanFolder(target, SETTLE_DELAY_MS)), SETTLE_DELAY_MS);
                    }));
                }
                if ((type == FileObserver.CLOSE_WRITE || type == FileObserver.MOVED_TO)
                        && isVoiceNoteName(path)) {
                    worker.postDelayed(safe(() -> onVoiceNote(target)), SETTLE_DELAY_MS);
                }
            }
        };
        observer.startWatching();
        observers.add(observer);
    }

    /** Safety net for storage stacks that do not deliver inotify events. */
    private void poll() {
        try {
            File root = voiceRoot;
            if (root != null && isEnabled(PREF_ENABLED, true)) {
                for (File folder : recentFolders(root, 2)) scanFolder(folder, POLL_SETTLE_MS);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Poll failed: " + t.getClass().getSimpleName());
        } finally {
            worker.postDelayed(safe(this::poll), POLL_INTERVAL_MS);
        }
    }

    /** Handles notes written since this process started and untouched for {@code settleMs}. */
    private void scanFolder(File folder, long settleMs) {
        File[] notes = folder.listFiles(f -> isVoiceNoteName(f.getName()));
        if (notes == null) return;
        long settledBefore = System.currentTimeMillis() - settleMs;
        for (File note : notes) {
            long modified = note.lastModified();
            if (modified >= startedAt && modified <= settledBefore) onVoiceNote(note);
        }
    }

    static boolean isVoiceNoteName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return !lower.startsWith(".") && (lower.endsWith(".opus") || lower.endsWith(".ogg"));
    }

    // ─── Handling a new voice note ──────────────────────────────────────

    private void onVoiceNote(File note) {
        if (!isEnabled(PREF_ENABLED, true)) return;
        if (!note.isFile() || note.length() == 0) return; // Cancelled recordings get deleted.
        String key = note.getAbsolutePath();
        long size = note.length();
        synchronized (seenNotes) {
            Long handledSize = seenNotes.get(key);
            if (handledSize != null && handledSize == size) return;
        }
        Boolean configured = VoiceTranscriptionBridge.ready(context);
        VoiceTranscriptionBridge.log("New voice note (" + Math.max(1, size / 1024) + " KB), transcription "
                + (configured == null ? "unreachable, retrying later" : configured ? "ready" : "not set up"));
        if (configured == null) return; // WAEX not reachable yet: leave it for the next scan.
        synchronized (seenNotes) {
            seenNotes.put(key, size);
        }
        if (!configured) return; // No provider configured: stay silent for this note.
        if (isEnabled(PREF_AUTO, false)) {
            transcribe(note);
        } else {
            notifyNewNote(note);
        }
    }

    private void transcribe(File note) {
        int id = notificationId(note);
        if (note.length() > TranscriptionClient.MAX_AUDIO_BYTES) {
            notifyFailure(note, UiText.get(R.string.waex_ui_voice_note_is_too_long_to_transcribe,
                    "Voice note is too long to transcribe."), false);
            return;
        }
        notifyProgress(note);
        VoiceTranscriptionBridge.Result result = VoiceTranscriptionBridge.transcribe(context, note);
        String text = result.text;
        if (text == null) {
            notifyFailure(note, result.error == null ? "Unknown error" : result.error, true);
            return;
        }
        if (text.isEmpty()) {
            text = UiText.get(R.string.waex_ui_no_speech_detected, "No speech detected.")
                    + " " + VoiceTranscriptionBridge.describeAudio(note);
        }
        synchronized (transcripts) {
            transcripts.put(id, text);
        }
        notifyTranscript(note, text);
    }

    // ─── Notifications and their buttons ────────────────────────────────

    private void notifyNewNote(File note) {
        Notification.Builder builder = baseBuilder(note)
                .setContentTitle(UiText.get(R.string.waex_ui_new_voice_note, "New voice note")
                        + " · " + timeOf(note))
                .setContentText(UiText.get(R.string.waex_ui_tap_transcribe_to_turn_it_into_text,
                        "Tap Transcribe to turn it into text."))
                .addAction(action(UiText.get(R.string.waex_ui_transcribe, "Transcribe"),
                        ACTION_TRANSCRIBE, note));
        post(note, builder);
    }

    private void notifyProgress(File note) {
        Notification.Builder builder = baseBuilder(note)
                .setContentTitle(UiText.get(R.string.waex_ui_transcribing_voice_note,
                        "Transcribing voice note…"))
                .setContentText(timeOf(note))
                .setProgress(0, 0, true)
                .setOngoing(true);
        post(note, builder);
    }

    private void notifyTranscript(File note, String text) {
        Notification.Builder builder = baseBuilder(note)
                .setContentTitle(UiText.get(R.string.waex_ui_voice_note_transcript,
                        "Voice note transcript") + " · " + timeOf(note))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .addAction(action(UiText.get(R.string.waex_ui_copy, "Copy"), ACTION_COPY, note));
        post(note, builder);
    }

    private void notifyFailure(File note, String message, boolean retry) {
        Notification.Builder builder = baseBuilder(note)
                .setContentTitle(UiText.get(R.string.waex_ui_transcription_failed,
                        "Transcription failed") + " · " + timeOf(note))
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message));
        if (retry) {
            builder.addAction(action(UiText.get(R.string.waex_ui_retry, "Retry"),
                    ACTION_TRANSCRIBE, note));
        }
        post(note, builder);
    }

    @SuppressWarnings("deprecation") // Builder(Context) is the only option below API 26.
    private Notification.Builder baseBuilder(File note) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, ensureChannel())
                : new Notification.Builder(context);
        return builder.setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(true)
                .setWhen(note.lastModified());
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private String ensureChannel() {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    UiText.get(R.string.waex_ui_waex_voice_transcripts, "WAEX voice transcripts"),
                    NotificationManager.IMPORTANCE_LOW));
        }
        return CHANNEL_ID;
    }

    private void post(File note, Notification.Builder builder) {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(notificationId(note), builder.build());
        } catch (Throwable t) {
            // Missing notification permission is the user's choice; nothing else to do.
            XposedBridge.log(TAG + " Notification failed: " + t.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("deprecation") // Notification.Action.Builder(int, ...) for API 24-25.
    private Notification.Action action(String title, String actionName, File note) {
        Intent intent = new Intent(actionName)
                .setPackage(context.getPackageName())
                .putExtra(EXTRA_PATH, note.getAbsolutePath());
        int requestCode = notificationId(note) * 31 + actionName.hashCode();
        PendingIntent pending = PendingIntent.getBroadcast(context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Action.Builder(0, title, pending).build();
    }

    // Before API 33 a runtime receiver cannot be registered as not exported, and the androidx
    // permission emulation depends on a permission declared in WhatsApp's manifest. Callers are
    // constrained by trustedNote() instead, and Copy never trusts text from the intent.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerActionReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_TRANSCRIBE);
        filter.addAction(ACTION_COPY);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                safe(() -> {
                    File note = trustedNote(intent.getStringExtra(EXTRA_PATH));
                    if (note == null) return;
                    if (ACTION_TRANSCRIBE.equals(intent.getAction())) {
                        VoiceTranscriptionBridge.interactive().post(safe(() -> transcribe(note)));
                    } else if (ACTION_COPY.equals(intent.getAction())) {
                        copyTranscript(note);
                    }
                }).run();
            }
        };
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, null, worker, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, null, worker);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Button receiver unavailable: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Before API 33 a runtime receiver is reachable by other apps, so only voice notes inside
     * WhatsApp's own folder are accepted, and Copy uses text kept in memory, never intent text.
     */
    @Nullable
    private File trustedNote(@Nullable String path) {
        File root = voiceRoot;
        if (path == null || root == null || !isVoiceNoteName(new File(path).getName())) return null;
        try {
            File note = new File(path).getCanonicalFile();
            String rootPath = root.getCanonicalPath() + File.separator;
            return note.getPath().startsWith(rootPath) && note.isFile() ? note : null;
        } catch (IOException e) {
            return null;
        }
    }

    private void copyTranscript(File note) {
        String text;
        synchronized (transcripts) {
            text = transcripts.get(notificationId(note));
        }
        if (text == null) return;
        ClipboardManager clipboard = context.getSystemService(ClipboardManager.class);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("Voice note transcript", text));
        new Handler(Looper.getMainLooper()).post(safe(() -> Toast.makeText(context,
                UiText.get(R.string.waex_ui_transcript_copied, "Transcript copied"),
                Toast.LENGTH_SHORT).show()));
    }

    private Runnable safe(Runnable work) {
        return VoiceTranscriptionBridge.guard(TAG, disabled, work);
    }

    private static int notificationId(File note) {
        return 0x5A1E0000 ^ note.getAbsolutePath().hashCode();
    }

    private static String timeOf(File note) {
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(note.lastModified()));
    }

    private static <K, V> Map<K, V> boundedMap() {
        return new LinkedHashMap<K, V>(64, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > REMEMBERED_NOTES;
            }
        };
    }
}
