package com.waenhancer.xposed.features.media;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/** Conservative base-value-object contract, not a positional string/field association. */
final class ProcessImageQualityContract {
    private static final List<Integer> CONSTRUCTOR = Arrays.asList(0x70,0x59,0x59,0x59,0x0e);
    private static final List<Integer> STRINGIFIER = Arrays.asList(
            0x52,0x52,0x52,0x1a,0x71,0x0c,0x1a,0x6e,0x6e,0x1a,0x71,0x0c,0x11);
    private ProcessImageQualityContract() {}

    static List<Field> resolve(DexKitBridge bridge,Class<?> type,ClassLoader loader)
            throws ReflectiveOperationException {
        if (bridge==null || type==null || type.getSuperclass()!=Object.class
                || type.isInterface() || Modifier.isAbstract(type.getModifiers())) return null;
        Constructor<?>[] constructors=type.getDeclaredConstructors();
        if (constructors.length!=1 || !Arrays.equals(constructors[0].getParameterTypes(),
                new Class<?>[]{int.class,int.class,int.class})) return null;
        Method printable=type.getDeclaredMethod("toString");
        if (printable.getReturnType()!=String.class || Modifier.isStatic(printable.getModifiers())
                || Modifier.isNative(printable.getModifiers()) || Modifier.isAbstract(printable.getModifiers())) return null;
        MethodData ctor=bridge.getMethodData(constructors[0]);
        MethodData string=bridge.getMethodData(printable);
        if (ctor==null || string==null || !CONSTRUCTOR.equals(ctor.getOpCodes())
                || !STRINGIFIER.equals(string.getOpCodes())) return null;
        Set<String> labels=new LinkedHashSet<>(string.getUsingStrings());
        if (!labels.equals(new LinkedHashSet<>(Arrays.asList(
                "ProcessImageQuality(maxKb=",", quality=",", maxEdge=")))) return null;
        Set<Field> reads=fields(string,type,loader),writes=fields(ctor,type,loader);
        if (reads==null || writes==null || reads.size()!=3 || !reads.equals(writes)) return null;
        if (ctor.getInvokes().size()!=1 || !ctor.getInvokes().get(0).isConstructor()) return null;
        Constructor<?> superCall=ctor.getInvokes().get(0).getConstructorInstance(loader);
        if (superCall.getDeclaringClass()!=Object.class || superCall.getParameterTypes().length!=0) return null;
        // The only permitted construction is Object.<init> followed by the three
        // int assignments. No delegated constructor or subsequent derived state.
        List<Field> result=new ArrayList<>(reads);
        for (Field field:result) field.setAccessible(true);
        return result;
    }

    private static Set<Field> fields(MethodData source,Class<?> owner,ClassLoader loader)
            throws ReflectiveOperationException {
        Set<Field> result=new LinkedHashSet<>();
        for (UsingFieldData use:source.getUsingFields()) {
            Field field=use.getField().getFieldInstance(loader);
            if (field.getDeclaringClass()!=owner || field.getType()!=int.class
                    || Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) return null;
            result.add(field);
        }
        return result;
    }
}
