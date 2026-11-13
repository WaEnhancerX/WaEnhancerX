package com.waenhancer.xposed.features.conversation;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.utils.EmojiInputPolicy;
import com.waenhancer.xposed.utils.ActivityTracker;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Double Tap to React Feature:
 * Allows double clicking on conversation messages to instantly send a reaction emoji (default "👍" or custom).
 */
public class DoubleTapReactionHook extends BaseFeature {

    private static final String TAG = "[WAEX:DoubleTapReaction]";
    private static final String PREF_KEY = "doubletap2like";
    private static final String PREF_EMOJI_KEY = "doubletap2like_emoji";
    private static final String FIELD_BOUND_MSG = "waex_doubletap_fmessage";
    private static final String FIELD_TOUCH_WRAPPER = "waex_doubletap_touch_wrapper";
    private static final int TAG_BOUND_MSG = 0x57414454; // 'WADT'
    private static final int TAG_TRACKER = 0x57414455;
    private static final int TAG_TOUCH_INSTALLED = 0x57414456;

    private Class<?> fMessageClass;
    private Class<?> actionUserClass;
    private Method reactionSenderMethod;
    private volatile Object cachedActionUserInstance;
    private final Set<Method> hookedAdapterMethods = ConcurrentHashMap.newKeySet();
    private boolean constructorHooked;

    private static class TouchTracker {
        float downX;
        float downY;
        long downTime;
        long lastTapTime;
        float lastTapX;
        float lastTapY;
        boolean moved;

        void resetGesture() {
            downTime = 0L;
            moved = false;
        }

        void resetAll() {
            resetGesture();
            lastTapTime = 0L;
        }
    }

