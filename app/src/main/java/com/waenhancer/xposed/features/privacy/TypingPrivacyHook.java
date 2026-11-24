package com.waenhancer.xposed.features.privacy;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.wa.WaJid;
import com.waenhancer.xposed.core.wa.WaTargets;
import de.robv.android.xposed.XC_MethodHook;
import com.waenhancer.xposed.core.privacy.PrivacyToggle;
import com.waenhancer.xposed.core.wa.SafeHooks;
import java.lang.reflect.Method;

/**
 * Typing Privacy: skips WhatsApp's outgoing "composing" (typing) and "recording" presence.
 *
 * The target is HandleMeComposing/sendComposing. Its int argument is the state
 * (0 = typing, 1 = recording) and its Jid argument is the chat. Both are located by parameter
 * type once, at hook time, so the callback never guesses from runtime values.
 */
public class TypingPrivacyHook extends BaseFeature {

    private static final int STATE_TYPING = 0;
    private static final int STATE_RECORDING = 1;

    public TypingPrivacyHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        Method sendComposing = WaTargets.sendComposing(context, classLoader);
        SafeHooks.requireVoid(sendComposing);
        final int stateIndex = firstIntParameter(sendComposing);
        if (stateIndex < 0) throw new NoSuchMethodException("sendComposing state parameter");

        XposedBridge.hookMethod(sendComposing, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object rawState = param.args[stateIndex];
                if (!(rawState instanceof Integer)) return;
                int state = (Integer) rawState;
                if (state != STATE_TYPING && state != STATE_RECORDING) return;
                String chat = findChatJid(param.args);
                if (SafeHooks.test(() -> shouldHide(state, chat))) {
                    param.setResult(null);
                }
            }
        });
    }

    private boolean shouldHide(int state, @Nullable String chatJid) {
        PrivacyToggle toggle = state == STATE_TYPING ? PrivacyToggle.HIDE_TYPING : PrivacyToggle.HIDE_RECORDING;
        return getPrivacy().appliesTo(context, toggle, chatJid);
    }

    @Nullable
    private static String findChatJid(@NonNull Object[] args) {
        for (Object arg : args) {
            if (WaJid.isJid(arg)) return WaJid.raw(arg);
        }
        return null;
    }

    private static int firstIntParameter(@NonNull Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class || types[i] == Integer.class) return i;
        }
        return -1;
    }

    @NonNull
    @Override
    public String getName() {
        return "Typing Privacy";
    }
}
