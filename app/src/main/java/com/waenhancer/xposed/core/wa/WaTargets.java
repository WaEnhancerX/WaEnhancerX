package com.waenhancer.xposed.core.wa;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.devkit.DexCacheManager;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Single source of WhatsApp hook targets for the privacy features.
 *
 * Rules every finder here follows (they are what keeps a hook working across WhatsApp updates):
 * <ol>
 *   <li>One fingerprint per target, anchored on a WhatsApp log string or a stable (non-obfuscated)
 *       class name. These anchors have survived every WhatsApp release since 2024.</li>
 *   <li>No speculative fallback chains. A loose fallback that finds "a" method is how a hook ends
 *       up on the wrong method and crashes WhatsApp.</li>
 *   <li>A missing target throws. The feature is then reported as FAILED and simply does nothing,
 *       which is always safer than hooking a guess.</li>
 * </ol>
 * Cache keys carry a version suffix; bump it whenever a fingerprint changes.
 */
public final class WaTargets {

    private WaTargets() {}

    // ─── Messages ────────────────────────────────────────────────────────

    /** WhatsApp's message class (FMessage). */
    @NonNull
    public static Class<?> fMessageClass(@NonNull Context context, @NonNull ClassLoader loader)
            throws ClassNotFoundException {
        Class<?> result = DexSearchEngine.getInstance().findClassWithCache(context, loader,
                "waex_fmessage_class",
                (bridge, cl) -> {
                    MethodData data = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .addUsingString("FMessage/getSenderUserJid/key.id", StringMatchType.Contains)))
                            .firstOrNull();
                    return data != null ? data.getMethodInstance(cl).getDeclaringClass() : null;
                });
        if (result == null) throw new ClassNotFoundException("FMessage class");
        return result;
    }

    /**
     * FMessage's sender getter (for group messages, the participant). It is the method that
     * logs "FMessage/getSenderUserJid/key.id", i.e. the same anchor as the message class.
     */
    @Nullable
    public static Method messageSenderGetter(@NonNull Context context, @NonNull ClassLoader loader) {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(context, loader,
                "waex_wa_fmessage_sender_getter_v1",
                (bridge, cl) -> {
                    MethodData data = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .addUsingString("FMessage/getSenderUserJid/key.id", StringMatchType.Contains)))
                            .firstOrNull();
                    return data != null ? data.getMethodInstance(cl) : null;
                });
        if (method == null || method.getParameterTypes().length != 0
                || method.getReturnType().isPrimitive() || Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        method.setAccessible(true);
        return method;
    }

    /** WhatsApp's base Jid class (class name ends with "jid.Jid"). */
    @Nullable
    public static Class<?> jidClass(@NonNull Context context, @NonNull ClassLoader loader) {
        return DexSearchEngine.getInstance().findClassWithCache(context, loader,
                "waex_wa_jid_class_v1",
                (bridge, cl) -> {
                    for (ClassData data : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                            .className("jid.Jid", StringMatchType.EndsWith)))) {
                        Class<?> candidate = data.getInstance(cl);
                        if ("Jid".equals(candidate.getSimpleName())) return candidate;
                    }
                    return null;
                });
    }

    /** The message key class: exactly one Jid, one String id and one boolean fromMe. */
    @NonNull
    public static Class<?> messageKeyClass(@NonNull Context context, @NonNull ClassLoader loader)
            throws ClassNotFoundException {
        final Class<?> fMessage = fMessageClass(context, loader);
        Class<?> result = DexSearchEngine.getInstance().findClassWithCache(context, loader,
                "waex_wa_message_key_class_v1",
                (bridge, cl) -> {
                    for (ClassData data : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                            .fieldCount(3)
                            .addMethod(MethodMatcher.create().name("toString")
                                    .addUsingString("Key", StringMatchType.Contains))))) {
                        Class<?> candidate = data.getInstance(cl);
                        if (WaMessage.KeyLayout.of(candidate) == null) continue;
                        if (findFieldOfType(fMessage, candidate) != null) return candidate;
                    }
                    return null;
                });
        if (result == null) throw new ClassNotFoundException("FMessage.Key class");
        return result;
    }

    /** The int field on FMessage that holds the media type (2 = voice note, 42/43/82 = view once). */
    @Nullable
    public static Field mediaTypeField(@NonNull Context context, @NonNull ClassLoader loader) {
        try {
            final Class<?> fMessage = fMessageClass(context, loader);
            return DexCacheManager.getInstance(context).getField(loader, "waex_wa_media_type_field_v1", () -> {
                DexKitBridge bridge = bridge(context);
                if (bridge == null) return null;
                MethodData data = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .addUsingString("conversation/refresh", StringMatchType.Contains))).firstOrNull();
                if (data == null) return null;
                for (UsingFieldData use : data.getUsingFields()) {
                    FieldData field = use.getField();
                    if (!"int".equals(field.getTypeName())) continue;
                    if (!fMessage.getName().equals(field.getDeclaredClassName())) continue;
                    return field.getFieldInstance(loader);
                }
                return null;
            });
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ─── Presence / typing ───────────────────────────────────────────────

    @NonNull
    public static Method presenceSetAvailable(@NonNull Context context, @NonNull ClassLoader loader)
            throws NoSuchMethodException {
        return requireMethod(context, loader, "waex_presence_set_available",
                "presencestatemanager/setAvailable/new-state");
    }

    @NonNull
    public static Method presenceTransitionToUnavailable(@NonNull Context context, @NonNull ClassLoader loader)
            throws NoSuchMethodException {
        return requireMethod(context, loader, "waex_always_online_transition_unavailable",
                "presencestatemanager/startTransitionToUnavailable/new-state");
    }

    /** Sends the outgoing composing/recording presence. Must take the int state argument. */
    @NonNull
    public static Method sendComposing(@NonNull Context context, @NonNull ClassLoader loader)
            throws NoSuchMethodException {
        Method method = requireMethod(context, loader, "waex_handle_me_composing_method",
                "HandleMeComposing/sendComposing");
        for (Class<?> type : method.getParameterTypes()) {
            if (type == int.class || type == Integer.class) return method;
        }
        throw new NoSuchMethodException("HandleMeComposing/sendComposing has no int state parameter");
    }

    /** WhatsApp's incoming-message handler; skipping it is the documented DND behaviour. */
    @NonNull
    public static Method messageHandlerStart(@NonNull Context context, @NonNull ClassLoader loader)
            throws NoSuchMethodException {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(context, loader,
                "waex_wa_message_handler_start_v1",
                (bridge, cl) -> {
                    MethodData data = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .addUsingString("MessageHandler/start", StringMatchType.Equals))).firstOrNull();
                    return data != null ? data.getMethodInstance(cl) : null;
                });
        if (method == null) throw new NoSuchMethodException("MessageHandler/start");
        return method;
    }

    // ─── View once ───────────────────────────────────────────────────────

    /**
     * Implementations of the two-method interface that INSERT_VIEW_ONCE_SQL's caller invokes, whose
     * {@code (int)void} setter stores the view-once state on a message.
     */
    @NonNull
    public static Method[] viewOnceSetters(@NonNull Context context, @NonNull ClassLoader loader)
            throws NoSuchMethodException {
        Method[] methods = DexSearchEngine.getInstance().findMethodsWithCache(context, loader,
                "waex_wa_view_once_setters_v1",
                (bridge, cl) -> {
                    MethodData caller = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .addUsingString("INSERT_VIEW_ONCE_SQL", StringMatchType.Contains))).firstOrNull();
                    if (caller == null) return null;
                    List<Method> setters = new ArrayList<>();
                    for (MethodData invoke : caller.getInvokes()) {
                        Method invoked;
                        try {
                            invoked = invoke.getMethodInstance(cl);
                        } catch (Throwable ignored) {
                            continue;
                        }
                        Class<?> owner = invoked.getDeclaringClass();
                        if (!owner.isInterface() || owner.getMethods().length != 2) continue;
                        for (ClassData impl : bridge.findClass(FindClass.create().matcher(
                                ClassMatcher.create().addInterface(owner.getName())))) {
                            for (Method m : impl.getInstance(cl).getDeclaredMethods()) {
                                Class<?>[] params = m.getParameterTypes();
                                if (params.length == 1 && params[0] == int.class
                                        && m.getReturnType() == void.class
                                        && !Modifier.isAbstract(m.getModifiers())) {
                                    setters.add(m);
                                }
                            }
                        }
                    }
                    return setters.isEmpty() ? null : setters.toArray(new Method[0]);
                });
        if (methods == null || methods.length == 0) throw new NoSuchMethodException("INSERT_VIEW_ONCE_SQL setters");
        return methods;
    }

    /**
     * The media viewer's menu builder for view-once media: it references WhatsApp's
     * {@code ic_viewonce} drawable, calls MenuItem.setShowAsAction and receives the Menu.
     * Optional: returns null when this build has no such method.
     */
    @Nullable
    public static Method viewOnceMediaMenu(@NonNull Context context, @NonNull ClassLoader loader) {
        final int iconId = context.getResources().getIdentifier("ic_viewonce", "drawable", context.getPackageName());
        if (iconId == 0) return null;
        return DexSearchEngine.getInstance().findMethodWithCache(context, loader,
                "waex_wa_view_once_media_menu_v1_" + iconId,
                (bridge, cl) -> {
                    for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .usingNumbers(iconId)
                            .addInvoke("Landroid/view/MenuItem;->setShowAsAction(I)V")))) {
                        if (data.getParamCount() > 1 && data.getParamTypeNames().contains("android.view.Menu")) {
                            return data.getMethodInstance(cl);
                        }
                    }
                    return null;
                });
    }

    // ─── Receipts ────────────────────────────────────────────────────────

    /**
     * The method that sends played receipts for a Set of messages (voice notes, view once).
     * Only the Set overload sends the receipt; the single-message overload requests a media
     * re-upload and must never be blocked.
     */
    @NonNull
    public static Method sendPlayedReceipts(@NonNull Context context, @NonNull ClassLoader loader)
            throws NoSuchMethodException {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(context, loader,
                "waex_wa_send_played_receipts_v1",
                (bridge, cl) -> {
                    ClassData owner = bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                            .usingStrings("sendmethods/sendClearDirty"))).firstOrNull();
                    if (owner == null) return null;
                    for (Method m : owner.getInstance(cl).getDeclaredMethods()) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length > 0 && params[0] == Set.class && m.getReturnType() == void.class) {
                            return m;
                        }
                    }
                    return null;
                });
        if (method == null) throw new NoSuchMethodException("sendmethods/sendClearDirty Set dispatcher");
        return method;
    }

    /** ProtocolTreeNode (stanza) class. */
    @Nullable
    public static Class<?> protocolTreeNodeClass(@NonNull Context context, @NonNull ClassLoader loader) {
        return DexSearchEngine.getInstance().findClassWithCache(context, loader,
                "waex_protocol_tree_node_class",
                (bridge, cl) -> {
                    ClassData data = bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                            .usingStrings("ProtocolTreeNode/getAttributeJid"))).firstOrNull();
                    return data != null ? data.getInstance(cl) : null;
                });
    }

    /**
     * The low-level writer every outgoing stanza passes through. Used only as a last safety net
     * for delivery receipts that escaped the receipt builder hook; optional.
     */
    @Nullable
    public static Method stanzaWriter(@NonNull Context context, @NonNull ClassLoader loader) {
        final Class<?> node = protocolTreeNodeClass(context, loader);
        if (node == null) return null;
        return DexSearchEngine.getInstance().findMethodWithCache(context, loader,
                "waex_wa_stanza_writer_v1",
                (bridge, cl) -> {
                    List<MethodData> matches = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .paramTypes(node.getName(), "int")
                            .returnType("void")
                            .addInvoke("Ljava/util/zip/DeflaterOutputStream;-><init>(Ljava/io/OutputStream;)V")));
                    return matches.size() == 1 ? matches.get(0).getMethodInstance(cl) : null;
                });
    }

    // ─── Revoke ──────────────────────────────────────────────────────────

    /** The message-store revoke entry point. Two anchors, both used by WhatsApp itself. */
    @NonNull
    public static Method revokeMessage(@NonNull Context context, @NonNull ClassLoader loader)
            throws NoSuchMethodException {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(context, loader,
                "waex_wa_revoke_message_v1",
                (bridge, cl) -> {
                    for (String anchor : new String[]{"msgstore/edit/revoke", "msgstore/revoking/"}) {
                        MethodData data = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                                .addUsingString(anchor, StringMatchType.Contains))).firstOrNull();
                        if (data != null) return data.getMethodInstance(cl);
                    }
                    return null;
                });
        if (method == null) throw new NoSuchMethodException("msgstore revoke method");
        return method;
    }

    /**
     * The singleton "None" implementation of an acknowledgement interface used in revoke results
     * (e.g. NoneIncomingAck). Returning WhatsApp's own "nothing happened" value is what keeps a
     * blocked revoke from crashing the caller.
     */
    @Nullable
    public static Object noneInstanceOf(@NonNull Context context, @NonNull ClassLoader loader,
                                        @NonNull Class<?> interfaceType) {
        if (!interfaceType.isInterface()) return null;
        Class<?> none = DexSearchEngine.getInstance().findClassWithCache(context, loader,
                "waex_wa_none_impl_v1_" + interfaceType.getName(),
                (bridge, cl) -> {
                    for (ClassData data : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                            .addInterface(interfaceType.getName())
                            .addMethod(MethodMatcher.create().name("toString")
                                    .addUsingString("None", StringMatchType.Contains))))) {
                        Class<?> candidate = data.getInstance(cl);
                        if (staticSelfField(candidate) != null) return candidate;
                    }
                    return null;
                });
        if (none == null) return null;
        Field field = staticSelfField(none);
        try {
            return field != null ? field.get(null) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ─── Calls ───────────────────────────────────────────────────────────

    @NonNull
    public static Method callStateChanged(@NonNull Context context, @NonNull ClassLoader loader)
            throws NoSuchMethodException {
        return requireMethod(context, loader, "waex_voip_on_call_state_changed",
                "voip/callStateChangedOnUIThread");
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    @NonNull
    private static Method requireMethod(@NonNull Context context, @NonNull ClassLoader loader,
                                        @NonNull String cacheKey, @NonNull String anchor)
            throws NoSuchMethodException {
        Method method = DexSearchEngine.getInstance().findMethodWithCache(context, loader, cacheKey,
                (bridge, cl) -> {
                    MethodData data = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .addUsingString(anchor, StringMatchType.Contains))).firstOrNull();
                    return data != null ? data.getMethodInstance(cl) : null;
                });
        if (method == null) throw new NoSuchMethodException(anchor);
        return method;
    }

    @Nullable
    private static DexKitBridge bridge(@NonNull Context context) {
        DexSearchEngine engine = DexSearchEngine.getInstance();
        if (engine.getBridge() == null) engine.initialize(context);
        return engine.getBridge();
    }

    @Nullable
    static Field findFieldOfType(@NonNull Class<?> owner, @NonNull Class<?> type) {
        Field found = null;
        for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
                // The last declared match is the message's own key (earlier ones are quoted keys).
                found = f;
            }
            if (found != null) break;
        }
        if (found != null) found.setAccessible(true);
        return found;
    }

    @Nullable
    private static Field staticSelfField(@NonNull Class<?> type) {
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == type) {
                f.setAccessible(true);
                return f;
            }
        }
        return null;
    }
}
