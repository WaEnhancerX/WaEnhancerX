package com.waenhancer.xposed.features.privacy;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Binds incoming ownership to the primary immutable key read by the sender anchor.
 * Reflection discovery runs during installation, never while changing media state.
 * Unsupported or ambiguous message/key contracts preserve the host operation.
 */
final class ViewOnceMessageContract {
    private final Class<?> messageType;
    private final Field messageKey;
    private final Field own;

    private ViewOnceMessageContract(Class<?> type, Field key, Field own) {
        this.messageType = type;
        this.messageKey = key;
        this.own = own;
    }

    static ViewOnceMessageContract resolve(DexKitBridge bridge, ClassLoader loader)
            throws ReflectiveOperationException {
        MethodDataList senders = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .paramCount(0)
                .addUsingString("FMessage/getSenderUserJid/key.id=", StringMatchType.Equals)));
        if (senders.size() != 1 || !senders.get(0).isMethod()) return null;
        MethodData senderData = senders.get(0);
        Method sender = senderData.getMethodInstance(loader);
        if (!concreteInstance(sender) || sender.getParameterTypes().length != 0
                || !jidName(sender.getReturnType(), "UserJid")) return null;
        Class<?> message = sender.getDeclaringClass();
        Field key = null, own = null;
        for (UsingFieldData use : senderData.getUsingFields()) {
            Field candidate = use.getField().getFieldInstance(loader);
            if (candidate.getDeclaringClass() != message || !immutable(candidate)) continue;
            Field ownership = keyOwnership(candidate.getType());
            if (ownership == null) continue;
            if (key != null && !key.equals(candidate)) return null;
            key = candidate;
            own = ownership;
        }
        if (key == null) return null;
        key.setAccessible(true);
        own.setAccessible(true);
        return new ViewOnceMessageContract(message, key, own);
    }

    private static Field keyOwnership(Class<?> type) {
        Field id = null, own = null, chat = null;
        int count = 0, depth = 0;
        for (Class<?> owner = type; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            if (++depth > 8) return null;
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (++count > 3 || !immutable(field)) return null;
                if (field.getType() == String.class && id == null) id = field;
                else if (field.getType() == boolean.class && own == null) own = field;
                else if (jidName(field.getType(), "Jid") && chat == null) chat = field;
                else return null;
            }
        }
        return count == 3 && id != null && chat != null ? own : null;
    }

    private static boolean jidName(Class<?> type, String leaf) {
        String name = type.getName();
        return name.equals("com.whatsapp.infra.core.jid." + leaf)
                || name.equals("com.whatsapp.jid." + leaf);
    }

    private static boolean immutable(Field field) {
        int flags = field.getModifiers();
        return Modifier.isFinal(flags) && !Modifier.isStatic(flags) && !field.isSynthetic();
    }

    boolean accepts(Method setter) {
        return concreteInstance(setter) && Modifier.isPublic(setter.getModifiers())
                && messageType.isAssignableFrom(setter.getDeclaringClass())
                && setter.getReturnType() == void.class
                && setter.getParameterTypes().length == 1 && setter.getParameterTypes()[0] == int.class;
    }

    boolean isMessageType(Class<?> type) {
        return messageType.isAssignableFrom(type);
    }

    boolean isIncoming(Object receiver) {
        if (!messageType.isInstance(receiver)) return false;
        try {
            Object key = messageKey.get(receiver);
            return key != null && key.getClass() == messageKey.getType() && !own.getBoolean(key);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** Resolve the only getter/setter pair on the interface actually read by SQL. */
    static Method interfaceSetter(Method invoked) {
        if (invoked == null || !invoked.getDeclaringClass().isInterface()) return null;
        Method getter = null, setter = null;
        for (Method method : invoked.getDeclaringClass().getMethods()) {
            int flags = method.getModifiers();
            if (Modifier.isStatic(flags) || method.isBridge() || method.isSynthetic()) continue;
            Class<?>[] args = method.getParameterTypes();
            if (method.getReturnType() == int.class && args.length == 0) {
                if (getter != null && !getter.equals(method)) return null;
                getter = method;
            } else if (method.getReturnType() == void.class && args.length == 1 && args[0] == int.class) {
                if (setter != null && !setter.equals(method)) return null;
                setter = method;
            } else {
                // A wider interface does not establish a unique view-once state contract.
                return null;
            }
        }
        return getter != null && setter != null && (invoked.equals(getter) || invoked.equals(setter))
                ? setter : null;
    }

    private static boolean concreteInstance(Method method) {
        if (method == null) return false;
        int flags = method.getModifiers();
        return !Modifier.isStatic(flags) && !Modifier.isAbstract(flags) && !Modifier.isNative(flags)
                && !method.isBridge() && !method.isSynthetic();
    }
}
