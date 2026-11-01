package com.waenhancer.xposed.features.media;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.media.MediaRecorder;
import android.os.Build;
import android.text.Editable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.utils.ActivityTracker;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Adds a 🎙️ button inside the chat's message field: tap to record, tap again to stop; the
 * speech is transcribed by the WAEX provider and inserted at the cursor instead of being sent
 * as a voice note.
 *
 * <p>Recording uses this feature's own MediaRecorder rather than WhatsApp's recorder, whose
 * controls are obfuscated. The button is a compound drawable, so WhatsApp's layout is not
 * changed; only touches on the drawable itself are intercepted.</p>
 */
public final class VoiceDictationHook extends BaseFeature {
    private static final String TAG = "[WAEX][VoiceDictation]";
    static final String PREF_KEY = "voice_dictation_button";

    private static final String FIELD_ATTACHED = "waex_dictation";
    private static final String FIELD_PRESSED = "waex_dictation_pressed";
    private static final String MIC = "🎙️";
    private static final String STOP = "⏹️";
    private static final String BUSY = "⏳";
    /** About 600 KB at 32 kbit/s: under the provider's size limit. */
    private static final int MAX_RECORDING_MS = 150_000;
    private static final int MIN_RECORDING_MS = 700;

    private enum State { IDLE, RECORDING, TRANSCRIBING }

    private final Set<Class<?>> hookedFieldClasses = Collections.synchronizedSet(new HashSet<>());
    private State state = State.IDLE;
    /** Set when this feature hit an unexpected error; it then stays off until restart. */
    private final java.util.concurrent.atomic.AtomicBoolean disabled =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    @Nullable private MediaRecorder recorder;
    @Nullable private File recording;
    private long recordingStartedAt;
    private WeakReference<EditText> recordingField = new WeakReference<>(null);
    private WeakReference<Activity> recordingActivity = new WeakReference<>(null);

    public VoiceDictationHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                              @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull @Override public String getName() { return "Voice Dictation Button"; }

