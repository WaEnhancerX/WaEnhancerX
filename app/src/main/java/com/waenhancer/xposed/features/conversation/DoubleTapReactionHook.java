package com.waenhancer.xposed.features.conversation;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.utils.ActivityTracker;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.WeakHashMap;

/**
 * Double Tap to React Feature:
 * Allows double clicking on conversation messages to instantly send a reaction emoji (default "👍" or custom).
 */
public class DoubleTapReactionHook extends BaseFeature {

    private static final String TAG = "[WAEX:DoubleTapReaction]";
    private static final String PREF_KEY = "doubletap2like";
    private static final String PREF_EMOJI_KEY = "doubletap2like_emoji";
    private static final String FIELD_BOUND_MSG = "waex_doubletap_fmessage";

    private Class<?> fMessageClass;
    private Class<?> actionUserClass;
    private Method reactionSenderMethod;
    private volatile Object cachedActionUserInstance;

    private static final WeakHashMap<View, Long> lastClickMap = new WeakHashMap<>();

    public DoubleTapReactionHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        initReflection();
        hookConversationRowConstructor();
        hookListViewAdapter();
    }

    private void initReflection() {
        try {
            // 1. Resolve FMessage Class
            fMessageClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_fmessage_core",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("FMessage/getSenderUserJid/key.id", StringMatchType.Contains)))
                                .firstOrNull();
                        return data != null ? data.getMethodInstance(loader).getDeclaringClass() : null;
                    }
            );

            // 2. Resolve ActionUser Class & Sender Method
            if (fMessageClass != null) {
                actionUserClass = DexSearchEngine.getInstance().findClassWithCache(
                        context, classLoader, "wpp_action_user_class",
                        (bridge, loader) -> {
                            MethodData data = bridge.findMethod(FindMethod.create()
                                    .matcher(MethodMatcher.create()
                                            .paramTypes(fMessageClass, String.class, boolean.class)
                                            .modifiers(Modifier.PUBLIC | Modifier.FINAL)
                                            .returnType(boolean.class)))
                                    .firstOrNull();
                            return data != null ? data.getMethodInstance(loader).getDeclaringClass() : null;
                        }
                );

                if (actionUserClass != null) {
                    for (Method m : actionUserClass.getDeclaredMethods()) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 3 && params[0].isAssignableFrom(fMessageClass)
                                && params[1] == String.class && params[2] == boolean.class) {
                            m.setAccessible(true);
                            reactionSenderMethod = m;
                            break;
                        }
                    }

                    // Hook constructors of ActionUser to capture singleton instance
                    XposedBridge.hookAllConstructors(actionUserClass, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            cachedActionUserInstance = param.thisObject;
                        }
                    });
                }
            }
            XposedBridge.log(TAG + " Initialized. FMessage: " + (fMessageClass != null)
                    + ", ActionUser: " + (actionUserClass != null)
                    + ", ReactionSender: " + (reactionSenderMethod != null));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Init reflection error: " + t.getMessage());
        }
    }

    private void hookConversationRowConstructor() {
        try {
            Class<?> conversationRowClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "wpp_conversation_row_class",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("ConversationRow/setupUserNameInGroupView/", StringMatchType.Contains)))
                                .firstOrNull();
                        return data != null ? data.getMethodInstance(loader).getDeclaringClass() : null;
                    }
            );

            if (conversationRowClass != null) {
                XposedBridge.hookAllConstructors(conversationRowClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled(PREF_KEY, false)) return;
                        if (param.thisObject instanceof ViewGroup) {
                            ViewGroup vg = (ViewGroup) param.thisObject;
                            vg.setOnTouchListener(null);
                        }
                    }
                });
                XposedBridge.log(TAG + " Hooked ConversationRow constructors.");
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook ConversationRow constructor: " + t.getMessage());
        }
    }

    private void hookListViewAdapter() {
        try {
            XposedHelpers.findAndHookMethod(
                    ListView.class,
                    "setAdapter",
                    ListAdapter.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Activity currentAct = ActivityTracker.getCurrentActivity();
                            if (currentAct == null || !currentAct.getClass().getSimpleName().contains("Conversation")) {
                                return;
                            }

                            ListView listView = (ListView) param.thisObject;
                            if (listView.getId() != android.R.id.list) return;

                            ListAdapter adapter = (ListAdapter) param.args[0];
                            if (adapter instanceof HeaderViewListAdapter) {
                                adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                            }
                            if (adapter == null) return;

                            try {
                                Method getViewMethod = XposedHelpers.findMethodExactIfExists(
                                        adapter.getClass(),
                                        "getView",
                                        int.class,
                                        View.class,
                                        ViewGroup.class
                                );
                                if (getViewMethod == null) {
                                    getViewMethod = XposedHelpers.findMethodBestMatch(
                                            adapter.getClass(),
                                            "getView",
                                            int.class,
                                            View.class,
                                            ViewGroup.class
                                    );
                                }
                                if (getViewMethod == null) return;

                                final ListAdapter activeAdapter = adapter;
                                XposedBridge.hookMethod(getViewMethod, new XC_MethodHook() {
                                    @Override
                                    protected void afterHookedMethod(MethodHookParam p) {
                                        if (!isEnabled(PREF_KEY, false)) return;

                                        int pos = (int) p.args[0];
                                        ViewGroup row = (ViewGroup) p.getResult();
                                        if (row == null) return;

                                        Object item = null;
                                        try {
                                            item = activeAdapter.getItem(pos);
                                        } catch (Throwable ignored) {}

                                        if (item == null || (fMessageClass != null && !fMessageClass.isInstance(item))) {
                                            return;
                                        }

                                        final Object fMessage = item;
                                        XposedHelpers.setAdditionalInstanceField(row, FIELD_BOUND_MSG, fMessage);

                                        bindDoubleClickListener(row, fMessage);
                                    }
                                });
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " Error hooking getView: " + t.getMessage());
                            }
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking ListView setAdapter: " + t.getMessage());
        }
    }

    private void bindDoubleClickListener(final ViewGroup row, final Object fMessage) {
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!isEnabled(PREF_KEY, false)) return;

                long now = System.currentTimeMillis();
                Long lastClick = lastClickMap.get(v);
                if (lastClick != null && (now - lastClick) < 500) {
                    lastClickMap.remove(v);
                    handleDoubleTapReaction(v, fMessage);
                } else {
                    lastClickMap.put(v, now);
                }
            }
        });
    }

    private void handleDoubleTapReaction(View rowView, Object fMessage) {
        try {
            String selectedEmoji = prefs.getString(PREF_EMOJI_KEY, "👍");
            if (selectedEmoji == null || selectedEmoji.trim().isEmpty()) {
                selectedEmoji = "👍";
            }

            // Check if user already reacted with this emoji to toggle it off
            int bubbleResId = context.getResources().getIdentifier("reactions_bubble_layout", "id", context.getPackageName());
            if (bubbleResId > 0 && rowView instanceof ViewGroup) {
                View reactionBubble = rowView.findViewById(bubbleResId);
                if (reactionBubble instanceof ViewGroup && reactionBubble.getVisibility() == View.VISIBLE) {
                    ViewGroup bubbleGroup = (ViewGroup) reactionBubble;
                    for (int i = 0; i < bubbleGroup.getChildCount(); i++) {
                        View child = bubbleGroup.getChildAt(i);
                        if (child instanceof TextView) {
                            String text = ((TextView) child).getText().toString();
                            if (text.contains(selectedEmoji)) {
                                sendReaction("", fMessage);
                                return;
                            }
                        }
                    }
                }
            }

            sendReaction(selectedEmoji, fMessage);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " handleDoubleTapReaction error: " + t.getMessage());
        }
    }

    private void sendReaction(String emoji, Object fMessage) {
        try {
            if (reactionSenderMethod == null) {
                initReflection();
            }
            if (reactionSenderMethod == null) {
                XposedBridge.log(TAG + " Reaction sender method not resolved");
                return;
            }

            Object actionUser = getActionUser();
            if (actionUser == null) {
                XposedBridge.log(TAG + " ActionUser instance not available");
                return;
            }

            boolean hasEmoji = emoji != null && !emoji.trim().isEmpty();
            reactionSenderMethod.invoke(actionUser, fMessage, emoji, hasEmoji);
            XposedBridge.log(TAG + " Sent reaction '" + emoji + "' on message");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to send reaction: " + t.getMessage());
        }
    }

    @Nullable
    private Object getActionUser() {
        if (cachedActionUserInstance != null) {
            return cachedActionUserInstance;
        }
        if (actionUserClass != null) {
            try {
                cachedActionUserInstance = actionUserClass.getDeclaredConstructors()[0].newInstance();
                return cachedActionUserInstance;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Failed to create ActionUser instance: " + t.getMessage());
            }
        }
        return null;
    }

    @NonNull
    @Override
    public String getName() {
        return "Double Tap Reaction";
    }
}


