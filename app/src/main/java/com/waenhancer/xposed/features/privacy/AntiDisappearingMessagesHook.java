package com.waenhancer.xposed.features.privacy;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;
import androidx.annotation.NonNull;
import com.waenhancer.xposed.core.BaseFeature;
import com.waenhancer.xposed.core.devkit.DexSearchEngine;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Method;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Anti-Disappearing Messages Hook:
 * Prevents ephemeral / disappearing messages from expiring and disappearing from chats.
 * 
 * Strategy:
 * 1. Overrides `expire_timestamp` in the `message_ephemeral` table to far future (~year 2050).
 * 2. Hooks ephemeral ContentValues generator method in bytecode to set far-future expiry.
 * 3. Updates existing ephemeral messages on database access/open.
 */
public class AntiDisappearingMessagesHook extends BaseFeature {

    private static final String TAG = "[WAEX][AntiDisappearing]";
    private static final long FAR_FUTURE_EXPIRY_MS = 2553512370000L; // Year 2050+
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    public AntiDisappearingMessagesHook(@NonNull Context context, @NonNull ClassLoader classLoader, @NonNull SharedPreferences prefs) {
        super(context, classLoader, prefs);
    }

    @NonNull
    @Override
    public String getName() {
        return "AntiDisappearingMessagesHook";
    }

    @Override
    public void hook() throws Throwable {
        try {
            hookEphemeralContentValues();
            hookDatabaseEphemeralWrites();
        } catch (Throwable t) {
            XposedBridge.log(TAG + " Error installing hooks: " + t.getMessage());
        }
    }

    /**
     * Intercepts the bytecode method that produces ContentValues for ephemeral message storage.
     */
    private void hookEphemeralContentValues() {
        DexSearchEngine engine = DexSearchEngine.getInstance();
        Method ephemeralInsertDbMethod = engine.findMethodWithCache(
                context,
                classLoader,
                "wpp_ephemeral_insert_content_values",
                (bridge, loader) -> {
                    MethodData md = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .addUsingString("expire_timestamp")
                                    .addUsingString("ephemeral_initiated_by_me")
                                    .addUsingString("ephemeral_trigger")
                                    .returnType(ContentValues.class.getName())
                            )
                    ).firstOrNull();
                    return md != null ? md.getMethodInstance(loader) : null;
                }
        );

        if (ephemeralInsertDbMethod != null) {
            XposedBridge.hookMethod(ephemeralInsertDbMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!isAntiDisappearingEnabled()) return;
                    if (param.getResult() instanceof ContentValues) {
                        ContentValues values = (ContentValues) param.getResult();
                        values.put("expire_timestamp", FAR_FUTURE_EXPIRY_MS);
                    }
                }
            });
            XposedBridge.log(TAG + " Hooked ephemeralInsertDbMethod: " + ephemeralInsertDbMethod.getName());
        }
    }

    /**
     * Intercepts SQLite inserts and updates on `message_ephemeral` table to ensure expiry is never triggered.
     */
    private void hookDatabaseEphemeralWrites() {
        // Intercept SQLiteDatabase.insert / insertWithOnConflict
        XposedBridge.hookAllMethods(SQLiteDatabase.class, "insertWithOnConflict", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isAntiDisappearingEnabled()) return;
                if (param.args.length > 2 && "message_ephemeral".equals(param.args[0])) {
                    if (param.args[2] instanceof ContentValues) {
                        ContentValues cv = (ContentValues) param.args[2];
                        cv.put("expire_timestamp", FAR_FUTURE_EXPIRY_MS);
                    }
                }
            }
        });

        // Intercept SQLiteDatabase.update
        XposedBridge.hookAllMethods(SQLiteDatabase.class, "updateWithOnConflict", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!isAntiDisappearingEnabled()) return;
                if (param.args.length > 1 && "message_ephemeral".equals(param.args[0])) {
                    if (param.args[1] instanceof ContentValues) {
                        ContentValues cv = (ContentValues) param.args[1];
                        cv.put("expire_timestamp", FAR_FUTURE_EXPIRY_MS);
                    }
                }
            }
        });
    }

    private boolean isAntiDisappearingEnabled() {
        return isEnabled("antidisappearing", false);
    }
}
