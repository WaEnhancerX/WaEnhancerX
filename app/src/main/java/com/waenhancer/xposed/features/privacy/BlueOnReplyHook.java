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
import com.waenhancer.xposed.utils.HookErrors;
import com.waenhancer.xposed.utils.HostFields;
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
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;
import com.waenhancer.xposed.core.privacy.PrivacyToggle;
import com.waenhancer.xposed.core.wa.WaJid;

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
    private static final String MANUAL_CHAT_ATTEMPT = "waex_manual_chat_attempt";
    /** sendPendingReadJobs result when a privacy setting still withholds this chat's receipts. */
    private static final int SEND_BLOCKED = -1;
    /** Upper bound of owed receipts sent from the ledger in one action, and IDs per job. */
    private static final int MAX_LEDGER_SEND = 500;
    private static final int IDS_PER_JOB = 100;
    /** Sending reads the ledger; never do that on WhatsApp's UI or job threads. */
    private static final ExecutorService SEND_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "WAEX-ReadReceipts");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

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
        // Owed receipts from the persistent ledger are rebuilt from stored JIDs. Say once if
        // that is impossible on this build, instead of silently sending only session receipts.
        if (sendReadConstructor != null && WaJid.create(context, classLoader, "status@broadcast") == null) {
            XposedBridge.log(TAG + " Owed read receipts cannot be rebuilt: Jid factory unavailable");
        }
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
                        if (remoteJid != null) SEND_EXECUTOR.execute(() -> sendPendingReadJobs(remoteJid));
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking Blue on Reply: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Replying sends every owed read receipt of that chat, whichever privacy setting withheld
     * it, including messages from before Blue-on-reply was enabled.
     */
    public boolean isBlueOnReplyEnabled() {
        return getPrivacy().flag(PREF_KEY);
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
        Object attempt = XposedHelpers.getAdditionalInstanceField(job, MANUAL_CHAT_ATTEMPT);
        if (attempt instanceof ManualReadAttempt) {
            // A local privacy veto is not a successful receipt. Retain only the original
            // action-time recipe for another explicit action, never for automatic retry.
            ((ManualReadAttempt) attempt).recordLocalVeto();
            return;
        }
        Object value = XposedHelpers.getAdditionalInstanceField(job, PENDING_CHAT_RECEIPT);
        if (!(value instanceof PendingChatReceipt)) {
            return;
        }
        PendingChatReceipt pending = (PendingChatReceipt) value;
        PENDING_JOBS.add(pending.recipient, pending.recipe);
        XposedHelpers.removeAdditionalInstanceField(job, PENDING_CHAT_RECEIPT);
    }

    /** Coordinates enqueue acknowledgement with synchronous or delayed local vetoes. */
    private static final class ManualReadAttempt {
        final String recipient;
        final ReadJobRecipe original;
        final ReadJobRecipe retryRecipe;
        private boolean accepted;
        private boolean vetoed;

        ManualReadAttempt(String recipient, ReadJobRecipe recipe) {
            this.recipient = recipient;
            this.original = recipe;
            // The synthetic or host job may mutate its arrays after construction.
            // Retry the private original snapshot, not fields from the executing job.
            this.retryRecipe = new ReadJobRecipe(recipe.constructor, copyArguments(recipe.arguments));
        }

        synchronized void acknowledgeEnqueue() {
            if (accepted) return;
            accepted = true;
            PENDING_JOBS.remove(recipient, original);
            Context context = appContext;
            if (context != null) UnsentReadLedger.markSent(context, recipient, idsOf(original));
            if (vetoed) restoreRecipe();
        }

        synchronized void recordLocalVeto() {
            if (vetoed) return;
            vetoed = true;
            // A synchronous veto inside enqueue must wait for its acknowledgement.
            // If enqueue throws, the original remains pending and no duplicate is added.
            if (accepted) restoreRecipe();
        }

        private void restoreRecipe() {
            PENDING_JOBS.add(recipient, retryRecipe);
            Context context = appContext;
            if (context != null) UnsentReadLedger.markOwed(context, recipient, idsOf(original));
        }
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
            for (Field field : HostFields.declared(type)) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType() != String[].class) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(job);
                    if (!(value instanceof String[])) continue;
                    for (String id : (String[]) value) {
                        if (id != null && !id.isEmpty()) ids.add(id);
                    }
                } catch (Throwable ignored) { HookErrors.swallowed("BlueOnReplyHook", ignored); }
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
        // Blue-on-reply / the manual button are not installed: the persistent ledger already
        // holds this message, so there is nothing to stage and nothing worth logging.
        if (ctor == null) return;
        Object[] args = buildReadJobArgs(ctor, typedJid, null, new String[]{messageId});
        if (args == null) return;
        ReadJobRecipe recipe = new ReadJobRecipe(ctor, args);
        PENDING_JOBS.add(key, recipe);
    }

    /** Message IDs carried by a recipe's String[] arguments. */
    private static List<String> idsOf(ReadJobRecipe recipe) {
        List<String> ids = new ArrayList<>();
        for (Object argument : recipe.arguments) {
            if (!(argument instanceof String[])) continue;
            for (String id : (String[]) argument) {
                if (id != null && !id.isEmpty()) ids.add(id);
            }
        }
        return ids;
    }

    /**
     * Arguments for WhatsApp's read-receipt job: the chat in the first Jid slot that accepts it,
     * the group sender in the next one (groups only), the IDs in the String[] slot, neutral
     * defaults elsewhere. Returns null if any required slot cannot be filled.
     */
    private static Object[] buildReadJobArgs(Constructor<?> ctor, Object chat, Object participant,
                                             String[] messageIds) {
        if (chat == null || chat instanceof String) return null;
        Class<?>[] params = ctor.getParameterTypes();
        Object[] args = new Object[params.length];
        boolean chatSet = false, participantSet = participant == null, idsSet = false;
        for (int i = 0; i < params.length; i++) {
            Class<?> p = params[i];
            if (!idsSet && p == String[].class) {
                args[i] = messageIds;
                idsSet = true;
            } else if (!chatSet && p.isInstance(chat)) {
                args[i] = chat;
                chatSet = true;
            } else if (chatSet && !participantSet && p.isInstance(participant)) {
                args[i] = participant;
                participantSet = true;
            } else if (p == boolean.class) {
                args[i] = false;
            } else if (p == int.class) {
                args[i] = 0;
            } else if (p == long.class) {
                args[i] = 0L;
            } else if (p.isPrimitive()) {
                return null; // An unknown primitive slot has no safe default.
            }
        }
        return chatSet && participantSet && idsSet ? args : null;
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
        for (Class<?> c=type;c!=null&&c!=Object.class;c=c.getSuperclass()) for (Field f:HostFields.declared(c)) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            try { f.setAccessible(true); String found=findStatusId(f.get(value),seen,depth+1); if(found!=null)return found; } catch (Throwable ignored) { HookErrors.swallowed("BlueOnReplyHook", ignored); }
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
                for (Field field : HostFields.declared(managerClass)) {
                    if (Modifier.isStatic(field.getModifiers()) && managerClass.isAssignableFrom(field.getType())) {
                        field.setAccessible(true);
                        Object instance = field.get(null);
                        if (instance != null) {
                            jobManager = instance;
                            return jobManager;
                        }
                    }
                }
            } catch (Throwable ignored) { HookErrors.swallowed("BlueOnReplyHook", ignored); }
        }
        return null;
    }

    /**
     * Sends every owed read receipt of a chat: first the exact jobs WhatsApp built this session,
     * then everything else recorded in the persistent ledger (also from before a restart).
     * Returns the number of jobs enqueued, or SEND_BLOCKED while Ghost Mode or Hide delivered
     * still withholds this chat's receipts (they stay owed). Never call on the main thread.
     */
    private int sendPendingReadJobs(String jid) {
        String key = canonicalJid(jid);
        if (key == null) {
            // Preserve upstream's exact outgoing-recipient guard and sanitized diagnostic.
            XposedBridge.log(TAG + " Cannot dispatch: outgoing recipient is unavailable");
            return 0;
        }
        if (getPrivacy().appliesTo(context, PrivacyToggle.HIDE_DELIVERY, key)) return SEND_BLOCKED;

        Method enqueue = enqueueJobMethod;
        Object manager = getOrFindJobManager();
        if (enqueue == null || manager == null) {
            XposedBridge.log(TAG + " Cannot dispatch: WaJobManager or enqueueJobMethod is not ready");
            return 0;
        }

        // Exact recipes captured this session. Keep them until WhatsApp accepts their jobs.
        int sent = 0;
        Set<String> covered = new HashSet<>();
        for (ReadJobRecipe recipe : PENDING_JOBS.snapshot(key)) {
            if (enqueueExplicit(key, recipe, enqueue, manager)) {
                sent++;
                covered.addAll(idsOf(recipe));
            }
        }
        return sent + sendOwedFromLedger(key, covered, enqueue, manager);
    }

    private int sendOwedFromLedger(String key, Set<String> covered, Method enqueue, Object manager) {
        Constructor<?> ctor = sendReadConstructor;
        if (ctor == null) return 0;
        // Group rows need their sender; rows without one stay owed rather than be misaddressed.
        Map<String, List<HideSeenStore.PendingRead>> batches = new LinkedHashMap<>();
        for (HideSeenStore.PendingRead row : UnsentReadLedger.owed(context, key, MAX_LEDGER_SEND)) {
            if (covered.contains(row.messageId)) continue;
            boolean group = row.chat.endsWith("@g.us");
            if (group && row.participant == null) continue;
            batches.computeIfAbsent(row.chat + "\n" + (group ? row.participant : ""),
                    k -> new ArrayList<>()).add(row);
        }
        int sent = 0;
        for (List<HideSeenStore.PendingRead> rows : batches.values()) {
            HideSeenStore.PendingRead first = rows.get(0);
            Object chat = WaJid.create(context, classLoader, first.chat);
            Object participant = first.participant != null && first.chat.endsWith("@g.us")
                    ? WaJid.create(context, classLoader, first.participant) : null;
            if (chat == null || (first.chat.endsWith("@g.us") && participant == null)) continue;
            for (int start = 0; start < rows.size(); start += IDS_PER_JOB) {
                List<HideSeenStore.PendingRead> chunk = rows.subList(start, Math.min(rows.size(), start + IDS_PER_JOB));
                String[] ids = new String[chunk.size()];
                for (int i = 0; i < ids.length; i++) ids[i] = chunk.get(i).messageId;
                Object[] args = buildReadJobArgs(ctor, chat, participant, ids);
                if (args == null) break;
                if (enqueueExplicit(first.chat, new ReadJobRecipe(ctor, args), enqueue, manager)) sent++;
            }
        }
        return sent;
    }

    /** Enqueue one explicit read job; only its exact IDs may bypass read hiding. */
    private static boolean enqueueExplicit(String recipient, ReadJobRecipe recipe, Method enqueue, Object manager) {
        try {
            Object grant = ExplicitReadReceiptPolicy.captureJobGrant(recipient, recipe.arguments);
            if (grant == null) return false;
            HideReceiptsHook.allowExplicitReceiptIds(recipient, recipe.arguments);
            Object job = recipe.constructor.newInstance(copyArguments(recipe.arguments));
            XposedHelpers.setAdditionalInstanceField(job, "blue_on_reply", true);
            XposedHelpers.setAdditionalInstanceField(job, ExplicitReadReceiptPolicy.JOB_GRANT_KEY, grant);
            ManualReadAttempt attempt = new ManualReadAttempt(recipient, recipe);
            XposedHelpers.setAdditionalInstanceField(job, MANUAL_CHAT_ATTEMPT, attempt);
            enqueue.invoke(manager, job);
            attempt.acknowledgeEnqueue();
            return true;
        } catch (Throwable t) {
            // Constructor or dispatch failure must not leave a stale permission.
            HideReceiptsHook.revokeExplicitReceiptIds(recipient, recipe.arguments);
            XposedBridge.log(TAG + " Error enqueueing read receipt: " + t.getClass().getSimpleName());
            return false;
        }
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
            Context toastContext = conversation instanceof Context
                    ? (Context) conversation : context;
            SEND_EXECUTOR.execute(() -> {
                int sent = jid == null ? 0 : sendPendingReadJobs(jid);
                String text;
                if (jid == null) {
                    text = UiText.get(R.string.waex_l10n_recipient_unconfirmed, "Cannot verify chat recipient");
                } else if (sent == SEND_BLOCKED) {
                    // Still withheld; the receipts stay owed until that setting is turned off.
                    text = getPrivacy().isOn(PrivacyToggle.GHOST_MODE)
                            ? UiText.get(R.string.waex_ui_managed_by_ghost_mode, "Managed by Ghost Mode")
                            : UiText.get(R.string.waex_ui_hide_delivered_receipts, "Hide Delivered Receipts");
                } else if (sent > 0) {
                    text = UiText.get(R.string.waex_ui_blue_tick_sent, "Seen sent");
                } else {
                    text = UiText.get(R.string.waex_ui_no_hidden_read_receipt_to_send, "No hidden read receipt to send");
                }
                MAIN.post(() -> Toast.makeText(toastContext, text, Toast.LENGTH_SHORT).show());
            });
            return true;
        });
    }

    /** The button sends receipts withheld by any privacy setting, so it only needs its own switch. */
    private boolean isManualButtonEnabled() {
        return getPrivacy().flag("seentick");
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
        } catch (Throwable ignored) { HookErrors.swallowed("BlueOnReplyHook", ignored); }
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
