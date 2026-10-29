package com.waenhancer.xposed.features.media;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Named host call events. A statistics callback or an Activity is not a call lifetime. */
final class CallRecordingEventContract {
    final Method stateChanged;
    final Method soundPortCreated;
    final Method callEnding;
    private final Method currentCallInfo;
    private final Class<?> infoType;
    private final Field callId;
    private final Field callState;
    private final Method peerJid;
    private final Method isCaller;
    private final Object active;
    private final Object none;

    private CallRecordingEventContract(Method stateChanged, Method soundPortCreated, Method callEnding,
                                       Method currentCallInfo, Field callId, Field callState,
                                       Method peerJid, Method isCaller,
                                       Object active, Object none) {
        this.stateChanged = stateChanged;
        this.soundPortCreated = soundPortCreated;
        this.callEnding = callEnding;
        this.currentCallInfo = currentCallInfo;
        this.infoType = currentCallInfo.getReturnType();
        this.callId = callId;
        this.callState = callState;
        this.peerJid = peerJid;
        this.isCaller = isCaller;
        this.active = active;
        this.none = none;
    }

    static CallRecordingEventContract resolve(Class<?> callback) throws ReflectiveOperationException {
        String name = callback.getName();
        if (!name.equals("com.whatsapp.calling.service.VoiceServiceEventCallback")
                && !name.equals("com.whatsapp.voipcalling.VoiceServiceEventCallback")) {
            throw new NoSuchMethodException("Unsupported call callback owner");
        }
        Method currentInfo = callback.getDeclaredMethod("getCallInfo");
        Class<?> info = currentInfo.getReturnType();
        if (!info.getName().equals("com.whatsapp.calling.infra.voipcalling.CallInfo")
                && !info.getName().equals("com.whatsapp.voipcalling.CallInfo")) {
            throw new NoSuchMethodException("Unsupported CallInfo type");
        }
        requireInstanceMethod(currentInfo);
        Field id = info.getDeclaredField("callId");
        Field state = info.getDeclaredField("callState");
        Class<?> stateType = state.getType();
        if (id.getType() != String.class || Modifier.isStatic(id.getModifiers())
                || !Modifier.isFinal(id.getModifiers()) || !Modifier.isFinal(state.getModifiers())
                || Modifier.isStatic(state.getModifiers()) || !stateType.isEnum()
                || !stateType.getName().equals(info.getName().replace("CallInfo", "CallState"))) {
            throw new NoSuchFieldException("Unsupported call identity/state fields");
        }
        Object active = stateType.getField("ACTIVE").get(null);
        Object none = stateType.getField("NONE").get(null);
        if (!stateType.isInstance(active) || !stateType.isInstance(none) || active == none) {
            throw new NoSuchFieldException("Unsupported call state constants");
        }
        Method changed = callback.getDeclaredMethod("callStateChanged", stateType, info);
        Method sound = callback.getDeclaredMethod("soundPortCreated", int.class);
        Method ending = resolveCallEndingMethod(callback, info);
        Method peer = optionalMethod(info, "getPeerJid");
        Method caller = optionalMethod(info, "isCaller");
        requireVoidMethod(changed);
        requireVoidMethod(sound);
        requireVoidMethod(ending);
        id.setAccessible(true);
        state.setAccessible(true);
        currentInfo.setAccessible(true);
        changed.setAccessible(true);
        sound.setAccessible(true);
        ending.setAccessible(true);
        return new CallRecordingEventContract(changed, sound, ending, currentInfo, id, state,
                peer, caller, active, none);
    }

    private static Method optionalMethod(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Method resolveCallEndingMethod(Class<?> callback, Class<?> info) throws NoSuchMethodException {
        Class<?> logInfo = null;
        try {
            logInfo = Class.forName(info.getName().replace("CallInfo", "CallLogInfo"),
                    false, callback.getClassLoader());
        } catch (ClassNotFoundException ignored) {
        }

        // Try exact legacy signature: (CallLogInfo, int, String, String)
        if (logInfo != null) {
            try {
                return callback.getDeclaredMethod("callEnding", logInfo, int.class, String.class, String.class);
            } catch (NoSuchMethodException ignored) {
            }
        }

        // Search declared methods for any callEnding starting with CallLogInfo or matching parameters
        Method fallback = null;
        for (Method m : callback.getDeclaredMethods()) {
            if ("callEnding".equals(m.getName())) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length >= 1 && (logInfo == null || params[0].equals(logInfo) || params[0].getName().endsWith("CallLogInfo"))) {
                    return m;
                }
                if (fallback == null) {
                    fallback = m;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new NoSuchMethodException("VoiceServiceEventCallback.callEnding not found");
    }

    private static void requireInstanceMethod(Method method) throws NoSuchMethodException {
        int modifiers = method.getModifiers();
        if (Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers)
                || Modifier.isNative(modifiers) || method.isBridge() || method.isSynthetic()) {
            throw new NoSuchMethodException("Unsupported call callback method");
        }
    }

    private static void requireVoidMethod(Method method) throws NoSuchMethodException {
        requireInstanceMethod(method);
        if (method.getReturnType() != void.class) {
            throw new NoSuchMethodException("Unsupported call callback return type");
        }
    }

    Snapshot read(Object info) throws IllegalAccessException {
        if (!infoType.isInstance(info)) return null;
        String id = (String) callId.get(info);
        Object state = callState.get(info);
        if (id == null || id.isEmpty() || state == null) return null;
        String jid = null;
        boolean outgoing = false;
        boolean directionKnown = false;
        try {
            Object peer = peerJid == null ? null : peerJid.invoke(info);
            if (peer != null) {
                try {
                    Method raw = peer.getClass().getMethod("getRawString");
                    jid = String.valueOf(raw.invoke(peer));
                } catch (ReflectiveOperationException ignored) {
                    jid = peer.toString();
                }
            }
            if (isCaller != null) {
                Object value = isCaller.invoke(info);
                if (value instanceof Boolean) {
                    outgoing = (Boolean) value;
                    directionKnown = true;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return new Snapshot(id, state == active, state == none, jid,
                directionKnown ? (outgoing ? "outgoing" : "incoming") : "unknown");
    }

    Snapshot current(Object callback) throws ReflectiveOperationException {
        return read(currentCallInfo.invoke(callback));
    }

    static final class Snapshot {
        final String callId;
        final boolean active;
        final boolean ended;
        final String peerJid;
        final String direction;

        Snapshot(String callId, boolean active, boolean ended, String peerJid, String direction) {
            this.callId = callId;
            this.active = active;
            this.ended = ended;
            this.peerJid = peerJid;
            this.direction = direction;
        }
    }
}
