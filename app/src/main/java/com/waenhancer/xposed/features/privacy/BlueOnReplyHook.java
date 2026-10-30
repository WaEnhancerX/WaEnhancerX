package com.waenhancer.xposed.features.privacy;

import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.Activity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.core.db.HideSeenStore;
import com.waenhancer.xposed.features.homescreen.MenuIconLoader;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import android.graphics.drawable.Drawable;
import java.lang.reflect.Array;
import java.util.IdentityHashMap;
import java.util.Iterator;

/**
 * Blue on Reply Hook:
 * Defers sending blue read ticks until the user actually sends a reply message to the contact/group.
 */
public class BlueOnReplyHook extends BaseFeature {

    private static final String TAG = "[WAEX][BlueOnReply]";
    private static final String PREF_KEY = "blueonreply";
    private static final int MAX_MANUAL_READ_JOBS = 50;
    // Bound retained constructor arguments and never route one chat's read receipts to another.
    private static final PendingReadReceiptStore<ReadJobRecipe> PENDING_JOBS =
            new PendingReadReceiptStore<>(128, MAX_MANUAL_READ_JOBS);
    private static volatile Constructor<?> sendReadConstructor;
    private static volatile Method enqueueJobMethod;
    private static volatile Class<?> managerClass;
    private static volatile Object jobManager;
    private static volatile Context appContext;
    private static final int MENU_SEND_BLUE_TICK = 0x7EAE0012;
    private static final Map<String, ReadJobRecipe> PENDING_STATUS_RECEIPTS = new LinkedHashMap<>();
    // Status player wrappers do not always retain the MessageKey. Keep the most recently
    // suppressed host job as the playback-order fallback; the host creates it for the
    // status that is being opened.
    private static final Object MANUAL_STATUS_RECEIPT = new Object();
    private static final String PENDING_CHAT_RECEIPT = "waex_pending_chat_receipt";

    private static final class ReadJobRecipe {
        final Constructor<?> constructor;
        final Object[] arguments;

        ReadJobRecipe(Constructor<?> constructor, Object[] arguments) {
            this.constructor = constructor;
            this.arguments = arguments;
        }
    }

