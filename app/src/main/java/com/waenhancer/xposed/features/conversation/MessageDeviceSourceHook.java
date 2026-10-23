package com.waenhancer.xposed.features.conversation;

import com.waenhancer.utils.UiText;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.waenhancer.app.R;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.db.MessageDeviceSourceStore;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.utils.ActivityTracker;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Message Device Source Feature for WhatsApp.
 *
 * Shows whether a received message was sent from a Primary phone (📱) or a Linked device (💻).
 * Clicking the device icon displays an informative Toast.
 */
public class MessageDeviceSourceHook extends BaseFeature {

    public static final String PREF_KEY = "message_device_source";
    private static final String TAG = "[WAEX][DeviceSource]";

    private static final String PRIMARY_DEVICE_EMOJI = " \uD83D\uDCF1";
    private static final String LINKED_DEVICE_EMOJI = " \uD83D\uDCBB";

    private static final String FIELD_DEVICE_SUFFIX = "wae_device_source_suffix";
    private static final String FIELD_DEVICE_GUARD = "wae_device_source_guard";
    private static final String FIELD_DEVICE_MSG_ID = "wae_device_source_message_id";

    private static volatile boolean conversationActive = false;
    private final Set<Class<?>> hookedAdapterClasses = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static Class<?> fMessageClass;
    private static Class<?> deviceJidClass;

    private static final Executor UI_EXECUTOR = action -> {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(action);
    };

