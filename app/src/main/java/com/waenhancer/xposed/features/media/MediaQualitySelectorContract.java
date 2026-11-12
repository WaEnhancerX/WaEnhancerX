package com.waenhancer.xposed.features.media;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/**
 * Resolves the raw gallery flag reader, never the aggregate eligibility predicate.
 * The supported instruction/call shapes were inspected in Messenger 2.26.43.76.
 * Renamed members are acceptable; changed or ambiguous semantics are not guessed.
 */
final class MediaQualitySelectorContract {
    static final String FLAG = "show_media_quality_toggle";
    private static final List<Integer> MERGED_FLAG_OPS = Arrays.asList(
            0x71,0x0a,0x39,0x54,0x38,0x6e,0x0a,0x12,0x38,0x12,0x0f);
    private static final List<Integer> INTENT_FLAG_OPS = Arrays.asList(
            0x6e,0x0c,0x38,0x6e,0x0c,0x38,0x6e,0x0a,0x12,0x33,0x6e,0x0a,0x0f,0x0f);

    private MediaQualitySelectorContract() {}

    static Method resolve(DexKitBridge bridge, ClassLoader loader) throws ReflectiveOperationException {
        if (bridge == null) return null;
        MethodData predicateData = null;
        Method predicate = null;
        for (MethodData data : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                .paramCount(0).returnType(boolean.class).addUsingString(FLAG,StringMatchType.Equals)))) {
            if (!data.isMethod()) continue;
            Method candidate = data.getMethodInstance(loader);
            if (!concrete(candidate,false) || candidate.getReturnType()!=boolean.class
                    || candidate.getParameterTypes().length!=0) continue;
            if (predicate != null && !predicate.equals(candidate)) return null;
            predicate=candidate;predicateData=data;
        }
        if (predicate == null) return null;
        Method unique = null;
        for (MethodData invoked : predicateData.getInvokes()) {
            if (!invoked.isMethod()) continue;
            Method helper=invoked.getMethodInstance(loader);
            if (!rawShape(helper,predicate.getDeclaringClass())) continue;
            if (!verifiedRawGetter(invoked,helper,loader)) continue;
            if (unique != null && !unique.equals(helper)) return null;
            unique=helper;
        }
        return unique;
    }

    static boolean rawShape(Method method,Class<?> owner) {
        if (!concrete(method,true) || method.getDeclaringClass()!=owner
                || method.getReturnType()!=boolean.class) return false;
        Class<?>[] p=method.getParameterTypes();
        return p.length==3 && p[0]==owner && p[1]==String.class && p[2]==boolean.class;
    }

    private static boolean verifiedRawGetter(MethodData data,Method method,ClassLoader loader)
            throws ReflectiveOperationException {
        if (!MERGED_FLAG_OPS.equals(data.getOpCodes()) || !data.getUsingStrings().isEmpty()) return false;
        List<UsingFieldData> fields=data.getUsingFields();
        if (fields.size()!=1) return false;
        Field bundle=fields.get(0).getField().getFieldInstance(loader);
        if (Modifier.isStatic(bundle.getModifiers()) || bundle.isSynthetic()
                || !bundle.getType().getName().equals("android.os.Bundle")
                || !bundle.getDeclaringClass().isAssignableFrom(method.getDeclaringClass())) return false;
        Set<Method> seen=new LinkedHashSet<>();
        MethodData delegateData=null;Method delegate=null;boolean bundleRead=false;
        for (MethodData invoked:data.getInvokes()) {
            if (!invoked.isMethod()) return false;
            Method call=invoked.getMethodInstance(loader);
            if (!seen.add(call)) continue;
            if (platformBoolean(call,"android.os.BaseBundle","getBoolean")) bundleRead=true;
            else if (rawShape(call,method.getDeclaringClass()) && !call.equals(method)) {
                if (delegate != null && !delegate.equals(call)) return false;
                delegate=call;delegateData=invoked;
            } else return false;
        }
        return seen.size()==2 && bundleRead && delegate!=null
                && verifiedIntentGetter(delegateData,delegate,loader);
    }

    private static boolean verifiedIntentGetter(MethodData data,Method method,ClassLoader loader)
            throws ReflectiveOperationException {
        if (!INTENT_FLAG_OPS.equals(data.getOpCodes()) || !data.getUsingStrings().isEmpty()
                || !data.getUsingFields().isEmpty()) return false;
        Set<Method> seen=new LinkedHashSet<>();int getter=0,intent=0,hasExtra=0,booleanExtra=0;
        for (MethodData invoked:data.getInvokes()) {
            if (!invoked.isMethod()) return false;
            Method call=invoked.getMethodInstance(loader);
            if (!seen.add(call)) continue;
            String owner=call.getDeclaringClass().getName();Class<?>[] p=call.getParameterTypes();
            if (owner.equals("androidx.fragment.app.Fragment") && concrete(call,false)
                    && p.length==0 && isActivity(call.getReturnType())) getter++;
            else if (owner.equals("android.app.Activity") && call.getName().equals("getIntent")
                    && p.length==0 && call.getReturnType().getName().equals("android.content.Intent")) intent++;
            else if (owner.equals("android.content.Intent") && call.getName().equals("hasExtra")
                    && p.length==1 && p[0]==String.class && call.getReturnType()==boolean.class) hasExtra++;
            else if (platformBoolean(call,"android.content.Intent","getBooleanExtra")) booleanExtra++;
            else return false;
        }
        return seen.size()==4 && getter==1 && intent==1 && hasExtra==1 && booleanExtra==1;
    }

    private static boolean platformBoolean(Method method,String owner,String name) {
        Class<?>[] p=method.getParameterTypes();
        return !Modifier.isStatic(method.getModifiers()) && method.getDeclaringClass().getName().equals(owner)
                && method.getName().equals(name) && method.getReturnType()==boolean.class
                && p.length==2 && p[0]==String.class && p[1]==boolean.class;
    }

    private static boolean isActivity(Class<?> type) {
        for (Class<?> c=type;c!=null;c=c.getSuperclass()) {
            if (c.getName().equals("android.app.Activity")) return true;
        }
        return false;
    }

    private static boolean concrete(Method method,boolean requireStatic) {
        if (method==null) return false;
        int flags=method.getModifiers();
        return Modifier.isStatic(flags)==requireStatic && !Modifier.isAbstract(flags)
                && !Modifier.isNative(flags) && !method.isBridge() && !method.isSynthetic();
    }
}
