package com.waenhancer.xposed.features.conversation

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
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
 * WhatsApp Business's own SQLite msgstore database and notifies HookProvider for UI sync.
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
        private const val PROVIDER_URI = "content://com.waenhancer.hookprovider"
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
                        val whereArgs = param.args.getOrNull(2) as? Array<*>

                        val db = param.thisObject as? SQLiteDatabase ?: return

                        try {
                            val sqlArgs = whereArgs?.map { it?.toString() ?: "" }?.toTypedArray() ?: emptyArray()
                            val querySql = "SELECT _id, key_id, chat_row_id, from_me, message_type, text_data, timestamp FROM message WHERE $where"

                            db.rawQuery(querySql, sqlArgs).use { cursor ->
                                if (cursor != null && cursor.moveToFirst()) {
                                    do {
                                        val rowId = cursor.getLong(0)
                                        val keyId = cursor.getString(1)
                                        val chatRowId = cursor.getLong(2)
                                        val fromMe = cursor.getInt(3) == 1
                                        val msgType = cursor.getInt(4)
                                        val textData = cursor.getString(5) ?: ""
                                        val msgTs = cursor.getLong(6)

                                        if (!keyId.isNullOrEmpty() && msgType != 15) {
                                            val now = System.currentTimeMillis()
                                            val chatJid = resolveChatJid(db, chatRowId)
                                            val isGroup = chatJid.contains("@g.us")

                                            // 1. Record in WhatsApp's internal DB
                                            DelMessageStore.getInstance(context).insertMessage(
                                                chatJid,
                                                keyId,
                                                now
                                            )

                                            // 2. Notify Manager App IPC Bridge
                                            dispatchToManagerApp(chatJid, keyId, textData, if (msgTs > 0) msgTs else now, fromMe, isGroup)

                                            XposedBridge.log("$TAG Preserved Delete-For-Me: keyId=$keyId, jid=$chatJid, fromMe=$fromMe, text='$textData'")
                                        }
                                    } while (cursor.moveToNext())

                                    // Block physical deletion from msgstore.db
                                    param.result = 0
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Error inspecting message before delete: ${t.message}")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG Database Delete-For-Me hook installed successfully.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error installing DB Delete-For-Me hook: ${t.message}")
        }
    }

    private fun resolveChatJid(db: SQLiteDatabase, chatRowId: Long): String {
        if (chatRowId <= 0) return "Unknown"
        try {
            val sql = "SELECT raw_string FROM jid WHERE _id = (SELECT jid_row_id FROM chat WHERE _id = ?)"
            db.rawQuery(sql, arrayOf(chatRowId.toString())).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val raw = cursor.getString(0)
                    if (!raw.isNullOrEmpty()) return raw
                }
            }
        } catch (ignored: Throwable) {}
        return "chat_$chatRowId"
    }

    private fun dispatchToManagerApp(
        jid: String,
        msgId: String,
        text: String,
        timestamp: Long,
        fromMe: Boolean,
        isGroup: Boolean
    ) {
        try {
            val bundle = Bundle().apply {
                putString("jid", jid)
                putString("name", jid.substringBefore("@"))
                putString("msgId", msgId)
                putString("text", text)
                putLong("timestamp", timestamp)
                putBoolean("fromMe", fromMe)
                putBoolean("isGroup", isGroup)
            }
            context.contentResolver.call(
                Uri.parse(PROVIDER_URI),
                "record_preserved_message",
                null,
                bundle
            )
        } catch (ignored: Throwable) {}
    }

    private fun resolveDeleteForMeMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_delete_for_me_method_v2",
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
