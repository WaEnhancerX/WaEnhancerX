package com.waenhancer.xposed.features.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.bridge.HookProvider;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.io.FileDescriptor;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Records the audio route of an active WhatsApp call into WAEX-owned storage. */
public final class CallRecordingHook extends BaseFeature {
    private static final String TAG = "[WAEX][CallRecording]";
    private static final String ENABLED = "call_recording_enabled";
    private static final Uri BRIDGE_URI = Uri.parse("content://" + HookProvider.AUTHORITY);

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "WAEX-CallRecorder");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ScheduledFuture<?> pendingStart;
    private volatile MediaRecorder recorder;
    private volatile ParcelFileDescriptor destination;
    private volatile String outputPath;
    private volatile String outputName;

    public CallRecordingHook(@NonNull Context context, @NonNull ClassLoader classLoader,
                             @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "CallRecording";
    }

    @Override
    public void hook() {
        Class<?> callbackClass = resolveCallbackClass();
        if (callbackClass == null) {
            XposedBridge.log(TAG + " VoiceServiceEventCallback not found");
            return;
        }

        Set<?> connectionHooks = XposedBridge.hookAllMethods(callbackClass, "soundPortCreated",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled(ENABLED, false)) return;
                        connected.set(true);
                        scheduleStart();
                    }
                });
        Set<?> endHooks = XposedBridge.hookAllMethods(callbackClass, "fieldstatsReady",
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        finishCall("fieldstatsReady");
                    }
                });

        hookActivityEnd("com.whatsapp.voipcalling.VoipActivityV2");
        hookActivityEnd("com.whatsapp.voipcalling.VoipActivity");
        XposedBridge.log(TAG + " installed, connectHooks=" + connectionHooks.size()
                + ", endHooks=" + endHooks.size());
    }

    private Class<?> resolveCallbackClass() {
        String[] knownNames = {
                "com.whatsapp.voipcalling.VoiceServiceEventCallback",
                "com.whatsapp.calling.service.VoiceServiceEventCallback"
        };
        for (String name : knownNames) {
            Class<?> found = XposedHelpers.findClassIfExists(name, classLoader);
            if (found != null) return found;
        }
        return DexSearchEngine.getInstance().findClassWithCache(context, classLoader,
                "call_recording_callback_class", (bridge, loader) -> {
                    ClassData data = bridge.findClass(FindClass.create().matcher(
                            ClassMatcher.create().className(
                                    "VoiceServiceEventCallback", StringMatchType.EndsWith)))
                            .firstOrNull();
                    return data == null ? null : data.getInstance(loader);
                });
    }

    private void hookActivityEnd(String className) {
        Class<?> activity = XposedHelpers.findClassIfExists(className, classLoader);
        if (activity == null) return;
        XposedBridge.hookAllMethods(activity, "onDestroy", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                finishCall("activity destroyed");
            }
        });
    }

    private synchronized void scheduleStart() {
        if (pendingStart != null) pendingStart.cancel(false);
        pendingStart = worker.schedule(() -> {
            if (connected.get() && isEnabled(ENABLED, false)) startRecorder();
        }, 2, TimeUnit.SECONDS);
    }

    private synchronized void finishCall(String reason) {
        connected.set(false);
        if (pendingStart != null) pendingStart.cancel(false);
        pendingStart = null;
        worker.execute(() -> stopRecorder(reason));
    }

    private synchronized void startRecorder() {
        if (!recording.compareAndSet(false, true)) return;
        try {
            RecordingFormat format = RecordingFormat.from(prefs.getString("call_recording_format", "m4a"));
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            Bundle request = new Bundle();
            outputName = "WhatsApp_Call_" + timestamp + format.extension;
            request.putString("name", outputName);
            Bundle response = context.getContentResolver().call(
                    BRIDGE_URI, "create_call_recording", null, request);
            if (response == null) throw new IllegalStateException("WAEX storage bridge unavailable");
            destination = Build.VERSION.SDK_INT >= 33
                    ? response.getParcelable("descriptor", ParcelFileDescriptor.class)
                    : response.getParcelable("descriptor");
            outputPath = response.getString("path");
            if (destination == null) throw new IllegalStateException("Could not open output file");

            boolean preferCallAudio = prefs.getBoolean("call_recording_use_root", false);
            int[] sources = preferCallAudio
                    ? new int[]{MediaRecorder.AudioSource.VOICE_CALL,
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC}
                    : new int[]{MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.VOICE_CALL};
            Throwable lastError = null;
            for (int source : sources) {
                try {
                    recorder = createRecorder(source, destination.getFileDescriptor(), format);
                    XposedBridge.log(TAG + " started source=" + source + ", path=" + outputPath);
                    return;
                } catch (Throwable failure) {
                    lastError = failure;
                    releaseRecorder(false);
                }
            }
            throw new IllegalStateException("No supported audio source", lastError);
        } catch (Throwable failure) {
            XposedBridge.log(TAG + " start failed: " + failure);
            recording.set(false);
            closeDestination(true);
        }
    }

    private MediaRecorder createRecorder(int source, FileDescriptor output,
                                         RecordingFormat format) throws Exception {
        MediaRecorder candidate = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(context) : new MediaRecorder();
        try {
            candidate.setAudioSource(source);
            candidate.setOutputFormat(format.outputFormat);
            candidate.setAudioEncoder(format.encoder);
            candidate.setAudioSamplingRate(format.sampleRate);
            candidate.setAudioEncodingBitRate(format.bitRate);
            candidate.setOutputFile(output);
            candidate.prepare();
            candidate.start();
            return candidate;
        } catch (Throwable error) {
            try { candidate.release(); } catch (Throwable ignored) {}
            throw error;
        }
    }

    private synchronized void stopRecorder(String reason) {
        if (!recording.getAndSet(false)) return;
        boolean valid = false;
        try {
            MediaRecorder active = recorder;
            recorder = null;
            if (active != null) {
                active.stop();
                valid = true;
                active.release();
            }
        } catch (Throwable failure) {
            XposedBridge.log(TAG + " stop failed: " + failure);
            releaseRecorder(false);
        } finally {
            closeDestination(!valid);
            XposedBridge.log(TAG + " stopped (" + reason + "), saved=" + valid);
        }
    }

    private void releaseRecorder(boolean stopFirst) {
        MediaRecorder active = recorder;
        recorder = null;
        if (active == null) return;
        if (stopFirst) try { active.stop(); } catch (Throwable ignored) {}
        try { active.reset(); } catch (Throwable ignored) {}
        try { active.release(); } catch (Throwable ignored) {}
    }

    private void closeDestination(boolean deleteInvalid) {
        ParcelFileDescriptor active = destination;
        destination = null;
        try { if (active != null) active.close(); } catch (Throwable ignored) {}
        if (deleteInvalid && outputPath != null) {
            try {
                Bundle request = new Bundle();
                request.putString("name", outputName);
                context.getContentResolver().call(
                        BRIDGE_URI, "delete_call_recording", null, request);
            } catch (Throwable ignored) {}
        }
        outputPath = null;
        outputName = null;
    }

    private static final class RecordingFormat {
        final String extension;
        final int outputFormat;
        final int encoder;
        final int sampleRate;
        final int bitRate;

        private RecordingFormat(String extension, int outputFormat, int encoder,
                                int sampleRate, int bitRate) {
            this.extension = extension;
            this.outputFormat = outputFormat;
            this.encoder = encoder;
            this.sampleRate = sampleRate;
            this.bitRate = bitRate;
        }

        static RecordingFormat from(String requested) {
            if ("opus".equals(requested) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return new RecordingFormat(".opus", MediaRecorder.OutputFormat.OGG,
                        MediaRecorder.AudioEncoder.OPUS, 48000, 64000);
            }
            return new RecordingFormat(".m4a", MediaRecorder.OutputFormat.MPEG_4,
                    MediaRecorder.AudioEncoder.AAC, 44100, 96000);
        }
    }
}