    public BlueOnReplyHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
        appContext = context.getApplicationContext();
    }

    @Override
    public void hook() throws Throwable {
        initializeReadReceiptDispatcher();
        hookMessageSend();
        hookConversationMenu();
    }

    private void initializeReadReceiptDispatcher() {
        try {
            Class<?> sendReadClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "waex_send_read_receipt_job_class",
                    (bridge, loader) -> {
                        ClassData data = bridge.findClass(FindClass.create().matcher(
                                ClassMatcher.create().className(
                                        "SendReadReceiptJob", StringMatchType.EndsWith)))
                                .firstOrNull();
                        return data != null ? data.getInstance(loader) : null;
                    });
            if (sendReadClass == null) return;

            Constructor<?>[] constructors = sendReadClass.getDeclaredConstructors();
            if (constructors.length == 0) return;
            sendReadConstructor = constructors[0];
            sendReadConstructor.setAccessible(true);

            Method anchor = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "waex_wa_job_manager_start_anchor",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create().matcher(
                                MethodMatcher.create().addUsingString(
                                        "WaJobManager/start", StringMatchType.Contains)))
                                .firstOrNull();
                        return data != null ? data.getMethodInstance(loader) : null;
                    });
            if (anchor == null) return;

            Class<?> declaringManagerClass = anchor.getDeclaringClass();
            managerClass = declaringManagerClass;
            Class<?> baseJobClass = XposedHelpers.findClassIfExists(
                    "org.whispersystems.jobqueue.Job", classLoader);
            for (Method method : declaringManagerClass.getDeclaredMethods()) {
                Class<?>[] methodParameters = method.getParameterTypes();
                if (methodParameters.length != 1) continue;
                Class<?> parameter = methodParameters[0];
                boolean exactJobMethod = baseJobClass != null && parameter == baseJobClass;
                boolean compatibleJobMethod = baseJobClass == null
                        && parameter.isAssignableFrom(sendReadClass);
                if (exactJobMethod || compatibleJobMethod) {
                    method.setAccessible(true);
                    enqueueJobMethod = method;
                    break;
                }
            }
            if (enqueueJobMethod == null) return;

            // Try to find singleton instance if already created
            getOrFindJobManager();

            XposedBridge.hookAllConstructors(declaringManagerClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    jobManager = param.thisObject;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error initializing receipt dispatcher: " + t.getClass().getSimpleName());
        }
    }

    private void hookMessageSend() {
        try {
            Method sendMessageMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context,
                    classLoader,
                    "waex_blue_on_reply_send_e2e_job",
                    (bridge, loader) -> {
                        MethodData md = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .paramCount(0)
                                        .addUsingString("SendE2EMessageJob/onRun", StringMatchType.Contains)
                                )
                        ).firstOrNull();
                        return md != null ? md.getMethodInstance(loader) : null;
                    }
            );

            if (sendMessageMethod != null) {
                XposedBridge.hookMethod(sendMessageMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isBlueOnReplyEnabled()) return;

                        // Use the outgoing job's actual recipient, not the Activity currently
                        // in the foreground: a queued send may belong to another conversation.
                        String remoteJid = extractOutgoingJobJid(param.thisObject);
                        if (remoteJid != null) sendPendingReadJobs(remoteJid);
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking Blue on Reply: " + t.getClass().getSimpleName());
        }
    }

    public boolean isBlueOnReplyEnabled() {
        return isHideBlueTicksEnabled() && isEnabled(PREF_KEY, false);
    }

    /**
     * Stage a receipt on its job instance. It becomes a manual-send candidate only when
     * HideReceipts confirms that this job actually reached the hidden-read execution path.
     */
    public static void prepareSuppressedReadJob(String jid, Constructor<?> constructor,
                                                Object[] constructorArgs, Object constructedJob) {
        String key = canonicalJid(jid);
        if (key == null || constructor == null || constructorArgs == null) {
            XposedBridge.log(TAG + " chat receipt not staged: incomplete job contract");
            return;
        }
        // WhatsApp's constructor arguments have changed across builds. The completed
        // SendReadReceiptJob is the authoritative source of its message IDs, just as it
        // is for the actual suppression hook. Do not assume a particular argument slot.
        if (readReceiptMessageIds(constructedJob).isEmpty()) {
            XposedBridge.log(TAG + " chat receipt not staged: job has no message IDs");
            return;
        }
        constructor.setAccessible(true);
        XposedHelpers.setAdditionalInstanceField(constructedJob, PENDING_CHAT_RECEIPT,
                new PendingChatReceipt(key, new ReadJobRecipe(constructor, copyArguments(constructorArgs))));
    }

    /** Called by the verified SendReadReceiptJob execution hook once hiding is confirmed. */
    public static void recordSuppressedReadJob(Object job) {
        Object value = XposedHelpers.getAdditionalInstanceField(job, PENDING_CHAT_RECEIPT);
        if (!(value instanceof PendingChatReceipt)) {
            return;
        }
        PendingChatReceipt pending = (PendingChatReceipt) value;
        markEvictedHandled(pending.recipient, PENDING_JOBS.add(pending.recipient, pending.recipe));
        XposedHelpers.removeAdditionalInstanceField(job, PENDING_CHAT_RECEIPT);
    }

    private static final class PendingChatReceipt {
        final String recipient;
        final ReadJobRecipe recipe;

        PendingChatReceipt(String recipient, ReadJobRecipe recipe) {
            this.recipient = recipient;
            this.recipe = recipe;
        }
    }

    public static Set<String> readReceiptMessageIds(Object job) {
        Set<String> ids = new LinkedHashSet<>();
        if (job == null) return ids;
        for (Class<?> type = job.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String[].class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(job);
                    if (!(value instanceof String[])) continue;
                    for (String id : (String[]) value) {
                        if (id != null && !id.isEmpty()) ids.add(id);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return ids;
    }

    public static void captureSuppressedStatusReadJob(Constructor<?> constructor, Object[] arguments) {
        if (constructor == null || arguments == null) return;
        ReadJobRecipe recipe = new ReadJobRecipe(constructor, copyArguments(arguments));
        synchronized (PENDING_STATUS_RECEIPTS) {
            for (Object argument : arguments) if (argument instanceof String[])
                for (String id : (String[]) argument) if (id != null && !id.isEmpty()) {
                    PENDING_STATUS_RECEIPTS.put(id, recipe);
                    while (PENDING_STATUS_RECEIPTS.size() > 256)
                        PENDING_STATUS_RECEIPTS.remove(PENDING_STATUS_RECEIPTS.keySet().iterator().next());
                }
        }
    }

    /**
     * Called by HideReceiptsHook when a message arrives via the type-9 in-chat dispatch path.
     * WhatsApp skips SendReadReceiptJob for messages received while the user is in the chat,
     * so we build a single-ID job recipe here using the same constructor we already resolved.
     * sendPendingReadJobs will replay it when the user taps "Send blue tick".
     */
    public static void recordObservedReceipt(String jid, Object typedJid,
                                             String messageId) {
        String key = canonicalJid(jid);
        if (key == null || typedJid == null || messageId == null || messageId.isEmpty()) return;
        Constructor<?> ctor = sendReadConstructor;
        if (ctor == null) {
            XposedBridge.log(TAG + " Cannot record observed receipt: constructor unavailable");
            return;
        }
        // Build args: find an existing pending recipe for this jid to reuse its constructor shape,
        // or construct minimal args with just the messageId array.
        Object[] args = buildMinimalConstructorArgs(
                ctor, typedJid, new String[]{messageId});
        if (args == null) return;
        ReadJobRecipe recipe = new ReadJobRecipe(ctor, args);
        markEvictedHandled(key, PENDING_JOBS.add(key, recipe));
    }

    private static void markEvictedHandled(String recipient, ReadJobRecipe evicted) {
        Context context = appContext;
        if (context == null || evicted == null) return;
        for (Object argument : evicted.arguments) {
            if (!(argument instanceof String[])) continue;
            for (String id : (String[]) argument) {
                if (id != null && !id.isEmpty()) {
                    HideSeenStore.getInstance(context).updateViewedMessageAsync(
                            recipient, id, HideSeenStore.TYPE_READ, true);
                }
            }
        }
    }

    /**
     * Creates the smallest valid direct-chat recipe using the typed JID retained
     * from the decrypted message. Optional participant/recipient fields stay null.
     */
    private static Object[] buildMinimalConstructorArgs(Constructor<?> ctor, Object typedJid,
                                                        String[] messageIds) {
        Class<?>[] params = ctor.getParameterTypes();
        Object[] args = new Object[params.length];
        boolean jidSet = false;
        boolean idsSet = false;
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            if (!idsSet && p == String[].class) {
                args[i] = messageIds;
                idsSet = true;
            } else if (!jidSet && typedJid != null
                    && !(typedJid instanceof String) && p.isInstance(typedJid)) {
                // The current SendReadReceiptJob constructor requires the host's
                // typed JID as its first argument. The remaining participant and
                // recipient JID parameters are optional for a direct chat.
                args[i] = typedJid;
                jidSet = true;
            } else if (p == boolean.class || p == Boolean.class) {
                args[i] = false;
            } else if (p == int.class || p == Integer.class) {
                args[i] = 0;
            } else if (p == long.class || p == Long.class) {
                args[i] = 0L;
            } else {
                args[i] = null; // Reference types default to null
            }
        }
        if (!idsSet || !jidSet) {
            XposedBridge.log(TAG + " buildMinimalConstructorArgs: required argument unavailable");
            return null;
        }
        return args;
    }

    public static boolean sendCapturedStatusReceipt(Object statusItem) {
        String id = findStatusId(statusItem, new IdentityHashMap<>(), 0);
        ReadJobRecipe recipe;
        synchronized (PENDING_STATUS_RECEIPTS) {
            // Never substitute the most recently captured receipt when the active playback
            // item cannot be identified. After backward navigation, that receipt belongs to
            // the last-viewed status and would acknowledge the wrong item.
            recipe = id == null ? null : PENDING_STATUS_RECEIPTS.get(id);
        }
        if (recipe == null || enqueueJobMethod == null || getOrFindJobManager() == null) return false;
        try {
            Object job = recipe.constructor.newInstance(copyArguments(recipe.arguments));
            XposedHelpers.setAdditionalInstanceField(job, "waex_manual_status_seen", MANUAL_STATUS_RECEIPT);
            enqueueJobMethod.invoke(getOrFindJobManager(), job);
            synchronized (PENDING_STATUS_RECEIPTS) {
                // A host status receipt can cover several message IDs. Remove every alias
                // for the exact recipe so delivered entries cannot block later statuses.
                Iterator<Map.Entry<String, ReadJobRecipe>> iterator =
                        PENDING_STATUS_RECEIPTS.entrySet().iterator();
                while (iterator.hasNext()) {
                    if (iterator.next().getValue() == recipe) iterator.remove();
                }
            }
            return true;
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error enqueueing status receipt: "
                    + t.getClass().getSimpleName());
            return false;
        }
    }

    /** True only when this item exposes an exact receipt ID that was suppressed earlier. */
    public static boolean hasCapturedStatusReceipt(Object statusItem) {
        return findStatusId(statusItem, new IdentityHashMap<>(), 0) != null;
    }

    private static Object[] copyArguments(Object[] source) { Object[] copy = Arrays.copyOf(source, source.length); for (int i=0;i<copy.length;i++) if(copy[i] instanceof String[]) copy[i]=((String[])copy[i]).clone(); return copy; }
    private static String findStatusId(Object value, IdentityHashMap<Object, Boolean> seen, int depth) {
        // The current status item is wrapped differently between playback pages. Traverse
        // its small object graph deeply enough to reach the MessageKey, but accept an ID
        // only when it exactly matches a receipt WhatsApp itself already suppressed.
        if (value == null || depth > 12 || seen.put(value, Boolean.TRUE) != null) return null;
        if (value instanceof String) { synchronized (PENDING_STATUS_RECEIPTS) { return PENDING_STATUS_RECEIPTS.containsKey(value) ? (String)value : null; } }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            for (int i = 0; i < length; i++) {
                String found = findStatusId(Array.get(value, i), seen, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        if (value instanceof Iterable) {
            for (Object child : (Iterable<?>) value) {
                String found = findStatusId(child, seen, depth + 1);
                if (found != null) return found;
            }
            return null;
        }
        Class<?> type=value.getClass(); if (type.getName().startsWith("java.")) return null;
        for (Class<?> c=type;c!=null&&c!=Object.class;c=c.getSuperclass()) for (Field f:c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            try { f.setAccessible(true); String found=findStatusId(f.get(value),seen,depth+1); if(found!=null)return found; } catch(Throwable ignored) {}
        } return null;
    }

    public static boolean isManualStatusSeenEnabled(SharedPreferences prefs) {
        // Show the "Send Seen" button whenever the user has enabled it, regardless of
        // whether a stealth/hide-status-view setting is also active. The receipt capture
        // in HideReceiptsHook is already gated by shouldHideStatusView, so on a miss the
        // button simply shows a "no receipt to send" toast — which is the correct UX.
        return prefs != null && prefs.getBoolean("manual_status_seen", false);
    }

    public static boolean isManualStatusSeenMenuPlacement(SharedPreferences prefs) {
        return isManualStatusSeenEnabled(prefs)
                && "menu".equals(prefs.getString("manual_status_seen_placement", "menu"));
    }

    public static boolean isManualStatusReceipt(Object job) {
        return XposedHelpers.getAdditionalInstanceField(job, "waex_manual_status_seen") == MANUAL_STATUS_RECEIPT;
    }

    /** Explicit chat receipt created by the manual/blue-on-reply action. */
    public static boolean isManualChatReceipt(Object job) {
        return Boolean.TRUE.equals(XposedHelpers.getAdditionalInstanceField(job, "blue_on_reply"));
    }

    private static Object getOrFindJobManager() {
        if (jobManager != null) return jobManager;
        if (managerClass != null) {
            try {
                for (Field field : managerClass.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) && managerClass.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object instance = field.get(null);
                        if (instance != null) {
                            jobManager = instance;
                            return jobManager;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static int sendPendingReadJobs(String jid) {
        String key = canonicalJid(jid);
        if (key == null) {
            // Preserve upstream's exact outgoing-recipient guard and sanitized diagnostic.
            XposedBridge.log(TAG + " Cannot dispatch: outgoing recipient is unavailable");
            return 0;
        }

        Method enqueue = enqueueJobMethod;
        Object manager = getOrFindJobManager();
        if (enqueue == null || manager == null) {
            XposedBridge.log(TAG + " Cannot dispatch: WaJobManager or enqueueJobMethod is not ready");
            return 0;
        }

        // Exact canonical JID match only. @lid, @s.whatsapp.net and @g.us are
        // distinct identity namespaces; same local user part is NOT proof that
        // their queued read receipts belong to the same recipient.
        // Keep records until WhatsApp has accepted their explicit jobs. This mirrors the
        // reliable chat flow: a temporary manager/constructor failure must not make a
        // user's hidden messages impossible to mark read on the next tap or reply.
        List<ReadJobRecipe> pending = PENDING_JOBS.snapshot(key);
        if (pending.isEmpty()) {
            return 0;
        }

        // Do not authorize an entire JID: only IDs from explicitly enqueued jobs.
        int sent = 0;
        for (ReadJobRecipe recipe : pending) {
            try {
                HideReceiptsHook.allowExplicitReceiptIds(key, recipe.arguments);
                Object job = recipe.constructor.newInstance(copyArguments(recipe.arguments));
                XposedHelpers.setAdditionalInstanceField(job, "blue_on_reply", true);
                enqueue.invoke(manager, job);
                PENDING_JOBS.remove(key, recipe);
                sent++;
            } catch (Throwable t) {
                // Constructor or dispatch failure must not leave a stale permission.
                HideReceiptsHook.revokeExplicitReceiptIds(key, recipe.arguments);
                XposedBridge.log(TAG + " Error enqueueing read receipt: "
                        + t.getClass().getSimpleName());
            }
        }
        return sent;
    }

    /**
     * The verified SendE2EMessageJob host declares a String field named "jid".
     * Never scan arbitrary job fields: they may contain participant or device IDs
     * that are valid JIDs but do not identify the outbound conversation.
     * Unknown hosts fail closed rather than releasing another chat's receipts.
     */
    private static String extractOutgoingJobJid(Object job) {
        if (job == null) return null;
        try {
            Object value = XposedHelpers.getObjectField(job, "jid");
            return value instanceof String
                    ? ReceiptJidPolicy.canonical((String) value) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void hookConversationMenu() {
        try {
            Class<?> conversationClass = XposedHelpers.findClassIfExists(
                    "com.whatsapp.Conversation", classLoader);
            if (conversationClass == null) {
                XposedBridge.log(TAG + " Conversation class not found");
                return;
            }

            Set<Method> menuMethods = new LinkedHashSet<>();
            Class<?> current = conversationClass;
            while (current != null && current != Object.class) {
                String className = current.getName();
                if (className.startsWith("android.") || className.startsWith("androidx.")) break;
                for (Method method : current.getDeclaredMethods()) {
                    Class<?>[] parameters = method.getParameterTypes();
                    for (Class<?> parameter : parameters) {
                        if (parameter == Menu.class) {
                            menuMethods.add(method);
                            break;
                        }
                    }
                }
                current = current.getSuperclass();
            }
            if (menuMethods.isEmpty()) {
                XposedBridge.log(TAG + " No Conversation method accepting Menu found");
                return;
            }

            XC_MethodHook menuHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // Some menu methods live on a base activity shared by HomeActivity and
                    // Conversation. A hook on that declaration runs for every subclass, so never
                    // inject unless the current receiver is the real chat screen.
                    if (param.thisObject == null
                            || !conversationClass.isInstance(param.thisObject)) return;
                    Menu menu = null;
                    for (Object argument : param.args) {
                        if (argument instanceof Menu) {
                            menu = (Menu) argument;
                            break;
                        }
                    }
                    injectConversationMenuItem(menu, param.thisObject);
                }
            };
            for (Method menuMethod : menuMethods) {
                menuMethod.setAccessible(true);
                XposedBridge.hookMethod(menuMethod, menuHook);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error adding conversation blue-tick menu: " + t.getClass().getSimpleName());
        }
    }

    private void injectConversationMenuItem(Menu menu, Object conversation) {
        if (menu == null) return;
        if (!isManualButtonEnabled()) {
            menu.removeItem(MENU_SEND_BLUE_TICK);
            return;
        }
        if (menu.findItem(MENU_SEND_BLUE_TICK) != null) return;

        MenuItem item = menu.add(Menu.NONE, MENU_SEND_BLUE_TICK, Menu.NONE, UiText.get(R.string.waex_ui_send_blue_tick, "Send blue tick"));
        Drawable icon = MenuIconLoader.load(context, "ic_waex_mark_read");
        if (icon == null) {
            int iconId = context.getResources().getIdentifier(
                    "ic_notif_mark_read", "drawable", "com.whatsapp");
            if (iconId != 0) item.setIcon(iconId);
        } else {
            item.setIcon(icon);
        }
        item.setShowAsAction(manualButtonPlacement() == 1
                ? MenuItem.SHOW_AS_ACTION_ALWAYS : MenuItem.SHOW_AS_ACTION_NEVER);
        item.setOnMenuItemClickListener(clicked -> {
            String jid = resolveConversationJid(conversation);
            int sent = jid == null ? 0 : sendPendingReadJobs(jid);
            Context toastContext = conversation instanceof Context
                    ? (Context) conversation : context;
            Toast.makeText(toastContext,
                    jid == null ? UiText.get(R.string.waex_l10n_recipient_unconfirmed, "Cannot verify chat recipient")
                            : (sent > 0 ? UiText.get(R.string.waex_ui_blue_tick_sent, "Seen sent") : UiText.get(R.string.waex_ui_no_hidden_read_receipt_to_send, "No hidden read receipt to send")),
                    Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private boolean isManualButtonEnabled() {
        if (!isHideBlueTicksEnabled()) return false;
        Object value = prefs.getAll().get("seentick");
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).intValue() != 0;
        if (value instanceof String) {
            String text = (String) value;
            return !text.isEmpty() && !"0".equals(text) && !"false".equalsIgnoreCase(text);
        }
        return false;
    }

    private boolean isHideBlueTicksEnabled() {
        return isEnabled("hide_read_receipts", false) || isEnabled("hideread", false);
    }

    /** 1 = toolbar icon, 2 = overflow menu. Boolean legacy values migrate as toolbar mode. */
    private int manualButtonPlacement() {
        Object value = prefs.getAll().get("seentick");
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return isManualButtonEnabled() ? 1 : 0;
    }

    /**
     * For a manual user action, require an explicit and unambiguous chat address.
     * Scanning the Activity's fields or arbitrary Intent extras risks selecting a
     * different contact's pending receipt job, so unknown hosts fail closed.
     */
    private String resolveConversationJid(Object conversation) {
        if (!(conversation instanceof Activity)) return null;
        try {
            Intent intent = ((Activity) conversation).getIntent();
            if (intent == null) return null;
            List<String> candidates = new ArrayList<>();
            if (intent.getData() != null) {
                String exactData = canonicalJid(intent.getData().toString());
                if (exactData != null) candidates.add(exactData);
            }
            Bundle extras = intent.getExtras();
            if (extras != null) {
                String[] keys = {"jid", "chat_jid", "remote_jid",
                        "extra_chat_jid", "conversation_jid"};
                for (String key : keys) {
                    if (!extras.containsKey(key)) continue;
                    String value = extractJidString(extras.get(key));
                    if (value == null) return null;
                    candidates.add(value);
                }
            }
            return ConversationRecipientPolicy.resolve(candidates);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String extractJidString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) {
            return canonicalJid((String) obj);
        }
        try {
            Object raw = XposedHelpers.callMethod(obj, "getRawString");
            if (raw != null) {
                String c = canonicalJid(raw.toString());
                if (c != null) return c;
            }
        } catch (Throwable ignored) {}
        return canonicalJid(obj.toString());
    }

    private static String canonicalJid(String value) {
        return ReceiptJidPolicy.canonical(value);
    }

    @NonNull
    @Override
    public String getName() {
        return "Blue on Reply";
    }
}
