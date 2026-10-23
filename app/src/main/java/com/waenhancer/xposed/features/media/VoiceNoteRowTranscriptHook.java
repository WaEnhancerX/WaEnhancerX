package com.waenhancer.xposed.features.media;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.components.WaexBottomSheet;
import com.waenhancer.xposed.utils.ActivityTracker;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XCallback;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Adds a 📝 marker to the timestamp of voice notes in a chat; tapping it opens a sheet with the
 * transcript and a Copy button.
 *
 * <p>Rows are found the way {@code AntiRevokeHook} finds them (the conversation ListView's
 * adapter and its "date" view). The voice note file is located by field <em>types</em> on the
 * message object (message -> media data -> {@link File}), so no obfuscated names are needed.</p>
 */
public final class VoiceNoteRowTranscriptHook extends BaseFeature {
    private static final String TAG = "[WAEX][VoiceRowTranscript]";
    static final String PREF_KEY = "voice_note_transcript_button";

    /** Set on a voice note's timestamp view; other features leave its tap to this one. */
    public static final String FIELD_FILE = "waex_stt_file";
    private static final String FIELD_PREVIOUS_LEFT = "waex_stt_prev_left";
    private static final AtomicBoolean LOOKUP_WARNED = new AtomicBoolean(false);

    /** Message class -> field path to its File; an empty array means "has no file". */
    private static final Map<Class<?>, Field[]> FILE_PATHS = new ConcurrentHashMap<>();
    private static final Field[] NO_PATH = new Field[0];
    /** Path -> time it was last seen on disk; spares a storage call per row while scrolling. */
    private static final Map<String, Long> SEEN_ON_DISK = new ConcurrentHashMap<>();
    private static final long SEEN_ON_DISK_MS = 60_000;

    /** getView methods already hooked; hooks stay, so another list cannot detach the chat's. */
    private final java.util.Set<Method> hookedGetViews =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private WeakReference<ListView> listViewRef = new WeakReference<>(null);
    private boolean readyRefreshPending;
    /** Set when this feature hit an unexpected error; it then stays off until restart. */
    private final AtomicBoolean disabled = new AtomicBoolean(false);

    public VoiceNoteRowTranscriptHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                                      @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Voice Note Transcript Button"; }

