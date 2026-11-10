package com.waenhancer.xposed.features.privacy;


import com.waenhancer.app.R;
import com.waenhancer.utils.UiText;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HeaderViewListAdapter;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ConcurrentHashMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.db.DelMessageStore;
import com.waenhancer.xposed.features.conversation.PreserveDeleteForMeHook;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import com.waenhancer.xposed.features.homescreen.MenuIconLoader;
import com.waenhancer.xposed.utils.ActivityTracker;
import com.waenhancer.licensing.features.HideAntiRevokeProFeature;

import com.waenhancer.utils.ContactNameResolver;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.Date;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.waenhancer.xposed.features.automation.PresenceToastsHook;
import java.lang.reflect.Constructor;
import java.util.IdentityHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anti-Revoke & Deleted Indicator Engine for Messages and Statuses.
 *
 * Strategy:
 *  1. Block SQLite DELETE on 'message' and 'status' / 'status_v3' tables.
 *  2. Block SQLite INSERT of message_type=15 placeholders.
 *  3. Block bytecode revocation calls for both chat messages and status stories.
 *  4. Persist revoked message/status key_ids in DelMessageStore for cross-session survival.
 *  5. Decorate revoked messages in Conversation via ListView adapter getView hook.
 *  6. Decorate revoked statuses in Status Playback viewer header.
 */
public class AntiRevokeHook extends BaseFeature {

