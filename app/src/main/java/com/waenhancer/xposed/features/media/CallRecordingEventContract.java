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
    private final Object active;
    private final Object none;

    private CallRecordingEventContract(Method stateChanged, Method soundPortCreated, Method callEnding,
                                       Method currentCallInfo, Field callId, Field callState,
                                       Object active, Object none) {
        this.stateChanged = stateChanged;
        this.soundPortCreated = soundPortCreated;
        this.callEnding = callEnding;
        this.currentCallInfo = currentCallInfo;
        this.infoType = currentCallInfo.getReturnType();
        this.callId = callId;
        this.callState = callState;
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
        requireVoidMethod(changed);
        requireVoidMethod(sound);
        requireVoidMethod(ending);
        id.setAccessible(true);
        state.setAccessible(true);
        currentInfo.setAccessible(true);
        changed.setAccessible(true);
        sound.setAccessible(true);
        ending.setAccessible(true);
        return new CallRecordingEventContract(changed, sound, ending, currentInfo, id, state, active, none);
    }

    private static Method resolveCallEndingMethod(Class<?> callback, Class<?> info) throws NoSuchMethodException {
        final Class<?> logInfo;
        try {
            logInfo = Class.forName(info.getName().replace("CallInfo", "CallLogInfo"),
                    false, callback.getClassLoader());
        } catch (ClassNotFoundException missing) {
            throw new NoSuchMethodException("Unsupported CallLogInfo type");
        }

        // Prefer the verified host signature, after validating it as a hookable event.
        // An overload with this parameter list but a different return/static contract
        // must not prevent a separate, usable typed event from being considered.
        try {
            Method exact = callback.getDeclaredMethod("callEnding", logInfo, int.class, String.class, String.class);
            requireVoidMethod(exact);
            return exact;
        } catch (NoSuchMethodException ignored) {
        }

        // Additional trailing host arguments are not inspected or changed by this
        // hook. Accept one concrete instance event with the exact CallLogInfo owner;
        // reflection enumeration order and a matching class-name suffix are not
        // sufficient evidence that a different overload marks the end of a call.
        Method candidate = null;
        for (Method method : callback.getDeclaredMethods()) {
            if (!"callEnding".equals(method.getName())) continue;
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 0 || params[0] != logInfo) continue;
            try {
                requireVoidMethod(method);
            } catch (NoSuchMethodException unsupported) {
                continue;
            }
            if (candidate != null) {
                throw new NoSuchMethodException("Ambiguous callEnding event");
            }
            candidate = method;
        }
        if (candidate != null) {
            return candidate;
        }
        throw new NoSuchMethodException("Supported callEnding event not found");
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
        return new Snapshot(id, state == active, state == none);
    }

    Snapshot current(Object callback) throws ReflectiveOperationException {
        return read(currentCallInfo.invoke(callback));
    }

    static final class Snapshot {
        final String callId;
        final boolean active;
        final boolean ended;

        Snapshot(String callId, boolean active, boolean ended) {
            this.callId = callId;
            this.active = active;
            this.ended = ended;
        }
    }
}
