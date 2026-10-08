package com.waenhancer.xposed.features.automation;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/** Process-local presence state shared by the receiver hook and visible conversation rows. */
public final class PresenceStateStore {
    public interface Listener { void onPresenceChanged(String jid, State state); }

    public static final class State {
        public final boolean online;
        public final long changedAtMillis;
        private State(boolean online, long changedAtMillis) {
            this.online = online;
            this.changedAtMillis = changedAtMillis;
        }
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final Set<Listener> LISTENERS = new CopyOnWriteArraySet<>();

    private PresenceStateStore() {}

    public static State get(String jid) { return jid == null ? null : STATES.get(normalize(jid)); }
    public static void addListener(Listener listener) { if (listener != null) LISTENERS.add(listener); }
    public static void removeListener(Listener listener) { LISTENERS.remove(listener); }

    public static boolean update(String jid, boolean online) {
        String key = normalize(jid);
        if (key.isEmpty() || key.contains("@g.us")) return false;
        State old = STATES.get(key);
        if (old != null && old.online == online) return false;
        State state = new State(online, System.currentTimeMillis());
        STATES.put(key, state);
        for (Listener listener : LISTENERS) listener.onPresenceChanged(key, state);
        return true;
    }

    public static String normalize(String jid) {
        if (jid == null) return "";
        String value = jid.trim();
        int slash = value.indexOf('/');
        if (slash > 0) value = value.substring(0, slash);
        return value;
    }
}
