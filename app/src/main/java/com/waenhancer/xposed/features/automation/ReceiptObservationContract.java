package com.waenhancer.xposed.features.automation;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;

/** Validates and snapshots the host relationships established by the receipt anchors. */
final class ReceiptObservationContract {
    static boolean concreteInstance(Method method, Class<?> returns, Class<?>... parameters) {
        if (method == null || method.getReturnType() != returns) return false;
        int flags = method.getModifiers();
        return !Modifier.isStatic(flags) && !Modifier.isAbstract(flags) && !Modifier.isNative(flags)
                && !method.isBridge() && !method.isSynthetic()
                && Arrays.equals(method.getParameterTypes(), parameters);
    }

    static final class Keys {
        final Class<?> type;
        final Class<?> jidType;
        final Field id;
        final Field own;
        final Field chat;
        final Method rawJid;

        private Keys(Class<?> type, Class<?> jidType, Field id, Field own, Field chat, Method rawJid) {
            this.type = type; this.jidType = jidType; this.id = id;
            this.own = own; this.chat = chat; this.rawJid = rawJid;
        }

        static Keys create(Class<?> type, Class<?> jidType) {
            if (type == null || jidType == null) return null;
            try {
                List<Field> fields = instanceFields(type);
                if (fields.size() != 3) return null;
                Field id = oneField(fields, String.class, false);
                Field own = oneField(fields, boolean.class, false);
                Field chat = oneField(fields, jidType, true);
                if (!immutable(id) || !immutable(own) || !immutable(chat)) return null;
                Method raw = jidType.getMethod("getRawString");
                if (raw.getReturnType() != String.class || raw.getParameterTypes().length != 0
                        || Modifier.isStatic(raw.getModifiers()) || Modifier.isNative(raw.getModifiers())) return null;
                return new Keys(type, jidType, accessible(id), accessible(own), accessible(chat), raw);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return null;
            }
        }

        ReceiptObservationPolicy.Observation read(Object key, ReceiptObservationPolicy.Kind kind,
                                                  int typeCode, long timestamp, Object viewer)
                throws ReflectiveOperationException {
            if (key == null || key.getClass() != type || !jidType.isInstance(viewer)) return null;
            Object chatJid = chat.get(key);
            if (!jidType.isInstance(chatJid)) return null;
            return ReceiptObservationPolicy.capture(kind, typeCode, timestamp, own.getBoolean(key),
                    (String) invoke(rawJid, chatJid), (String) id.get(key), (String) invoke(rawJid, viewer));
        }
    }

    static final class Messages {
        final Class<?> receiptType;
        final Class<?> userJidType;
        final Keys keys;
        final Field receiptCode;
        final Field timestamp;
        final Field directViewer;
        final Field device;
        final Field deviceUser;
        final Field message;
        final Field messageKey;

        private Messages(Class<?> receiptType, Class<?> userJidType, Keys keys,
                         Field receiptCode, Field timestamp, Field directViewer, Field device,
                         Field deviceUser, Field message, Field messageKey) {
            this.receiptType = receiptType; this.userJidType = userJidType; this.keys = keys;
            this.receiptCode = receiptCode; this.timestamp = timestamp; this.directViewer = directViewer;
            this.device = device; this.deviceUser = deviceUser; this.message = message; this.messageKey = messageKey;
        }

        static Messages create(Class<?> receiptType, Class<?> messageType, Class<?> userJidType,
                               Class<?> deviceJidType, Keys keys, Field anchoredMessageKey) {
            if (receiptType == null || messageType == null || userJidType == null
                    || deviceJidType == null || keys == null || !immutable(anchoredMessageKey)
                    || anchoredMessageKey.getDeclaringClass() != messageType
                    || anchoredMessageKey.getType() != keys.type) return null;
            try {
                List<Field> fields = instanceFields(receiptType);
                if (fields.size() != 5 || !keys.jidType.isAssignableFrom(userJidType)
                        || !keys.jidType.isAssignableFrom(deviceJidType)) return null;
                Field code = oneField(fields, int.class, false);
                Field time = oneField(fields, long.class, false);
                Field direct = oneField(fields, userJidType, false);
                Field device = oneField(fields, deviceJidType, false);
                Field message = oneField(fields, messageType, false);
                Field deviceUser = oneField(instanceFields(deviceJidType), userJidType, false);
                for (Field field : new Field[]{code, time, direct, device, message, deviceUser}) {
                    if (!immutable(field)) return null;
                    accessible(field);
                }
                receiptType.getDeclaredConstructor(deviceJidType, userJidType, messageType, int.class, long.class);
                return new Messages(receiptType, userJidType, keys, code, time, direct, device,
                        deviceUser, message, accessible(anchoredMessageKey));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                return null;
            }
        }

        List<ReceiptObservationPolicy.Observation> snapshot(Object argument) {
            if (!(argument instanceof Collection)) return Collections.emptyList();
            Collection<?> input = (Collection<?>) argument;
            List<ReceiptObservationPolicy.Observation> result = new ArrayList<>();
            try {
                if (input.size() > ReceiptObservationPolicy.MAX_BATCH) return Collections.emptyList();
                int inspected = 0;
                for (Object value : input) {
                    if (++inspected > ReceiptObservationPolicy.MAX_BATCH) return Collections.emptyList();
                    if (value == null || value.getClass() != receiptType) continue;
                    int code = receiptCode.getInt(value);
                    long time = timestamp.getLong(value);
                    if (code != ReceiptObservationPolicy.READ_RECEIPT || time <= 0L) continue;
                    Object viewer = directViewer.get(value);
                    if (viewer == null) {
                        Object deviceJid = device.get(value);
                        if (deviceJid == null) continue;
                        viewer = deviceUser.get(deviceJid);
                    }
                    if (!userJidType.isInstance(viewer)) continue;
                    Object fMessage = message.get(value);
                    if (fMessage == null) continue;
                    ReceiptObservationPolicy.Observation event = keys.read(messageKey.get(fMessage),
                            ReceiptObservationPolicy.Kind.MESSAGE, code, time, viewer);
                    if (event != null) result.add(event);
                }
                return Collections.unmodifiableList(result);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
                return Collections.emptyList();
            }
        }
    }

