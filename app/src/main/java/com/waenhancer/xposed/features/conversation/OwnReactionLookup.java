package com.waenhancer.xposed.features.conversation;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.db.MessageStore;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers "which emoji have I (the signed-in user) already reacted with on this message?".
 *
 * The reaction chip under a bubble aggregates everyone's reactions, so it cannot tell whether the
 * current user reacted. The message store can: own reactions are add-ons with {@code from_me = 1}.
 */
final class OwnReactionLookup {

    /** The lookup could not be answered (message key or schema not resolved). */
    static final String UNKNOWN = "\u0000unknown";

    private static final String TAG = "[WAEX:DoubleTapReaction]";
    private static final Field NO_FIELD;
    private static final Map<Class<?>, Field> KEY_FIELDS = new ConcurrentHashMap<>();

    static {
        try {
            NO_FIELD = OwnReactionLookup.class.getDeclaredField("UNKNOWN");
        } catch (NoSuchFieldException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private OwnReactionLookup() {}

    /**
     * @return the user's current reaction emoji, {@code null} when the user has not reacted, or
     *         {@link #UNKNOWN} when it cannot be determined.
     */
    @Nullable
    static String ownReaction(@NonNull Context context, @NonNull Object message) {
        String keyId = messageKeyId(message);
        if (keyId == null) return UNKNOWN;
        try {
            MessageStore store = MessageStore.getInstance(context);
            long rowId = store.getIdFromKey(keyId);
            if (rowId <= 0L) return UNKNOWN;
            SQLiteDatabase db = store.getDatabase();
            if (db == null) return UNKNOWN;
            try (Cursor cursor = db.rawQuery(
                    "SELECT r.reaction FROM message_add_on a "
                            + "JOIN message_add_on_reaction r ON r.message_add_on_row_id = a._id "
                            + "WHERE a.parent_message_row_id = ? AND a.from_me = 1 "
                            + "ORDER BY a._id DESC LIMIT 1",
                    new String[]{String.valueOf(rowId)})) {
                if (cursor == null || !cursor.moveToFirst()) return null;
                String reaction = cursor.getString(0);
                return reaction == null || reaction.isEmpty() ? null : reaction;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Own reaction lookup failed: " + t.getClass().getSimpleName());
            return UNKNOWN;
        }
    }

    /** The WhatsApp message id (the {@code key_id} column) of an FMessage, or null. */
    @Nullable
    static String messageKeyId(@NonNull Object message) {
        try {
            Field keyField = KEY_FIELDS.computeIfAbsent(message.getClass(), OwnReactionLookup::findKeyField);
            if (keyField == NO_FIELD) return null;
            Object key = keyField.get(message);
            return key == null ? null : idOf(key);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field findKeyField(Class<?> messageClass) {
        Field fallback = null;
        for (Class<?> type = messageClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()
                        || field.getType() == String.class || !looksLikeMessageKey(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                // The message's own key is the final field; other key-shaped fields are references.
                if (Modifier.isFinal(field.getModifiers())) return field;
                if (fallback == null) fallback = field;
            }
        }
        return fallback != null ? fallback : NO_FIELD;
    }

    private static boolean looksLikeMessageKey(Class<?> type) {
        boolean hasString = false;
        boolean hasBoolean = false;
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (field.getType() == String.class) hasString = true;
                if (field.getType() == boolean.class) hasBoolean = true;
            }
        }
        return hasString && hasBoolean;
    }

    @Nullable
    private static String idOf(Object key) {
        String best = null;
        for (Class<?> c = key.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(key);
                    if (!(value instanceof String)) continue;
                    String id = (String) value;
                    if (id.length() < 6 || id.indexOf('@') >= 0 || id.indexOf('/') >= 0) continue;
                    if (id.length() >= 12) return id;
                    if (best == null) best = id;
                } catch (Throwable ignored) {
                }
            }
        }
        return best;
    }
}