    private static final String TAG = "[WAEX][AntiRevoke]";
    private static final int TAG_DECORATED = 0x57415244; // 'WARD'
    private static final int TAG_KEY_ID = 0x57414B59;    // 'WAKY'
    private static volatile int sDateResId = 0;
    private static volatile int sMsgTextResId = 0;
    private static final ConcurrentHashMap<Class<?>, Field> S_CLASS_TO_KEY_FIELD = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Field> S_KEY_CLASS_TO_ID_FIELD = new ConcurrentHashMap<>();
    private final Set<Class<?>> recyclerBindHooks = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Map<String, String> revokedTextKeyCache = Collections.synchronizedMap(new LinkedHashMap<String, String>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 128; }
    });

    // Persistent history stays in DelMessageStore; in-memory lookups are bounded.
    private static final int MAX_RECENT_REVOKES = 4096;
    private static final Map<String, Long> REVOKED_MESSAGES = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(256, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_RECENT_REVOKES;
                }
            });
    private static final Set<String> PRESERVED_OWN_ROW_IDS = Collections.synchronizedSet(
            new LinkedHashSet<String>() {
                @Override public boolean add(String value) {
                    boolean added = super.add(value);
                    if (size() > MAX_RECENT_REVOKES) {
                        Iterator<String> it = iterator();
                        if (it.hasNext()) { it.next(); it.remove(); }
                    }
                    return added;
                }
            });
    private static final ThreadLocal<DateFormat> TIME_FORMAT = new ThreadLocal<DateFormat>() {
        @Override protected DateFormat initialValue() {
            return DateFormat.getTimeInstance(DateFormat.SHORT);
        }
    };
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Resolved WhatsApp FMessage class — used to guard getView items. */
    private Class<?> fMessageClass;

    /** Resolved FMessage Key class and reflection cache. */
    private Class<?> messageKeyClass;
    /** WhatsApp's status-store key type, resolved independently from message keys. */
    private Class<?> statusKeyClass;
    /** Maps WhatsApp's status model to its backing FMessage for playback decoration. */
    private Method statusToMessageMethod;
    private Class<?> statusModelClass;
    private volatile Object statusToMessageMapper;
    private volatile Field cachedFMessageKeyField;
    private volatile Field cachedKeyIdField;
    /** Track previous getView hook so we can unhook on adapter change. */
    private volatile XC_MethodHook.Unhook previousGetViewHook;
    /** Track the currently hooked adapter and ListView. */
    private volatile ListAdapter currentAdapter;
    private static WeakReference<ListView> activeListView = new WeakReference<>(null);

    public AntiRevokeHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @Override
    public void hook() throws Throwable {
        initMessageClasses();
        hookBytecodeRevocation();
        hookStatusBytecodeRevocation();
        hookDatabaseRevocation();
        HideAntiRevokeProFeature.install(classLoader, prefs,
                new HideAntiRevokeProFeature.HostBridge() {
                    @Nullable
                    @Override
                    public String resolveConversationJid(@NonNull Activity activity) {
                        return ContactNameResolver.INSTANCE.extractJidFromActivity(activity);
                    }

                    @Nullable
                    @Override
                    public Activity getCurrentConversation() {
                        return ActivityTracker.getCurrentConversation();
                    }

                    @Override
                    public boolean isAntiRevokeAvailable() {
                        return isAntiRevokeEnabled() || isPreserveOwnRevokeEnabled();
                    }

                    @Override
                    public void refreshConversation(@NonNull Activity activity) {
                        HideAntiRevokeProFeature.refreshConversationRows(
                                activity, currentAdapter, activeListView);
                    }
                });
        hookListViewAdapter();
        hookStatusPlaybackUI();
    }

    // ─── Key class resolution ────────────────────────────────────────────

    private void initMessageClasses() {
        try {
            // ── Resolve FMessage class ──
            fMessageClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "waex_fmessage_class",
                    (bridge, loader) -> {
                        MethodData data = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .addUsingString("FMessage/getSenderUserJid/key.id",
                                                StringMatchType.Contains)))
                                .firstOrNull();
                        if (data != null) {
                            return data.getMethodInstance(loader).getDeclaringClass();
                        }
                        return null;
                    });

            if (fMessageClass != null) {
            }

            // ── Resolve Key class ──
            messageKeyClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "waex_message_key_class",
                    (bridge, loader) -> {
                        ClassDataList list = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().fieldCount(3)
                                        .addMethod(MethodMatcher.create()
                                                .addUsingString("Key", StringMatchType.Contains)
                                                .name("toString"))));
                        for (ClassData cd : list) {
                            return cd.getInstance(loader);
                        }
                        return null;
                    });

            if (messageKeyClass != null) {
            }

            statusToMessageMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "waex_status_to_message_mapper_v2",
                    (bridge, loader) -> {
                        Set<Method> candidates = new LinkedHashSet<>();
                        for (MethodData data : bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create().addUsingString(
                                        "mapFStatusToFMessageForForwarding", StringMatchType.Contains)))) {
                            candidates.add(data.getMethodInstance(loader));
                        }
                        return StatusHookContract.selectStatusMapper(candidates, fMessageClass);
                    });
            if (StatusHookContract.isStatusMapper(statusToMessageMethod, fMessageClass)) {
                statusToMessageMethod.setAccessible(true);
                statusModelClass = statusToMessageMethod.getParameterTypes()[0];
                XposedBridge.hookAllConstructors(statusToMessageMethod.getDeclaringClass(),
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                statusToMessageMapper = param.thisObject;
                            }
                        });
            } else {
                statusToMessageMethod = null;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error initializing message key classes: " + t.getClass().getSimpleName());
        }
    }

    // ─── Bytecode revocation hook ────────────────────────────────────────

    private void hookBytecodeRevocation() {
        try {
            Method revokeMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "waex_core_msgstore_revoke",
                    (bridge, loader) -> {
                        String[] anchors = {
                                "msgstore/edit/revoke",
                                "msgstore/add/revoke",
                                "CoreMessageStore/revoke",
                                "RevokeMessageStore/revoke",
                                "revoke_message"
                        };
                        for (String anchor : anchors) {
                            try {
                                MethodData data = bridge.findMethod(FindMethod.create()
                                        .matcher(MethodMatcher.create().usingStrings(anchor))
                                ).firstOrNull();
                                if (data != null) {
                                    Method m = data.getMethodInstance(loader);
                                    if (m.getParameterTypes().length >= 1) return m;
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });

            if (revokeMethod != null) {
                XposedBridge.hookMethod(revokeMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        // Run if either anti-revoke (incoming) or preserve-own-revoke is enabled.
                        if (!isAntiRevokeEnabled() && !isPreserveOwnRevokeEnabled()) return;

                        try {
                            Object fMessage = (param.args != null && param.args.length > 0) ? param.args[0] : null;
                            if (fMessage == null) return;

                            Boolean fromMe = extractIsFromMe(fMessage);
                            if (fromMe == null) return;

                            String keyId = extractKeyIdFromFMessage(fMessage);
                            if (keyId == null || keyId.isEmpty()) return;

                            if (fromMe) {
                                // Own-message "delete for everyone".
                                // Record the key so the UI decorator can show the deleted indicator,
                                // but do NOT intercept the method — WhatsApp must proceed so the
                                // revocation signal is sent to recipients over the network.
                                // Show-as-deleted is itself a presentation mode. It must also
                                // retain the revoke marker for own messages; otherwise the
                                // original bubble remains visible and there is no key to bind
                                // WhatsApp's native revoked row on the next adapter pass.
                                if (isPreserveOwnRevokeEnabled()
                                        || HideAntiRevokeProFeature.isHiddenForActiveConversation(prefs)) {
                                    long now = System.currentTimeMillis();
                                    REVOKED_MESSAGES.put(keyId, now);
                                    DelMessageStore.getInstance(context).insertMessage(null, keyId, now);
                                    dispatchRealtimeRevokeUI(keyId, now);
                                }
                                // Fall through — do NOT call setHandledResult / param.setResult.
                            } else {
                                // Incoming message being revoked by the sender.
                                if (!isAntiRevokeEnabled()) return;
                                long now = System.currentTimeMillis();
                                REVOKED_MESSAGES.put(keyId, now);
                                DelMessageStore.getInstance(context).insertMessage(null, keyId, now);
                                dispatchRealtimeRevokeUI(keyId, now);
                                showDeletedMessageToast(extractSenderJid(fMessage));
                                // Keep the original incoming message row. The anti-revoke
                                // indicator is rendered from the persisted key below.
                                setHandledResult(param);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " Error extracting key from bytecode revoke: " + t.getClass().getSimpleName());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking bytecode revoke: " + t.getClass().getSimpleName());
        }
    }

    private void hookStatusBytecodeRevocation() {
        try {
            statusKeyClass = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "waex_status_key_class_v3",
                    (bridge, loader) -> {
                        Class<?> selected = null;
                        for (ClassData data : bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .addUsingString("Key(id=", StringMatchType.Contains)
                                        .addUsingString("senderJid", StringMatchType.Contains)))) {
                            Class<?> candidate = data.getInstance(loader);
                            if (!StatusHookContract.supportsKeyClass(candidate)) continue;
                            if (selected != null && selected != candidate) return null;
                            selected = candidate;
                        }
                        return selected;
                    });

            Method revokeStatusMethod = DexSearchEngine.getInstance().findMethodWithCache(
                    context, classLoader, "waex_revoke_status_manager_v3",
                    (bridge, loader) -> {
                        if (statusKeyClass == null) return null;

                        Set<Method> candidates = new LinkedHashSet<>();
                        for (ClassData managerData : bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create()
                                        .addUsingString("RevokeStatusManager/failed", StringMatchType.Contains)))) {
                            Class<?> current = managerData.getInstance(loader);
                            while (current != null && current != Object.class) {
                                for (Method method : current.getDeclaredMethods()) {
                                    candidates.add(method);
                                }
                                current = current.getSuperclass();
                            }
                        }
                        return StatusHookContract.selectRevokeMethod(candidates, statusKeyClass);
                    });

            final StatusHookContract contract =
                    StatusHookContract.resolveRevoke(revokeStatusMethod, statusKeyClass);
            if (contract != null) {
                XposedBridge.hookMethod(revokeStatusMethod, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!isAntiRevokeStatusEnabled()) return;
                        try {
                            StatusHookContract.Target target = contract.incomingTarget(param.args);
                            if (target == null) return;

                            long now = System.currentTimeMillis();
                            REVOKED_MESSAGES.put(target.id, now);
                            DelMessageStore.getInstance(context).insertMessage(null, target.id, now);
                            param.setResult(contract.acknowledgement());
                            showDeletedStatusToast(resolveStatusSenderJid(
                                    target.key, param.args != null ? param.args[0] : null));
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " Error in status bytecode revoke hook: " + t.getClass().getSimpleName());
                        }
                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking status bytecode revoke: " + t.getClass().getSimpleName());
        }
    }

    /** Completes an intercepted revoke with a value compatible with its runtime method. */
    private void setHandledResult(@NonNull XC_MethodHook.MethodHookParam param) {
        Class<?> returnType = ((Method) param.method).getReturnType();
        if (returnType == boolean.class || returnType == Boolean.class) {
            param.setResult(true);
        } else if (returnType == byte.class || returnType == Byte.class) {
            param.setResult((byte) 0);
        } else if (returnType == short.class || returnType == Short.class) {
            param.setResult((short) 0);
        } else if (returnType == int.class || returnType == Integer.class) {
            param.setResult(0);
        } else if (returnType == long.class || returnType == Long.class) {
            param.setResult(0L);
        } else if (returnType == float.class || returnType == Float.class) {
            param.setResult(0f);
        } else if (returnType == double.class || returnType == Double.class) {
            param.setResult(0d);
        } else if (returnType == char.class || returnType == Character.class) {
            param.setResult('\0');
        } else {
            param.setResult(null);
        }
    }

    // ─── Database revocation hooks ───────────────────────────────────────

    private void hookDatabaseRevocation() {
        try {
            // 1. Intercept DELETE on 'message' and 'status' / 'status_v3' tables
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "delete", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    String table = (String) param.args[0];
                    if (table == null) return;

                    boolean isMsgTable = "message".equals(table) || "messages".equals(table);
                    boolean isStatusTable = "status".equals(table) || "status_v3".equals(table);

                    if (!isMsgTable && !isStatusTable) return;
                    // A user-initiated Delete-for-me must remove the WhatsApp row. The dedicated
                    // preservation hook has already copied its contents to the WAEX vault.
                    if (PreserveDeleteForMeHook.isDeleteForMeInProgress()) return;

                    String where = (String) param.args[1];
                    String[] whereArgs = param.args.length > 2 ? (String[]) param.args[2] : null;
                    SQLiteDatabase db = (SQLiteDatabase) param.thisObject;

                    // Handle Status table deletion
                    if (isStatusTable) {
                        if (!isAntiRevokeStatusEnabled()) return;
                        // A whole-table cleanup has no identified revoke target.
                        if (where == null || where.trim().isEmpty()) return;
                        try {
                            String query = "SELECT * FROM " + table + " WHERE " + where;
                            Set<String> keys = readRemoteStatusKeys(db, query, whereArgs);
                            if (keys == null || keys.isEmpty()) return;

                            // Validate and close the entire cursor before either suppressing the
                            // delete or writing markers. A mixed/unknown batch stays with the host.
                            param.setResult(0);
                            long now = System.currentTimeMillis();
                            for (String keyId : keys) {
                                REVOKED_MESSAGES.put(keyId, now);
                                // No sender identity was verified by this query.
                                DelMessageStore.getInstance(context).insertMessage(null, keyId, now);
                            }
                            showDeletedMessageToast(null);
                        } catch (Throwable t) {
                            // Query/close failures leave the host operation untouched. Once a
                            // verified plan suppresses it, marker failures must not delete the rows.
                            XposedBridge.log(TAG + " Error processing status delete: " + t.getClass().getSimpleName());
                        }
                        return;
                    }

                    // Handle Message table deletion
                    if (isMsgTable) {
                        if (!isAntiRevokeEnabled() && !isPreserveOwnRevokeEnabled()) return;
                        if (!isSingleRowIdDelete(where, whereArgs)) return;

                        String msgId = whereArgs[0];
                        try (Cursor cursor = db.rawQuery(
                                "SELECT from_me, message_type, key_id, chat_row_id, sender_jid_row_id FROM " + table + " WHERE _id=?",
                                new String[]{msgId})) {
                            if (cursor != null && cursor.moveToFirst()) {
                                if (cursor.getCount() != 1
                                        || cursor.getType(0) != Cursor.FIELD_TYPE_INTEGER
                                        || cursor.getType(1) != Cursor.FIELD_TYPE_INTEGER
                                        || cursor.getType(2) != Cursor.FIELD_TYPE_STRING) return;
                                long ownership = cursor.getLong(0);
                                if (ownership != 0L && ownership != 1L) return;
                                int fromMe = (int) ownership;
                                int msgType = cursor.getInt(1);
                                String keyId = cursor.getString(2);
                                if (keyId == null || keyId.trim().isEmpty()) return;
                                long chatRowId = cursor.getLong(3);
                                long senderJidRowId = cursor.isNull(4) ? 0L : cursor.getLong(4);

                                if (msgType != 15 && keyId != null) {
                                    boolean preserveIncoming = fromMe == 0 && isAntiRevokeEnabled();
                                    boolean preserveOwnRevoke = fromMe == 1 && isPreserveOwnRevokeEnabled();

                                    if (preserveIncoming) {
                                        // Incoming: record & block the DELETE so the row stays visible.
                                        long now = System.currentTimeMillis();
                                        REVOKED_MESSAGES.put(keyId, now);
                                        DelMessageStore.getInstance(context).insertMessage(String.valueOf(chatRowId), keyId, now);
                                        dispatchRealtimeRevokeUI(keyId, now);
                                        String senderJid = resolveJidRowId(db, senderJidRowId);
                                        if (senderJid == null) senderJid = resolveChatJid(db, chatRowId);
                                        showDeletedMessageToast(senderJid);
                                        param.setResult(0);
                                    } else if (preserveOwnRevoke) {
                                        // Own-message "delete for everyone" with preserve enabled:
                                        // Record the key NOW, then block the DELETE to keep the original
                                        // message row. The insertWithOnConflict hook will detect the key
                                        // in REVOKED_MESSAGES and block the type-15 placeholder insert,
                                        // preventing the UNIQUE PK collision crash.
                                        // Result: original message stays visible with deleted indicator,
                                        // while the revocation is still sent to recipients via network.
                                        long now = System.currentTimeMillis();
                                        REVOKED_MESSAGES.put(keyId, now);
                                        PRESERVED_OWN_ROW_IDS.add(msgId);
                                        DelMessageStore.getInstance(context).insertMessage(String.valueOf(chatRowId), keyId, now);
                                        dispatchRealtimeRevokeUI(keyId, now);
                                        param.setResult(0); // Block DELETE — original row stays.
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + " Error intercepting message delete: " + t.getClass().getSimpleName());
                        }
                    }
                }
            });

            // WhatsApp's own revoke path uses insertOrThrow and reuses the deleted
            // message's primary key. When that row is intentionally preserved, give
            // only the local revoke placeholder a fresh row ID. The insert must still
            // execute and return a real ID so the outbound revoke transaction commits.
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "insertOrThrow", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sanitizePreservedOwnRowId(param);
                }
            });

            // 2. Intercept INSERT of type=15 (revocation placeholder)
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "insertWithOnConflict", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sanitizePreservedOwnRowId(param);
                    if (!isAntiRevokeEnabled() && !isPreserveOwnRevokeEnabled()) return;
                    String table = (String) param.args[0];
                    if (!"message".equals(table) && !"messages".equals(table)) return;

                    ContentValues cv = (ContentValues) param.args[2];
                    if (cv == null) return;
                    SQLiteDatabase db = (SQLiteDatabase) param.thisObject;

                    Integer msgType = cv.getAsInteger("message_type");
                    Integer fromMe = cv.getAsInteger("from_me");

                    boolean preserveIncoming = fromMe != null && fromMe == 0
                            && isAntiRevokeEnabled();
                    boolean preserveOwnRevoke = fromMe != null && fromMe == 1
                            && isPreserveOwnRevokeEnabled();

                    if (msgType != null && msgType == 15) {
                        String keyId = cv.getAsString("key_id");
                        if (keyId != null) {
                            // A bounded cache may evict the key; SQLite is authoritative.
                            boolean alreadyPreserved = REVOKED_MESSAGES.containsKey(keyId)
                                    || DelMessageStore.getInstance(context).getTimestampByMessageId(keyId) > 0L;

                            if (preserveOwnRevoke && alreadyPreserved) {
                                // WhatsApp reuses the original message row ID for its local revoke
                                // placeholder. The original row is intentionally still present, so
                                // let SQLite allocate a distinct placeholder ID. Returning a fake
                                // failure here aborts the revoke pipeline before it reaches peers.
                                cv.remove("_id");
                            } else if (preserveIncoming && alreadyPreserved) {
                                // Incoming revokes have no outbound work to complete. Keep the
                                // original row and discard only their local placeholder.
                                showDeletedMessageToast(resolveSenderJid(db, cv));
                                param.setResult(-1L);
                            } else if (preserveIncoming) {
                                // Fallback: incoming type-15 not seen in delete hook yet.
                                // Record and block the insert so the original row remains visible.
                                long now = System.currentTimeMillis();
                                REVOKED_MESSAGES.put(keyId, now);
                                DelMessageStore.getInstance(context).insertMessage(null, keyId, now);
                                dispatchRealtimeRevokeUI(keyId, now);
                                showDeletedMessageToast(resolveSenderJid(db, cv));
                                param.setResult(-1L);
                            }
                            // If neither: own message with preserve OFF — let WhatsApp write
                            // the type-15 placeholder normally.
                        }
                    }
                }
            });

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Failed to hook SQLiteDatabase: " + t.getClass().getSimpleName());
        }
    }

    /** A bound argument is a row ID only for this exact, single-argument predicate. */
    private boolean isSingleRowIdDelete(@Nullable String where, @Nullable String[] args) {
        return where != null && args != null && args.length == 1 && args[0] != null
                && where.matches("\\s*_id\\s*=\\s*\\?\\s*");
    }

    /**
     * Reads only the explicit legacy status schema. SQL arguments, similarly named
     * columns, unknown ownership and partial batches are not evidence of a status ID.
     * Null means that the original operation must proceed without marker side effects.
     */
    @Nullable
    private Set<String> readRemoteStatusKeys(@NonNull SQLiteDatabase db, @NonNull String query,
                                             @Nullable String[] args) {
        Set<String> keys = new LinkedHashSet<>();
        try (Cursor cursor = db.rawQuery(query, args)) {
            if (cursor == null) return null;
            int keyColumn = -1;
            int ownershipColumn = -1;
            for (int i = 0; i < cursor.getColumnCount(); i++) {
                String column = cursor.getColumnName(i);
                if ("key_id".equalsIgnoreCase(column)) {
                    if (keyColumn >= 0) return null;
                    keyColumn = i;
                } else if ("from_me".equalsIgnoreCase(column)) {
                    if (ownershipColumn >= 0) return null;
                    ownershipColumn = i;
                }
            }
            if (keyColumn < 0 || ownershipColumn < 0) return null;
            while (cursor.moveToNext()) {
                if (cursor.getType(keyColumn) != Cursor.FIELD_TYPE_STRING
                        || cursor.getType(ownershipColumn) != Cursor.FIELD_TYPE_INTEGER
                        || cursor.getLong(ownershipColumn) != 0L) return null;
                String key = cursor.getString(keyColumn);
                if (key == null || key.trim().isEmpty() || !keys.add(key)
                        || keys.size() > MAX_RECENT_REVOKES) return null;
            }
        }
        return keys;
    }

    private void showDeletedMessageToast(@Nullable String senderJid) {
        PresenceToastsHook.showDeletedMessageToast(
                context, prefs, senderJid, uiHandler);
    }

    private void showDeletedStatusToast(@Nullable String senderJid) {
        PresenceToastsHook.showDeletedStatusToast(
                context, prefs, senderJid, uiHandler);
    }

    /**
     * Status target keys commonly expose status@broadcast while the revoke metadata carries
     * the actual viewer/contact identity. Prefer a real user JID from the target, then reuse
     * the metadata key as the same identity source used by ordinary message revoke toasts.
     */
    @Nullable
    private String resolveStatusSenderJid(@Nullable Object targetKey, @Nullable Object metadataKey) {
        String target = extractSenderJid(targetKey);
        if (isUserJid(target)) return target;
        String metadata = extractSenderJid(metadataKey);
        return isUserJid(metadata) ? metadata : null;
    }

    private boolean isUserJid(@Nullable String jid) {
        return jid != null && (jid.contains("@s.whatsapp.net") || jid.endsWith("@lid"));
    }

    @Nullable
    private String resolveSenderJid(@NonNull SQLiteDatabase db, @NonNull ContentValues values) {
        Long senderRowId = values.getAsLong("sender_jid_row_id");
        String jid = resolveJidRowId(db, senderRowId != null ? senderRowId : 0L);
        if (jid != null) return jid;
        Long chatRowId = values.getAsLong("chat_row_id");
        return resolveChatJid(db, chatRowId != null ? chatRowId : 0L);
    }

    @Nullable
    private String resolveJidRowId(@NonNull SQLiteDatabase db, long jidRowId) {
        if (jidRowId <= 0) return null;
        try (Cursor cursor = db.rawQuery(
                "SELECT raw_string FROM jid WHERE _id=? LIMIT 1",
                new String[]{String.valueOf(jidRowId)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Nullable
    private String resolveChatJid(@NonNull SQLiteDatabase db, long chatRowId) {
        if (chatRowId <= 0) return null;
        try (Cursor cursor = db.rawQuery(
                "SELECT j.raw_string FROM chat c JOIN jid j ON j._id=c.jid_row_id "
                        + "WHERE c._id=? LIMIT 1",
                new String[]{String.valueOf(chatRowId)})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Finds the sender/remote JID carried by an FMessage or status key. */
    @Nullable
    private String extractSenderJid(@Nullable Object value) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        collectJids(value, 4,
                Collections.newSetFromMap(new IdentityHashMap<>()),
                candidates);
        for (String jid : candidates) {
            if (jid.contains("@s.whatsapp.net") || jid.endsWith("@lid")) return jid;
        }
        for (String jid : candidates) {
            if (!jid.contains("@broadcast")) return jid;
        }
        return candidates.isEmpty() ? null : candidates.iterator().next();
    }

    private void collectJids(@Nullable Object value, int depth, @NonNull Set<Object> seen,
                             @NonNull Set<String> output) {
        if (value == null || depth < 0 || !seen.add(value)) return;
        try {
            Matcher matcher = Pattern.compile(
                    "[+0-9A-Za-z._:-]+@(?:s\\.whatsapp\\.net|lid|g\\.us|broadcast)")
                    .matcher(value.toString());
            while (matcher.find()) output.add(matcher.group());
        } catch (Throwable ignored) {}

        Class<?> type = value.getClass();
        while (type != null && type != Object.class
                && !type.getName().startsWith("java.")
                && !type.getName().startsWith("android.")) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    collectJids(field.get(value), depth - 1, seen, output);
                } catch (Throwable ignored) {}
            }
            type = type.getSuperclass();
        }
    }

    private void sanitizePreservedOwnRowId(@NonNull XC_MethodHook.MethodHookParam param) {
        if (!isPreserveOwnRevokeEnabled() || param.args == null || param.args.length < 3) return;
        Object tableArg = param.args[0];
        Object valuesArg = param.args[2];
        if (!(tableArg instanceof String) || !(valuesArg instanceof ContentValues)) return;

        String table = (String) tableArg;
        if (!"message".equals(table) && !"messages".equals(table)) return;

        ContentValues values = (ContentValues) valuesArg;
        // Non-revoke INSERTs may reuse an explicit row ID. Never consume their
        // pending reservation before WhatsApp writes the type-15 placeholder.
        Integer messageType = values.getAsInteger("message_type");
        Long rowId = values.getAsLong("_id");
        if (PreservedOwnRowIdPolicy.shouldStripPlaceholderId(
                messageType, rowId, PRESERVED_OWN_ROW_IDS)) {
            values.remove("_id");
        }
    }

    /**
     * Real-time UI dispatcher: instantly updates on-screen visible chat bubble when a revocation occurs.
     */
    private void dispatchRealtimeRevokeUI(@NonNull String targetKeyId, long timestamp) {
        uiHandler.post(() -> {
            try {
                // 1. Direct row update on active ListView
                ListView lv = activeListView.get();
                if (lv != null) {
                    int count = lv.getChildCount();
                    for (int i = 0; i < count; i++) {
                        View child = lv.getChildAt(i);
                        if (child instanceof ViewGroup) {
                            Object tag = child.getTag(TAG_KEY_ID);
                            String childKey = tag instanceof String ? (String) tag
                                    : (String) XposedHelpers.getAdditionalInstanceField(child, "waex_key_id");
                            if (targetKeyId.equals(childKey)) {
                                decorateRevokedRow((ViewGroup) child, timestamp);
                                child.invalidate();
                                break;
                            }
                        }
                    }
                    // WhatsApp Business uses a custom ListAdapter rather than BaseAdapter.
                    // Force its visible rows through getView again so a newly revoked row
                    // switches from the normal bubble to conversation_row_revoked immediately.
                    lv.invalidateViews();
                }

                // 2. Notify adapter to rebind dataset
                if (currentAdapter instanceof BaseAdapter) {
                    ((BaseAdapter) currentAdapter).notifyDataSetChanged();
                } else if (currentAdapter instanceof HeaderViewListAdapter) {
                    ListAdapter wrapped = ((HeaderViewListAdapter) currentAdapter).getWrappedAdapter();
                    if (wrapped instanceof BaseAdapter) {
                        ((BaseAdapter) wrapped).notifyDataSetChanged();
                    }
                }
                if (currentAdapter != null && !(currentAdapter instanceof BaseAdapter)) {
                    try {
                        XposedHelpers.callMethod(currentAdapter, "notifyDataSetChanged");
                    } catch (Throwable ignored) {
                        // invalidateViews above is sufficient for adapters without a notify API.
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log(TAG + " Error in realtime UI update runnable: " + t.getClass().getSimpleName());
            }
        });
    }

    // ─── ListView adapter hook (main indicator path) ─────────────────────

    /**
     * Hooks ListView.setAdapter on the conversation list view (android.R.id.list).
     * On each getView call, checks if the bound FMessage is revoked and decorates accordingly.
     */
    private void hookListViewAdapter() {
        try {
            Class<?> recyclerView = classLoader.loadClass("androidx.recyclerview.widget.RecyclerView");
            // Do not resolve RecyclerView.Adapter through findAndHookMethod. On recent WA
            // builds that lookup can throw NoSuchMethodError even though setAdapter exists,
            // which used to abort the whole conversation-row installation.
            XposedBridge.hookAllMethods(recyclerView, "setAdapter", new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Activity activity = ActivityTracker.getCurrentActivity();
                            if (activity == null || !activity.getClass().getSimpleName().contains("Conversation")) return;
                            Object adapter = param.args[0];
                            if (adapter == null) return;
                            hookRecyclerBind(adapter);
                            HideAntiRevokeProFeature.attachRecyclerAdapterItemHooks(
                                    adapter, classLoader, prefs, new HideAntiRevokeProFeature.MessageBridge() {
                                        @Override public String getMessageKeyId(Object message) { return getOriginalOrCurrentKeyId(message); }
                                        @Override public long getRevokedTimestamp(String keyId) { return AntiRevokeHook.this.getRevokedTimestamp(keyId); }
                                        @Override public boolean isAntiRevokeAvailable() { return isAntiRevokeEnabled() || isPreserveOwnRevokeEnabled(); }
                                        @Override public void resolveNativeRevokedModel(Object message) { resolveNativeRevokedModelWithDexKit(message); }
                                    });
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " RecyclerView adapter hook setup failed: " + t.getClass().getSimpleName());
            // Some WA builds expose a relocated/inherited RecyclerView without a hookable
            // setAdapter declaration. Recover by observing the stable Adapter bind contract;
            // the concrete adapter is then hooked once it performs its first bind.
            try {
                Class<?> adapterClass = classLoader.loadClass(
                        "androidx.recyclerview.widget.RecyclerView$Adapter");
                XposedBridge.hookAllMethods(adapterClass, "onBindViewHolder", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Activity activity = ActivityTracker.getCurrentActivity();
                        if (activity == null || !activity.getClass().getSimpleName().contains("Conversation")) return;
                        if (param.thisObject != null) hookRecyclerBind(param.thisObject);
                    }
                });
                XposedBridge.log(TAG + " RecyclerView Adapter bind fallback installed");
            } catch (Throwable fallback) {
                XposedBridge.log(TAG + " RecyclerView bind fallback failed: "
                        + fallback.getClass().getSimpleName());
            }
        }
        try {
            XposedHelpers.findAndHookMethod(ListView.class, "setAdapter", ListAdapter.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Activity currentActivity = ActivityTracker.getCurrentActivity();
                    if (currentActivity == null
                            || !currentActivity.getClass().getSimpleName().contains("Conversation")) return;

                    ListView listView = (ListView) param.thisObject;
                    if (listView == null) return;

                    activeListView = new WeakReference<>(listView);

                    // Unwrap adapter
                    ListAdapter adapter = (ListAdapter) param.args[0];
                    if (adapter == null) return;
                    if (adapter instanceof HeaderViewListAdapter) {
                        adapter = ((HeaderViewListAdapter) adapter).getWrappedAdapter();
                    }
                    if (adapter == null) return;

                    // Unhook previous getView hook to prevent stale adapter refs
                    if (previousGetViewHook != null) {
                        try {
                            previousGetViewHook.unhook();
                        } catch (Throwable ignored) {}
                        previousGetViewHook = null;
                    }
                    currentAdapter = adapter;

                    // Diagnostic log: report the full adapter class chain
                    XposedBridge.log(TAG + " ListView.setAdapter:"
                            + " outer=" + ((ListAdapter) param.args[0]).getClass().getName()
                            + " wrapped=" + adapter.getClass().getName()
                            + " underlying=" + getUnderlyingAdapter(adapter).getClass().getName());

                    final ListAdapter boundAdapter = adapter;
                    final ListAdapter underlyingAdapter = getUnderlyingAdapter(adapter);
                    HideAntiRevokeProFeature.attachAdapterItemHooks(
                            boundAdapter, underlyingAdapter, classLoader, prefs,
                            new HideAntiRevokeProFeature.MessageBridge() {
                                @Override public String getMessageKeyId(Object message) {
                                    return getOriginalOrCurrentKeyId(message);
                                }
                                @Override public long getRevokedTimestamp(String keyId) {
                                    return AntiRevokeHook.this.getRevokedTimestamp(keyId);
                                }
                                @Override public boolean isAntiRevokeAvailable() {
                                    return isAntiRevokeEnabled() || isPreserveOwnRevokeEnabled();
                                }
                                @Override public void resolveNativeRevokedModel(Object message) {
                                    resolveNativeRevokedModelWithDexKit(message);
                                }
                            });
                    try {
                        Method getViewMethod = findConcreteAdapterGetView(boundAdapter.getClass());

                        if (getViewMethod == null) {
                            XposedBridge.log(TAG + " getView method not found on " + boundAdapter.getClass().getName());
                            return;
                        }

                        XposedBridge.log(TAG + " Hooking getView on "
                                + getViewMethod.getDeclaringClass().getName()
                                + "#" + getViewMethod.getName());

                        previousGetViewHook = XposedBridge.hookMethod(getViewMethod, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam p) {
                                if (REVOKED_MESSAGES.isEmpty()) return;
                                if (p.args != null && p.args.length > 1 && p.args[1] instanceof ViewGroup) {
                                    ViewGroup recycledRow = (ViewGroup) p.args[1];
                                    if (recycledRow.getTag(TAG_DECORATED) != null) {
                                        resetRowDecoration(recycledRow);
                                    }
                                }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam p) {
                                if (p.thisObject != currentAdapter && p.thisObject != underlyingAdapter) return;
                                if (!isAntiRevokeEnabled() && !isPreserveOwnRevokeEnabled()) return;

                                Object renderedRow = p.getResult();
                                if (!(renderedRow instanceof ViewGroup)) return;
                                ViewGroup row = (ViewGroup) renderedRow;

                                if (REVOKED_MESSAGES.isEmpty()) {
                                    if (row.getTag(TAG_DECORATED) != null) {
                                        resetRowDecoration(row);
                                    }
                                    return;
                                }

                                int pos = (int) p.args[0];
                                Object item;
                                try {
                                    item = boundAdapter.getItem(pos);
                                } catch (Throwable t) {
                                    return;
                                }
                                if (item == null) return;

                                if (HideAntiRevokeProFeature.isHiddenForActiveConversation(prefs)) {
                                    HideAntiRevokeProFeature.observeNativeDeletedRow(row, item);
                                }

                                String keyId = getOriginalOrCurrentKeyId(item);
                                row.setTag(TAG_KEY_ID, keyId);

                                long deletedTs = getRevokedTimestamp(keyId);
                                if (deletedTs <= 0 && HideAntiRevokeProFeature.isHiddenForActiveConversation(prefs)) {
                                    String textKey = resolveRecordedKeyFromRowText(row);
                                    if (textKey != null) {
                                        keyId = textKey;
                                        deletedTs = getRevokedTimestamp(keyId);
                                    }
                                }

                                if (deletedTs > 0) {
                                    // If WhatsApp already returned its native revoked
                                    // row, keep it untouched. Recorded anti-revokes can
                                    // still arrive as the normal KaO container and must
                                    // be converted through X.Kxn below.
                                    if ("X.Kxn".equals(row.getClass().getName())
                                            || "Kxn".equals(row.getClass().getSimpleName())) {
                                        return;
                                    }
                                    // If user has already revealed this message, leave it as the
                                    // original row — do NOT re-convert to revoked layout.
                                    if (HideAntiRevokeProFeature.isRevealed(keyId)) {
                                        resetRowDecoration(row);
                                        return;
                                    }

                                    row.setTag(TAG_DECORATED, Boolean.TRUE);
                                    if (HideAntiRevokeProFeature.isHiddenForActiveConversation(prefs)) {
                                        // show_as_deleted: return WhatsApp's native revoked row.
                                        // If its private constructor is unavailable, retain the
                                        // original row and show the legacy indicator safely.
                                        ViewGroup nativeRevokedRow = null;
                                        try {
                                            nativeRevokedRow = HideAntiRevokeProFeature.renderDeletedMessageRow(
                                                    row, item, keyId, deletedTs, classLoader);
                                        } catch (Throwable renderFailure) {
                                            XposedBridge.log(TAG + " native show-as-deleted failed key="
                                                    + keyId + " error=" + renderFailure.getClass().getSimpleName());
                                        }
                                        if (nativeRevokedRow != null) {
                                            nativeRevokedRow.setTag(TAG_DECORATED, Boolean.TRUE);
                                            nativeRevokedRow.setTag(TAG_KEY_ID, keyId);
                                            p.setResult(nativeRevokedRow);
                                        } else {
                                            decorateRevokedRow(row, deletedTs);
                                        }
                                    } else {
                                        decorateRevokedRow(row, deletedTs);
                                    }
                                } else {
                                    if (row.getTag(TAG_DECORATED) != null) {
                                        resetRowDecoration(row);
                                    }
                                }
                            }
                        });

                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " Error hooking getView on " + boundAdapter.getClass().getName() + ": " + t.getClass().getSimpleName());
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error setting up ListView adapter hook: " + t.getClass().getSimpleName());
        }
    }

    private static volatile int sMessageTextResId = 0;

    @Nullable
    private String resolveRecordedKeyFromRowText(@NonNull ViewGroup row) {
        try {
            int textId = sMessageTextResId;
            if (textId == 0) {
                textId = row.getResources().getIdentifier("message_text", "id", row.getContext().getPackageName());
                if (textId != 0) sMessageTextResId = textId;
            }
            View view = textId == 0 ? null : row.findViewById(textId);
            if (!(view instanceof TextView)) return null;
            CharSequence cs = ((TextView) view).getText();
            if (cs == null || cs.length() == 0) return null;
            return revokedTextKeyCache.get(cs.toString());
        } catch (Throwable ignored) {}
        return null;
    }

    private void hookRecyclerBind(final Object adapter) {
        Class<?> cls = adapter.getClass();
        if (!recyclerBindHooks.add(cls)) return;
        try {
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!m.getName().equals("onBindViewHolder") || p.length < 2
                            || p[0].getName().contains("RecyclerView") == false
                            || p[1] != int.class) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam hook) {
                            if (!isAntiRevokeEnabled() && !isPreserveOwnRevokeEnabled()) return;
                            Activity activity = ActivityTracker.getCurrentActivity();
                            if (activity == null || !activity.getClass().getSimpleName().contains("Conversation")) return;
                            if (REVOKED_MESSAGES.isEmpty()) return;
                            if (hook.args == null || hook.args.length < 1) return;
                            Object holder = hook.args[0];
                            View itemView = null;
                            try { itemView = (View) XposedHelpers.getObjectField(holder, "itemView"); }
                            catch (Throwable ignored) {}
                            if (!(itemView instanceof ViewGroup)) return;
                            Object message = null;
                            if (hook.args.length > 1 && hook.args[1] instanceof Integer) {
                                try {
                                    Object candidate = XposedHelpers.callMethod(adapter, "getItem", (Integer) hook.args[1]);
                                    if (candidate != null && getOriginalOrCurrentKeyId(candidate) != null) message = candidate;
                                } catch (Throwable ignored) {}
                            }
                            if (message == null) return;
                            String key = getOriginalOrCurrentKeyId(message);
                            long ts = getRevokedTimestamp(key);
                            if (key == null || ts <= 0) return;
                            HideAntiRevokeProFeature.renderRecyclerBoundDeletedRow((ViewGroup) itemView, message, key, ts);
                            XposedBridge.log(TAG + " Recycler native revoked bind: " + key);
                        }
                    });
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Recycler bind hook failed: " + t.getClass().getSimpleName());
        }
    }

    @Nullable
    private Object findBoundRevokedMessage(Object value, IdentityHashMap<Object, Boolean> seen, int depth) {
        if (value == null || depth > 2 || seen.put(value, Boolean.TRUE) != null) return null;
        String key = getOriginalOrCurrentKeyId(value);
        if (key != null && getRevokedTimestamp(key) > 0) return value;
        if (value instanceof View || value instanceof Context || value instanceof String
                || value instanceof Number || value instanceof Class) return null;
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object child = f.get(value);
                    Object found = findBoundRevokedMessage(child, seen, depth + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    private void resolveNativeRevokedModelWithDexKit(@NonNull Object message) {
        try {
            Object key = HideAntiRevokeProFeature.extractKeyObjectFromFMessage(message);
            if (key == null) return;
            Class<?> keyType = key.getClass();
            final Object source = message;
            Class<?> model = DexSearchEngine.getInstance().findClassWithCache(
                    context, classLoader, "waex_native_revoked_model_v2",
                    (bridge, loader) -> {
                        ClassDataList classes = bridge.findClass(FindClass.create()
                                .matcher(ClassMatcher.create().className("X.", StringMatchType.StartsWith)));
                        for (ClassData data : classes) {
                            Class<?> candidate;
                            try { candidate = data.getInstance(loader); } catch (Throwable ignored) { continue; }
                            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                                Class<?>[] types = ctor.getParameterTypes();
                                if (types.length != 2 || types[1] != long.class) continue;
                                if (types[0].isAssignableFrom(keyType)) return candidate;
                                Class<?> current = source.getClass();
                                while (current != null && current != Object.class) {
                                    for (Field field : current.getDeclaredFields()) {
                                        if (Modifier.isStatic(field.getModifiers())) continue;
                                        try {
                                            field.setAccessible(true);
                                            Object value = field.get(source);
                                            if (value != null && types[0].isInstance(value)) return candidate;
                                        } catch (Throwable ignored) {}
                                    }
                                    current = current.getSuperclass();
                                }
                            }
                        }
                        return null;
                    });
            HideAntiRevokeProFeature.setResolvedRevokedMessageModelClass(model);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Native revoked model DexKit resolution failed: "
                    + t.getClass().getSimpleName());
        }
    }

    /**
     * WhatsApp frequently inherits ListAdapter#getView from an obfuscated base class.
     * XposedHelpers.findMethodBestMatch can miss that implementation and report the
     * Class arguments themselves as the requested signature. Walk the hierarchy and
     * hook the concrete declaration that the adapter actually dispatches to.
     */
    @Nullable
    private static Method findConcreteAdapterGetView(@NonNull Class<?> adapterClass) {
        Class<?> current = adapterClass;
        while (current != null && current != Object.class) {
            try {
                Method method = current.getDeclaredMethod(
                        "getView", int.class, View.class, ViewGroup.class);
                if (!Modifier.isAbstract(method.getModifiers())) {
                    method.setAccessible(true);
                    return method;
                }
            } catch (NoSuchMethodException ignored) {
                // The concrete implementation may live on an obfuscated superclass.
            }
            current = current.getSuperclass();
        }
        return null;
    }

    // ─── Status Playback UI Hook ─────────────────────────────────────────

    // Status-key DB fallback is consumed exactly once through StatusKeyHandoff.
    // This prevents reusing the same key on subsequent status views on one thread.

    private void hookStatusPlaybackUI() {
        try {
            DexSearchEngine engine = DexSearchEngine.getInstance();

            // ── 1. Resolve StatusPlaybackContactFragment class ──
            Class<?> playbackFragmentClass;
            try {
                // This class name is stable and loading it directly avoids both a false inner-class
                // match and an expensive DexKit class scan during WhatsApp startup.
                playbackFragmentClass = classLoader.loadClass(
                        "com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment");
            } catch (Throwable directLoadFailure) {
                playbackFragmentClass = engine.findClassWithCache(
                    context, classLoader, "waex_status_playback_fragment_class_v3",
                    (bridge, loader) -> {
                        String[] patterns = {
                                "com.whatsapp.status.playback.fragment.StatusPlaybackContactFragment",
                                "StatusPlaybackContactFragment",
                                "StatusPlaybackFragment"
                        };
                        for (String pattern : patterns) {
                            try {
                                ClassData cd = bridge.findClass(FindClass.create()
                                        .matcher(ClassMatcher.create().className(pattern, StringMatchType.Contains))
                                ).firstOrNull();
                                if (cd != null) {
                                    return cd.getInstance(loader);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });
            }

            if (playbackFragmentClass != null) {
            }

            // ── 2. Find status playback page-bind method via DexKit invokes & anchors ──
            final Class<?> targetFragmentClass = playbackFragmentClass;
            Method refreshSubtitleMethod = engine.findMethodWithCache(
                    // v2 is tied to the corrected fragment resolver above.
                    context, classLoader, "waex_status_playback_refresh_subtitle_v2",
                    (bridge, loader) -> {
                        try {
                            MethodData refreshCurrentPage = bridge.findMethod(FindMethod.create()
                                    .matcher(MethodMatcher.create().usingStrings("playbackFragment/refreshCurrentPageSubTitle message is empty"))
                            ).firstOrNull();
                            if (refreshCurrentPage != null) {
                                List<MethodData> invokes = refreshCurrentPage.getInvokes();
                                if (invokes != null) {
                                    for (MethodData invoke : invokes) {
                                        try {
                                            Method m = invoke.getMethodInstance(loader);
                                            if (targetFragmentClass == null
                                                    || !Modifier.isStatic(m.getModifiers())
                                                    || m.getDeclaringClass() != targetFragmentClass) continue;
                                            Class<?>[] methodParameters = m.getParameterTypes();
                                            if (methodParameters.length > 1) {
                                                for (Class<?> pType : methodParameters) {
                                                    if (pType == targetFragmentClass) {
                                                        return m;
                                                    }
                                                }
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }
                                return refreshCurrentPage.getMethodInstance(loader);
                            }
                        } catch (Throwable ignored) {}

                        String[] anchors = {
                                "playbackFragment/refreshCurrentPageSubTitle message is empty",
                                "StatusPlaybackContactFragment/refreshCurrentPageSubTitle",
                                "status_playback_subtitle",
                                "story_playback_page"
                        };
                        for (String anchor : anchors) {
                            try {
                                MethodData md = bridge.findMethod(FindMethod.create()
                                        .matcher(MethodMatcher.create().usingStrings(anchor))
                                ).firstOrNull();
                                if (md != null) {
                                    return md.getMethodInstance(loader);
                                }
                            } catch (Throwable ignored) {}
                        }
                        return null;
                    });

            // ── 3. Find StatusPlaybackViewClass (the view container holding date & menu) ──
            Class<?> statusPlaybackViewClass = engine.findClassWithCache(
                    context, classLoader, "waex_status_playback_view_class",
                    (bridge, loader) -> {
                        try {
                            int statusHeaderId = context.getResources().getIdentifier("status_header", "id", context.getPackageName());
                            int menuId = context.getResources().getIdentifier("menu", "id", context.getPackageName());
                            if (statusHeaderId != 0 && menuId != 0) {
                                ClassData cd = bridge.findClass(FindClass.create()
                                        .matcher(ClassMatcher.create()
                                                .addMethod(MethodMatcher.create().usingNumbers(statusHeaderId, menuId)))
                                ).firstOrNull();
                                if (cd != null) {
                                    return cd.getInstance(loader);
                                }
                            }
                        } catch (Throwable ignored) {}
                        return null;
                    });

            if (statusPlaybackViewClass != null) {
            }

            // ── 4. Hook the status playback subtitle / bind method ──
            if (refreshSubtitleMethod != null) {
                final Method finalRefresh = refreshSubtitleMethod;
                XposedBridge.hookMethod(finalRefresh,
                        buildStatusPageHook("refreshSubtitle", playbackFragmentClass, statusPlaybackViewClass));
            } else {
                XposedBridge.log(TAG + " Status refresh method not found; using fallback hooks.");
            }

            // ── 5. Hook TARGETED lifecycle methods on the fragment class ──
            if (playbackFragmentClass != null) {
                hookStatusFragmentTargeted(playbackFragmentClass, refreshSubtitleMethod);
            }

            // ── 5. AndroidX Fragment.onResume fallback (fast class-name filter) ──
            hookAndroidXFragmentResumeFallback();

            // ── 6. Track status DB reads to capture current keyId reliably ──
            hookStatusDbQueryReads();

        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error hooking status playback UI: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Hook only specific, named lifecycle methods on the playback fragment.
     * NEVER loops all declared methods.
     */
    private void hookStatusFragmentTargeted(Class<?> fragmentClass, @Nullable Method excludeMethod) {
        // Lifecycle method names to try (not a loop over all methods)
        String[] targetNames = {"onResume", "onStart", "onHiddenChanged", "setUserVisibleHint"};
        for (String name : targetNames) {
            try {
                Class<?> curr = fragmentClass;
                while (curr != null && curr != Object.class) {
                    try {
                        Method m = curr.getDeclaredMethod(name);
                        if (excludeMethod == null || !m.equals(excludeMethod)) {
                            XposedBridge.hookMethod(m, buildStatusPageHook(name));
                        }
                        break;
                    } catch (NoSuchMethodException ignored) {
                        curr = curr.getSuperclass();
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Hook at most 2 single-int-arg methods (page-position setters) on the fragment
        int intArgCount = 0;
        for (Method m : fragmentClass.getDeclaredMethods()) {
            if (intArgCount >= 2) break;
            if (Modifier.isStatic(m.getModifiers())) continue;
            if (excludeMethod != null && m.equals(excludeMethod)) continue;
            Class<?>[] methodParameters = m.getParameterTypes();
            if (methodParameters.length == 1 && methodParameters[0] == int.class) {
                try {
                    XposedBridge.hookMethod(m, buildStatusPageHookWithArg());
                    intArgCount++;
                } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * Safety-net: hook AndroidX Fragment.onResume with a fast heuristic class-name guard.
     * Only triggers decoration for fragments whose simple class name implies status/story context.
     */
    private void hookAndroidXFragmentResumeFallback() {
        try {
            Class<?> fragmentBase = classLoader.loadClass("androidx.fragment.app.Fragment");
            XposedHelpers.findAndHookMethod(fragmentBase, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isAntiRevokeStatusEnabled()) return;
                    Object fragment = param.thisObject;
                    String simple = fragment.getClass().getSimpleName().toLowerCase();
                    if (!simple.contains("status") && !simple.contains("story") && !simple.contains("playback")) return;
                    uiHandler.postDelayed(() -> tryDecorateStatusFragment(fragment), 250);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " AndroidX Fragment.onResume hook failed: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Hook SQLiteDatabase.rawQuery to record the keyId of status rows being read.
     * Opportunistic fallback only; cross-thread query/fragment identity is not guaranteed.
     */
    private void hookStatusDbQueryReads() {
        try {
            XposedBridge.hookAllMethods(SQLiteDatabase.class, "rawQuery", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isAntiRevokeStatusEnabled()) return;
                    try {
                        String sql = (String) param.args[0];
                        if (sql == null) return;
                        String sqlLower = sql.toLowerCase();
                        if (!sqlLower.contains("status")) return;

                        Cursor cursor = (Cursor) param.getResult();
                        if (cursor == null || cursor.getCount() == 0) return;

                        // Try to find a key_id column without moving the cursor
                        int keyIdCol = cursor.getColumnIndex("key_id");
                        if (keyIdCol < 0) {
                            for (int i = 0; i < cursor.getColumnCount(); i++) {
                                if (cursor.getColumnName(i).toLowerCase().contains("key_id")) {
                                    keyIdCol = i;
                                    break;
                                }
                            }
                        }
                        if (keyIdCol < 0) return;

                        // Only peek if cursor is already positioned (not before-first)
                        if (!cursor.isBeforeFirst() && !cursor.isAfterLast()) {
                            String keyId = cursor.getString(keyIdCol);
                            if (isLikelyStatusKeyId(keyId)) {
                                StatusKeyHandoff.record(keyId);
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error installing status DB read tracker: " + t.getClass().getSimpleName());
        }
    }

    /** Hook callback for DexKit-found method and named lifecycle hooks. */
    /** Hook callback for DexKit-found method and named lifecycle hooks. */
    private XC_MethodHook buildStatusPageHook(String tag) {
        return buildStatusPageHook(tag, null, null);
    }

    private XC_MethodHook buildStatusPageHook(String tag, @Nullable Class<?> fragmentClass,
                                               @Nullable Class<?> playbackViewClass) {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isAntiRevokeStatusEnabled()) return;
                try {
                    // Try to inspect arguments first (especially for static helper methods like refreshCurrentPageSubTitle)
                    String keyIdFromArgs = findRevokedStatusKey(param.args);
                    Object fragmentObj = param.thisObject;

                    if (param.args != null && param.args.length > 0) {
                        for (Object arg : param.args) {
                            if (arg == null) continue;
                            if (arg instanceof View || arg instanceof Number || arg instanceof Boolean) continue;

                            // If thisObject is null (static method), check if any argument is the fragment/controller
                            if (fragmentClass != null && fragmentClass.isInstance(arg)) {
                                fragmentObj = arg;
                            } else if (fragmentObj == null) {
                                String clsName = arg.getClass().getName().toLowerCase();
                                if (clsName.contains("fragment") || clsName.contains("playback") || clsName.contains("controller")) {
                                    fragmentObj = arg;
                                }
                            }

                            if (keyIdFromArgs == null) {
                                // Generic fallback for WhatsApp variants whose binder exposes FMessage directly.
                                String extracted = extractKeyIdFromFMessage(arg);
                                if (extracted == null) extracted = extractStatusKeyIdStrict(arg);
                                if (extracted != null && getRevokedTimestamp(extracted) > 0) {
                                    keyIdFromArgs = extracted;
                                }
                            }
                        }
                    }

                    if (keyIdFromArgs != null) {
                        long ts = getRevokedTimestamp(keyIdFromArgs);
                        if (ts > 0) {
                            final String finalKey = keyIdFromArgs;
                            final long finalTs = ts;
                            final Object targetObj = fragmentObj;
                            final Object[] finalArgs = param.args;
                            uiHandler.post(() -> {
                                TextView exactDate = findStatusDateTextView(targetObj, playbackViewClass);
                                if (exactDate != null) {
                                    decorateRevokedStatusTextView(exactDate, finalTs);
                                } else {
                                    applyStatusDecorationFromContext(targetObj, finalArgs, finalKey, finalTs);
                                }
                            });
                            return;
                        }
                    }

                    if (fragmentObj != null) {
                        tryDecorateStatusFragment(fragmentObj);
                    }
                } catch (Throwable ignored) {}
            }
        };
    }

    /** Finds the revoked key in the binder arguments without recursively walking the UI model. */
    @Nullable
    private String findRevokedStatusKey(@Nullable Object[] args) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg == null || arg instanceof View || arg instanceof Context) continue;
            String direct = extractKeyIdFromFMessage(arg);
            if (direct != null && getRevokedTimestamp(direct) > 0) return direct;
            try {
                Class<?> current = arg.getClass();
                while (current != null && current != Object.class) {
                    for (Field field : current.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                        field.setAccessible(true);
                        Object nested = field.get(arg);
                        if (nested == null) continue;
                        if ((statusKeyClass != null && statusKeyClass.isInstance(nested))
                                || (fMessageClass != null && fMessageClass.isInstance(nested))) {
                            String candidate = extractKeyIdFromFMessage(nested);
                            if (candidate != null && getRevokedTimestamp(candidate) > 0) return candidate;
                        } else if (statusModelClass != null && statusModelClass.isInstance(nested)
                                && statusToMessageMethod != null) {
                            Object receiver = null;
                            if (!Modifier.isStatic(statusToMessageMethod.getModifiers())) {
                                receiver = statusToMessageMapper;
                                if (receiver == null) {
                                    try {
                                        Constructor<?> constructor =
                                                statusToMessageMethod.getDeclaringClass().getDeclaredConstructor();
                                        constructor.setAccessible(true);
                                        receiver = constructor.newInstance();
                                        statusToMessageMapper = receiver;
                                    } catch (Throwable ignored) {
                                        continue;
                                    }
                                }
                            }
                            Object message = statusToMessageMethod.invoke(receiver, nested);
                            String candidate = extractKeyIdFromFMessage(message);
                            if (candidate != null && getRevokedTimestamp(candidate) > 0) return candidate;
                        }
                    }
                    current = current.getSuperclass();
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Finds the date TextView through the exact playback-view field, as WaEnhancer does. */
    @Nullable
    private TextView findStatusDateTextView(@Nullable Object fragment, @Nullable Class<?> playbackViewClass) {
        if (fragment == null || playbackViewClass == null) return null;
        try {
            Object playbackView = null;
            Class<?> curr = fragment.getClass();
            while (curr != null && curr != Object.class && playbackView == null) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !playbackViewClass.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    playbackView = field.get(fragment);
                    if (playbackView != null) break;
                }
                curr = curr.getSuperclass();
            }
            if (playbackView == null) return null;

            int dateId = context.getResources().getIdentifier("date", "id", context.getPackageName());
            curr = playbackViewClass;
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !TextView.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    TextView textView = (TextView) field.get(playbackView);
                    if (textView != null && (dateId == 0 || textView.getId() == dateId)) return textView;
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Exact status date lookup failed: " + t.getClass().getSimpleName());
        }
        return null;
    }

    /** Hook callback for single-int-arg methods (page index binders). */
    private XC_MethodHook buildStatusPageHookWithArg() {
        return new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!isAntiRevokeStatusEnabled()) return;
                try {
                    // If any non-int argument is a status-like object, try extracting keyId from it
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg != null && !(arg instanceof Integer) && !(arg instanceof int[])) {
                                String keyId = extractStatusKeyIdStrict(arg);
                                if (keyId == null) {
                                    keyId = extractKeyIdFromFMessage(arg);
                                }
                                if (keyId != null) {
                                    long ts = getRevokedTimestamp(keyId);
                                    if (ts > 0) {
                                        final long finalTs = ts;
                                        final String finalKeyId = keyId;
                                        final Object targetObj = param.thisObject;
                                        final Object[] finalArgs = param.args;
                                        uiHandler.post(() -> applyStatusDecorationFromContext(targetObj, finalArgs, finalKeyId, finalTs));
                                        return;
                                    }
                                }
                            }
                        }
                    }
                    if (param.thisObject != null) {
                        tryDecorateStatusFragment(param.thisObject);
                    }
                } catch (Throwable ignored) {}
            }
        };
    }

    /**
     * Main status decoration entry point.
     * Tries multiple keyId sources in priority order.
     */
    private void tryDecorateStatusFragment(Object fragment) {
        try {
            // Consume the DB candidate even when the fragment has an exact key.
            // Otherwise a previous status could be decorated using stale data.
            String pendingDbKey = StatusKeyHandoff.consume();
            // Priority 1: extract from fragment fields (status-specific strict scan)
            String keyId = extractStatusKeyIdFromFragment(fragment);

            // Priority 2: most recent unconsumed DB key from this thread
            if (keyId == null) {
                keyId = pendingDbKey;
            }

            // Priority 3: fall back to general FMessage extraction
            if (keyId == null) {
                keyId = extractStatusKeyFromFragmentGeneral(fragment);
            }

            if (keyId == null) return;

            long ts = getRevokedTimestamp(keyId);
            if (ts <= 0) return;

            final String finalKeyId = keyId;
            final long finalTs = ts;
            uiHandler.post(() -> applyStatusDecoration(fragment, finalKeyId, finalTs));
        } catch (Throwable t) {
            XposedBridge.log(TAG + " tryDecorateStatusFragment error: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Attempts to find the status date/time TextView from args, fragment/controller object, or root view hierarchy.
     */
    private void applyStatusDecorationFromContext(@Nullable Object fragmentOrController, @Nullable Object[] args, @NonNull String keyId, long ts) {
        try {
            // 1. Check if date TextView can be found directly from arguments
            if (args != null) {
                for (Object arg : args) {
                    if (arg instanceof TextView) {
                        TextView tv = (TextView) arg;
                        decorateRevokedStatusTextView(tv, ts);
                        return;
                    }
                    if (arg instanceof View) {
                        TextView tv = findStatusTimestampTextViewInViewTree((View) arg);
                        if (tv != null) {
                            decorateRevokedStatusTextView(tv, ts);
                            return;
                        }
                    }
                }
            }

            // 2. Check if fragmentOrController has any View or statusPlaybackView field
            if (fragmentOrController != null) {
                TextView tv = findDateTextViewFromObjectFields(fragmentOrController);
                if (tv != null) {
                    decorateRevokedStatusTextView(tv, ts);
                    return;
                }
                applyStatusDecoration(fragmentOrController, keyId, ts);
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " applyStatusDecorationFromContext error: " + t.getClass().getSimpleName());
        }
    }

    /**
     * Inspects fields of an object (e.g. fragment or status playback page controller) for date TextView or View container.
     */
    @Nullable
    private TextView findDateTextViewFromObjectFields(@NonNull Object obj) {
        try {
            Class<?> curr = obj.getClass();
            int depth = 0;
            while (curr != null && curr != Object.class && depth < 4) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if (val == null) continue;

                    if (val instanceof TextView) {
                        TextView tv = (TextView) val;
                        if (isLikelyDateTextView(tv)) return tv;
                    } else if (val instanceof View) {
                        TextView tv = findStatusTimestampTextViewInViewTree((View) val);
                        if (tv != null) return tv;
                    } else if (!val.getClass().isPrimitive() && !val.getClass().isArray() && val.getClass().getName().startsWith("com.whatsapp")) {
                        // Check one level deeper (e.g. statusPlaybackView container object)
                        for (Field subField : val.getClass().getDeclaredFields()) {
                            if (Modifier.isStatic(subField.getModifiers())) continue;
                            subField.setAccessible(true);
                            Object subVal = subField.get(val);
                            if (subVal instanceof TextView) {
                                TextView subTv = (TextView) subVal;
                                if (isLikelyDateTextView(subTv)) return subTv;
                            } else if (subVal instanceof View) {
                                TextView subTv = findStatusTimestampTextViewInViewTree((View) subVal);
                                if (subTv != null) return subTv;
                            }
                        }
                    }
                }
                curr = curr.getSuperclass();
                depth++;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean isLikelyDateTextView(TextView tv) {
        if (tv.getId() != View.NO_ID) {
            try {
                String entry = tv.getResources().getResourceEntryName(tv.getId()).toLowerCase();
                if (entry.contains("date") || entry.contains("time") || entry.contains("subtitle")) return true;
            } catch (Throwable ignored) {}
        }
        CharSequence text = tv.getText();
        return text != null && looksLikeTimeString(text.toString());
    }

    @Nullable
    private TextView findStatusTimestampTextViewInViewTree(@NonNull View view) {
        if (view instanceof TextView && isLikelyDateTextView((TextView) view)) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            return findStatusTimestampTextView((ViewGroup) view, view.getContext());
        }
        return null;
    }

    /**
     * Apply the "Deleted" visual indicator to the status playback header TextView.
     * If view is not ready yet, retries after 300 ms.
     */
    private void applyStatusDecoration(Object fragment, String keyId, long ts) {
        try {
            View root = extractFragmentRootView(fragment);
            if (root == null) {
                // Retry once after delay — view may not be attached yet
                uiHandler.postDelayed(() -> {
                    try {
                        View r = extractFragmentRootView(fragment);
                        if (r != null) doApplyStatusDecoration(r, keyId, ts);
                    } catch (Throwable ignored) {}
                }, 350);
                return;
            }
            doApplyStatusDecoration(root, keyId, ts);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " applyStatusDecoration error: " + t.getClass().getSimpleName());
        }
    }

    private void doApplyStatusDecoration(View root, String keyId, long ts) {
        if (!(root instanceof ViewGroup)) return;
        Context ctx = root.getContext();

        TextView target = findStatusTimestampTextView((ViewGroup) root, ctx);
        if (target == null) {
            return;
        }
        decorateRevokedStatusTextView(target, ts);
    }

    /**
     * Finds the timestamp/date TextView in the status header using resource names then text heuristic.
     */
    @Nullable
    private TextView findStatusTimestampTextView(ViewGroup root, Context ctx) {
        // Strategy 1: by resource name
        String[] idNames = {"date", "time", "timestamp", "subtitle", "status_time", "story_time", "caption_time"};
        for (String name : idNames) {
            try {
                int id = ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
                if (id != 0) {
                    View v = root.findViewById(id);
                    if (v instanceof TextView) return (TextView) v;
                }
            } catch (Throwable ignored) {}
        }
        // Strategy 2: recursive scan for time-like text
        return findTimeTextViewRecursive(root);
    }

    @Nullable
    private TextView findTimeTextViewRecursive(ViewGroup group) {
        try {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;

                if (child instanceof TextView) {
                    TextView tv = (TextView) child;
                    // Check resource name
                    if (child.getId() != View.NO_ID) {
                        try {
                            String entry = child.getResources().getResourceEntryName(child.getId()).toLowerCase();
                            if (entry.contains("date") || entry.contains("time") || entry.contains("subtitle")) {
                                return tv;
                            }
                        } catch (Throwable ignored) {}
                    }
                    // Check text content looks like a time
                    CharSequence text = tv.getText();
                    if (text != null && looksLikeTimeString(text.toString())) return tv;
                } else if (child instanceof ViewGroup) {
                    TextView found = findTimeTextViewRecursive((ViewGroup) child);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private boolean looksLikeTimeString(String text) {
        if (text == null || text.length() < 4 || text.length() > 20) return false;
        return text.matches(".*\\d{1,2}:\\d{2}.*") // e.g. "10:30 AM", "22:15"
                || text.equalsIgnoreCase("just now")
                || text.equalsIgnoreCase("now");
    }

    // ─── Status keyId extraction helpers ────────────────────────────────

    /**
     * Strict status keyId extraction from a fragment.
     * Scans String fields with isLikelyStatusKeyId() filter.
     * Does NOT use shared chat-message cache (cachedFMessageKeyField).
     */
    @Nullable
    private String extractStatusKeyIdFromFragment(Object fragment) {
        try {
            // 1. Check fragment arguments Bundle
            try {
                Method getArguments = fragment.getClass().getMethod("getArguments");
                Object bundle = getArguments.invoke(fragment);
                if (bundle != null) {
                    String keyId = extractKeyIdFromBundle(bundle);
                    if (keyId != null) return keyId;
                }
            } catch (Throwable ignored) {}

            // 2. Scan fragment String fields directly
            Class<?> curr = fragment.getClass();
            int depth = 0;
            while (curr != null && curr != Object.class && depth < 6) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        if (f.getType() == String.class) {
                            f.setAccessible(true);
                            String val = (String) f.get(fragment);
                            if (isLikelyStatusKeyId(val)) return val;
                        } else if (!f.getType().isPrimitive() && !f.getType().isArray()
                                && f.getType() != Context.class
                                && !View.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object nested = f.get(fragment);
                            if (nested == null) continue;
                            String keyId = extractStatusKeyIdStrict(nested);
                            if (keyId != null) return keyId;
                        }
                    } catch (Throwable ignored) {}
                }
                curr = curr.getSuperclass();
                depth++;
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " extractStatusKeyIdFromFragment error: " + t.getClass().getSimpleName());
        }
        return null;
    }

    /** Extract keyId-like strings from an Android Bundle via reflection. */
    @Nullable
    private String extractKeyIdFromBundle(Object bundle) {
        try {
            Set<?> keys = (Set<?>) bundle.getClass().getMethod("keySet").invoke(bundle);
            if (keys == null) return null;
            Method getString = bundle.getClass().getMethod("getString", String.class);
            for (Object key : keys) {
                try {
                    String val = (String) getString.invoke(bundle, key);
                    if (isLikelyStatusKeyId(val)) return val;
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Strict keyId scan of one arbitrary object (not a fragment).
     * Only checks its own String fields — does not recurse further.
     */
    @Nullable
    private String extractStatusKeyIdStrict(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String) {
            return isLikelyStatusKeyId((String) obj) ? (String) obj : null;
        }
        try {
            Class<?> clazz = obj.getClass();
            if (clazz.isPrimitive() || clazz.isArray()) return null;
            Class<?> curr = clazz;
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() != String.class) continue;
                    try {
                        f.setAccessible(true);
                        String val = (String) f.get(obj);
                        if (isLikelyStatusKeyId(val)) return val;
                    } catch (Throwable ignored) {}
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * General (fallback) extraction via existing extractKeyIdFromFMessage.
     * Uses the shared cache which is tuned for chat messages —
     * only used as last resort for status.
     */
    @Nullable
    private String extractStatusKeyFromFragmentGeneral(Object fragment) {
        try {
            Class<?> curr = fragment.getClass();
            int depth = 0;
            while (curr != null && curr != Object.class && depth < 5) {
                for (Field f : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(fragment);
                        if (val != null) {
                            String key = extractKeyIdFromFMessage(val);
                            if (key != null) return key;
                        }
                    } catch (Throwable ignored) {}
                }
                curr = curr.getSuperclass();
                depth++;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Returns true if the string matches the WA keyId pattern:
     * 1–64 alphanumeric/base64 chars, no spaces, dots, slashes, '@', or ':'.
     */
    private boolean isLikelyStatusKeyId(@Nullable String str) {
        if (str == null) return false;
        int len = str.length();
        if (len < 1 || len > 64) return false;
        if (str.contains(".") || str.contains("/") || str.contains("@")
                || str.contains(" ") || str.contains(":") || str.contains("\\")) return false;
        if (str.startsWith("com.") || str.startsWith("android") || str.startsWith("java.")) return false;
        // Must be base64/hex/numeric-like: letters, digits, +, /, =, -, _
        return str.matches("[A-Za-z0-9+/=_-]+");
    }

    @Nullable
    private String extractStatusKeyFromFragment(@NonNull Object fragment) {
        // Kept for compatibility — delegates to the new strict variant, then general
        String key = extractStatusKeyIdFromFragment(fragment);
        return key != null ? key : extractStatusKeyFromFragmentGeneral(fragment);
    }

    @Nullable
    private View extractFragmentRootView(@NonNull Object fragment) {
        try {
            Method getViewMethod = fragment.getClass().getMethod("getView");
            return (View) getViewMethod.invoke(fragment);
        } catch (Throwable ignored) {}
        try {
            Class<?> curr = fragment.getClass();
            while (curr != null && curr != Object.class) {
                for (Field f : curr.getDeclaredFields()) {
                    if (View.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        View v = (View) f.get(fragment);
                        if (v != null) return v;
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // ─── Revoke timestamp lookup ─────────────────────────────────────────

    private boolean isNativeDeletedPresentationEnabled() {
        try {
            return prefs.getBoolean("hide_anti_revoke", false)
                    || HideAntiRevokeProFeature.isHiddenForActiveConversation(prefs);
        } catch (Throwable ignored) {
            return prefs.getBoolean("hide_anti_revoke", false);
        }
    }

    private long getRevokedTimestamp(@Nullable String keyId) {
        if (keyId == null || keyId.isEmpty()) return 0L;
        Long ts = REVOKED_MESSAGES.get(keyId);
        if (ts != null && ts > 0) return ts;
        return DelMessageStore.getInstance(context).getTimestampByMessageId(keyId);
    }

    // ─── Row decoration ──────────────────────────────────────────────────

    private void decorateRevokedRow(@NonNull ViewGroup rowView, long deletedTimestamp) {
        try {
            rowView.setTag(TAG_DECORATED, Boolean.TRUE);
            Context rowContext = rowView.getContext();
            if (sDateResId == 0) {
                sDateResId = rowContext.getResources().getIdentifier("date", "id", rowContext.getPackageName());
                sMsgTextResId = rowContext.getResources().getIdentifier("message_text", "id", rowContext.getPackageName());
            }

            TextView dateTextView = sDateResId != 0 ? rowView.findViewById(sDateResId) : null;
            TextView messageTextView = sMsgTextResId != 0 ? rowView.findViewById(sMsgTextResId) : null;

            // Fallback: if dateTextView is not found by ID, scan rowView recursively
            if (dateTextView == null) {
                dateTextView = findDateTextViewFallback(rowView);
            }

            if (dateTextView == null) {
                return;
            }

            decorateRevokedTextView(dateTextView, messageTextView, rowContext, deletedTimestamp);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " decorateRevokedRow error: " + t.getClass().getSimpleName());
        }
    }

    public void decorateRevokedTextView(@NonNull TextView dateTextView, @Nullable TextView messageTextView, @NonNull Context rowContext, long deletedTimestamp) {
        try {
            // Preserve original colors before mutating
            if (XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color") == null) {
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_color", dateTextView.getCurrentTextColor());
            }
            if (messageTextView != null && XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color") == null) {
                XposedHelpers.setAdditionalInstanceField(messageTextView, "waex_original_color", messageTextView.getCurrentTextColor());
            }

            int indicatorType = getIndicatorType();
            boolean colorEnabled = isColorDeletedMessagesEnabled();
            int customColor = getDeletedMessageColor();

            if (colorEnabled) {
                dateTextView.setTextColor(customColor);
                if (messageTextView != null) {
                    messageTextView.setTextColor(customColor);
                }
            } else {
                Integer origDateColor = (Integer) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color");
                if (origDateColor != null) dateTextView.setTextColor(origDateColor);
                if (messageTextView != null) {
                    Integer origMsgColor = (Integer) XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color");
                    if (origMsgColor != null) messageTextView.setTextColor(origMsgColor);
                }
            }

            // Save original date text
            String originalDate = (String) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_date");
            if (originalDate == null) {
                originalDate = dateTextView.getText().toString();
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_date", originalDate);
            }

            if (indicatorType == 1) { // Show "Deleted" text
                dateTextView.setCompoundDrawables(null, null, null, null);
                String newText = UiText.get(R.string.waex_ui_deleted, "Deleted • ") + originalDate;
                dateTextView.setText(newText);
            } else { // indicatorType == 2 (Default: Show deleted icon)
                Drawable deleteDrawable = getDeletedIconDrawable(rowContext);
                if (deleteDrawable != null) {
                    deleteDrawable = deleteDrawable.mutate();
                    deleteDrawable.setTint(customColor);
                    int size = (int) (dateTextView.getTextSize() * 1.35f);
                    deleteDrawable.setBounds(0, 0, size, size);
                    dateTextView.setCompoundDrawables(null, null, deleteDrawable, null);
                    dateTextView.setCompoundDrawablePadding(8);
                    dateTextView.setText(originalDate);
                } else {
                    // Fallback to emoji indicator if vector resource fails to load
                    dateTextView.setCompoundDrawables(null, null, null, null);
                    dateTextView.setText("🚫 " + originalDate);
                }
            }

            // Clickable deletion timestamp toast
            if (deletedTimestamp > 0) {
                String formattedTime = TIME_FORMAT.get().format(new Date(deletedTimestamp));
                dateTextView.getPaint().setUnderlineText(true);
                dateTextView.setOnClickListener(v ->
                        Toast.makeText(rowContext, UiText.get(R.string.waex_ui_deleted_at, "Deleted at: ") + formattedTime, Toast.LENGTH_LONG).show()
                );
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " decorateRevokedTextView error: " + t.getClass().getSimpleName());
        }
    }

    /** Status playback uses text deliberately; injected module drawables are not reliable in WA views. */
    private void decorateRevokedStatusTextView(@NonNull TextView dateTextView, long deletedTimestamp) {
        decorateRevokedTextView(dateTextView, null, dateTextView.getContext(), deletedTimestamp);
        try {
            String originalDate = (String) XposedHelpers.getAdditionalInstanceField(
                    dateTextView, "waex_original_date");
            if (originalDate == null || originalDate.isEmpty()) {
                originalDate = dateTextView.getText().toString();
                XposedHelpers.setAdditionalInstanceField(dateTextView, "waex_original_date", originalDate);
            }
            dateTextView.setCompoundDrawables(null, null, null, null);
            dateTextView.setCompoundDrawablePadding(0);
            dateTextView.setText(UiText.get(R.string.waex_ui_deleted_message_prefix, "🚫 Deleted • ") + originalDate);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Status emoji decoration failed: " + t.getClass().getSimpleName());
        }
    }

    private void resetRowDecoration(@NonNull ViewGroup rowView) {
        if (rowView.getTag(TAG_DECORATED) == null) return;
        rowView.setTag(TAG_DECORATED, null);
        try {
            HideAntiRevokeProFeature.resetDeletedMessageLayout(rowView);
            rowView.setVisibility(View.VISIBLE);
            Context rowContext = rowView.getContext();
            if (sDateResId == 0) {
                sDateResId = rowContext.getResources().getIdentifier("date", "id", rowContext.getPackageName());
                sMsgTextResId = rowContext.getResources().getIdentifier("message_text", "id", rowContext.getPackageName());
            }

            TextView dateTextView = sDateResId != 0 ? rowView.findViewById(sDateResId) : null;
            TextView messageTextView = sMsgTextResId != 0 ? rowView.findViewById(sMsgTextResId) : null;
            if (dateTextView == null) {
                dateTextView = findDateTextViewFallback(rowView);
            }
            if (dateTextView == null) return;

            Integer originalDateColor = (Integer) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_color");
            if (originalDateColor != null) {
                dateTextView.setTextColor(originalDateColor);
                XposedHelpers.removeAdditionalInstanceField(dateTextView, "waex_original_color");
            }
            if (messageTextView != null) {
                Integer originalMsgColor = (Integer) XposedHelpers.getAdditionalInstanceField(messageTextView, "waex_original_color");
                if (originalMsgColor != null) {
                    messageTextView.setTextColor(originalMsgColor);
                    XposedHelpers.removeAdditionalInstanceField(messageTextView, "waex_original_color");
                }
            }

            String originalDate = (String) XposedHelpers.getAdditionalInstanceField(dateTextView, "waex_original_date");
            if (originalDate != null) {
                dateTextView.setText(originalDate);
                XposedHelpers.removeAdditionalInstanceField(dateTextView, "waex_original_date");
            }

            dateTextView.setCompoundDrawables(null, null, null, null);
            dateTextView.getPaint().setUnderlineText(false);
            dateTextView.setOnClickListener(null);
        } catch (Throwable ignored) {}
    }

    @Nullable
    private TextView findDateTextViewFallback(ViewGroup group) {
        try {
            int count = group.getChildCount();
            for (int i = 0; i < count; i++) {
                View child = group.getChildAt(i);
                if (child instanceof TextView) {
                    int id = child.getId();
                    if (id != View.NO_ID) {
                        try {
                            String entryName = child.getResources().getResourceEntryName(id);
                            if (entryName != null && entryName.toLowerCase().contains("date")) {
                                return (TextView) child;
                            }
                        } catch (Throwable ignored) {}
                    }
                } else if (child instanceof ViewGroup) {
                    TextView found = findDateTextViewFallback((ViewGroup) child);
                    if (found != null) return found;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    @Nullable
    private Drawable getDeletedIconDrawable(@NonNull Context rowContext) {
        Drawable d = MenuIconLoader.load(rowContext, "ic_deleted");
        if (d != null) return d;
        return MenuIconLoader.load(context, "ic_deleted");
    }

    // ─── Key extraction from FMessage / FStatus objects ──────────────────

    /**
     * Reads the direction bit from an FMessage key. A null result means that
     * ownership could not be established and callers may use their normal path.
     */
    @Nullable
    private Boolean extractIsFromMe(@Nullable Object message) {
        if (message == null) return null;
        try {
            Object key = null;
            if (cachedFMessageKeyField != null
                    && cachedFMessageKeyField.getDeclaringClass().isInstance(message)) {
                try {
                    key = cachedFMessageKeyField.get(message);
                } catch (Throwable ignored) {}
            }

            if (key == null) {
                Class<?> curr = message.getClass();
                while (curr != null && curr != Object.class && key == null) {
                    for (Field field : curr.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers())) continue;
                        field.setAccessible(true);
                        Object value = field.get(message);
                        if (value == null) continue;
                        Class<?> valueClass = value.getClass();
                        if ((messageKeyClass != null && messageKeyClass.isInstance(value))
                                || looksLikeMessageKey(valueClass)) {
                            key = value;
                            break;
                        }
                    }
                    curr = curr.getSuperclass();
                }
            }

            // Some revoke methods receive the key itself rather than FMessage.
            if (key == null && ((messageKeyClass != null && messageKeyClass.isInstance(message))
                    || looksLikeMessageKey(message.getClass()))) {
                key = message;
            }
            if (key == null) return null;

            Class<?> curr = key.getClass();
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    Class<?> type = field.getType();
                    if (type == boolean.class || type == Boolean.class) {
                        field.setAccessible(true);
                        Object value = field.get(key);
                        return value instanceof Boolean ? (Boolean) value : null;
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " extractIsFromMe error: " + t.getClass().getSimpleName());
        }
        return null;
    }

    private boolean looksLikeMessageKey(@NonNull Class<?> candidate) {
        boolean hasBoolean = false;
        boolean hasString = false;
        Class<?> curr = candidate;
        while (curr != null && curr != Object.class) {
            for (Field field : curr.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> type = field.getType();
                if (type == boolean.class || type == Boolean.class) hasBoolean = true;
                if (type == String.class) hasString = true;
            }
            curr = curr.getSuperclass();
        }
        return hasBoolean && hasString;
    }

    /**
     * Robust message key_id extractor.
     * Scans for the Key object within FMessage or FStatus (which has remoteJid, messageId String, isFromMe boolean).
     */
    @Nullable
    private String extractKeyIdFromFMessage(@Nullable Object fMessageObj) {
        if (fMessageObj == null) return null;
        try {
            Class<?> objClass = fMessageObj.getClass();

            // 1. Fast Path: Use cached fields if already discovered
            Field cachedKField = S_CLASS_TO_KEY_FIELD.get(objClass);
            if (cachedKField != null) {
                try {
                    Object keyObj = cachedKField.get(fMessageObj);
                    if (keyObj != null) {
                        Field cachedIdField = S_KEY_CLASS_TO_ID_FIELD.get(keyObj.getClass());
                        if (cachedIdField != null) {
                            String id = (String) cachedIdField.get(keyObj);
                            if (id != null && !id.isEmpty()) return id;
                        }
                    }
                } catch (Throwable ignored) {}
            }

            // 2. Discover Key object inside FMessage
            Class<?> curr = objClass;
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object val = field.get(fMessageObj);
                    if (val == null) continue;

                    Class<?> valClass = val.getClass();
                    if (valClass.isPrimitive() || valClass == String.class || valClass.isArray()) continue;

                    // Inspect fields of val to determine if it is a Key object
                    String keyIdCandidate = null;
                    Field candidateField = null;
                    boolean hasBoolean = false;
                    boolean hasJidOrObject = false;

                    for (Field kField : valClass.getDeclaredFields()) {
                        if (Modifier.isStatic(kField.getModifiers())) continue;
                        kField.setAccessible(true);
                        Class<?> kType = kField.getType();

                        if (kType == String.class) {
                            String str = (String) kField.get(val);
                            if (str != null && str.length() >= 1 && !str.contains("@") && !str.contains("/")) {
                                keyIdCandidate = str;
                                candidateField = kField;
                            }
                        } else if (kType == boolean.class || kType == Boolean.class) {
                            hasBoolean = true;
                        } else if (!kType.isPrimitive()) {
                            hasJidOrObject = true;
                        }
                    }

                    if (keyIdCandidate != null && (hasBoolean || hasJidOrObject)) {
                        S_CLASS_TO_KEY_FIELD.put(objClass, field);
                        if (candidateField != null) {
                            S_KEY_CLASS_TO_ID_FIELD.put(valClass, candidateField);
                        }
                        cachedFMessageKeyField = field;
                        cachedKeyIdField = candidateField;
                        return keyIdCandidate;
                    }
                }
                curr = curr.getSuperclass();
            }

            // 3. Direct String field scan fallback
            curr = objClass;
            while (curr != null && curr != Object.class) {
                for (Field field : curr.getDeclaredFields()) {
                    if (field.getType() == String.class) {
                        field.setAccessible(true);
                        String strVal = (String) field.get(fMessageObj);
                        if (strVal != null && strVal.length() >= 1 && !strVal.contains("@") && isLikelyStatusKeyId(strVal)) {
                            return strVal;
                        }
                    }
                }
                curr = curr.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " extractKeyIdFromFMessage error: " + t.getClass().getSimpleName());
        }
        return null;
    }

    // ─── Preference helpers ──────────────────────────────────────────────

    private boolean isAntiRevokeEnabled() {
        // The native deleted-row presentation depends on the same preserved incoming
        // message marker. Enabling only show_as_deleted must therefore activate the
        // preservation path as well.
        if (HideAntiRevokeProFeature.isEnabled(prefs) && HideAntiRevokeProFeature.isHiddenForActiveConversation(prefs)) return true;
        return isEnabled("anti_revoke", false);
    }

    private boolean isPreserveOwnRevokeEnabled() {
        return isEnabled("preserve_own_revoke", false);
    }

    private boolean isAntiRevokeStatusEnabled() {
        return isEnabled("antirevokestatus", false);
    }

    private boolean isColorDeletedMessagesEnabled() {
        return isEnabled("anti_revoke_color_enabled", true);
    }

    private int getDeletedMessageColor() {
        try {
            // Bridge getters return their default for a mismatched type rather
            // than throwing. A non-null default would hide the schema's INT.
            String colorStr = prefs.getString("deleted_message_color", null);
            if (colorStr != null && !colorStr.isEmpty()) {
                try {
                    return Color.parseColor(colorStr);
                } catch (IllegalArgumentException ignored) {
                    return Integer.parseInt(colorStr);
                }
            }
        } catch (Throwable ignored) {}
        try {
            return prefs.getInt("deleted_message_color", Color.parseColor("#EF4444"));
        } catch (Throwable ignored) {}
        return Color.parseColor("#EF4444");
    }

    private int getIndicatorType() {
        try {
            String strVal = prefs.getString("anti_revoke_indicator", null);
            if (strVal != null) return Integer.parseInt(strVal);
        } catch (Throwable ignored) {}
        try {
            return prefs.getInt("anti_revoke_indicator", 2);
        } catch (Throwable ignored) {}
        return 2;
    }

    @NonNull
    @Override
    public String getName() {
        return "Anti-Revoke";
    }

    @NonNull
    private ListAdapter getUnderlyingAdapter(@NonNull ListAdapter adapter) {
        return HideAntiRevokeProFeature.getUnderlyingAdapter(adapter);
    }

    @Nullable
    private String getOriginalOrCurrentKeyId(@Nullable Object message) {
        if (message == null) return null;
        if (HideAntiRevokeProFeature.isEnabled(prefs)) {
            try {
                Object original = XposedHelpers.getAdditionalInstanceField(
                        message, "waex_original_key_id");
                if (original instanceof String && !((String) original).isEmpty()) {
                    return (String) original;
                }
            } catch (Throwable ignored) {}
        }
        return extractKeyIdFromFMessage(message);
    }

    @Nullable
    private String findRecordedKeyDeep(@Nullable Object value,
                                       @NonNull IdentityHashMap<Object, Boolean> seen,
                                       int depth) {
        if (value == null || depth > 6 || seen.put(value, Boolean.TRUE) != null) return null;
        if (value instanceof String) {
            String candidate = (String) value;
            return getRevokedTimestamp(candidate) > 0 ? candidate : null;
        }
        if (value instanceof View || value instanceof Context || value instanceof Number
                || value instanceof Boolean || value instanceof Class) return null;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                try {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);
                    Object child = field.get(value);
                    String found = findRecordedKeyDeep(child, seen, depth + 1);
                    if (found != null) return found;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

}