    @Override
    public void hook() {
        VoiceTranscriptionBridge.attach(context);
        XposedHelpers.findAndHookMethod(ListView.class, "setAdapter", ListAdapter.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                safe(() -> onSetAdapter((ListView) param.thisObject, (ListAdapter) param.args[0])).run();
            }
        });
    }

    private void onSetAdapter(ListView listView, @Nullable ListAdapter adapter) {
        Activity activity = ActivityTracker.getCurrentActivity();
        if (activity == null || !activity.getClass().getSimpleName().contains("Conversation")) return;
        if (adapter instanceof HeaderViewListAdapter) {
            adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
        }
        if (adapter == null || !isEnabled(PREF_KEY, true)) return;
        listViewRef = new WeakReference<>(listView);
        refreshReadiness();

        Method getView = XposedHelpers.findMethodBestMatch(adapter.getClass(), "getView",
                int.class, View.class, ViewGroup.class);
        if (getView == null || !hookedGetViews.add(getView)) return;
        // Highest priority runs this after-hook last, after AntiRevokeHook reset the date view.
        // Rows without a "date" view (other lists sharing the method) return immediately.
        XposedBridge.hookMethod(getView, new XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!(param.thisObject instanceof ListAdapter) || disabled.get()) return;
                safe(() -> decorate((ListAdapter) param.thisObject, (int) param.args[0],
                        param.getResult())).run();
            }
        });
    }

    /** Readiness needs IPC; check off the UI thread and redraw rows once it turns true. */
    private void refreshReadiness() {
        if (readyRefreshPending) return;
        readyRefreshPending = true;
        boolean before = VoiceTranscriptionBridge.lastKnownReady();
        VoiceTranscriptionBridge.worker().post(safe(() -> {
            Boolean ready = VoiceTranscriptionBridge.ready(context);
            VoiceTranscriptionBridge.main().post(safe(() -> {
                readyRefreshPending = false;
                ListView list = listViewRef.get();
                if (Boolean.TRUE.equals(ready) && !before && list != null) list.invalidateViews();
            }));
        }));
    }

    private void decorate(ListAdapter adapter, int position, Object rendered) {
        if (!(rendered instanceof ViewGroup)) return;
        ViewGroup row = (ViewGroup) rendered;
        TextView date = findDate(row);
        if (date == null) return;
        File voiceNote = null;
        if (isEnabled(PREF_KEY, true) && VoiceTranscriptionBridge.lastKnownReady()) {
            Object item;
            try {
                item = adapter.getItem(position);
            } catch (Throwable t) {
                item = null;
            }
            voiceNote = voiceNoteOf(item);
        }
        if (voiceNote != null) {
            markRow(date, voiceNote);
        } else {
            unmarkRow(date);
        }
    }

    /** Resource id of the row timestamp; looked up once, since getIdentifier is slow. */
    private static volatile int sDateId;

    @Nullable
    private static TextView findDate(ViewGroup row) {
        int id = sDateId;
        if (id == 0) {
            Context rowContext = row.getContext();
            id = rowContext.getResources().getIdentifier("date", "id", rowContext.getPackageName());
            if (id == 0) return null;
            sDateId = id;
        }
        View view = row.findViewById(id);
        return view instanceof TextView ? (TextView) view : null;
    }

    private void markRow(TextView date, File voiceNote) {
        Drawable[] drawables = date.getCompoundDrawables();
        if (!(drawables[0] instanceof GlyphDrawable)) {
            XposedHelpers.setAdditionalInstanceField(date, FIELD_PREVIOUS_LEFT, drawables[0]);
        }
        GlyphDrawable marker = new GlyphDrawable("📝", date.getTextSize());
        date.setCompoundDrawables(marker, drawables[1], drawables[2], drawables[3]);
        XposedHelpers.setAdditionalInstanceField(date, FIELD_FILE, voiceNote.getAbsolutePath());
        // The listener reads the field at tap time, so a recycled row never opens a stale note,
        // and listeners other hooks set on this view are never cleared from here.
        date.setOnClickListener(v -> safe(() -> {
            Object path = XposedHelpers.getAdditionalInstanceField(v, FIELD_FILE);
            if (path instanceof String) showTranscript((String) path);
        }).run());
    }

    private static void unmarkRow(TextView date) {
        if (XposedHelpers.getAdditionalInstanceField(date, FIELD_FILE) == null) return;
        XposedHelpers.removeAdditionalInstanceField(date, FIELD_FILE);
        Drawable[] drawables = date.getCompoundDrawables();
        if (drawables[0] instanceof GlyphDrawable) {
            Object previous = XposedHelpers.getAdditionalInstanceField(date, FIELD_PREVIOUS_LEFT);
            date.setCompoundDrawables(previous instanceof Drawable ? (Drawable) previous : null,
                    drawables[1], drawables[2], drawables[3]);
        }
    }

    // ─── Finding the voice note file of a message ───────────────────────

    @Nullable
    static File voiceNoteOf(@Nullable Object message) {
        if (message == null) return null;
        Field[] path = FILE_PATHS.computeIfAbsent(message.getClass(), VoiceNoteRowTranscriptHook::findFilePath);
        if (path.length == 0) return null;
        try {
            Object value = message;
            for (Field field : path) {
                if (value == null) return null;
                value = field.get(value);
            }
            if (!(value instanceof File)) return null;
            File file = (File) value;
            return isVoiceNote(file) && existsRecently(file) ? file : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** getView runs on the UI thread: check the disk at most once a minute per note. */
    private static boolean existsRecently(File file) {
        String path = file.getPath();
        long now = System.currentTimeMillis();
        Long seen = SEEN_ON_DISK.get(path);
        if (seen != null && now - seen < SEEN_ON_DISK_MS) return true;
        if (!file.isFile()) {
            SEEN_ON_DISK.remove(path);
            return false;
        }
        if (SEEN_ON_DISK.size() > 512) SEEN_ON_DISK.clear();
        SEEN_ON_DISK.put(path, now);
        return true;
    }

    static boolean isVoiceNote(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        File parent = file.getParentFile();
        String parentPath = parent == null ? "" : parent.getPath();
        return (name.endsWith(".opus") || name.endsWith(".ogg"))
                && (name.startsWith("ptt-") || parentPath.contains("Voice Notes"));
    }

    /** Type-based search: a File field on the message, or one level down in its media data. */
    private static Field[] findFilePath(Class<?> messageClass) {
        try {
            for (Field outer : instanceFields(messageClass)) {
                if (outer.getType() == File.class) return accessible(outer);
                if (!isHostType(outer.getType())) continue;
                for (Field inner : instanceFields(outer.getType())) {
                    if (inner.getType() == File.class) return accessible(outer, inner);
                }
            }
        } catch (Throwable t) {
            if (LOOKUP_WARNED.compareAndSet(false, true)) {
                XposedBridge.log(TAG + " File lookup failed: " + t.getClass().getSimpleName());
            }
        }
        return NO_PATH;
    }

    private static boolean isHostType(Class<?> type) {
        if (type.isPrimitive() || type.isArray() || type.isInterface()) return false;
        String name = type.getName();
        return !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("android.")
                || name.startsWith("androidx.") || name.startsWith("kotlin."));
    }

    private static List<Field> instanceFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) fields.add(field);
            }
        }
        return fields;
    }

    private static Field[] accessible(Field... fields) {
        for (Field field : fields) field.setAccessible(true);
        return fields;
    }

    // ─── Transcript sheet ───────────────────────────────────────────────

    private void showTranscript(String path) {
        Activity activity = ActivityTracker.getCurrentActivity();
        if (activity == null || activity.isFinishing()) return;
        File note = new File(path);
        String title = UiText.get(R.string.waex_ui_voice_note_transcript, "Voice note transcript");
        String cached = VoiceTranscriptionBridge.cached(note);
        VoiceTranscriptionBridge.log("📝 tapped (" + Math.max(1, note.length() / 1024) + " KB"
                + (cached != null ? ", cached)" : ")"));

        TextView body = new TextView(activity);
        body.setTextIsSelectable(true);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        body.setTextColor(themeTextColor(activity));
        body.setText(cached != null ? displayText(cached, note)
                : UiText.get(R.string.waex_ui_transcribing_voice_note, "Transcribing voice note…"));

        new WaexBottomSheet(activity)
                .asBottomSheet()
                .setTitle(title)
                .setView(body)
                .setPositiveButton(UiText.get(R.string.waex_ui_copy, "Copy"), (dialog, which) -> {
                    String text = VoiceTranscriptionBridge.cached(note);
                    if (text == null || text.isEmpty()) return;
                    ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                    if (clipboard == null) return;
                    clipboard.setPrimaryClip(ClipData.newPlainText(title, text));
                    Toast.makeText(activity, UiText.get(R.string.waex_ui_transcript_copied,
                            "Transcript copied"), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(UiText.get(R.string.waex_ui_close, "Close"),
                        (dialog, which) -> dialog.dismiss())
                .show();

        if (cached != null) return;
        VoiceTranscriptionBridge.interactive().post(safe(() -> {
            VoiceTranscriptionBridge.Result result = VoiceTranscriptionBridge.transcribe(context, note);
            String shown = result.text != null ? displayText(result.text, note) : null;
            VoiceTranscriptionBridge.main().post(safe(() -> body.setText(shown != null
                    ? shown
                    : UiText.get(R.string.waex_ui_transcription_failed, "Transcription failed")
                            + "\n" + result.error)));
        }));
    }

    /** Call off the UI thread for empty text: describing the audio reads its metadata. */
    private Runnable safe(Runnable work) {
        return VoiceTranscriptionBridge.guard(TAG, disabled, work);
    }

    private static String displayText(String text, File note) {
        return text.isEmpty()
                ? UiText.get(R.string.waex_ui_no_speech_detected, "No speech detected.")
                        + " " + VoiceTranscriptionBridge.describeAudio(note)
                : text;
    }

    private static int themeTextColor(Activity activity) {
        TypedArray attrs = activity.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            return attrs.getColor(0, 0xFF808080);
        } finally {
            attrs.recycle();
        }
    }
}
