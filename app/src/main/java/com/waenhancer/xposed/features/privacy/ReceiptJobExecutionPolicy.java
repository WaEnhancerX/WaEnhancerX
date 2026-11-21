package com.waenhancer.xposed.features.privacy;

import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Shape checks complement (not replace) unique semantic DEX resolution. */
public final class ReceiptJobExecutionPolicy {
    private ReceiptJobExecutionPolicy() {}

    public static boolean isExecutionMethod(Class<?> jobClass, Method method) {
        if (jobClass == null || method == null) return false;
        Class<?> owner = method.getDeclaringClass();
        // Current hosts can inherit execution from the direct job superclass.
        if (owner != jobClass && owner != jobClass.getSuperclass()) return false;
        int flags = method.getModifiers();
        return Modifier.isPublic(flags) && !Modifier.isStatic(flags)
                && !Modifier.isAbstract(flags) && !Modifier.isNative(flags)
                && !method.isBridge() && !method.isSynthetic()
                && method.getReturnType() == void.class && method.getParameterTypes().length == 0;
    }

    public static boolean isExecutionCall(Class<?> jobClass, Method selected, Member called,
                                          Object receiver, Object[] args) {
        return isExecutionMethod(jobClass, selected) && selected.equals(called)
                && jobClass.isInstance(receiver) && (args == null || args.length == 0);
    }
}
