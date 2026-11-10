package com.waenhancer.xposed.features.privacy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded pending read-receipt store; only the exact recipient can release a queue. */
final class PendingReadReceiptStore<T> {
    private final int maxRecipients;
    private final int maxPerRecipient;
    private final LinkedHashMap<String, ArrayDeque<T>> queues =
            new LinkedHashMap<>(16, 0.75f, true);

    PendingReadReceiptStore(int maxRecipients, int maxPerRecipient) {
        if (maxRecipients <= 0 || maxPerRecipient <= 0)
            throw new IllegalArgumentException("Both limits must be positive");
        this.maxRecipients = maxRecipients;
        this.maxPerRecipient = maxPerRecipient;
    }

    /** Adds one item and returns the oldest evicted item, if this recipient was at capacity. */
    synchronized T add(String recipient, T job) {
        Objects.requireNonNull(recipient, "recipient");
        Objects.requireNonNull(job, "job");
        if (recipient.isEmpty()) throw new IllegalArgumentException("Empty recipient");
        ArrayDeque<T> pending = queues.computeIfAbsent(recipient, key -> new ArrayDeque<>());
        T evicted = pending.size() == maxPerRecipient ? pending.removeFirst() : null;
        pending.addLast(job);
        while (queues.size() > maxRecipients) {
            String eldest = queues.keySet().iterator().next();
            queues.remove(eldest);
        }
        return evicted;
    }

    /** Return a stable view for dispatch; entries remain held until acknowledged. */
    synchronized List<T> snapshot(String recipient) {
        if (recipient == null || recipient.isEmpty()) return Collections.emptyList();
        ArrayDeque<T> pending = queues.get(recipient);
        return pending == null ? Collections.emptyList() : new ArrayList<>(pending);
    }

    /** Remove exactly the recipe that was successfully handed to WhatsApp's job queue. */
    synchronized boolean remove(String recipient, T job) {
        if (recipient == null || recipient.isEmpty() || job == null) return false;
        ArrayDeque<T> pending = queues.get(recipient);
        if (pending == null || !pending.removeFirstOccurrence(job)) return false;
        if (pending.isEmpty()) queues.remove(recipient);
        return true;
    }

    synchronized int pendingCount(String recipient) {
        ArrayDeque<T> pending = queues.get(recipient);
        return pending == null ? 0 : pending.size();
    }

    synchronized int recipientCount() { return queues.size(); }
}
