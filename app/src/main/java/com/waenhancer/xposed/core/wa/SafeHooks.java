package com.waenhancer.xposed.core.wa;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.HookRegistrar;
import com.waenhancer.xposed.utils.HookErrors;

import de.robv.android.xposed.XC_MethodHook;

import java.lang.reflect.Method;
import java.util.function.BooleanSupplier;

/**
 * Hook installation helpers that encode the two rules which keep WhatsApp from crashing:
 * <ol>
 *   <li>Only a {@code void} method may be skipped. Skipping anything else hands the caller a
 *       null or wrong-typed value, which is the classic "works until the next update" crash.
 *       The check happens at install time, so a changed signature turns into a FAILED feature,
 *       never a crash.</li>
 *   <li>The condition is evaluated live on every call and any error inside it lets WhatsApp
 *       run normally. That is also what lets quick actions switch features at runtime.</li>
 * </ol>
 * Hooks go through {@link HookRegistrar#BRIDGE}, so they belong to the feature being installed
 * and are removed with it.
 */
public final class SafeHooks {

    private static final String TAG = "SafeHooks";

    private SafeHooks() {}

    public static void requireVoid(@NonNull Method method) throws NoSuchMethodException {
        if (method.getReturnType() != void.class) {
            throw new NoSuchMethodException("Expected a void method, WhatsApp changed it: " + method);
        }
    }

    /** Skip a void method while {@code condition} is true. */
    @NonNull
    public static XC_MethodHook.Unhook skipWhile(@NonNull Method method, @NonNull BooleanSupplier condition)
            throws NoSuchMethodException {
        requireVoid(method);
        return HookRegistrar.BRIDGE.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (test(condition)) param.setResult(null);
            }
        });
    }

    /** Evaluates a live condition; an error means "do not interfere". */
    public static boolean test(@NonNull BooleanSupplier condition) {
        try {
            return condition.getAsBoolean();
        } catch (Throwable t) {
            HookErrors.swallowed(TAG, t);
            return false;
        }
    }
}
