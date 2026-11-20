package com.waenhancer.xposed.features.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.SparseArray;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicBoolean;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Proximity Sensor Hook:
 * Prevents proximity-driven screen-off and audio routing in the target application.
 */
public class ProximitySensorHook extends BaseFeature {

    private static final String TAG = "[WAEX][ProximitySensor]";
    private static final String PREF_KEY = "disable_sensor_proximity";
    private static final AtomicBoolean SENSOR_MAP_WARNING = new AtomicBoolean(false);
    private static final AtomicBoolean WAKE_LOCK_WARNING = new AtomicBoolean(false);
    private static final AtomicBoolean WAKE_LOCK_RESTORE_WARNING = new AtomicBoolean(false);

    public ProximitySensorHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookProximitySensor();
        hookProximityWakeLock();
    }

    private void hookProximityWakeLock() {
        try {
            final String targetPackage = context.getPackageName();
            if (targetPackage == null || targetPackage.isEmpty() || "android".equals(targetPackage)) {
                throw new IllegalStateException("A target application context is required");
            }
            final Class<?> wakeLockClass = PowerManager.WakeLock.class;
            final Method acquire = wakeLockClass.getDeclaredMethod("acquireLocked");
            if (acquire.getReturnType() != void.class || !Modifier.isPrivate(acquire.getModifiers())
                    || Modifier.isStatic(acquire.getModifiers()) || Modifier.isNative(acquire.getModifiers())) {
                throw new NoSuchMethodException("Unexpected WakeLock.acquireLocked contract");
            }
            final Field flagsField = wakeLockClass.getDeclaredField("mFlags");
            final Field tokenField = wakeLockClass.getDeclaredField("mToken");
            final Field packageField = wakeLockClass.getDeclaredField("mPackageName");
            if (flagsField.getType() != int.class || Modifier.isStatic(flagsField.getModifiers())
                    || Modifier.isFinal(flagsField.getModifiers())
                    || !IBinder.class.isAssignableFrom(tokenField.getType())
                    || Modifier.isStatic(tokenField.getModifiers()) || !Modifier.isFinal(tokenField.getModifiers())
                    || packageField.getType() != String.class || Modifier.isStatic(packageField.getModifiers())) {
                throw new NoSuchFieldException("Unexpected WakeLock acquisition fields");
            }
            flagsField.setAccessible(true);
            tokenField.setAccessible(true);
            packageField.setAccessible(true);
            acquire.setAccessible(true);
            XposedBridge.hookMethod(acquire, new XC_MethodHook() {
                private final String stateKey = ProximitySensorHook.class.getName()
                        + ".wakeLockFlags." + System.identityHashCode(this);

                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        final int originalFlags = flagsField.getInt(param.thisObject);
                        if (!ProximityWakeLockPolicy.isProximity(originalFlags)
                                || !targetPackage.equals(packageField.get(param.thisObject))) return;
                        if (!isProximitySensorDisabled()) return;
                        final Object token = tokenField.get(param.thisObject);
                        // Both AOSP acquire overloads already hold this monitor. Do not widen
                        // an unfamiliar vendor contract or race another acquisition/release.
                        if (token == null || !Thread.holdsLock(token)) {
                            warnWakeLock("Acquisition is outside the expected token monitor", null);
                            return;
                        }
                        final int replacement = ProximityWakeLockPolicy.forAcquire(originalFlags, true);
                        if (replacement == originalFlags) {
                            warnWakeLock("Proximity wake-lock has flags incompatible with PARTIAL; left unchanged", null);
                            return;
                        }
                        // Save before writing so afterHookedMethod can also restore on failure.
                        param.setObjectExtra(stateKey, new WakeLockFlags(token, originalFlags));
                        flagsField.setInt(param.thisObject, replacement);
                    } catch (Throwable failure) {
                        warnWakeLock("Unable to adapt proximity wake-lock acquisition", failure);
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final Object saved = param.getObjectExtra(stateKey);
                    if (!(saved instanceof WakeLockFlags)) return;
                    final WakeLockFlags state = (WakeLockFlags) saved;
                    try {
                        // Runs on success or throwable, still inside the acquire overload's
                        // monitor. PowerManager retains its real held/count/timeout behavior.
                        synchronized (state.token) {
                            final int current = flagsField.getInt(param.thisObject);
                            final int restored = ProximityWakeLockPolicy.restoreAfterAcquire(state.flags, current);
                            flagsField.setInt(param.thisObject, restored);
                            if (!ProximityWakeLockPolicy.isProximity(restored)) {
                                warnWakeLock("Wake-lock level changed during acquisition; preserving that change", null);
                            }
                        }
                    } catch (Throwable failure) {
                        if (WAKE_LOCK_RESTORE_WARNING.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + " Unable to restore proximity wake-lock flags: "
                                    + failure.getClass().getSimpleName());
                        }
                    } finally {
                        param.setObjectExtra(stateKey, null);
                    }
                }
            });
        } catch (Throwable failure) {
            warnWakeLock("Proximity wake-lock hook unavailable", failure);
        }
    }

    private static void warnWakeLock(String message, Throwable failure) {
        if (WAKE_LOCK_WARNING.compareAndSet(false, true)) {
            XposedBridge.log(TAG + " " + message + (failure == null ? ""
                    : ": " + failure.getClass().getSimpleName()));
        }
    }

    private static final class WakeLockFlags {
        final Object token;
        final int flags;

        WakeLockFlags(Object token, int flags) {
            this.token = token;
            this.flags = flags;
        }
    }

    private void hookProximitySensor() {
        try {
            Class<?> sensorQueue = XposedHelpers.findClassIfExists(
                    "android.hardware.SystemSensorManager$SensorEventQueue", classLoader);
            if (sensorQueue == null) return;
            XposedBridge.hookAllMethods(sensorQueue, "dispatchSensorEvent", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isProximitySensorDisabled() || param.args == null || param.args.length < 2
                            || !(param.args[0] instanceof Integer)
                            || !(param.args[1] instanceof float[])) return;
                    try {
                        // A queue carries many sensor types. Never alter accelerometer/gyro events.
                        // AOSP keeps its handle -> SensorEvent mapping in mSensorsEvents.
                        Object mapping = XposedHelpers.getObjectField(param.thisObject, "mSensorsEvents");
                        if (!(mapping instanceof SparseArray<?>)) return;
                        Object candidate = ((SparseArray<?>) mapping).get((Integer) param.args[0]);
                        if (!(candidate instanceof SensorEvent)) return;
                        Sensor sensor = ((SensorEvent) candidate).sensor;
                        if (sensor == null) return;
                        ProximityEventPolicy.replaceNearWithFar(
                                sensor.getType(), sensor.getMaximumRange(), (float[]) param.args[1]);
                    } catch (Throwable failure) {
                        // Vendor sensor queue layouts may differ: fail closed, not all-sensor mutation.
                        if (SENSOR_MAP_WARNING.compareAndSet(false, true)) {
                            XposedBridge.log(TAG + " Sensor-event mapping unavailable: "
                                    + failure.getClass().getSimpleName());
                        }
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking Proximity Sensor: " + t.getClass().getSimpleName());
        }
    }

    private boolean isProximitySensorDisabled() {
        return isEnabled(PREF_KEY, false) || isEnabled("proximity_audios", false);
    }

    @NonNull
    @Override
    public String getName() {
        return "Disable Proximity Sensor";
    }
}
