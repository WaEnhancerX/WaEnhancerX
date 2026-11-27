package com.waenhancer.utils;

import com.waenhancer.config.PreferenceSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * A bounded, schema-filtered environment snapshot assembled only when the user exports.
 * The Android adapter supplies typed OS facts; this class rejects arbitrary strings and
 * never includes identifiers, secret preferences or free-form preference values.
 */
public final class DiagnosticsSnapshot {
    private static final int MAX_EXITS = 16;
    private static final int MAX_FLAGS = 768;
    private static final long DAY_MS = 86_400_000L;
    private final List<String> systemLines = new ArrayList<>();
    private final List<String> exitLines = new ArrayList<>();
    private final TreeSet<String> flagLines = new TreeSet<>();

    public void addDevice(String manufacturer, String model, String patch, String abi) {
        systemLines.add("device.manufacturer=" + safeLabel(manufacturer));
        systemLines.add("device.model=" + safeLabel(model));
        systemLines.add("android.security_patch=" + safeLabel(patch));
        systemLines.add("device.abi=" + safeLabel(abi));
    }

    public void addRuntime(String framework, int apiLevel) {
        systemLines.add("hook.framework=" + safeLabel(framework));
        systemLines.add("hook.api=" + safeNumber(apiLevel));
    }

    public void addResources(long totalMemory, long availableMemory, long appHeap,
                             long availableStorage, int batteryPercent) {
        systemLines.add("memory.total_bytes=" + safeNumber(totalMemory));
        systemLines.add("memory.available_bytes=" + safeNumber(availableMemory));
        systemLines.add("memory.app_heap_bytes=" + safeNumber(appHeap));
        systemLines.add("storage.available_bytes=" + safeNumber(availableStorage));
        systemLines.add("battery.percent=" + (batteryPercent >= 0 && batteryPercent <= 100
                ? batteryPercent : "unknown"));
    }

    public void addTargetVersion(String packageName, String versionName, long versionCode) {
        if (!DeclaredWhatsAppTargets.contains(packageName)) return;
        systemLines.add("target." + packageName + ".version=" + safeVersion(versionName));
        systemLines.add("target." + packageName + ".version_code=" + safeNumber(versionCode));
    }

    /** Include only explicitly declared exportable public Booleans, never their string values. */
    public void addPreference(String key, boolean value, boolean targetScoped) {
        PreferenceSchema.Entry schema = PreferenceSchema.entry(key);
        if (schema == null
                || schema.type != PreferenceSchema.Type.BOOLEAN
                || schema.sensitivity != PreferenceSchema.Sensitivity.PUBLIC_SETTING
                || schema.store != PreferenceSchema.Store.PUBLIC
                || flagLines.size() >= MAX_FLAGS) return;
        flagLines.add("setting." + (targetScoped ? "target." : "universal.")
                + key + "=" + value);
    }

    /** Own-app exit reasons only; no stack traces, process names, UIDs or descriptions. */
    public void addExit(long now, long timestamp, int reason, int status,
                        long pssKiB, long rssKiB) {
        if (exitLines.size() >= MAX_EXITS || timestamp < 0 || timestamp > now
                || now - timestamp > 7L * DAY_MS || reason < 0 || reason > 100) return;
        exitLines.add("exit.at_ms=" + timestamp + " reason=" + reason
                + " status=" + status
                + " pss_kib=" + safeNumber(pssKiB)
                + " rss_kib=" + safeNumber(rssKiB));
    }

    public String render() {
        StringBuilder text = new StringBuilder(
                "WAEX on-demand environment snapshot\n"
                + "Captured at export only. No Android IDs, serial numbers, contacts, "
                + "accounts, message text or secret/string preference values.\n");
        appendBounded(text, systemLines);
        appendBounded(text, flagLines);
        appendBounded(text, exitLines);
        return text.toString();
    }

    private static void appendBounded(StringBuilder text, Iterable<String> lines) {
        for (String line : lines) {
            if (text.length() + line.length() + 1 > 64 * 1024) break;
            text.append(line).append('\n');
        }
    }

    private static String safeLabel(String value) {
        if (value == null || value.isEmpty() || value.length() > 64
                || !value.matches("[A-Za-z0-9._+ /()\\-]+")) return "unknown";
        return value;
    }

    private static String safeVersion(String value) {
        return value != null && value.length() <= 40
                && value.matches("[A-Za-z0-9._+\\-]+") ? value : "unknown";
    }

    private static String safeNumber(long value) {
        return value < 0 ? "unknown" : Long.toString(value);
    }
}