    static final class Statuses {
        final Method handler;
        final Class<?> userJidType;
        final Field statusKey;
        final Field baseKey;
        final Field statusId;
        final Field statusOwn;
        final Field selfProvider;
        final Method selfPredicate;
        final Keys keys;

        private Statuses(Method handler, Class<?> userJidType, Field statusKey, Field baseKey,
                         Field statusId, Field statusOwn, Field selfProvider, Method selfPredicate, Keys keys) {
            this.handler = handler; this.userJidType = userJidType; this.statusKey = statusKey;
            this.baseKey = baseKey; this.statusId = statusId; this.statusOwn = statusOwn;
            this.selfProvider = selfProvider; this.selfPredicate = selfPredicate; this.keys = keys;
        }

        static Statuses create(Method handler, Class<?> userJidType, Class<?> statusKeyType,
                               Keys keys, Field selfProvider, Method selfPredicate) {
            if (handler == null || keys == null || statusKeyType == null || selfPredicate == null
                    || selfProvider == null) return null;
            Class<?>[] handlerParameters = handler.getParameterTypes();
            if (handlerParameters.length != 4) return null;
            Class<?> statusType = handlerParameters[1];
            if (!concreteInstance(handler, void.class, userJidType, statusType, int.class, long.class)
                    || statusType.isPrimitive() || selfProvider.getDeclaringClass() != handler.getDeclaringClass()
                    || Modifier.isStatic(selfProvider.getModifiers())) return null;
            int flags = selfPredicate.getModifiers();
            Class<?>[] selfArgs = selfPredicate.getParameterTypes();
            if (selfArgs.length != 2 || selfPredicate.getReturnType() != boolean.class
                    || !Modifier.isStatic(flags) || Modifier.isAbstract(flags) || Modifier.isNative(flags)
                    || selfPredicate.isSynthetic() || selfPredicate.isBridge()
                    || selfArgs[0] != selfProvider.getType() || !selfArgs[1].isAssignableFrom(userJidType)) return null;
            try {
                Field key = oneField(instanceFields(statusType), statusKeyType, false);
                Field base = oneField(instanceFields(statusKeyType), keys.type, false);
                Field id = oneField(instanceFields(statusKeyType), String.class, false);
                Field own = oneField(instanceFields(statusKeyType), boolean.class, false);
                for (Field field : new Field[]{key, base, id, own}) if (!immutable(field)) return null;
                selfPredicate.setAccessible(true);
                return new Statuses(handler, userJidType, accessible(key), accessible(base), accessible(id),
                        accessible(own), accessible(selfProvider), selfPredicate, keys);
            } catch (RuntimeException | LinkageError unavailable) {
                return null;
            }
        }

        ReceiptObservationPolicy.Observation snapshot(Object receiver, Object[] args) {
            if (args == null || args.length != 4 || !handler.getDeclaringClass().isInstance(receiver)
                    || !userJidType.isInstance(args[0]) || !statusKey.getDeclaringClass().isInstance(args[1])
                    || !(args[2] instanceof Integer) || !(args[3] instanceof Long)
                    || (Integer) args[2] != ReceiptObservationPolicy.READ_RECEIPT || (Long) args[3] <= 0L) return null;
            try {
                Object provider = selfProvider.get(receiver);
                if (provider == null || !Boolean.FALSE.equals(invoke(selfPredicate, null, provider, args[0]))) return null;
                Object key = statusKey.get(args[1]);
                if (key == null || !statusOwn.getBoolean(key)) return null;
                Object messageKey = baseKey.get(key);
                ReceiptObservationPolicy.Observation event = keys.read(messageKey,
                        ReceiptObservationPolicy.Kind.STATUS, (Integer) args[2], (Long) args[3], args[0]);
                return event != null && event.messageId.equals(statusId.get(key)) ? event : null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unreadable) {
                return null;
            }
        }
    }

    static Field oneField(List<Field> fields, Class<?> type, boolean assignable) {
        Field result = null;
        for (Field field : fields) {
            if (assignable ? type.isAssignableFrom(field.getType()) : field.getType() == type) {
                if (result != null && !result.equals(field)) return null;
                result = field;
            }
        }
        return result;
    }

    static List<Field> instanceFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        int depth = 0;
        for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            if (++depth > 16) throw new IllegalArgumentException("unsupported hierarchy");
            for (Field field : owner.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) result.add(field);
                if (result.size() > 512) throw new IllegalArgumentException("unsupported field count");
            }
        }
        return result;
    }

    static boolean immutable(Field field) {
        return field != null && !Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers()) && !field.isSynthetic();
    }

    private static Field accessible(Field field) { field.setAccessible(true); return field; }

    private static Object invoke(Method method, Object receiver, Object... args) throws ReflectiveOperationException {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof VirtualMachineError) throw (VirtualMachineError) cause;
            if (cause instanceof ThreadDeath) throw (ThreadDeath) cause;
            throw failure;
        }
    }

    private ReceiptObservationContract() {}
}
