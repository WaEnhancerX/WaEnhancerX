package com.waenhancer.xposed.features.media;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Proximity Sensor Hook:
 * Prevents screen turning off when playing audio / voice notes by suppressing proximity sensor events.
 */
public class ProximitySensorHook extends BaseFeature {

    private static final String TAG = "[WAEX][ProximitySensor]";
    private static final String PREF_KEY = "disable_sensor_proximity";

    public ProximitySensorHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookProximitySensor();
    }

    private void hookProximitySensor() {
        try {
            Class<?> proximityClass = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager$SensorEventQueue", classLoader);
            if (proximityClass == null) {
                proximityClass = XposedHelpers.findClassIfExists("android.hardware.SensorManager", classLoader);
            }

            if (proximityClass != null) {
                XposedBridge.hookAllMethods(proximityClass, "dispatchSensorEvent", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isProximitySensorDisabled()) return;

                        if (param.args.length > 0 && param.args[0] instanceof Integer) {
                            int handle = (int) param.args[0];
                            // Suppress proximity event dispatch
                            if (param.args.length > 1 && param.args[1] instanceof float[]) {
                                float[] values = (float[]) param.args[1];
                                if (values.length > 0) {
                                    values[0] = 100.0f; // Force far distance (screen remains on)
                                }
                            }
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked proximity sensor event dispatcher.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking Proximity Sensor: " + t.getMessage());
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
