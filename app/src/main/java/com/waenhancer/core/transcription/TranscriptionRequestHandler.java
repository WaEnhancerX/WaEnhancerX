package com.waenhancer.core.transcription;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.config.PreferenceStores;
import com.waenhancer.core.transcription.local.LocalTranscriber;

/**
 * Runs in the module process for the {@code transcribe_audio} provider call. API keys live in
 * the private store, so the hooked WhatsApp process sends audio here instead of calling the
 * transcription service itself.
 */
public final class TranscriptionRequestHandler {

    public static final String METHOD = "transcribe_audio";
    public static final String METHOD_READY = "transcription_ready";
    /** Lets the WhatsApp side add metadata lines to the TranscriptionLog. */
    public static final String METHOD_LOG = "transcription_log";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_AUDIO = "audio";
    public static final String EXTRA_FILE_NAME = "file_name";
    public static final String RESULT_TEXT = "text";
    public static final String RESULT_ERROR = "error";
    public static final String RESULT_READY = "ready";

    /**
     * At most two requests at once: voice notes arrive in bursts and servers rate-limit, but a
     * request the user is waiting on must not queue behind a background one.
     */
    private static final java.util.concurrent.Semaphore SLOTS = new java.util.concurrent.Semaphore(2);

    private TranscriptionRequestHandler() {}

    /** Answers {@link #METHOD_READY}: enabled, with the credentials its provider needs. */
    @NonNull
    public static Bundle ready(@NonNull Context context) {
        TranscriptionClient.Config config = configFor(context);
        boolean configured = TranscriptionClient.PROVIDER_LOCAL.equals(config.provider)
                ? LocalTranscriber.usableModel(context, localModel(context)) != null
                : !config.apiKey.isEmpty()
                        && (!TranscriptionClient.PROVIDER_CUSTOM.equals(config.provider) || !config.url.isEmpty());
        boolean enabled = readBoolean(context, "voice_transcription", true);
        boolean ready = enabled && configured;
        if (!ready) {
            TranscriptionLog.add(context, "app", "Not ready: " + (!enabled ? "transcription is off"
                    : "provider " + config.provider + " is not set up" + localStatus(context, config)));
        }
        Bundle result = new Bundle();
        result.putBoolean(RESULT_READY, ready);
        return result;
    }

    @NonNull
    public static Bundle handle(@NonNull Context context, @NonNull Bundle extras) {
        Bundle result = new Bundle();
        if (!readBoolean(context, "voice_transcription", true)) {
            result.putString(RESULT_ERROR, "Audio transcription is turned off in WAEX");
            return result;
        }
        byte[] audio = extras.getByteArray(EXTRA_AUDIO);
        String fileName = extras.getString(EXTRA_FILE_NAME, "voice-note.opus");
        TranscriptionClient.Config config = configFor(context);
        long started = System.currentTimeMillis();
        TranscriptionLog.add(context, "app", "Request: provider=" + config.provider
                + (TranscriptionClient.PROVIDER_LOCAL.equals(config.provider) ? " model=" + localModel(context) : "")
                + " language=" + (config.language.isEmpty() ? "auto" : config.language)
                + " audio=" + (audio == null ? 0 : audio.length / 1024) + " KB ." + extensionOf(fileName));
        try {
            String text;
            if (TranscriptionClient.PROVIDER_LOCAL.equals(config.provider)) {
                // Serialized inside LocalTranscriber; it must not take a slot from network calls.
                text = LocalTranscriber.transcribe(context, localModel(context),
                        audio, fileName, config.language);
            } else {
                SLOTS.acquire();
                try {
                    text = new TranscriptionClient().transcribe(config, audio, fileName);
                } finally {
                    SLOTS.release();
                }
            }
            result.putString(RESULT_TEXT, text);
            TranscriptionLog.add(context, "app", "Done in " + (System.currentTimeMillis() - started)
                    + " ms: " + (text.isEmpty() ? "no speech detected" : text.length() + " characters"));
        } catch (TranscriptionClient.TranscriptionException e) {
            result.putString(RESULT_ERROR, e.getMessage());
            TranscriptionLog.add(context, "app", "Failed after " + (System.currentTimeMillis() - started)
                    + " ms: " + e.getMessage());
        } catch (Exception e) {
            // Network failures: report the kind of failure, never URLs or headers.
            result.putString(RESULT_ERROR, "Network error (" + e.getClass().getSimpleName() + ")");
            TranscriptionLog.add(context, "app", "Failed: " + e.getClass().getName());
        }
        return result;
    }

