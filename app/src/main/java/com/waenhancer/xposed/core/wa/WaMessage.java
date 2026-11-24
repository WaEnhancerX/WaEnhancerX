package com.waenhancer.xposed.core.wa;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.utils.HookErrors;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Typed, read-only access to WhatsApp message objects (FMessage) and their keys.
 *
 * Fields are identified by their type contract, never by obfuscated names:
 * the key is the FMessage field whose type is the key class, and the key class has exactly one
 * String (message id), one boolean (fromMe) and one Jid (remote chat). Every accessor returns
 * null / -1 when the contract cannot be confirmed, so callers can stay neutral instead of guessing.
 */
public final class WaMessage {

    private static final String TAG = "WaMessage";

    public static final int MEDIA_VOICE_NOTE = 2;

    private static volatile boolean initialized;
    private static Class<?> fMessageClass;
    private static Field keyField;
    private static KeyLayout keyLayout;
    private static Field mediaTypeField;
    private static Method senderGetter;

    private WaMessage() {}

    /** Resolves the message contracts once. Safe to call from every feature; never throws. */
    public static synchronized void init(@NonNull Context context, @NonNull ClassLoader loader) {
        if (initialized) return;
        try {
            fMessageClass = WaTargets.fMessageClass(context, loader);
            Class<?> keyClass = WaTargets.messageKeyClass(context, loader);
            keyLayout = KeyLayout.of(keyClass);
            keyField = WaTargets.findFieldOfType(fMessageClass, keyClass);
        } catch (Throwable t) {
            HookErrors.swallowed(TAG, t);
        }
        mediaTypeField = WaTargets.mediaTypeField(context, loader);
        try {
            senderGetter = WaTargets.messageSenderGetter(context, loader);
        } catch (Throwable t) {
            HookErrors.swallowed(TAG, t);
        }
        if (mediaTypeField != null) mediaTypeField.setAccessible(true);
        initialized = true;
    }

    /** Throws when the key contract (id / fromMe / chat) could not be confirmed on this WhatsApp build. */
    public static void requireKeyContract() throws NoSuchFieldException {
        if (keyLayout == null || keyField == null) {
            throw new NoSuchFieldException("FMessage key contract unresolved");
        }
    }

    /** The message key type, or null if unresolved. */
    @Nullable
    public static Class<?> keyClass() {
        return keyLayout != null ? keyLayout.type : null;
    }

    public static boolean isMessage(@Nullable Object value) {
        return value != null && fMessageClass != null && fMessageClass.isInstance(value);
    }

    @Nullable
    public static Class<?> messageClass() {
        return fMessageClass;
    }

    /** The key object of a message, or the value itself when it already is a key. */
    @Nullable
    public static Object key(@Nullable Object messageOrKey) {
        if (messageOrKey == null || keyLayout == null) return null;
        if (keyLayout.type.isInstance(messageOrKey)) return messageOrKey;
        if (keyField == null || !keyField.getDeclaringClass().isInstance(messageOrKey)) return null;
        try {
            return keyField.get(messageOrKey);
        } catch (Throwable t) {
            HookErrors.swallowed(TAG, t);
            return null;
        }
    }

    @Nullable
    public static String id(@Nullable Object messageOrKey) {
        Object key = key(messageOrKey);
        return key != null ? (String) keyLayout.read(keyLayout.id, key) : null;
    }

    /** TRUE for messages you sent, FALSE for received ones, null when unknown. */
    @Nullable
    public static Boolean isFromMe(@Nullable Object messageOrKey) {
        Object key = key(messageOrKey);
        return key != null ? (Boolean) keyLayout.read(keyLayout.fromMe, key) : null;
    }

    /** Normalized raw JID of the chat the message belongs to. */
    @Nullable
    public static String remoteJid(@Nullable Object messageOrKey) {
        Object key = key(messageOrKey);
        return key != null ? WaJid.raw(keyLayout.read(keyLayout.remoteJid, key)) : null;
    }

    /** The chat's Jid object as WhatsApp holds it (for APIs that need the typed value). */
    @Nullable
    public static Object remoteJidObject(@Nullable Object messageOrKey) {
        Object key = key(messageOrKey);
        return key != null ? keyLayout.read(keyLayout.remoteJid, key) : null;
    }

    /** Normalized sender JID (the participant in a group), or null when unknown. */
    @Nullable
    public static String senderJid(@Nullable Object message) {
        Method getter = senderGetter;
        if (message == null || getter == null || !getter.getDeclaringClass().isInstance(message)) return null;
        try {
            return WaJid.raw(getter.invoke(message));
        } catch (Throwable t) {
            HookErrors.swallowed(TAG, t);
            return null;
        }
    }

    public static boolean hasMediaType() {
        return mediaTypeField != null;
    }

    /** WhatsApp media type, or -1 when unknown. */
    public static int mediaType(@Nullable Object message) {
        if (message == null || mediaTypeField == null
                || !mediaTypeField.getDeclaringClass().isInstance(message)) return -1;
        try {
            return mediaTypeField.getInt(message);
        } catch (Throwable t) {
            HookErrors.swallowed(TAG, t);
            return -1;
        }
    }

    public static boolean isViewOnceType(int mediaType) {
        return mediaType == 42 || mediaType == 43 || mediaType == 82;
    }

    public static boolean isVoiceNoteType(int mediaType) {
        return mediaType == MEDIA_VOICE_NOTE;
    }

    /** Field layout of the message key class. */
    static final class KeyLayout {
        final Class<?> type;
        final Field id;
        final Field fromMe;
        final Field remoteJid;

        private KeyLayout(Class<?> type, Field id, Field fromMe, Field remoteJid) {
            this.type = type;
            this.id = id;
            this.fromMe = fromMe;
            this.remoteJid = remoteJid;
        }

        /** Exactly one String, one boolean and one other object field; otherwise not a key. */
        @Nullable
        static KeyLayout of(@NonNull Class<?> type) {
            Field id = null, fromMe = null, jid = null;
            for (Field f : type.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t == String.class) {
                    if (id != null) return null;
                    id = f;
                } else if (t == boolean.class) {
                    if (fromMe != null) return null;
                    fromMe = f;
                } else if (!t.isPrimitive()) {
                    if (jid != null) return null;
                    jid = f;
                } else {
                    return null;
                }
            }
            if (id == null || fromMe == null || jid == null) return null;
            id.setAccessible(true);
            fromMe.setAccessible(true);
            jid.setAccessible(true);
            return new KeyLayout(type, id, fromMe, jid);
        }

        @Nullable
        Object read(@NonNull Field field, @NonNull Object key) {
            try {
                return field.get(key);
            } catch (Throwable t) {
                HookErrors.swallowed(TAG, t);
                return null;
            }
        }
    }
}
