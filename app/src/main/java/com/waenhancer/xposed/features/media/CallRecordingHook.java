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
import com.waenhancer.utils.ContactNameResolver;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

import java.io.FileDescriptor;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Tries the selected platform audio sources while an identified WhatsApp call is active. */
public final class CallRecordingHook extends BaseFeature {
    private static final String TAG = "[WAEX][CallRecording]";
    private static final String ENABLED = "call_recording_enabled";
    private static final Uri BRIDGE_URI = Uri.parse("content://" + HookProvider.AUTHORITY);

    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final ScheduledThreadPoolExecutor worker = createWorker();
    private final Object installationLock = new Object();
    private boolean installed;
    // Host callbacks never wait for MediaRecorder.prepare/start/stop or storage IPC.
    private final Object sessionLock = new Object();
    private Session session;
    private boolean stopQueued;
    private final SharedPreferences.OnSharedPreferenceChangeListener enabledListener = (preferences, key) -> {
        if (key != null && !ENABLED.equals(key)) return;
        synchronized (sessionLock) {
            if (!isEnabled(ENABLED, false) && session != null) finishSession();
        }
    };
    private volatile ScheduledFuture<?> pendingStart;
    private volatile MediaRecorder recorder;
    private volatile ParcelFileDescriptor destination;
    private volatile String outputPath;
    private volatile String outputName;
    private volatile String outputCallId;

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
        synchronized (installationLock) {
            if (installed) return;
            installHooks();
            installed = true;
        }
    }

    private void installHooks() {
        Class<?> callbackClass = resolveCallbackClass();
        if (callbackClass == null) {
            throw new IllegalStateException("VoiceServiceEventCallback not found");
        }
        final CallRecordingEventContract contract;
        try {
            contract = CallRecordingEventContract.resolve(callbackClass);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            XposedBridge.log(TAG + " Unsupported call recording event contract: " + failure);
            throw new IllegalStateException("Unsupported call recording event contract", failure);
        }
        AtomicBoolean ready = new AtomicBoolean(false);
        List<XC_MethodHook.Unhook> registered = new ArrayList<>(3);
        try {
            registered.add(XposedBridge.hookMethod(contract.stateChanged, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!ready.get() || param.hasThrowable()) return;
                    try {
                        // The first argument is the OLD state; the new state belongs to CallInfo.
                        applySnapshot(param.thisObject, contract.read(param.args[1]));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        XposedBridge.log(TAG + " state callback failed: " + failure.getClass().getSimpleName());
                    }
                }
            }));
            registered.add(XposedBridge.hookMethod(contract.soundPortCreated,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!ready.get() || param.hasThrowable()) return;
                            try {
                                applySnapshot(param.thisObject, contract.current(param.thisObject));
                            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                                XposedBridge.log(TAG + " audio callback failed: " + failure.getClass().getSimpleName());
                            }
                        }
                    }));
            registered.add(XposedBridge.hookMethod(contract.callEnding, new XC_MethodHook() {
                private final String snapshotKey = "waex.recording.callEnding.snapshot";

                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!ready.get()) return;
                    try {
                        // The host still exposes the ending call here; it may be absent after return.
                        param.setObjectExtra(snapshotKey, contract.current(param.thisObject));
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        XposedBridge.log(TAG + " ending snapshot failed: " + failure.getClass().getSimpleName());
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!ready.get() || param.hasThrowable()) return;
                    Object value = param.getObjectExtra(snapshotKey);
                    if (!(value instanceof CallRecordingEventContract.Snapshot)) return;
                    CallRecordingEventContract.Snapshot ended = (CallRecordingEventContract.Snapshot) value;
                    synchronized (sessionLock) {
                        if (session != null && session.matches(param.thisObject, ended.callId)) finishSession();
                    }
                }
            }));
            prefs.registerOnSharedPreferenceChangeListener(enabledListener);
            ready.set(true);
        } catch (RuntimeException | LinkageError failure) {
            // A failed attempt never becomes active, even if removing a callback fails.
            for (int index = registered.size() - 1; index >= 0; index--) {
                try {
                    registered.get(index).unhook();
                } catch (RuntimeException | LinkageError cleanupFailure) {
                    XposedBridge.log(TAG + " unhook failed: " + cleanupFailure.getClass().getSimpleName());
                }
            }
            try {
                prefs.unregisterOnSharedPreferenceChangeListener(enabledListener);
            } catch (RuntimeException | LinkageError cleanupFailure) {
                XposedBridge.log(TAG + " listener cleanup failed: " + cleanupFailure.getClass().getSimpleName());
            }
            throw new IllegalStateException("Call recording hook installation failed", failure);
        }
    }

    private static ScheduledThreadPoolExecutor createWorker() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "WAEX-CallRecorder");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
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

    private void applySnapshot(Object callback, CallRecordingEventContract.Snapshot snapshot) {
        if (snapshot == null) return;
        synchronized (sessionLock) {
            if (snapshot.ended) {
                if (session != null && session.matches(callback, snapshot.callId)) finishSession();
                return;
            }
            if (!snapshot.active) return;
            if (!isEnabled(ENABLED, false)) {
                if (session != null && session.matches(callback, snapshot.callId)) finishSession();
                return;
            }
            if (session != null && !session.matches(callback, snapshot.callId)) finishSession();
            boolean newSession = session == null;
            if (newSession) session = new Session(callback, snapshot.callId, snapshot.peerJid,
                    snapshot.direction, System.currentTimeMillis());
            if ((!newSession && recording.get()) || pendingStart != null) return;
            Session expected = session;
            pendingStart = worker.schedule(() -> {
                synchronized (sessionLock) {
                    if (session != expected) return;
                    pendingStart = null;
                }
                if (!canRecord(expected)) return;
                startRecorder(expected);
                if (!canRecord(expected)) stopRecorder("call no longer active");
            }, 2, TimeUnit.SECONDS);
        }
    }

    /** Caller holds sessionLock; stopping the recorder happens only on the worker. */
    private void finishSession() {
        session = null;
        if (pendingStart != null) pendingStart.cancel(false);
        pendingStart = null;
        if (recording.get() && !stopQueued) {
            stopQueued = true;
            worker.execute(() -> {
                try {
                    stopRecorder("call ended");
                } finally {
                    synchronized (sessionLock) { stopQueued = false; }
                }
            });
        }
    }

    private boolean canRecord(Session expected) {
        synchronized (sessionLock) {
            return session == expected && expected.owner.get() != null && isEnabled(ENABLED, false);
        }
    }

    private synchronized void startRecorder(Session expected) {
        if (!canRecord(expected)) return;
        if (!recording.compareAndSet(false, true)) return;
        try {
            RecordingFormat format = RecordingFormat.from(prefs.getString("call_recording_format", "m4a"));
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            Bundle request = new Bundle();
            outputName = "WhatsApp_Call_" + timestamp + format.extension;
            request.putString("name", outputName);
            outputCallId = expected.callId;
            request.putString("call_id", expected.callId);
            request.putString("contact_jid", expected.peerJid);
            request.putString("contact_name", expected.peerJid == null ? "Unknown contact"
                    : ContactNameResolver.INSTANCE.resolveName(context, expected.peerJid, null, null));
            request.putString("direction", expected.direction);
            request.putString("target_package", context.getPackageName());
            request.putLong("started_at", expected.startedAt);
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
                if (!canRecord(expected)) throw new RecordingCancelledException();
                try {
                    recorder = createRecorder(source, destination.getFileDescriptor(), format, expected);
                    return;
                } catch (RecordingCancelledException cancelled) {
                    throw cancelled;
                } catch (Throwable failure) {
                    lastError = failure;
                    releaseRecorder(false);
                }
            }
            throw new IllegalStateException("No supported audio source", lastError);
        } catch (Throwable failure) {
            if (!(failure instanceof RecordingCancelledException)) {
                XposedBridge.log(TAG + " start failed: " + failure.getClass().getSimpleName());
            }
            recording.set(false);
            closeDestination(true);
        }
    }

    private MediaRecorder createRecorder(int source, FileDescriptor output,
                                         RecordingFormat format, Session expected) throws Exception {
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
            if (!canRecord(expected)) throw new RecordingCancelledException();
            candidate.start();
            return candidate;
        } catch (Throwable error) {
            try { candidate.release(); } catch (Throwable ignored) {}
            throw error;
        }
    }

    private synchronized void stopRecorder(String reason) {
        if (!recording.getAndSet(false)) return;
        MediaRecorder active = recorder;
        recorder = null;
        boolean valid = false;
        try {
            if (active != null) {
                active.stop();
                valid = true;
            }
        } catch (Throwable failure) {
            XposedBridge.log(TAG + " stop failed: " + failure.getClass().getSimpleName());
        } finally {
            try {
                if (active != null) active.release();
            } catch (Throwable failure) {
                XposedBridge.log(TAG + " release failed: " + failure.getClass().getSimpleName());
            } finally {
                closeDestination(!valid);
            }
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
        if (outputCallId != null) {
            try {
                Bundle request = new Bundle();
                request.putString("call_id", outputCallId);
                request.putLong("ended_at", System.currentTimeMillis());
                request.putBoolean("successful", !deleteInvalid);
                context.getContentResolver().call(
                        BRIDGE_URI, "finish_call_recording", null, request);
            } catch (Throwable ignored) {}
        }
        outputPath = null;
        outputName = null;
        outputCallId = null;
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

    private static final class Session {
        final WeakReference<Object> owner;
        final String callId;
        final String peerJid;
        final String direction;
        final long startedAt;

        Session(Object owner, String callId, String peerJid, String direction, long startedAt) {
            this.owner = new WeakReference<>(owner);
            this.callId = callId;
            this.peerJid = peerJid;
            this.direction = direction;
            this.startedAt = startedAt;
        }

        boolean matches(Object callback, String id) {
            return owner.get() == callback && callId.equals(id);
        }
    }

    private static final class RecordingCancelledException extends Exception {}
}