    @Override
    public void hook() {
        VoiceTranscriptionBridge.attach(context);
        ActivityTracker.addListener(activity -> safe(() -> {
            if (!isConversation(activity) || !isEnabled(PREF_KEY, true)) return;
            // Readiness needs IPC: ask off the UI thread, then attach on it.
            VoiceTranscriptionBridge.worker().post(safe(() -> {
                if (!Boolean.TRUE.equals(VoiceTranscriptionBridge.ready(context))) return;
                VoiceTranscriptionBridge.main().post(safe(() -> attach(activity, 0)));
            }));
        }).run());
        XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject == recordingActivity.get() && state == State.RECORDING) {
                    safe(VoiceDictationHook.this::cancelRecording).run();
                }
            }
        });
    }

    private static boolean isConversation(Activity activity) {
        return activity.getClass().getSimpleName().contains("Conversation");
    }

    // ─── Attaching the button ───────────────────────────────────────────

    private void attach(Activity activity, int attempt) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        EditText field = findMessageField(activity);
        if (field == null) {
            // The footer can be inflated after onResume.
            if (attempt < 3) retryAttach(activity, attempt + 1);
            return;
        }
        Drawable[] drawables = field.getCompoundDrawables();
        if (drawables[2] != null && !(drawables[2] instanceof GlyphDrawable)) return; // Not ours to replace.
        if (XposedHelpers.getAdditionalInstanceField(field, FIELD_ATTACHED) == null) {
            XposedHelpers.setAdditionalInstanceField(field, FIELD_ATTACHED, Boolean.TRUE);
            hookTouches(field.getClass());
        }
        if (state == State.IDLE || recordingField.get() != field) setGlyph(field, MIC);
    }

    private void retryAttach(Activity activity, int attempt) {
        activity.getWindow().getDecorView().postDelayed(safe(() -> attach(activity, attempt)), 400L * attempt);
    }

    @Nullable
    private static EditText findMessageField(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int id = activity.getResources().getIdentifier("entry", "id", activity.getPackageName());
        View byId = id != 0 ? decor.findViewById(id) : null;
        if (byId instanceof EditText && byId.isShown()) return (EditText) byId;
        // Fallback only on the chat screen itself, where the lowest visible field is the composer.
        if (!"Conversation".equals(activity.getClass().getSimpleName())) return null;
        EditText[] best = new EditText[1];
        int[] bestY = {Integer.MIN_VALUE};
        int[] location = new int[2];
        walk(decor, view -> {
            if (!(view instanceof EditText) || !view.isShown()) return;
            view.getLocationOnScreen(location);
            if (location[1] > bestY[0]) {
                bestY[0] = location[1];
                best[0] = (EditText) view;
            }
        });
        return best[0];
    }

    private interface Visitor { void visit(View view); }

    private static void walk(View view, Visitor visitor) {
        visitor.visit(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) walk(group.getChildAt(i), visitor);
        }
    }

    private void setGlyph(TextView field, String glyph) {
        Drawable[] drawables = field.getCompoundDrawables();
        if (drawables[2] != null && !(drawables[2] instanceof GlyphDrawable)) return;
        field.setCompoundDrawables(drawables[0], drawables[1],
                new GlyphDrawable(glyph, field.getTextSize()), drawables[3]);
    }

    /** Hooks the field class's own onTouchEvent once, so overrides in WhatsApp's class see us first. */
    private void hookTouches(Class<?> fieldClass) {
        if (!hookedFieldClasses.add(fieldClass)) return;
        try {
            Method onTouchEvent = XposedHelpers.findMethodBestMatch(fieldClass, "onTouchEvent", MotionEvent.class);
            XposedBridge.hookMethod(onTouchEvent, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof EditText) || disabled.get()) return;
                    EditText field = (EditText) param.thisObject;
                    if (XposedHelpers.getAdditionalInstanceField(field, FIELD_ATTACHED) == null) return;
                    boolean[] consumed = new boolean[1];
                    safe(() -> consumed[0] = onFieldTouch(field, (MotionEvent) param.args[0])).run();
                    if (consumed[0]) param.setResult(true);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Touch hook unavailable: " + t.getClass().getSimpleName());
        }
    }

    /** True when the touch belongs to the 🎙️ button and must not reach the text field. */
    private boolean onFieldTouch(EditText field, MotionEvent event) {
        Drawable button = field.getCompoundDrawables()[2];
        if (!(button instanceof GlyphDrawable)) return false;
        boolean pressed = XposedHelpers.getAdditionalInstanceField(field, FIELD_PRESSED) != null;
        float x = event.getX();
        boolean inside = x >= field.getWidth() - field.getTotalPaddingRight()
                && x <= field.getWidth() - field.getPaddingRight() + 8;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!inside) return false;
                XposedHelpers.setAdditionalInstanceField(field, FIELD_PRESSED, Boolean.TRUE);
                return true;
            case MotionEvent.ACTION_UP:
                if (!pressed) return false;
                XposedHelpers.removeAdditionalInstanceField(field, FIELD_PRESSED);
                if (inside) onButton(field);
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (!pressed) return false;
                XposedHelpers.removeAdditionalInstanceField(field, FIELD_PRESSED);
                return true;
            default:
                return pressed;
        }
    }

    // ─── Recording and transcribing ─────────────────────────────────────

    private void onButton(EditText field) {
        switch (state) {
            case IDLE:
                startRecording(field);
                break;
            case RECORDING:
                stopAndTranscribe();
                break;
            case TRANSCRIBING:
                break;
        }
    }

    @SuppressWarnings("deprecation") // MediaRecorder() is the only constructor below API 31.
    private void startRecording(EditText field) {
        Activity activity = ActivityTracker.getCurrentActivity();
        if (activity == null) return;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast(UiText.get(R.string.waex_ui_whatsapp_needs_microphone_access_to_di,
                    "WhatsApp needs microphone access to dictate."));
            return;
        }
        // AAC/MP4 at 44.1 kHz: Opus at 16 kHz recorded silence on some devices.
        File file = new File(context.getCacheDir(), "waex_dictation.m4a");
        MediaRecorder newRecorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(context) : new MediaRecorder();
        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            newRecorder.setAudioChannels(1);
            newRecorder.setAudioSamplingRate(44_100);
            newRecorder.setAudioEncodingBitRate(32_000);
            newRecorder.setMaxDuration(MAX_RECORDING_MS);
            newRecorder.setOutputFile(file.getAbsolutePath());
            newRecorder.setOnInfoListener((mr, what, extra) -> {
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    safe(this::stopAndTranscribe).run();
                }
            });
            newRecorder.prepare();
            newRecorder.start();
        } catch (IOException | RuntimeException e) {
            VoiceTranscriptionBridge.log("Dictation could not start recording: " + e.getClass().getSimpleName());
            newRecorder.release();
            toast(UiText.get(R.string.waex_ui_could_not_start_recording, "Could not start recording."));
            return;
        }
        recorder = newRecorder;
        recording = file;
        recordingStartedAt = System.currentTimeMillis();
        recordingField = new WeakReference<>(field);
        recordingActivity = new WeakReference<>(activity);
        state = State.RECORDING;
        setGlyph(field, STOP);
        toast(UiText.get(R.string.waex_ui_recording_tap_to_stop, "Recording… tap ⏹️ to stop."));
    }

    /** Returns true when a usable recording was finished. */
    private boolean finishRecorder() {
        MediaRecorder active = recorder;
        recorder = null;
        if (active == null) return false;
        boolean ok = System.currentTimeMillis() - recordingStartedAt >= MIN_RECORDING_MS;
        try {
            active.stop(); // Throws when nothing was captured.
        } catch (RuntimeException e) {
            ok = false;
        } finally {
            active.release();
        }
        return ok;
    }

    private void cancelRecording() {
        finishRecorder();
        deleteRecording();
        state = State.IDLE;
        EditText field = recordingField.get();
        if (field != null) setGlyph(field, MIC);
    }

    private void stopAndTranscribe() {
        if (state != State.RECORDING) return;
        EditText field = recordingField.get();
        File file = recording;
        if (!finishRecorder() || file == null) {
            deleteRecording();
            state = State.IDLE;
            if (field != null) setGlyph(field, MIC);
            toast(UiText.get(R.string.waex_ui_recording_too_short, "Recording too short."));
            return;
        }
        recording = null; // Owned by the transcription task from here on.
        state = State.TRANSCRIBING;
        if (field != null) setGlyph(field, BUSY);
        VoiceTranscriptionBridge.interactive().post(safeOrReset(() -> {
            VoiceTranscriptionBridge.Result result;
            String audioInfo = VoiceTranscriptionBridge.describeAudio(file);
            VoiceTranscriptionBridge.log("Dictation recorded " + audioInfo);
            try {
                result = VoiceTranscriptionBridge.transcribeBytes(context,
                        VoiceTranscriptionBridge.readFile(file), file.getName());
            } catch (IOException e) {
                result = new VoiceTranscriptionBridge.Result(null, "Could not read the recording");
            }
            if (!file.delete()) file.deleteOnExit();
            VoiceTranscriptionBridge.Result done = result;
            VoiceTranscriptionBridge.main().post(safeOrReset(() -> {
                state = State.IDLE;
                EditText target = recordingField.get();
                if (target != null) setGlyph(target, MIC);
                if (done.text == null) {
                    toast(UiText.get(R.string.waex_ui_transcription_failed, "Transcription failed")
                            + ": " + done.error);
                } else if (done.text.isEmpty()) {
                    toast(UiText.get(R.string.waex_ui_no_speech_detected, "No speech detected.")
                            + " " + audioInfo);
                } else if (target != null) {
                    insertAtCursor(target, done.text);
                }
            }));
        }));
    }

    static void insertAtCursor(EditText field, String text) {
        Editable editable = field.getText();
        if (editable == null) return;
        int start = Math.max(0, Math.min(field.getSelectionStart(), editable.length()));
        int end = Math.max(start, Math.min(field.getSelectionEnd(), editable.length()));
        String insert = text;
        if (start > 0 && !Character.isWhitespace(editable.charAt(start - 1))) insert = " " + insert;
        editable.replace(start, end, insert);
        field.setSelection(Math.min(start + insert.length(), editable.length()));
    }

    private void deleteRecording() {
        File file = recording;
        recording = null;
        if (file != null && file.exists() && !file.delete()) file.deleteOnExit();
    }

    private void toast(String message) {
        VoiceTranscriptionBridge.main().post(safe(() ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()));
    }

    private Runnable safe(Runnable work) {
        return VoiceTranscriptionBridge.guard(TAG, disabled, work);
    }

    /** Like {@link #safe}, but a failed transcription also returns the button to 🎙️. */
    private Runnable safeOrReset(Runnable work) {
        return () -> {
            try {
                work.run();
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Transcription step failed: " + VoiceTranscriptionBridge.describe(t));
                VoiceTranscriptionBridge.main().post(safe(() -> {
                    state = State.IDLE;
                    EditText target = recordingField.get();
                    if (target != null) setGlyph(target, MIC);
                }));
            }
        };
    }
}