    public DoubleTapReactionHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        if (!isEnabled(PREF_KEY, false)) return;
        initReflection();
        hookListViewAdapter();
    }

    private void initReflection() {
        try {
            // 1. Message base class (DexKit string anchors).
            if (fMessageClass == null) {
                fMessageClass = ReactionSenderResolver.resolveMessageClass(context, classLoader);
            }

            // 2. Reaction entry point (DexKit behavioural anchor, signature fallback).
            if (reactionSenderMethod == null) {
                Method sender = ReactionSenderResolver.resolveSender(context, classLoader, fMessageClass);
                if (sender == null) return;
                sender.setAccessible(true);
                reactionSenderMethod = sender;
                actionUserClass = sender.getDeclaringClass();
                if (fMessageClass == null) fMessageClass = ReactionSenderResolver.messageParameter(sender);
            }

            // 3. Capture the host's own instance (constructed before or after us, or used by WhatsApp's
            //    native reaction tray) so the sender can be invoked on the real object.
            if (!constructorHooked && actionUserClass != null) {
                constructorHooked = true;
                XposedBridge.hookAllConstructors(actionUserClass, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        cachedActionUserInstance = param.thisObject;
                    }
                });
                if (!Modifier.isStatic(reactionSenderMethod.getModifiers())) {
                    XposedBridge.hookMethod(reactionSenderMethod, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (param.thisObject != null) cachedActionUserInstance = param.thisObject;
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Init reflection error: " + t.getMessage());
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
                            ListView listView = (ListView) param.thisObject;
                            if (listView.getId() != android.R.id.list) return;
                            if (!isConversationList(listView)) return;

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

                                if (!hookedAdapterMethods.add(getViewMethod)) return;
                                try {
                                    XposedBridge.hookMethod(getViewMethod, new XC_MethodHook() {
                                        @Override
                                        protected void afterHookedMethod(MethodHookParam p) {
                                            if (!(p.getResult() instanceof ViewGroup)) return;
                                            ViewGroup row = (ViewGroup) p.getResult();

                                            // Always drop the previous identity, even while disabled,
                                            // so re-enabling can never react to a stale message.
                                            row.setTag(TAG_BOUND_MSG, null);
                                            TouchTracker tracker = (TouchTracker) row.getTag(TAG_TRACKER);
                                            if (tracker != null) tracker.resetAll();
                                            if (!isEnabled(PREF_KEY, false)) return;
                                            if (!(p.thisObject instanceof ListAdapter) || p.args == null
                                                    || p.args.length == 0 || !(p.args[0] instanceof Integer)) return;

                                            Object item = null;
                                            try {
                                                // The Method hook is shared by every adapter instance.
                                                // Read the instance rendering this row, not its installer.
                                                item = ((ListAdapter) p.thisObject).getItem((Integer) p.args[0]);
                                            } catch (Throwable ignored) {}

                                            if (item == null || fMessageClass == null || !fMessageClass.isInstance(item)) {
                                                return;
                                            }

                                            row.setTag(TAG_BOUND_MSG, item);
                                            bindDoubleTapListener(row);
                                        }
                                    });
                                } catch (Throwable failure) {
                                    hookedAdapterMethods.remove(getViewMethod);
                                    throw failure;
                                }
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

    /**
     * The tracked activity can still be null/stale while the chat's ListView receives its adapter
     * during onCreate, so also trust the ListView's own host activity.
     */
    private static boolean isConversationList(ListView listView) {
        Activity tracked = ActivityTracker.getCurrentActivity();
        if (tracked != null && tracked.getClass().getSimpleName().contains("Conversation")) return true;
        Context host = listView.getContext();
        return host instanceof Activity && host.getClass().getSimpleName().contains("Conversation");
    }

    private void bindDoubleTapListener(final ViewGroup row) {
        TouchTracker tracker = (TouchTracker) row.getTag(TAG_TRACKER);
        if (tracker == null) {
            tracker = new TouchTracker();
            row.setTag(TAG_TRACKER, tracker);
        } else {
            // List rows are recycled; a tap on the previous message must not carry over.
            tracker.resetAll();
        }

        // WhatsApp may call setOnTouchListener again while rebinding a recycled row, which silently
        // drops our wrapper. Re-check on every bind and re-wrap whatever listener is current.
        final View.OnTouchListener current = getOnTouchListener(row);
        if (current instanceof DoubleTapTouchListener) return;
        if (current == null && !sReflectionWorks && row.getTag(TAG_TOUCH_INSTALLED) != null) return;
        row.setTag(TAG_TOUCH_INSTALLED, Boolean.TRUE);

        final int touchSlop = ViewConfiguration.get(row.getContext()).getScaledTouchSlop();
        row.setOnTouchListener(new DoubleTapTouchListener(current, touchSlop));
    }

    private final class DoubleTapTouchListener implements View.OnTouchListener {
        private final View.OnTouchListener original;
        private final int touchSlop;
        /** True while the second tap of a double tap is being consumed here. */
        private boolean swallowing;

        DoubleTapTouchListener(@Nullable View.OnTouchListener original, int touchSlop) {
            this.original = original;
            this.touchSlop = touchSlop;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            int action = event.getActionMasked();
            try {
                if (action == MotionEvent.ACTION_DOWN) swallowing = isSecondTap(view, event);
                if (swallowing) {
                    // WhatsApp opens its own reaction tray on a native double tap. The second tap
                    // belongs to this feature, so the host never sees it.
                    observeTouch(view, event, touchSlop);
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        swallowing = false;
                    }
                    return true;
                }
            } catch (Throwable t) {
                swallowing = false;
                XposedBridge.log(TAG + " touch handling error: " + t.getMessage());
            }
            boolean handled = original != null && original.onTouch(view, event);
            try {
                observeTouch(view, event, touchSlop);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " observeTouch error: " + t.getMessage());
            }
            return handled;
        }

        private boolean isSecondTap(View view, MotionEvent event) {
            if (!isEnabled(PREF_KEY, false)) return false;
            TouchTracker tracker = (TouchTracker) view.getTag(TAG_TRACKER);
            if (tracker == null || tracker.lastTapTime == 0L || view.getTag(TAG_BOUND_MSG) == null) {
                return false;
            }
            long elapsed = SystemClock.uptimeMillis() - tracker.lastTapTime;
            if (elapsed > ViewConfiguration.getDoubleTapTimeout()) return false;
            float dx = event.getX() - tracker.lastTapX;
            float dy = event.getY() - tracker.lastTapY;
            int maxDistance = touchSlop * 8;
            return (dx * dx) + (dy * dy) <= (float) maxDistance * maxDistance;
        }
    }

    private static volatile java.lang.reflect.Field sListenerInfoField;
    private static volatile java.lang.reflect.Field sOnTouchListenerField;
    private static volatile boolean sReflectionWorks = true;

    @Nullable
    private View.OnTouchListener getOnTouchListener(View view) {
        try {
            java.lang.reflect.Field liField = sListenerInfoField;
            if (liField == null) {
                liField = View.class.getDeclaredField("mListenerInfo");
                liField.setAccessible(true);
                sListenerInfoField = liField;
            }
            Object listenerInfo = liField.get(view);
            if (listenerInfo == null) return null;

            java.lang.reflect.Field otlField = sOnTouchListenerField;
            if (otlField == null) {
                otlField = listenerInfo.getClass().getDeclaredField("mOnTouchListener");
                otlField.setAccessible(true);
                sOnTouchListenerField = otlField;
            }
            return (View.OnTouchListener) otlField.get(listenerInfo);
        } catch (Throwable ignored) {
            sReflectionWorks = false;
            return null;
        }
    }

    private void observeTouch(View view, MotionEvent event, int touchSlop) {
        TouchTracker tracker = (TouchTracker) view.getTag(TAG_TRACKER);
        if (tracker == null) return;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                tracker.downX = event.getX();
                tracker.downY = event.getY();
                tracker.downTime = SystemClock.uptimeMillis();
                tracker.moved = false;
                break;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - tracker.downX;
                float dy = event.getY() - tracker.downY;
                if ((dx * dx) + (dy * dy) > touchSlop * touchSlop) tracker.moved = true;
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                // Pinch/multi-finger gestures are never a tap.
                tracker.moved = true;
                break;
            case MotionEvent.ACTION_CANCEL:
                tracker.resetGesture();
                break;
            case MotionEvent.ACTION_UP:
                long now = SystemClock.uptimeMillis();
                boolean isTap = tracker.downTime != 0L && !tracker.moved
                        && now - tracker.downTime <= ViewConfiguration.getLongPressTimeout();
                long downTime = tracker.downTime;
                float x = tracker.downX;
                float y = tracker.downY;
                tracker.resetGesture();
                if (!isTap || !isEnabled(PREF_KEY, false)) {
                    tracker.lastTapTime = 0L;
                    return;
                }

                // Platform semantics: the second finger must land within the double-tap timeout
                // of the first release, and close to where the first tap was.
                float tapDx = x - tracker.lastTapX;
                float tapDy = y - tracker.lastTapY;
                int maxDistance = touchSlop * 8;
                if (tracker.lastTapTime != 0L
                        && downTime - tracker.lastTapTime <= ViewConfiguration.getDoubleTapTimeout()
                        && (tapDx * tapDx) + (tapDy * tapDy) <= (float) maxDistance * maxDistance) {
                    tracker.lastTapTime = 0L;
                    Object msgTag = view.getTag(TAG_BOUND_MSG);
                    Object message = msgTag != null ? msgTag : XposedHelpers.getAdditionalInstanceField(view, FIELD_BOUND_MSG);
                    if (message != null) handleDoubleTapReaction(view, message);
                } else {
                    tracker.lastTapTime = now;
                    tracker.lastTapX = x;
                    tracker.lastTapY = y;
                }
                break;
            default:
                break;
        }
    }

    private static final class RecentAction {
        final String emoji; // null = reaction removed
        final long time;
        RecentAction(String emoji, long time) {
            this.emoji = emoji;
            this.time = time;
        }
    }

    /** The message store updates asynchronously; trust our own last action for a short moment. */
    private static final long RECENT_ACTION_MS = 4000L;
    private final Map<String, RecentAction> recentActions = new ConcurrentHashMap<>();

    private void handleDoubleTapReaction(View rowView, Object fMessage) {
        try {
            String selectedEmoji = EmojiInputPolicy.singleEmojiOrDefault(
                    prefs.getString(PREF_EMOJI_KEY, EmojiInputPolicy.DEFAULT_EMOJI));

            // Double tapping a message that already carries my reaction removes it.
            boolean alreadyReacted = hasReaction(rowView, fMessage, selectedEmoji);
            sendReaction(alreadyReacted ? "" : selectedEmoji, fMessage);

            String keyId = OwnReactionLookup.messageKeyId(fMessage);
            if (keyId != null) {
                if (recentActions.size() > 64) recentActions.clear();
                recentActions.put(keyId, new RecentAction(
                        alreadyReacted ? null : selectedEmoji, SystemClock.uptimeMillis()));
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " handleDoubleTapReaction error: " + t.getMessage());
        }
    }

    private boolean hasReaction(View rowView, Object fMessage, String emoji) {
        String keyId = OwnReactionLookup.messageKeyId(fMessage);
        if (keyId != null) {
            RecentAction recent = recentActions.get(keyId);
            if (recent != null && SystemClock.uptimeMillis() - recent.time < RECENT_ACTION_MS) {
                return recent.emoji != null && sameEmoji(recent.emoji, emoji);
            }
        }

        String own = OwnReactionLookup.ownReaction(context, fMessage);
        if (!OwnReactionLookup.UNKNOWN.equals(own)) {
            return own != null && sameEmoji(own, emoji);
        }
        // Could not read the message store: fall back to what the reaction chip shows.
        return chipShowsEmoji(rowView, emoji);
    }

    private static boolean sameEmoji(String a, String b) {
        return stripVariation(a).equals(stripVariation(b));
    }

    private static String stripVariation(String emoji) {
        return emoji.replace("\uFE0F", "").replace("\uFE0E", "").trim();
    }

    /** The chip nests its emoji (Button > FrameLayout > TextView), so search the whole subtree. */
    private boolean chipShowsEmoji(View rowView, String emoji) {
        int bubbleResId = context.getResources().getIdentifier(
                "reactions_bubble_layout", "id", context.getPackageName());
        if (bubbleResId <= 0 || !(rowView instanceof ViewGroup)) return false;
        View chip = rowView.findViewById(bubbleResId);
        if (chip == null || chip.getVisibility() != View.VISIBLE) return false;
        return containsEmoji(chip, stripVariation(emoji));
    }

    private static boolean containsEmoji(View view, String emoji) {
        if (view instanceof TextView
                && stripVariation(((TextView) view).getText().toString()).contains(emoji)) {
            return true;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsEmoji(group.getChildAt(i), emoji)) return true;
            }
        }
        return false;
    }

    private void sendReaction(String emoji, Object fMessage) {
        try {
            if (reactionSenderMethod == null) {
                initReflection();
            }
            Method sender = reactionSenderMethod;
            if (sender == null) {
                XposedBridge.log(TAG + " Reaction sender method not resolved");
                return;
            }

            boolean isStatic = Modifier.isStatic(sender.getModifiers());
            Object actionUser = null;
            if (!isStatic || takesDeclaringClass(sender)) {
                actionUser = getActionUser();
                if (actionUser == null) {
                    XposedBridge.log(TAG + " ActionUser instance not available");
                    return;
                }
            }

            boolean hasEmoji = emoji != null && !emoji.trim().isEmpty();
            Object[] args = bindArguments(sender, actionUser, fMessage, emoji == null ? "" : emoji, hasEmoji);
            if (args == null) {
                XposedBridge.log(TAG + " Reaction sender has no message parameter: " + sender);
                return;
            }
            sender.invoke(isStatic ? null : actionUser, args);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to send reaction: " + t.getMessage());
        }
    }

    private static boolean takesDeclaringClass(Method method) {
        for (Class<?> p : method.getParameterTypes()) {
            if (p == method.getDeclaringClass()) return true;
        }
        return false;
    }

    /**
     * Binds arguments by parameter <em>type</em>, not position, so a host update that reorders or adds
     * parameters keeps working: the message, the emoji text, the "has emoji" flag and (for static
     * entry points) the declaring instance are matched to whichever slot accepts them. Unknown object
     * parameters are passed as null and unknown primitives as their zero value.
     *
     * @return the argument array, or null when no parameter can take the message
     */
    @Nullable
    static Object[] bindArguments(Method method, @Nullable Object actionUser, Object message,
                                  String emoji, boolean hasEmoji) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        boolean boundMessage = false;
        boolean boundText = false;
        boolean boundFlag = false;
        boolean boundInstance = false;
        for (int i = 0; i < types.length; i++) {
            Class<?> type = types[i];
            if (!boundMessage && !type.isPrimitive() && type != String.class && type != Object.class
                    && type.isInstance(message)) {
                args[i] = message;
                boundMessage = true;
            } else if (!boundText && type == String.class) {
                args[i] = emoji;
                boundText = true;
            } else if (!boundFlag && (type == boolean.class || type == Boolean.class)) {
                args[i] = hasEmoji;
                boundFlag = true;
            } else if (!boundInstance && actionUser != null && type == method.getDeclaringClass()) {
                args[i] = actionUser;
                boundInstance = true;
            } else if (type.isPrimitive()) {
                args[i] = zeroOf(type);
            }
        }
        // Never call a host method that cannot take the emoji text: it would send the wrong reaction.
        return boundMessage && boundText ? args : null;
    }

    private static Object zeroOf(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return false;
    }

    @Nullable
    private Object getActionUser() {
        if (cachedActionUserInstance != null) {
            return cachedActionUserInstance;
        }
        if (actionUserClass == null) return null;

        // The singleton may have been built before our constructor hook was installed.
        try {
            for (java.lang.reflect.Field f : actionUserClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && f.getType() == actionUserClass) {
                    f.setAccessible(true);
                    Object value = f.get(null);
                    if (value != null) {
                        cachedActionUserInstance = value;
                        return value;
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            for (java.lang.reflect.Constructor<?> c : actionUserClass.getDeclaredConstructors()) {
                if (c.getParameterTypes().length == 0) {
                    c.setAccessible(true);
                    cachedActionUserInstance = c.newInstance();
                    return cachedActionUserInstance;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to create ActionUser instance: " + t.getMessage());
        }
        return null;
    }

    @NonNull
    @Override
    public String getName() {
        return "Double Tap Reaction";
    }
}
