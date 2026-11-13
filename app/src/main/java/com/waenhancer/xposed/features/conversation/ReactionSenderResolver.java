package com.waenhancer.xposed.features.conversation;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XposedBridge;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/**
 * Locates WhatsApp's "send/remove a reaction on a message" entry point without relying on
 * obfuscated names, parameter order or one exact signature.
 *
 * <p>Anchors, in order of trust:
 * <ol>
 *   <li><b>Behaviour.</b> The routine that builds the reaction add-on message uses the reaction
 *       add-on type constant and takes the message, the emoji text and a flag. Methods carrying
 *       that constant and those parameter kinds are the "core". When a thin public wrapper calls
 *       the core with exactly (message, text, flag) it is preferred, otherwise the core itself is
 *       used; {@link DoubleTapReactionHook} binds arguments by type, so extra or reordered
 *       parameters are tolerated.</li>
 *   <li><b>Signature.</b> Only if the behavioural anchor finds nothing: every method taking
 *       (message, text, flag) is collected. A single match, or a single public method returning
 *       boolean, is accepted. Anything ambiguous is rejected rather than guessed, because
 *       invoking the wrong host method is worse than the feature being inactive.</li>
 * </ol>
 * Results are persisted by {@link DexSearchEngine#findMethodWithCache} per WhatsApp build.
 */
final class ReactionSenderResolver {

    private static final String TAG = "[WAEX:DoubleTapReaction]";
    /** message_add_on_type of a reaction. */
    private static final int REACTION_ADD_ON_TYPE = 56;
    private static final String SENDER_CACHE_KEY = "waex_reaction_sender_v2";
    private static final String MESSAGE_CACHE_KEY = "waex_fmessage_core";
    private static final String[] MESSAGE_ANCHORS = {
            "FMessage/getSenderUserJid/key.id",
            "FMessage/getSenderUserJid",
    };

    private ReactionSenderResolver() {}

