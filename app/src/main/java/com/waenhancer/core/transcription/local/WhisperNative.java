package com.waenhancer.core.transcription.local;

import android.os.Build;
import androidx.annotation.Nullable;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * whisper.cpp through JNI ({@code libwaexwhisper.so}). Only ever loaded in the WAEX app
 * process, never inside WhatsApp.
 */
public final class WhisperNative {

    private static volatile Boolean sSupported;
    private static volatile String sUnsupportedReason;
    private static volatile String sBackend;
    private static volatile String sCpuSummary;

    /**
     * ggml CPU backend libraries, best first, with the /proc/cpuinfo features each needs
     * (asimddp = dot product, asimdhp = fp16 arithmetic, i8mm = int8 matrix multiply).
     */
    private static final String[][] CPU_BACKENDS = {
            {"android_armv9.2_2", "asimddp i8mm asimdhp sve sve2 sme"},
            {"android_armv9.2_1", "asimddp i8mm asimdhp sve sme"},
            {"android_armv9.0_1", "asimddp i8mm asimdhp sve2"},
            {"android_armv8.6_1", "asimddp asimdhp i8mm"},
            {"android_armv8.2_2", "asimddp asimdhp"},
            {"android_armv8.2_1", "asimddp"},
            {"android_armv8.0_1", ""},
    };

    private WhisperNative() {}

    static native long init(String modelPath);

    static native void free(long handle);

    @Nullable
    static native byte[] transcribe(long handle, float[] samples16kMono, String language, int threads);

    static native String systemInfo();

    private static native boolean loadCpuBackend(String libraryName);

    /**
     * True when the library loaded and a CPU backend matching this phone was found. Every
     * 64-bit ARM phone has at least the ARMv8.0 backend; newer CPUs get faster variants. A
     * variant is only tried when /proc/cpuinfo lists its instructions, so an unsupported one
     * can never run and crash with an illegal instruction.
     */
    public static synchronized boolean isSupported() {
        if (sSupported != null) return sSupported;
        boolean arm64 = false;
        for (String abi : Build.SUPPORTED_ABIS) {
            if ("arm64-v8a".equals(abi)) arm64 = true;
        }
        if (!arm64) return unsupported("needs a 64-bit ARM phone");
        try {
            System.loadLibrary("waexwhisper");
        } catch (Throwable t) {
            return unsupported("native library failed to load: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " " + t.getMessage() : ""));
        }
        String features = cpuFeatures();
        StringBuilder tried = new StringBuilder();
        for (String[] backend : CPU_BACKENDS) {
            if (!hasAll(features, backend[1])) continue;
            tried.append(tried.length() == 0 ? "" : ", ").append(backend[0].replace("android_", ""));
            try {
                if (loadCpuBackend("libggml-cpu-" + backend[0] + ".so")) {
                    sBackend = backend[0].replace("android_", "");
                    sCpuSummary = "CPU features:" + relevantFeatures(features) + "; tried " + tried;
                    sSupported = true;
                    return true;
                }
            } catch (Throwable ignored) {
                // Try the next, more basic variant.
            }
        }
        return unsupported("no CPU backend could be loaded (tried " + tried + "; CPU features:"
                + relevantFeatures(features) + ")");
    }

    /** The CPU backend in use, e.g. "armv8.2_2"; null before or without support. */
    @Nullable
    public static String backendName() {
        return sBackend;
    }

    /** The CPU flags and backends tried while loading, for the transcription log; null before. */
    @Nullable
    public static String cpuSummary() {
        return sCpuSummary;
    }

    /** Only the instruction-set flags that decide the backend, for logs. */
    private static String relevantFeatures(@Nullable String features) {
        if (features == null) return " unknown";
        StringBuilder out = new StringBuilder();
        for (String flag : new String[]{"asimd", "asimddp", "asimdhp", "fphp", "i8mm", "sve", "sve2", "sme"}) {
            if (features.contains(" " + flag + " ")) out.append(' ').append(flag);
        }
        return out.length() == 0 ? " none" : out.toString();
    }

    static boolean hasAll(@Nullable String features, String required) {
        if (required.isEmpty()) return true;
        if (features == null) return false;
        for (String feature : required.split(" ")) {
            if (!features.contains(" " + feature + " ")) return false;
        }
        return true;
    }

    /** Why {@link #isSupported()} returned false, for the settings screen. */
    @Nullable
    public static String unsupportedReason() {
        isSupported();
        return sUnsupportedReason;
    }

    private static boolean unsupported(String reason) {
        sUnsupportedReason = reason;
        sSupported = false;
        return false;
    }

    /** The "Features" line of /proc/cpuinfo, padded with spaces so whole words can be matched. */
    @Nullable
    private static String cpuFeatures() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.toLowerCase(Locale.ROOT).startsWith("features")) {
                    int colon = line.indexOf(':');
                    return " " + line.substring(colon + 1).trim().toLowerCase(Locale.ROOT) + " ";
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
