package com.waenhancer.xposed.features.conversation

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.db.DelMessageStore
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/**
 * Feature: Preserve "Delete For Me" Messages.
 *
 * Intercepts both bytecode and database-level message deletion requests triggered
 * when the user clicks "Delete For Me". Prevents the row from being wiped from WhatsApp's /
 * WhatsApp Business's own SQLite msgstore database and records the preserved message index.
 */
class PreserveDeleteForMeHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "PreserveDeleteForMe"

    companion object {
        private const val TAG = "[WAEX:PreserveDeleteForMe]"
        private const val PREF_KEY = "preserve_delete_for_me"
    }

    override fun hook() {
        hookBytecodeDeleteForMe()
        hookDatabaseDeleteForMe()
    }

    private fun hookBytecodeDeleteForMe() {
        try {
            val deleteMethod = resolveDeleteForMeMethod()
            if (deleteMethod != null) {
                XposedBridge.hookMethod(
                    deleteMethod,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled(PREF_KEY, false)) return

                            try {
                                XposedBridge.log("$TAG Intercepted bytecode delete-for-me call, preserving message.")
                                param.result = null
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG Error in bytecode delete hook: ${t.message}")
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG Hooked bytecode delete-for-me on ${deleteMethod.name}")
            } else {
                XposedBridge.log("$TAG Warning: DeleteForMe method not found via dexkit.")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to install bytecode DeleteForMe hook: ${t.message}")
        }
    }

    private fun hookDatabaseDeleteForMe() {
        try {
            XposedBridge.hookAllMethods(
                SQLiteDatabase::class.java,
                "delete",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled(PREF_KEY, false)) return

                        val table = param.args.getOrNull(0) as? String ?: return
                        if (table != "message") return

                        val where = param.args.getOrNull(1) as? String ?: return
                        val whereArgs = param.args.getOrNull(2) as? Array<*> ?: return

                        if (!where.contains("_id=?") || whereArgs.isEmpty()) return

                        val rowId = whereArgs[0]?.toString() ?: return
                        val db = param.thisObject as? SQLiteDatabase ?: return

                        try {
                            db.rawQuery(
                                "SELECT key_id, chat_row_id, from_me, message_type FROM message WHERE _id=?",
                                arrayOf(rowId)
                            ).use { cursor ->
                                if (cursor != null && cursor.moveToFirst()) {
                                    val keyId = cursor.getString(0)
                                    val chatRowId = cursor.getLong(1)
                                    val fromMe = cursor.getInt(2)
                                    val msgType = cursor.getInt(3)

                                    if (!keyId.isNullOrEmpty() && msgType != 15) {
                                        val now = System.currentTimeMillis()
                                        DelMessageStore.getInstance(context).insertMessage(
                                            chatRowId.toString(),
                                            keyId,
                                            now
                                        )
                                        XposedBridge.log("$TAG Preserved Delete-For-Me message in db: keyId=$keyId, chatRowId=$chatRowId, fromMe=$fromMe")
                                        // Block physical deletion
                                        param.result = 0
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Error inspecting message before delete: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG Database Delete-For-Me hook installed.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error installing DB Delete-For-Me hook: ${t.message}")
        }
    }

    private fun resolveDeleteForMeMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_delete_for_me_method",
                { bridge, loader ->
                    val data = bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "CoreMessageStore/deleteMessageForMe",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull() ?: bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "msgstore/delete-for-me",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()
                    data?.getMethodInstance(loader)
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Exception while searching DeleteForMe method: ${t.message}")
            null
        }
    }
}
