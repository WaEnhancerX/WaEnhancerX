package com.waenhancer.xposed.features.conversation

import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.reflect.Method

/**
 * Feature: Preserve "Delete For Me" Messages.
 *
 * Intercepts message deletion requests when the user clicks "Delete For Me".
 * Resolves contact display names, sender names, & group subjects from WhatsApp's wa.db & msgstore.db,
 * copies the message to the Manager App vault, then allows WhatsApp to delete its own row.
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

        private val deleteForMeScope = ThreadLocal<Boolean>()

        /** Lets other database hooks distinguish a local Delete-for-me from a remote revoke. */
        @JvmStatic
        fun isDeleteForMeInProgress(): Boolean = deleteForMeScope.get() == true
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
                            if (deleteMethod.name == "run") {
                                // Multiplexed Runnables are shared by unrelated WhatsApp features.
                                // Only enter the delete scope after resolving real message keys.
                                if (!captureRunnableMessages(param.thisObject)) return
                            }
                            deleteForMeScope.set(true)
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            deleteForMeScope.remove()
                        }
                    }
                )
            } else {
                XposedBridge.log("$TAG Warning: DeleteForMe method not found via dexkit.")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to install bytecode DeleteForMe hook: ${t.javaClass.simpleName}")
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
                        if (!isDeleteForMeInProgress()) return

                        val table = param.args.getOrNull(0) as? String ?: return
                        if (table != "message") return

                        val where = param.args.getOrNull(1) as? String ?: return
                        val whereArgs = param.args.getOrNull(2) as? Array<*>

                        val db = param.thisObject as? SQLiteDatabase ?: return

                        try {
                            val sqlArgs = whereArgs?.map { it?.toString() ?: "" }?.toTypedArray() ?: emptyArray()
                            val querySql = "SELECT _id, key_id, chat_row_id, from_me, message_type, text_data, timestamp, sender_jid_row_id FROM message WHERE $where"

                            db.rawQuery(querySql, sqlArgs).use { cursor ->
                                if (cursor != null && cursor.moveToFirst()) {
                                    do {
                                        val keyId = cursor.getString(1)
                                        val chatRowId = cursor.getLong(2)
                                        val fromMe = cursor.getInt(3) == 1
                                        val msgType = cursor.getInt(4)
                                        val textData = cursor.getString(5) ?: ""
                                        val msgTs = cursor.getLong(6)
                                        val senderJidRowId = if (cursor.columnCount > 7) cursor.getLong(7) else 0L

                                        if (!keyId.isNullOrEmpty() && msgType != 15) {
                                            val now = System.currentTimeMillis()
                                            val chatJid = resolveChatJid(db, chatRowId)
                                            val isGroup = chatJid.contains("@g.us")
                                            val chatDisplayName = resolveChatDisplayName(context, db, chatRowId, chatJid)
                                            val senderDisplayName = if (isGroup)
                                                resolveSenderDisplayName(context, db, senderJidRowId, chatDisplayName)
                                            else chatDisplayName

                                            dispatchToManagerApp(
                                                jid = chatJid,
                                                chatName = chatDisplayName,
                                                senderName = senderDisplayName,
                                                msgId = keyId,
                                                text = textData,
                                                timestamp = if (msgTs > 0) msgTs else now,
                                                fromMe = fromMe,
                                                isGroup = isGroup
                                            )
                                        }
                                    } while (cursor.moveToNext())
                                }
                            }
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Error inspecting message before delete: ${t.javaClass.simpleName}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error installing DB Delete-For-Me hook: ${t.javaClass.simpleName}")
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

    private fun resolveSenderDisplayName(
        ctx: Context,
        msgstoreDb: SQLiteDatabase,
        senderJidRowId: Long,
        fallback: String
    ): String {
        if (senderJidRowId <= 0) return fallback
        try {
            var rawJid = ""
            var userPart = ""
            msgstoreDb.rawQuery("SELECT raw_string, user FROM jid WHERE _id = ?", arrayOf(senderJidRowId.toString())).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    rawJid = cursor.getString(0) ?: ""
                    userPart = cursor.getString(1) ?: ""
                }
            }
            if (rawJid.isNotBlank()) {
                val resolved = resolveUserDisplayName(ctx, msgstoreDb, rawJid, userPart)
                if (resolved.isNotBlank()) return resolved
            }
        } catch (ignored: Throwable) {}
        return fallback
    }

    private fun resolveChatDisplayName(
        ctx: Context,
        msgstoreDb: SQLiteDatabase,
        chatRowId: Long,
        rawJid: String
    ): String {
        // 1. If group chat, get subject from chat table in msgstore.db
        try {
            msgstoreDb.rawQuery("SELECT subject FROM chat WHERE _id = ?", arrayOf(chatRowId.toString())).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val subject = cursor.getString(0)
                    if (!subject.isNullOrBlank()) return subject
                }
            }
        } catch (ignored: Throwable) {}

        val userPart = rawJid.substringBefore("@")
        return resolveUserDisplayName(ctx, msgstoreDb, rawJid, userPart)
    }

    private fun resolveUserDisplayName(
        ctx: Context,
        msgstoreDb: SQLiteDatabase,
        rawJid: String,
        userPart: String
    ): String {
        var resolvedPhoneJid: String? = null
        var resolvedPhoneUser: String? = null
        val cleanJid = if (rawJid.contains("@")) rawJid else "$rawJid@lid"

        // 1. Check jid_map in msgstore.db (LID -> Phone JID)
        try {
            val resolvePhoneSql = """
                SELECT j2.raw_string, j2.user 
                FROM jid_map jm
                JOIN jid j1 ON jm.lid_row_id = j1._id
                JOIN jid j2 ON jm.jid_row_id = j2._id
                WHERE j1.raw_string = ? OR j1.user = ?
            """.trimIndent()
            msgstoreDb.rawQuery(resolvePhoneSql, arrayOf(cleanJid, userPart)).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    resolvedPhoneJid = cursor.getString(0)
                    resolvedPhoneUser = cursor.getString(1)
                }
            }
        } catch (ignored: Throwable) {}

        // 2. Query wa.db (wa_contacts table)
        try {
            val waDbFile = File(ctx.filesDir.parentFile, "databases/wa.db")
            if (waDbFile.exists()) {
                SQLiteDatabase.openDatabase(waDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { waDb ->
                    val querySql = "SELECT display_name, wa_name, sort_name, number FROM wa_contacts WHERE jid = ? OR jid = ? OR number = ? OR number = ? OR number LIKE ?"
                    val params = arrayOf(
                        rawJid,
                        resolvedPhoneJid ?: "",
                        userPart,
                        resolvedPhoneUser ?: "",
                        "%${resolvedPhoneUser ?: userPart}%"
                    )
                    waDb.rawQuery(querySql, params).use { cursor ->
                        if (cursor != null && cursor.moveToFirst()) {
                            val displayName = cursor.getString(0)
                            val waName = cursor.getString(1)
                            val sortName = cursor.getString(2)
                            val number = cursor.getString(3)

                            if (!displayName.isNullOrBlank() && !displayName.all { it.isDigit() }) return displayName
                            if (!waName.isNullOrBlank() && !waName.all { it.isDigit() }) return waName
                            if (!sortName.isNullOrBlank() && !sortName.all { it.isDigit() }) return sortName
                            if (!displayName.isNullOrBlank()) return displayName
                            if (!number.isNullOrBlank()) return "+$number"
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {}

        // 3. Check verified business names in wa.db
        try {
            val waDbFile = File(ctx.filesDir.parentFile, "databases/wa.db")
            if (waDbFile.exists()) {
                SQLiteDatabase.openDatabase(waDbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { waDb ->
                    waDb.rawQuery("SELECT verified_name FROM wa_vnames WHERE jid = ? OR jid = ?", arrayOf(rawJid, resolvedPhoneJid ?: "")).use { cursor ->
                        if (cursor != null && cursor.moveToFirst()) {
                            val vname = cursor.getString(0)
                            if (!vname.isNullOrBlank()) return vname
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {}

        if (!resolvedPhoneUser.isNullOrBlank()) {
            return "+$resolvedPhoneUser"
        }

        return if (userPart.all { it.isDigit() } && userPart.length in 10..15) {
            "+$userPart"
        } else {
            userPart
        }
    }

    private fun dispatchToManagerApp(
        jid: String,
        chatName: String,
        senderName: String,
        msgId: String,
        text: String,
        timestamp: Long,
        fromMe: Boolean,
        isGroup: Boolean
    ) {
        try {
            val bundle = Bundle().apply {
                putString("jid", jid)
                putString("name", chatName)
                putString("senderName", senderName)
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
        } catch (t: Throwable) {
            XposedBridge.log("$TAG dispatchToManagerApp failed: ${t.javaClass.simpleName}")
        }
    }

    private fun resolveDeleteForMeMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_delete_for_me_method_v7",
                { bridge, loader ->
                    resolveCurrentDeleteForMeRunnable(bridge, loader) ?: bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "CoreMessageStore/deleteMessageForMe",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()?.getMethodInstance(loader)?.takeIf {
                        it.name != "run" || it.parameterTypes.isNotEmpty()
                    } ?: bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "msgstore/delete-for-me",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()?.getMethodInstance(loader)?.takeIf {
                        it.name != "run" || it.parameterTypes.isNotEmpty()
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Exception while searching DeleteForMe method: ${t.javaClass.simpleName}")
            null
        }
    }

    /**
     * Resolve the local-delete Runnable from WhatsApp's stable semantic and interface graph rather
     * than obfuscated names, synthetic switch values, or constructor arity:
     * message-key helper string -> dialog builder(Set) -> positive listener -> Runnable -> run().
     */
    private fun resolveCurrentDeleteForMeRunnable(
        bridge: org.luckypray.dexkit.DexKitBridge,
        loader: ClassLoader
    ): Method? {
        val deleteDialogAnchors = bridge.findMethod(
            FindMethod.create().matcher(
                MethodMatcher.create().addUsingString(
                    "DeleteMessagesDialog/getSampleMessageForMediaType/multi chat jid delete",
                    StringMatchType.Equals
                )
            )
        )
        val deleteDialogAnchor = deleteDialogAnchors.firstOrNull() ?: return null

        val dialogBuilders = deleteDialogAnchor.declaredClass?.methods?.filter { method ->
            val params = method.paramTypeNames
            "java.util.Set" in params && "boolean" in params && params.size >= 6
        }.orEmpty()
        val dialogBuilder = dialogBuilders.firstOrNull() ?: return null

        val deleteListenerConstructor = dialogBuilder.invokes.firstOrNull { invoked ->
            if (!invoked.isConstructor) return@firstOrNull false
            val params = invoked.paramTypeNames
            if (params.firstOrNull() != "android.widget.CheckBox" ||
                "java.util.Set" !in params) return@firstOrNull false
            val declaredClass = invoked.declaredClass ?: return@firstOrNull false
            declaredClass.interfaces.any {
                it.name == "android.content.DialogInterface\$OnClickListener"
            }
        } ?: return null

        val deleteListenerClass = deleteListenerConstructor.declaredClass ?: return null
        val onClick = deleteListenerClass.methods.firstOrNull { method ->
            method.name == "onClick" &&
                method.paramTypeNames == listOf("android.content.DialogInterface", "int") &&
                method.returnTypeName == "void"
        } ?: return null

        val deleteRunnableConstructor = onClick.invokes.firstOrNull { invoked ->
            if (!invoked.isConstructor) return@firstOrNull false
            val params = invoked.paramTypeNames
            if ("int" !in params || "boolean" !in params) return@firstOrNull false
            val declaredClass = invoked.declaredClass ?: return@firstOrNull false
            declaredClass.interfaces.any { it.name == "java.lang.Runnable" }
        } ?: return null

        val deleteRunnableClass = deleteRunnableConstructor.declaredClass ?: return null
        return deleteRunnableClass.methods.firstOrNull { method ->
            method.name == "run" && method.paramCount == 0 && method.returnTypeName == "void"
        }?.getMethodInstance(loader)
    }

    /**
     * Current WhatsApp versions delete through an internal message-store API and may never call
     * SQLiteDatabase.delete(). The Delete-for-me Runnable still owns the selected message Set, so
     * copy those rows to the WAEX vault before allowing the Runnable to continue.
     */
    private fun captureRunnableMessages(instance: Any?): Boolean {
        if (instance == null) return false
        try {
            val messageSets = instance.javaClass.declaredFields.asSequence()
                .filterNot { it.type.isPrimitive }
                .mapNotNull { field ->
                    field.isAccessible = true
                    field.get(instance) as? Set<*>
                }
                .toList()
            if (messageSets.isEmpty()) return false

            val keys = messageSets.flatten().mapNotNull(::extractMessageKey).distinctBy { it.id }
            if (keys.isEmpty()) {
                XposedBridge.log("$TAG Warning: selected message keys were not resolved")
                return false
            }

            val dbFile = context.getDatabasePath("msgstore.db")
            if (!dbFile.exists()) {
                XposedBridge.log("$TAG Warning: msgstore.db was not found")
                return false
            }

            var captured = 0
            SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                keys.forEach { key ->
                    db.rawQuery(
                        "SELECT _id, key_id, chat_row_id, from_me, message_type, text_data, " +
                            "timestamp, sender_jid_row_id FROM message WHERE key_id = ? LIMIT 1",
                        arrayOf(key.id)
                    ).use { cursor ->
                        if (!cursor.moveToFirst() || cursor.getInt(4) == 15) return@use
                        val chatRowId = cursor.getLong(2)
                        val fromMe = cursor.getInt(3) == 1
                        val chatJid = resolveChatJid(db, chatRowId).ifBlank { key.chatJid }
                        val isGroup = chatJid.contains("@g.us")
                        val chatName = resolveChatDisplayName(context, db, chatRowId, chatJid)
                        val senderName = if (isGroup) {
                            resolveSenderDisplayName(context, db, cursor.getLong(7), chatName)
                        } else if (fromMe) {
                            "You"
                        } else {
                            chatName
                        }
                        dispatchToManagerApp(
                            jid = chatJid,
                            chatName = chatName,
                            senderName = senderName,
                            msgId = cursor.getString(1) ?: key.id,
                            text = cursor.getString(5) ?: "",
                            timestamp = cursor.getLong(6),
                            fromMe = fromMe,
                            isGroup = isGroup
                        )
                        captured++
                    }
                }
            }
            return captured > 0
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Error copying selected messages: ${t.javaClass.simpleName}")
            return false
        }
    }

    private data class SelectedMessageKey(
        val id: String,
        val chatJid: String
    )

    private fun extractMessageKey(message: Any?): SelectedMessageKey? {
        if (message == null) return null
        return try {
            val methodKey = message.javaClass.methods.firstOrNull {
                it.name == "Atl" && it.parameterTypes.isEmpty()
            }?.invoke(message)
            val key = methodKey ?: allInstanceFields(message.javaClass)
                .filterNot { it.type.isPrimitive || it.type == String::class.java }
                .mapNotNull { field ->
                    field.isAccessible = true
                    field.get(message)
                }
                .firstOrNull(::looksLikeMessageKey)
                ?: return null
            val fields = key.javaClass.declaredFields.onEach { it.isAccessible = true }
            val id = fields.singleOrNull { it.type == String::class.java }
                ?.get(key) as? String ?: return null
            val jid = fields.firstOrNull {
                !it.type.isPrimitive && it.type != String::class.java
            }?.get(key)?.toString().orEmpty()
            SelectedMessageKey(id, jid)
        } catch (_: Throwable) {
            null
        }
    }

    private fun allInstanceFields(type: Class<*>): Sequence<java.lang.reflect.Field> = sequence {
        var current: Class<*>? = type
        while (current != null && current != Any::class.java) {
            yieldAll(current.declaredFields.asSequence())
            current = current.superclass
        }
    }

    private fun looksLikeMessageKey(candidate: Any): Boolean {
        val fields = candidate.javaClass.declaredFields
        return fields.count { it.type == String::class.java } == 1 &&
            fields.count { it.type == Boolean::class.javaPrimitiveType } == 1 &&
            fields.any { !it.type.isPrimitive && it.type != String::class.java }
    }
}