    /** The message base class (FMessage), or null when no anchor matches. */
    @Nullable
    static Class<?> resolveMessageClass(@NonNull Context context, @NonNull ClassLoader classLoader) {
        return DexSearchEngine.getInstance().findClassWithCache(
                context, classLoader, MESSAGE_CACHE_KEY,
                (bridge, loader) -> {
                    for (String anchor : MESSAGE_ANCHORS) {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString(anchor, StringMatchType.Contains)))
                                .firstOrNull();
                        if (data != null) return data.getMethodInstance(loader).getDeclaringClass();
                    }
                    return null;
                });
    }

    /** The reaction entry point, or null when it cannot be identified unambiguously. */
    @Nullable
    static Method resolveSender(@NonNull Context context, @NonNull ClassLoader classLoader,
                                @Nullable Class<?> messageClass) {
        return DexSearchEngine.getInstance().findMethodWithCache(
                context, classLoader, SENDER_CACHE_KEY,
                (bridge, loader) -> {
                    Method viaBehaviour = fromReactionCore(bridge, loader, messageClass);
                    if (viaBehaviour != null) return viaBehaviour;
                    return fromSignature(bridge, loader, messageClass);
                });
    }

    /** The parameter that carries the message, used to infer the message class when no anchor hit. */
    @Nullable
    static Class<?> messageParameter(@NonNull Method sender) {
        Class<?> declaring = sender.getDeclaringClass();
        Class<?> best = null;
        for (Class<?> type : sender.getParameterTypes()) {
            if (type.isPrimitive() || type == String.class || type == declaring
                    || type.getName().startsWith("java.")) {
                continue;
            }
            if (best == null || best.isAssignableFrom(type)) best = type;
        }
        return best;
    }

    // ───────────────────────────── behavioural anchor ─────────────────────────────

    /**
     * The anchor is the reaction add-on constant plus a parameter that carries the message. Parameter
     * <em>kinds</em> only narrow the field: the strictest stage (message + text + flag) runs first and a
     * looser stage runs only when the stricter one finds nothing, so a host change that drops or
     * retypes the flag or the text parameter degrades the match instead of breaking it.
     */
    @Nullable
    private static Method fromReactionCore(DexKitBridge bridge, ClassLoader loader,
                                           @Nullable Class<?> messageClass) throws Throwable {
        MethodDataList cores = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .addUsingNumber(REACTION_ADD_ON_TYPE)
                        .paramCount(2, 10)));

        List<Method> loaded = new ArrayList<>();
        List<MethodData> data = new ArrayList<>();
        for (MethodData core : cores) {
            Method method;
            try {
                method = core.getMethodInstance(loader);
            } catch (Throwable unloadable) {
                continue;
            }
            if (!carriesMessage(method, messageClass)) continue;
            loaded.add(method);
            data.add(core);
        }

        for (int stage = 0; stage < 3; stage++) {
            List<Method> picked = new ArrayList<>();
            for (int k = 0; k < loaded.size(); k++) {
                Method core = loaded.get(k);
                if (!matchesStage(core, stage)) continue;
                Method wrapper = bestWrapper(data.get(k), loader, messageClass);
                picked.add(wrapper != null ? wrapper : core);
            }
            if (picked.size() == 1) return picked.get(0);
            if (picked.size() > 1) {
                XposedBridge.log(TAG + " Reaction entry point ambiguous (" + picked.size()
                        + " candidates, stage " + stage + ")");
                return null;
            }
        }
        return null;
    }

    /** Stage 0: message + text + flag; 1: message + text; 2: message only. */
    private static boolean matchesStage(Method method, int stage) {
        if (stage >= 2) return true;
        boolean text = false;
        boolean flag = false;
        for (Class<?> p : method.getParameterTypes()) {
            if (p == String.class) text = true;
            if (p == boolean.class || p == Boolean.class) flag = true;
        }
        return stage == 0 ? text && flag : text;
    }

    /**
     * A thin public caller of the core that also carries the message. The simplest one wins (fewest
     * parameters, then a text parameter, then a boolean result); a tie means no wrapper is trusted.
     */
    @Nullable
    private static Method bestWrapper(MethodData core, ClassLoader loader, @Nullable Class<?> messageClass) {
        Method best = null;
        int bestScore = Integer.MIN_VALUE;
        boolean tie = false;
        try {
            for (MethodData caller : core.getCallers()) {
                Method candidate;
                try {
                    candidate = caller.getMethodInstance(loader);
                } catch (Throwable unloadable) {
                    continue;
                }
                int modifiers = candidate.getModifiers();
                if (!Modifier.isPublic(modifiers) || Modifier.isAbstract(modifiers)
                        || Modifier.isStatic(modifiers) || !carriesMessage(candidate, messageClass)) {
                    continue;
                }
                int score = 100 - candidate.getParameterTypes().length * 10;
                for (Class<?> p : candidate.getParameterTypes()) if (p == String.class) score += 5;
                if (candidate.getReturnType() == boolean.class) score += 1;
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                    tie = false;
                } else if (score == bestScore) {
                    tie = true;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return tie ? null : best;
    }

    private static boolean isDirectSender(Method method, @Nullable Class<?> messageClass) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isAbstract(modifiers)) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        if (params.length != 3) return false;
        int strings = 0;
        int flags = 0;
        int messages = 0;
        for (Class<?> p : params) {
            if (p == String.class) strings++;
            else if (p == boolean.class) flags++;
            else if (isMessageType(p, messageClass)) messages++;
        }
        return strings == 1 && flags == 1 && messages == 1;
    }

    private static boolean carriesMessage(Method method, @Nullable Class<?> messageClass) {
        for (Class<?> p : method.getParameterTypes()) {
            if (isMessageType(p, messageClass)) return true;
        }
        return false;
    }

    private static boolean isMessageType(Class<?> type, @Nullable Class<?> messageClass) {
        if (type.isPrimitive() || type == String.class || type.getName().startsWith("java.")) return false;
        return messageClass != null ? type.isAssignableFrom(messageClass) : true;
    }

    // ───────────────────────────── signature fallback ─────────────────────────────

    @Nullable
    private static Method fromSignature(DexKitBridge bridge, ClassLoader loader,
                                        @Nullable Class<?> messageClass) throws Throwable {
        if (messageClass == null) return null;
        MethodDataList matches = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create().paramCount(3, 3)));
        List<Method> candidates = new ArrayList<>();
        for (MethodData data : matches) {
            List<String> names = data.getParamTypeNames();
            if (!names.contains("java.lang.String") || !names.contains("boolean")) continue;
            Method method;
            try {
                method = data.getMethodInstance(loader);
            } catch (Throwable unloadable) {
                continue;
            }
            if (isDirectSender(method, messageClass)) candidates.add(method);
        }
        if (candidates.size() == 1) return candidates.get(0);

        List<Method> booleans = new ArrayList<>();
        for (Method m : candidates) {
            if (m.getReturnType() == boolean.class && Modifier.isFinal(m.getModifiers())) booleans.add(m);
        }
        if (booleans.size() == 1) return booleans.get(0);

        if (!candidates.isEmpty()) {
            XposedBridge.log(TAG + " Reaction signature fallback ambiguous (" + candidates.size()
                    + " candidates)");
        }
        return null;
    }
}
