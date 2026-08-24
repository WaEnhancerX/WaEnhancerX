package com.waenhancer.xposed.features.conversation

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.Executors
import java.util.regex.Pattern

/**
 * Feature: Jump to First Message in Chat.
 *
 * Injects a menu option into WhatsApp's conversation 3-dot overflow menu.
 * When triggered, queries WhatsApp's SQLite msgstore database for the chat's earliest recorded
 * message index (sort_id & row_id) and relaunches Conversation navigation directly centered at the top.
 */
class JumpFirstMessageHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "JumpToFirstMessage"

    companion object {
        private const val TAG = "[WAEX:JumpFirstMessage]"
        private const val PREF_KEY = "jump_to_first_message"
        private const val MENU_ITEM_ID = 0x57414559 // 'WAEY'
        private const val MENU_LABEL = "Jump to First Message"
        private val bgExecutor = Executors.newSingleThreadExecutor()
    }

    private data class FirstMessageInfo(
        val rowId: Long,
        val sortId: Long,
        val chatRowId: Long
    )

    override fun hook() {
        try {
            val conversationClass = resolveConversationClass()
            if (conversationClass != null) {
                hookConversationMenuMethods(conversationClass)
            } else {
                XposedHelpers.findAndHookMethod(
                    Activity::class.java,
                    "onCreateOptionsMenu",
                    Menu::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            if (!isConversationActivity(activity)) return
                            val menu = param.args[0] as? Menu ?: return
                            injectJumpMenuItem(activity, menu)
                        }
                    }
                )
            }
            XposedBridge.log("$TAG JumpFirstMessage hook installed successfully.")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Installation error: ${t.message}")
        }
    }

    private fun resolveConversationClass(): Class<*>? {
        return try {
            classLoader.loadClass("com.whatsapp.Conversation")
        } catch (ignored: Throwable) {
            null
        }
    }

    private fun hookConversationMenuMethods(conversationClass: Class<*>) {
        var hooked = false
        for (m in conversationClass.declaredMethods) {
            if (m.parameterCount == 1 && m.parameterTypes[0] == Menu::class.java) {
                try {
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val activity = param.thisObject as? Activity ?: return
                            val menu = param.args[0] as? Menu ?: return
                            injectJumpMenuItem(activity, menu)
                        }
                    })
                    hooked = true
                } catch (ignored: Throwable) {}
            }
        }

        if (!hooked) {
            XposedHelpers.findAndHookMethod(
                conversationClass,
                "onCreateOptionsMenu",
                Menu::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        val menu = param.args[0] as? Menu ?: return
                        injectJumpMenuItem(activity, menu)
                    }
                }
            )
        }
    }

    private fun injectJumpMenuItem(activity: Activity, menu: Menu) {
        if (!isEnabled(PREF_KEY, false)) return

        if (menu.findItem(MENU_ITEM_ID) != null) return

        val menuItem = menu.add(Menu.NONE, MENU_ITEM_ID, Menu.NONE, MENU_LABEL)
        menuItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        menuItem.setOnMenuItemClickListener {
            val chatJid = extractChatJidFromActivity(activity)
            if (chatJid.isNullOrEmpty()) {
                Toast.makeText(activity, "Unable to determine chat JID", Toast.LENGTH_SHORT).show()
                return@setOnMenuItemClickListener true
            }

            bgExecutor.execute {
                val firstMsg = queryFirstMessage(activity, chatJid)
                if (firstMsg == null) {
                    activity.runOnUiThread {
                        Toast.makeText(activity, "No message history found", Toast.LENGTH_SHORT).show()
                    }
                    return@execute
                }

                activity.runOnUiThread {
                    navigateToFirstMessage(activity, chatJid, firstMsg)
                }
            }
            true
        }
    }

    private fun navigateToFirstMessage(activity: Activity, chatJid: String, msgInfo: FirstMessageInfo) {
        try {
            val navIntent = Intent(activity, activity.javaClass).apply {
                putExtra("jid", chatJid)
                putExtra("row_id", msgInfo.rowId)
                putExtra("sort_id", msgInfo.sortId)
                putExtra("start_t", SystemClock.uptimeMillis())
                putExtra("mat_entry_point", 64)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }

            activity.startActivity(navIntent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(0, 0)
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Navigation failure: ${t.message}")
        }
    }

    private fun queryFirstMessage(ctx: Context, rawJid: String): FirstMessageInfo? {
        try {
            val dbFile = File(ctx.filesDir.parentFile, "databases/msgstore.db")
            if (!dbFile.exists()) return null

            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val sql = """
                    WITH target_jid(jid_row_id) AS (
                        SELECT _id FROM jid WHERE raw_string=? OR user=?
                        UNION
                        SELECT jm.jid_row_id FROM jid_map jm
                        INNER JOIN jid j ON j._id = jm.lid_row_id
                        WHERE j.raw_string=? OR j.user=?
                        UNION
                        SELECT jm.lid_row_id FROM jid_map jm
                        INNER JOIN jid j ON j._id = jm.jid_row_id
                        WHERE j.raw_string=? OR j.user=?
                    ), target_chat AS (
                        SELECT _id FROM chat WHERE jid_row_id IN (SELECT jid_row_id FROM target_jid)
                    )
                    SELECT m._id, m.sort_id, m.chat_row_id
                    FROM message m
                    INNER JOIN target_chat c ON c._id = m.chat_row_id
                    ORDER BY m.sort_id ASC, m._id ASC
                    LIMIT 1
                """.trimIndent()

                val cursor: Cursor? = db.rawQuery(
                    sql,
                    arrayOf(rawJid, rawJid, rawJid, rawJid, rawJid, rawJid)
                )

                cursor?.use {
                    if (it.moveToFirst()) {
                        return FirstMessageInfo(
                            rowId = it.getLong(0),
                            sortId = it.getLong(1),
                            chatRowId = it.getLong(2)
                        )
                    }
                }

                // Fallback simpler query
                val fallbackSql = """
                    SELECT m._id, m.sort_id, m.chat_row_id
                    FROM message m
                    INNER JOIN chat c ON m.chat_row_id = c._id
                    INNER JOIN jid j ON c.jid_row_id = j._id
                    WHERE j.raw_string=? OR j.user=?
                    ORDER BY m.sort_id ASC, m._id ASC
                    LIMIT 1
                """.trimIndent()

                val fallbackCursor: Cursor? = db.rawQuery(fallbackSql, arrayOf(rawJid, rawJid))
                fallbackCursor?.use {
                    if (it.moveToFirst()) {
                        return FirstMessageInfo(
                            rowId = it.getLong(0),
                            sortId = it.getLong(1),
                            chatRowId = it.getLong(2)
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG queryFirstMessage error: ${t.message}")
        }
        return null
    }

    private fun extractChatJidFromActivity(activity: Activity): String? {
        // 1. Intent string extras
        activity.intent?.getStringExtra("jid")?.let { if (it.isNotEmpty()) return it }
        activity.intent?.getStringExtra("chat_jid")?.let { if (it.isNotEmpty()) return it }

        // 2. Intent parcelable extra
        activity.intent?.extras?.let { bundle ->
            for (key in bundle.keySet()) {
                val value = bundle.get(key)
                if (value != null) {
                    val s = value.toString()
                    if (s.contains("@s.whatsapp.net") || s.contains("@g.us") || s.contains("@lid")) {
                        return s.substringAfter("rawString=").substringBefore(")").substringBefore(" ")
                    }
                }
            }
        }

        // 3. Activity fields
        try {
            var curr: Class<*>? = activity.javaClass
            while (curr != null && curr != Any::class.java) {
                for (f in curr.declaredFields) {
                    f.isAccessible = true
                    val obj = f.get(activity) ?: continue
                    val str = obj.toString()
                    if (str.endsWith("@s.whatsapp.net") || str.endsWith("@g.us") || str.endsWith("@lid")) {
                        return str
                    }
                    if (str.contains("@s.whatsapp.net") || str.contains("@g.us") || str.contains("@lid")) {
                        val matcher = Pattern.compile("([a-zA-Z0-9._-]+@(s\\.whatsapp\\.net|g\\.us|lid))").matcher(str)
                        if (matcher.find()) {
                            return matcher.group(1)
                        }
                    }
                }
                curr = curr.superclass
            }
        } catch (ignored: Throwable) {}

        return null
    }

    private fun isConversationActivity(activity: Activity): Boolean {
        val name = activity.javaClass.name
        return name.contains("Conversation") || name.contains("ChatActivity")
    }
}
