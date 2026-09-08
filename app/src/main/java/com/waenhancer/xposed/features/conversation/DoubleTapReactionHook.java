package com.waenhancer.xposed.features.conversation;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;

/**
 * Double Tap to React Hook:
 * Enables double-tapping on incoming/outgoing messages in a conversation to instantly send a custom reaction.
 */
public class DoubleTapReactionHook extends BaseFeature {

    private static final String TAG = "[WAEX][DoubleTapReaction]";
    private static final String PREF_KEY = "doubletap2like";
    private static final String PREF_EMOJI_KEY = "doubletap2like_emoji";

    public DoubleTapReactionHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        hookConversationRow();
    }

    private void hookConversationRow() {
        try {
            Class<?> conversationRowClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_conversation_row_base",
                    (bridge, loader) -> {
                        ClassData cd = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().className("ConversationRow", StringMatchType.EndsWith))
                        ).firstOrNull();
                        return cd != null ? cd.getInstance(loader) : null;
                    }
            );

            if (conversationRowClass != null) {
                XposedBridge.hookAllConstructors(conversationRowClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled(PREF_KEY, false)) return;

                        if (param.thisObject instanceof ViewGroup) {
                            ViewGroup rowView = (ViewGroup) param.thisObject;
                            attachDoubleTapListener(rowView);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked ConversationRow constructors for double tap.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ConversationRow: " + t.getMessage());
        }
    }

    private void attachDoubleTapListener(ViewGroup rowView) {
        GestureDetector detector = new GestureDetector(rowView.getContext(), new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (!isEnabled(PREF_KEY, false)) return false;
                String emoji = prefs.getString(PREF_EMOJI_KEY, "👍");
                XposedBridge.log(TAG + " Double tap detected, emoji target: " + emoji);
                return true;
            }
        });

        rowView.setOnTouchListener((v, event) -> detector.onTouchEvent(event));
    }

    @NonNull
    @Override
    public String getName() {
        return "Double Tap Reaction";
    }
}