    /**
     * Model ids offered by the custom endpoint, for the settings screen (WAEX app only; the key
     * never leaves this process). Throws with a readable message. Blocking.
     */
    @NonNull
    public static java.util.List<String> listCustomModels(@NonNull Context context)
            throws TranscriptionClient.TranscriptionException {
        String url = readString(context, "custom_transcription_url", "");
        if (url.trim().isEmpty()) throw new TranscriptionClient.TranscriptionException("Enter the endpoint URL first");
        try {
            java.util.List<String> models = new TranscriptionClient().listModels(url,
                    readString(context, "custom_transcription_key", ""));
            TranscriptionLog.add(context, "app", "Model discovery: " + models.size() + " models");
            return models;
        } catch (TranscriptionClient.TranscriptionException e) {
            TranscriptionLog.add(context, "app", "Model discovery failed: " + e.getMessage());
            throw e;
        } catch (Exception e) {
            TranscriptionLog.add(context, "app", "Model discovery failed: " + e.getClass().getName());
            throw new TranscriptionClient.TranscriptionException("Network error (" + e.getClass().getSimpleName() + ")");
        }
    }

    /** Adds a line sent by the WhatsApp side; bounded, metadata only. */
    @NonNull
    public static Bundle log(@NonNull Context context, @NonNull Bundle extras) {
        String message = extras.getString(EXTRA_MESSAGE);
        if (message != null && !message.isEmpty()) {
            TranscriptionLog.add(context, "whatsapp", message.length() > 300 ? message.substring(0, 300) : message);
        }
        return new Bundle();
    }

    private static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String ext = dot >= 0 ? fileName.substring(dot + 1) : "";
        return ext.matches("[A-Za-z0-9]{1,5}") ? ext : "?";
    }

    private static String localStatus(Context context, TranscriptionClient.Config config) {
        if (!TranscriptionClient.PROVIDER_LOCAL.equals(config.provider)) return " (API key or URL missing)";
        String model = localModel(context);
        if (!com.waenhancer.core.transcription.local.WhisperNative.isSupported()) {
            return " (" + com.waenhancer.core.transcription.local.WhisperNative.unsupportedReason() + ")";
        }
        return " (model " + model + " not downloaded)";
    }

    /**
     * Sends one second of silence with the current settings, from the WAEX app itself.
     * Returns {@code null} when the service accepted the URL and key, otherwise a readable error.
     * Blocking: call off the main thread.
     */
    @Nullable
    public static String testConnection(@NonNull Context context) {
        TranscriptionClient.Config config = configFor(context);
        TranscriptionLog.add(context, "app", "Connection test: provider=" + config.provider);
        try {
            if (TranscriptionClient.PROVIDER_LOCAL.equals(config.provider)) {
                LocalTranscriber.transcribe(context, localModel(context),
                        TranscriptionClient.silentWav(), "waex-test.wav", config.language);
            } else {
                new TranscriptionClient().transcribe(config, TranscriptionClient.silentWav(), "waex-test.wav");
            }
            TranscriptionLog.add(context, "app", "Connection test passed");
            return null;
        } catch (TranscriptionClient.TranscriptionException e) {
            String message = e.getMessage() == null ? "Failed" : e.getMessage();
            // AssemblyAI only rejects the silent clip after upload and job creation succeeded,
            // which already proves the key works.
            boolean passed = message.startsWith("AssemblyAI: ");
            TranscriptionLog.add(context, "app", "Connection test " + (passed ? "passed" : "failed: " + message));
            return passed ? null : message;
        } catch (Exception e) {
            TranscriptionLog.add(context, "app", "Connection test failed: " + e.getClass().getName());
            return "Network error (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static TranscriptionClient.Config configFor(Context context) {
        String provider = readString(context, "transcription_provider", TranscriptionClient.PROVIDER_GROQ);
        String language = readString(context, "transcription_language", "");
        TranscriptionClient.Config config;
        switch (provider) {
            case TranscriptionClient.PROVIDER_ASSEMBLY:
                config = new TranscriptionClient.Config(provider, "",
                        firstNonEmpty(context, "assembly_key", "assemblyai_key"), "", language);
                break;
            case TranscriptionClient.PROVIDER_LOCAL:
                config = new TranscriptionClient.Config(provider, "", "", "", language);
                break;
            case TranscriptionClient.PROVIDER_CUSTOM:
                config = new TranscriptionClient.Config(provider,
                        readString(context, "custom_transcription_url", ""),
                        readString(context, "custom_transcription_key", ""),
                        readString(context, "custom_transcription_model", ""), language);
                break;
            default:
                config = new TranscriptionClient.Config(TranscriptionClient.PROVIDER_GROQ, "",
                        firstNonEmpty(context, "groq_key", "groq_api_key"), "", language);
        }
        return config;
    }

    /** The chosen on-device model, or the suggested one when none was chosen. */
    private static String localModel(Context context) {
        return LocalTranscriber.effectiveModelId(context, readString(context, "local_model", ""),
                readString(context, "transcription_language", ""));
    }

        private static String firstNonEmpty(Context context, String key, String legacyKey) {
        String value = readString(context, key, "");
        return value.trim().isEmpty() ? readString(context, legacyKey, "") : value;
    }

    private static String readString(Context context, String key, String fallback) {
        Object value = PreferenceStores.read(context, key);
        return value instanceof String ? (String) value : fallback;
    }

    private static boolean readBoolean(Context context, String key, boolean fallback) {
        Object value = PreferenceStores.read(context, key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }
}