    public MessageDeviceSourceHook(
            @NonNull Context context,
            @NonNull ClassLoader classLoader,
            @NonNull SharedPreferences prefs
    ) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "MessageDeviceSource";
    }

    @NonNull
    @Override
    public String getCategory() {
        return "Conversation";
    }

    @Override
    public void hook() {
        if (!isEnabled(PREF_KEY, false)) {
            return;
        }

        resolveMessageClasses();

        ActivityTracker.addListener(activity -> {
            String name = activity.getClass().getSimpleName();
            conversationActive = name.contains("Conversation");
        });

        hookPlaintextProcessor();
        hookListViewAdapter();
        hookTextViewSetText();
    }

    private void hookPlaintextProcessor() {
        try {
            Method targetMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "wpp_shared_msg_proc_plaintext",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create().matcher(
                                MethodMatcher.create().addUsingString("SharedMessageProcessor/handlePlaintext", StringMatchType.Contains)
                        )).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (targetMethod != null) {
                XposedBridge.hookMethod(targetMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!isEnabled(PREF_KEY, false)) return;
                        Object message = findFMessage(param.args);
                        if (message == null) return;
                        String messageId = extractMessageId(message);
                        if (messageId != null && !messageId.isEmpty()) {
                            // The sender's device JID travels with the incoming stanza, which is
                            // usually another argument than the stored message.
                            int devId = extractDeviceIdFromArgs(param.args, message);
                            if (devId >= 0) {
                                MessageDeviceSourceStore.getInstance(context).upsertDeviceId(messageId, devId);
                            }
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook plaintext processor: " + t.getMessage());
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
                            if (!isEnabled(PREF_KEY, false)) return;
                            Activity currentAct = ActivityTracker.getCurrentActivity();
                            if (currentAct == null) return;
                            String actName = currentAct.getClass().getSimpleName();
                            if (!actName.contains("Conversation")) return;

                            if (!(param.thisObject instanceof ListView)) return;

                            ListAdapter adapter = (ListAdapter) param.args[0];
                            if (adapter instanceof HeaderViewListAdapter) {
                                adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                            }
                            if (adapter == null) return;

                            if (!hookedAdapterClasses.add(adapter.getClass())) return;

                            Method getViewMethod = XposedHelpers.findMethodBestMatch(
                                    adapter.getClass(),
                                    "getView",
                                    int.class,
                                    View.class,
                                    ViewGroup.class
                            );
                            if (getViewMethod == null) return;

                            XposedBridge.hookMethod(getViewMethod, new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam p) {
                                    if (!isEnabled(PREF_KEY, false)) return;
                                    if (!(p.thisObject instanceof ListAdapter)) return;
                                    ListAdapter activeAdapter = (ListAdapter) p.thisObject;
                                    int pos = (int) p.args[0];
                                    if (!(p.getResult() instanceof ViewGroup)) return;
                                    ViewGroup row = (ViewGroup) p.getResult();

                                    Object item;
                                    try {
                                        // The method hook belongs to the adapter class and survives chat
                                        // recreation. Always read from the instance rendering this row;
                                        // capturing the first adapter leaves reopened chats bound to stale data.
                                        item = activeAdapter.getItem(pos);
                                    } catch (Throwable t) {
                                        return;
                                    }
                                    Object message = findFMessage(item);
                                    if (message == null) return;

                                    bindMessageRow(row, message);
                                }
                            });
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " ListView setAdapter hook error: " + t.getMessage());
        }
    }

    private void resolveMessageClasses() {
        try {
            fMessageClass = DexSearchEngine.getInstance().findClassWithCache(
                    context,
                    classLoader,
                    "wpp_fmessage_class",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create().matcher(
                                MethodMatcher.create().addUsingString(
                                        "FMessage/getSenderUserJid/key.id",
                                        StringMatchType.Contains)
                        )).firstOrNull();
                        return data != null
                                ? data.getMethodInstance(loader).getDeclaringClass()
                                : null;
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to resolve FMessage class: " + t.getMessage());
        }

        try {
            deviceJidClass = classLoader.loadClass("com.whatsapp.jid.DeviceJid");
        } catch (Throwable ignored) {
            try {
                deviceJidClass = DexSearchEngine.getInstance().findClassWithCache(
                        context,
                        classLoader,
                        "wpp_device_jid_class",
                        (bridge, loader) -> {
                            var data = bridge.findClass(org.luckypray.dexkit.query.FindClass.create()
                                    .matcher(org.luckypray.dexkit.query.matchers.ClassMatcher.create()
                                            .className("jid.DeviceJid", StringMatchType.EndsWith)))
                                    .firstOrNull();
                            return data != null ? data.getInstance(loader) : null;
                        }
                );
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Failed to resolve DeviceJid class: " + t.getMessage());
            }
        }
    }

    private Object findFMessage(Object[] candidates) {
        if (candidates == null) return null;
        for (Object candidate : candidates) {
            Object message = findFMessage(candidate);
            if (message != null) return message;
        }
        return null;
    }

    /** Resolves the typed FMessage from adapter items and plaintext-processor wrappers. */
    private Object findFMessage(Object candidate) {
        if (candidate == null || fMessageClass == null) return null;
        if (fMessageClass.isInstance(candidate)) return candidate;

        Class<?> current = candidate.getClass();
        while (current != null && current != Object.class) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())
                        || !fMessageClass.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object nested = field.get(candidate);
                    if (nested != null) return nested;
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private void bindMessageRow(ViewGroup row, Object messageObj) {
        TextView dateTextView = findDateTextView(row);
        if (dateTextView == null) return;

        String messageId = extractMessageId(messageObj);
        if (messageId == null || messageId.isEmpty()) return;

        XposedHelpers.setAdditionalInstanceField(dateTextView, FIELD_DEVICE_MSG_ID, messageId);
        dateTextView.setTag(R.id.wae_device_source_tag, Boolean.TRUE);

        CompletableFuture.supplyAsync(() -> resolveMessageDeviceId(messageId, messageObj))
                .thenAcceptAsync(resolvedDeviceId -> {
                    Object currentId = XposedHelpers.getAdditionalInstanceField(dateTextView, FIELD_DEVICE_MSG_ID);
                    if (!Objects.equals(currentId, messageId)) return;

                    String suffix = getDeviceEmojiSuffix(resolvedDeviceId);
                    XposedHelpers.setAdditionalInstanceField(dateTextView, FIELD_DEVICE_SUFFIX, suffix);

                    bindDeviceSourceUi(dateTextView, resolvedDeviceId);
                    if (!suffix.isEmpty()) {
                        applyDeviceSourceToMatchingViews(row, dateTextView, suffix, resolvedDeviceId);
                    }
                }, UI_EXECUTOR);
    }

    private TextView findDateTextView(ViewGroup row) {
        View target = findChildByResourceName(row, "date");
        if (target instanceof TextView) return (TextView) target;

        View wrapper = findChildByResourceName(row, "date_wrapper");
        if (wrapper instanceof ViewGroup) {
            ViewGroup wg = (ViewGroup) wrapper;
            for (int i = 0; i < wg.getChildCount(); i++) {
                View child = wg.getChildAt(i);
                if (child instanceof TextView) return (TextView) child;
            }
        }
        return null;
    }

    private View findChildByResourceName(ViewGroup viewGroup, String targetResName) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (targetResName.equals(getResourceEntryName(child))) return child;
            if (child instanceof ViewGroup) {
                View found = findChildByResourceName((ViewGroup) child, targetResName);
                if (found != null) return found;
            }
        }
        return null;
    }

    private String getResourceEntryName(View view) {
        int id = view.getId();
        if (id == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private int resolveMessageDeviceId(String messageId, Object messageObj) {
        int liveId = extractDeviceId(messageObj);
        if (liveId >= 0) {
            MessageDeviceSourceStore.getInstance(context).upsertDeviceId(messageId, liveId);
            return liveId;
        }
        return MessageDeviceSourceStore.getInstance(context).getDeviceId(messageId);
    }

    private String getDeviceEmojiSuffix(int deviceId) {
        if (deviceId == 0) return PRIMARY_DEVICE_EMOJI;
        if (deviceId > 0) return LINKED_DEVICE_EMOJI;
        return "";
    }

    private void bindDeviceSourceUi(TextView dateTextView, int deviceId) {
        String baseText = stripDeviceEmoji(String.valueOf(dateTextView.getText()));
        String suffix = getDeviceEmojiSuffix(deviceId);
        XposedHelpers.setAdditionalInstanceField(dateTextView, FIELD_DEVICE_SUFFIX, suffix);

        attachClickToast(dateTextView, deviceId);
        dateTextView.setText(baseText + suffix);
    }

    private void applyDeviceSourceToMatchingViews(ViewGroup root, TextView anchor, String suffix, int deviceId) {
        String anchorBase = stripDeviceEmoji(String.valueOf(anchor.getText()));
        if (TextUtils.isEmpty(anchorBase)) return;

        forEachTextView(root, textView -> {
            String current = String.valueOf(textView.getText());
            String base = stripDeviceEmoji(current);
            if (!anchorBase.equals(base)) return;

            XposedHelpers.setAdditionalInstanceField(textView, FIELD_DEVICE_SUFFIX, suffix);
            attachClickToast(textView, deviceId);
            if (!current.equals(base + suffix)) {
                textView.setText(base + suffix);
            }
        });
    }

    private void attachClickToast(TextView textView, int deviceId) {
        // Unknown device: leave the view alone; clearing it would also remove listeners other
        // features put on this timestamp.
        if (deviceId < 0) return;
        // Voice notes use their timestamp for the transcript sheet (VoiceNoteRowTranscriptHook,
        // #110); keep that tap. Matched by field name so this has no compile-time dependency.
        if (XposedHelpers.getAdditionalInstanceField(textView, "waex_stt_file") != null) {
            return;
        }

        textView.setOnClickListener(v -> {
            Context ctx = v.getContext();
            if (deviceId == 0) {
                Toast.makeText(ctx, UiText.get(R.string.message_sent_via_phone, "This message was sent via Phone"), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(ctx, UiText.get(R.string.waex_ui_this_message_was_sent_via_a_linked_dev_7be749b8, "This message was sent via a Linked Device (Desktop/Phone)"), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void forEachTextView(View view, Consumer<TextView> consumer) {
        if (view instanceof TextView) {
            consumer.accept((TextView) view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            forEachTextView(group.getChildAt(i), consumer);
        }
    }

    private String stripDeviceEmoji(String text) {
        if (text == null) return "";
        return text.replace(PRIMARY_DEVICE_EMOJI, "")
                .replace(LINKED_DEVICE_EMOJI, "")
                .replace(" \uD83D\uDDA5\uFE0F", "")
                .replace("\uD83D\uDCF1", "")
                .replace("\uD83D\uDCBB", "");
    }

    private void hookTextViewSetText() {
        XC_MethodHook setTextHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!conversationActive || !isEnabled(PREF_KEY, false)) return;
                if (!(param.thisObject instanceof TextView)) return;
                TextView textView = (TextView) param.thisObject;

                Object tag = textView.getTag(R.id.wae_device_source_tag);
                if (tag == null) return;

                Object suffixObj = XposedHelpers.getAdditionalInstanceField(textView, FIELD_DEVICE_SUFFIX);
                if (!(suffixObj instanceof String) || ((String) suffixObj).isEmpty()) return;
                String suffix = (String) suffixObj;

                if (Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(textView, FIELD_DEVICE_GUARD))) return;

                String current = String.valueOf(textView.getText());
                String base = stripDeviceEmoji(current);
                String desired = base + suffix;
                if (desired.equals(current)) return;

                XposedHelpers.setAdditionalInstanceField(textView, FIELD_DEVICE_GUARD, true);
                try {
                    textView.setText(desired);
                } finally {
                    XposedHelpers.setAdditionalInstanceField(textView, FIELD_DEVICE_GUARD, false);
                }
            }
        };

        try {
            Method internalSetText = null;
            for (Method m : TextView.class.getDeclaredMethods()) {
                if (!m.getName().equals("setText")) continue;
                Class<?>[] methodParameters = m.getParameterTypes();
                if (methodParameters.length == 4
                        && methodParameters[0] == CharSequence.class
                        && methodParameters[1] == TextView.BufferType.class
                        && methodParameters[2] == boolean.class
                        && methodParameters[3] == int.class) {
                    internalSetText = m;
                    break;
                }
            }
            if (internalSetText != null) {
                XposedBridge.hookMethod(internalSetText, setTextHook);
                return;
            }
        } catch (Throwable ignored) {}

        XposedBridge.hookAllMethods(TextView.class, "setText", setTextHook);
    }

    private String extractMessageId(Object messageObj) {
        if (messageObj == null) return null;
        try {
            Class<?> curr = messageObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object val = field.get(messageObj);
                    if (val == null) continue;
                    String className = val.getClass().getName();
                    if (className.contains("Key") || val.toString().startsWith("Key(")) {
                        String id = extractIdFromKeyObj(val);
                        if (id != null && !id.isEmpty()) return id;
                    }
                }
                curr = curr.getSuperclass();
            }

            Pattern pattern = Pattern.compile("(?:messageId|messageID|keyId|Key\\(id)=([A-Za-z0-9_-]+)");
            var matcher = pattern.matcher(messageObj.toString());
            if (matcher.find()) return matcher.group(1);
        } catch (Throwable ignored) {}
        return null;
    }

    private String extractIdFromKeyObj(Object keyObj) {
        try {
            Class<?> curr = keyObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == String.class) {
                        // Message-key member names are obfuscated (often A00/A01). The key has a
                        // single String payload, so validate its value instead of its field name.
                        String value = (String) f.get(keyObj);
                        if (value != null && value.length() >= 8 && !value.contains("@")
                                && value.indexOf(' ') < 0) return value;
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private int extractDeviceId(Object messageObj) {
        if (messageObj == null) return -1;
        try {
            Object deviceJid = getDeviceJidObject(messageObj);
            if (deviceJid != null) {
                try {
                    return (Integer) XposedHelpers.callMethod(deviceJid, "getDevice");
                } catch (Throwable ignored) {}

                try {
                    String raw = (String) XposedHelpers.callMethod(deviceJid, "getRawString");
                    int dev = parseDeviceIdFromRawString(raw, true);
                    if (dev >= 0) return dev;
                } catch (Throwable ignored) {}
            }

            // Fallback: check raw string representation on message object fields
            Class<?> curr = messageObj.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(messageObj);
                    if (val == null) continue;
                    String name = val.getClass().getName();
                    if (name.contains("Jid") || name.contains("jid")) {
                        try {
                            // Only an explicit ":device" counts here: a plain contact JID in some
                            // other field must not be reported as "sent from the phone".
                            String raw = (String) XposedHelpers.callMethod(val, "getRawString");
                            int dev = parseDeviceIdFromRawString(raw, false);
                            if (dev >= 0) return dev;
                        } catch (Throwable ignored) {}
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private Object getDeviceJidObject(Object messageObj) {
        try {
            if (deviceJidClass == null) {
                resolveMessageClasses();
            }

            if (deviceJidClass != null) {
                Class<?> curr = messageObj.getClass();
                while (curr != null && curr != Object.class) {
                    for (Field f : curr.getDeclaredFields()) {
                        if (deviceJidClass.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object dj = f.get(messageObj);
                            if (dj != null) return dj;
                        }
                    }
                    curr = curr.getSuperclass();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    static int parseDeviceIdFromRawString(String raw, boolean isDeviceJid) {
        return DeviceJidParser.deviceNumber(raw, isDeviceJid);
    }

    /** Sender device from incoming-message arguments: device JIDs first, then their fields. */
    private int extractDeviceIdFromArgs(Object[] args, Object message) {
        if (args != null) {
            if (deviceJidClass == null) resolveMessageClasses();
            for (Object arg : args) {
                if (arg == null || arg == message) continue;
                if (deviceJidClass != null && deviceJidClass.isInstance(arg)) {
                    int dev = deviceNumberOf(arg);
                    if (dev >= 0) return dev;
                }
            }
            for (Object arg : args) {
                if (arg == null || arg == message || arg instanceof String || arg.getClass().isPrimitive()) continue;
                int dev = extractDeviceId(arg);
                if (dev >= 0) return dev;
            }
        }
        return extractDeviceId(message);
    }

    private int deviceNumberOf(Object deviceJid) {
        try {
            return (Integer) XposedHelpers.callMethod(deviceJid, "getDevice");
        } catch (Throwable ignored) {}
        try {
            return parseDeviceIdFromRawString((String) XposedHelpers.callMethod(deviceJid, "getRawString"), true);
        } catch (Throwable ignored) {}
        try {
            return parseDeviceIdFromRawString(String.valueOf(deviceJid), true);
        } catch (Throwable ignored) {}
        return -1;
    }
}
