package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.privacy.PrivacyToggle;
import com.waenhancer.xposed.core.wa.WaMessage;
import com.waenhancer.xposed.core.wa.WaTargets;
import de.robv.android.xposed.XC_MethodHook;
import java.lang.reflect.Method;

/**
 * Anti View Once: stores received view-once media as normal media.
 *
 * WhatsApp writes the view-once state through the {@code (int)void} setter of the interface that
 * INSERT_VIEW_ONCE_SQL's caller invokes. Rewriting state 1 to 0 keeps the media permanent.
 * Messages the user sends are never touched: rewriting those would send the media as a normal,
 * permanent message, defeating the user's own view-once choice.
 */
public class AntiViewOnceHook extends BaseFeature {

    public AntiViewOnceHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        WaMessage.init(context, classLoader);
        WaMessage.requireKeyContract();
        XC_MethodHook callback = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!getPrivacy().isOn(PrivacyToggle.ANTI_VIEW_ONCE)) return;
                if (!(param.args[0] instanceof Integer) || (Integer) param.args[0] != 1) return;
                // Only incoming messages. Unknown ownership is left untouched.
                if (!Boolean.FALSE.equals(WaMessage.isFromMe(param.thisObject))) return;
                param.args[0] = 0;
            }
        };
        for (Method setter : WaTargets.viewOnceSetters(context, classLoader)) {
            XposedBridge.hookMethod(setter, callback);
        }
    }

    @NonNull
    @Override
    public String getName() {
        return "Anti-View Once";
    }
}
