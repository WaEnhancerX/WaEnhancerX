package com.waenhancer.xposed.features.privacy;

import android.app.Activity;
import android.content.Context;
import android.view.MenuItem;
import android.view.View;
import com.waenhancer.utils.ContactIdentityPolicy;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/**
 * The outgoing conversation operations shared by classic and popup call menus.
 * No numeric menu ID, obfuscated method name, arbitrary Activity extra, or cached
 * "last chat" participates in selecting a recipient.
 */
final class CallTypeHostContract {
    private static final Class<?>[] CALL_ARGUMENTS = {
            boolean.class, boolean.class, boolean.class
    };
    private static final Set<String> LIFECYCLE_STATES = new HashSet<>(Arrays.asList(
            "DESTROYED", "INITIALIZED", "CREATED", "STARTED", "RESUMED"));
    final List<Site> sites;

    private CallTypeHostContract(List<Site> sites) {
        this.sites = java.util.Collections.unmodifiableList(sites);
    }

    static CallTypeHostContract discover(Context context, ClassLoader loader) throws Throwable {
        DexSearchEngine engine = DexSearchEngine.getInstance();
        engine.initialize(context);
        DexKitBridge bridge = engine.getBridge();
        if (bridge == null) throw new IllegalStateException("Call selector: DexKit is unavailable");
        Class<?> menu = engine.findClassWithCache(context, loader,
                "call_type_contact_menu_v1", (dex, cl) -> {
                    Set<Class<?>> owners = new LinkedHashSet<>();
                    for (MethodData data : dex.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                    "contactconversationmenu/oncreateoptionsmenu",
                                    StringMatchType.Equals)))) {
                        owners.add(data.getMethodInstance(cl).getDeclaringClass());
                    }
                    if (owners.size() != 1) {
                        throw new NoSuchMethodException("Call selector: contact menu is missing or ambiguous");
                    }
                    return owners.iterator().next();
                });
        if (menu == null) throw new NoSuchMethodException("Call selector: contact menu is unavailable");
        Method selection = menu.getDeclaredMethod("onOptionsItemSelected", MenuItem.class);
        requireInstance(selection, boolean.class, false);
        Set<Method> calls = outgoingInvokes(bridge.getMethodData(selection), loader);
        if (calls.size() != 2) {
            throw new NoSuchMethodException("Call selector: expected two outgoing conversation operations");
        }
        Method chooser = engine.findMethodWithCache(context, loader,
                "call_type_popup_dispatch_v1", (dex, cl) -> {
                    Set<Method> candidates = new LinkedHashSet<>();
                    for (MethodData data : dex.findMethod(FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                    "CallMenuHelper/onPopupMenuEventListener unknown menu item id ",
                                    StringMatchType.Equals)))) {
                        candidates.add(data.getMethodInstance(cl));
                    }
                    if (candidates.size() != 1) {
                        throw new NoSuchMethodException("Call selector: popup dispatch is missing or ambiguous");
                    }
                    return candidates.iterator().next();
                });
        if (chooser == null || !Modifier.isStatic(chooser.getModifiers())
                || chooser.getReturnType() != void.class
                || !Arrays.equals(chooser.getParameterTypes(), new Class<?>[] {
                        chooser.getDeclaringClass(), int.class})
                || !outgoingInvokes(bridge.getMethodData(chooser), loader).equals(calls)) {
            throw new NoSuchMethodException("Call selector: popup and classic operations do not agree");
        }
        boolean opensChooser = false;
        for (MethodData invoke : bridge.getMethodData(selection).getInvokes()) {
            if (!invoke.isMethod()) continue;
            if (!invoke.getReturnTypeName().equals("void")
                    || !invoke.getParamTypeNames().equals(Arrays.asList(
                            "android.view.View", "java.lang.Integer", "boolean"))) continue;
            Method method = invoke.getMethodInstance(loader);
            if (method.getDeclaringClass() == chooser.getDeclaringClass()
                    && !Modifier.isStatic(method.getModifiers())) opensChooser = true;
        }
        if (!opensChooser) throw new NoSuchMethodException("Call selector: popup ownership is unverified");
        return resolve(new ArrayList<>(calls),
                Class.forName("com.whatsapp.Conversation", false, loader),
                Class.forName("com.whatsapp.conversation.ConversationFragment", false, loader),
                Class.forName("com.whatsapp.infra.core.jid.Jid", false, loader));
    }

    private static Set<Method> outgoingInvokes(MethodData data, ClassLoader loader)
            throws ReflectiveOperationException {
        if (data == null) throw new NoSuchMethodException("Call selector: missing executable menu body");
        Set<Method> methods = new LinkedHashSet<>();
        for (MethodData invoke : data.getInvokes()) {
            if (!invoke.isMethod() || !invoke.getReturnTypeName().equals("void")
                    || !invoke.getParamTypeNames().equals(Arrays.asList("boolean", "boolean", "boolean"))) {
                continue;
            }
            Method method = invoke.getMethodInstance(loader);
            if (method.getDeclaringClass().isInterface()
                    && !Modifier.isStatic(method.getModifiers())) methods.add(method);
        }
        return methods;
    }

    /** Reflection part also used by fixtures built from the verified host contract. */
    static CallTypeHostContract resolve(List<Method> calls, Class<?> activityOwner,
                                        Class<?> fragmentOwner, Class<?> jid)
            throws ReflectiveOperationException {
        if (calls.size() != 2 || new HashSet<>(calls).size() != 2
                || !Activity.class.isAssignableFrom(activityOwner)) {
            throw new NoSuchMethodException("Call selector: unsupported conversation owners");
        }
        Class<?> api = calls.get(0).getDeclaringClass();
        for (Method call : calls) {
            if (call.getDeclaringClass() != api || !api.isInterface()
                    || Modifier.isStatic(call.getModifiers()) || call.getReturnType() != void.class
                    || !Arrays.equals(call.getParameterTypes(), CALL_ARGUMENTS)) {
                throw new NoSuchMethodException("Call selector: unsupported outgoing method shape");
            }
        }
        Method apiPeer = api.getMethod("getChatJid");
        if (!jid.isAssignableFrom(apiPeer.getReturnType())
                || Modifier.isStatic(apiPeer.getModifiers())) {
            throw new NoSuchMethodException("Call selector: current conversation JID is unavailable");
        }
        Method raw = jid.getDeclaredMethod("getRawString");
        requireInstance(raw, String.class, false);
        raw.setAccessible(true);
        Field adapterField = null;
        for (Field field : fragmentOwner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && View.class.isAssignableFrom(field.getType())
                    && api.isAssignableFrom(field.getType())) {
                if (adapterField != null) {
                    throw new NoSuchFieldException("Call selector: multiple fragment conversation adapters");
                }
                adapterField = field;
            }
        }
        if (adapterField == null) throw new NoSuchFieldException("Call selector: fragment adapter is unavailable");
        adapterField.setAccessible(true);
        List<Site> sites = new ArrayList<>();
        for (Class<?> owner : new Class<?>[] {activityOwner, adapterField.getType()}) {
            if (!api.isAssignableFrom(owner)) {
                throw new NoSuchMethodException("Call selector: conversation does not implement outgoing API");
            }
            Method peer = owner.getMethod("getChatJid");
            requireInstance(peer, apiPeer.getReturnType(), false);
            Method lifecycle = owner.getMethod("getLifecycle");
            requireInstance(lifecycle, lifecycle.getReturnType(), false);
            Method state = findLifecycleState(lifecycle.getReturnType());
            Method activity = null;
            Method host = null;
            Field field = null;
            if (!Activity.class.isAssignableFrom(owner)) {
                activity = owner.getMethod("getActivityNullable");
                requireInstance(activity, activity.getReturnType(), false);
                if (!Activity.class.isAssignableFrom(activity.getReturnType())) {
                    throw new NoSuchMethodException("Call selector: adapter Activity is unavailable");
                }
                host = owner.getMethod("getHost");
                requireInstance(host, host.getReturnType(), false);
                if (!host.getReturnType().isAssignableFrom(fragmentOwner)) {
                    throw new NoSuchMethodException("Call selector: adapter Fragment is unavailable");
                }
                field = adapterField;
            }
            for (Method declaration : calls) {
                Method operation = owner.getDeclaredMethod(declaration.getName(), CALL_ARGUMENTS);
                requireInstance(operation, void.class, false);
                operation.setAccessible(true);
                sites.add(new Site(operation, peer, raw, lifecycle, state, activity, host, field));
            }
        }
        return new CallTypeHostContract(sites);
    }

    private static Method findLifecycleState(Class<?> lifecycle) throws NoSuchMethodException {
        Method result = null;
        for (Method method : lifecycle.getMethods()) {
            if (method.getParameterTypes().length != 0 || !method.getReturnType().isEnum()
                    || Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic()) continue;
            Set<String> values = new HashSet<>();
            for (Object value : method.getReturnType().getEnumConstants()) values.add(((Enum<?>) value).name());
            if (!values.equals(LIFECYCLE_STATES)) continue;
            if (result != null) throw new NoSuchMethodException("Call selector: ambiguous lifecycle state");
            result = method;
        }
        if (result == null) throw new NoSuchMethodException("Call selector: lifecycle state is unavailable");
        result.setAccessible(true);
        return result;
    }

    private static void requireInstance(Method method, Class<?> returns, boolean abstractAllowed)
            throws NoSuchMethodException {
        int flags = method.getModifiers();
        if (method.getReturnType() != returns || Modifier.isStatic(flags) || Modifier.isNative(flags)
                || (!abstractAllowed && Modifier.isAbstract(flags)) || method.isBridge() || method.isSynthetic()) {
            throw new NoSuchMethodException("Call selector: unsupported method shape");
        }
        method.setAccessible(true);
    }

    static final class Site {
        final Method operation;
        private final Method peer;
        private final Method raw;
        private final Method lifecycle;
        private final Method state;
        private final Method activity;
        private final Method host;
        private final Field adapter;

        Site(Method operation, Method peer, Method raw, Method lifecycle, Method state,
             Method activity, Method host, Field adapter) {
            this.operation = operation;
            this.peer = peer;
            this.raw = raw;
            this.lifecycle = lifecycle;
            this.state = state;
            this.activity = activity;
            this.host = host;
            this.adapter = adapter;
        }

        Snapshot read(Object owner) throws ReflectiveOperationException {
            if (owner == null || owner.getClass() != operation.getDeclaringClass()) return null;
            Object currentState = state.invoke(lifecycle.invoke(owner));
            if (!(currentState instanceof Enum<?>) || !((Enum<?>) currentState).name().equals("RESUMED")) return null;
            Activity currentActivity;
            if (activity == null) {
                currentActivity = (Activity) owner;
            } else {
                Object fragment = host.invoke(owner);
                if (!adapter.getDeclaringClass().isInstance(fragment) || adapter.get(fragment) != owner) return null;
                View view = (View) owner;
                if (!view.isAttachedToWindow() || !view.isShown()) return null;
                currentActivity = (Activity) activity.invoke(owner);
            }
            if (currentActivity == null || currentActivity.isFinishing() || currentActivity.isDestroyed()
                    || !currentActivity.getWindow().getDecorView().isAttachedToWindow()) return null;
            Object jid = peer.invoke(owner);
            if (!raw.getDeclaringClass().isInstance(jid)) return null;
            String canonical = ContactIdentityPolicy.canonicalJid((String) raw.invoke(jid));
            if (canonical == null || (ContactIdentityPolicy.phoneUser(canonical) == null
                    && !ContactIdentityPolicy.isLid(canonical))) return null;
            return new Snapshot(currentActivity, canonical);
        }
    }

    static final class Snapshot {
        final Activity activity;
        final String peer;

        Snapshot(Activity activity, String peer) {
            this.activity = activity;
            this.peer = peer;
        }
    }
}
