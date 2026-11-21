package com.waenhancer.utils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Records each meaningful host startup transition once per process.
 * WAEX receives a health heartbeat every ten seconds; storing every identical heartbeat
 * would waste I/O, wear the journal and evict actual errors from the bounded history.
 */
public final class DiagnosticsHealthGate {
    @FunctionalInterface
    public interface Writer {
        void write() throws IOException;
    }

    private final Map<String, String> lastByTarget = new HashMap<>();

    public synchronized boolean recordIfChanged(String target, int pid, String runtime,
                                                String bridge, boolean errorPresent,
                                                Writer writer) throws IOException {
        if (!DeclaredWhatsAppTargets.contains(target) || pid <= 0
                || runtime == null || bridge == null || writer == null) return false;
        String state = pid + "|" + runtime + "|" + bridge + "|" + errorPresent;
        if (Objects.equals(lastByTarget.get(target), state)) return false;
        // Update cache only after durable append; a temporary I/O error may be retried.
        writer.write();
        lastByTarget.put(target, state);
        return true;
    }

    public synchronized void clear() {
        lastByTarget.clear();
    }
}
