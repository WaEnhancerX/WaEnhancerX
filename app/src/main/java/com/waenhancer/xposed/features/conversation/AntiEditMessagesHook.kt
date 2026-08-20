package com.waenhancer.xposed.features.conversation

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HeaderViewListAdapter
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.core.components.WaexBottomSheet
import com.waenhancer.xposed.core.db.EditMessageStore
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Anti-Edit Messages Feature for WhatsApp.
 *
 * 1. Multi-layer interception of incoming message edits (Bytecode + SQLite DB).
 * 2. Persists original pre-edited message content, edit timestamps, and revisions.
 * 3. Decorates WhatsApp's message bubble "(edited)" label with interactive visual cues (📝).
 * 4. Tapping "(edited)" opens WhatsApp's native WDS Bottom Sheet displaying the full edit history
 *    with timestamps and one-tap clipboard copy for any version.
 */
class AntiEditMessagesHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "AntiEditMessages"

    companion object {
        private const val TAG = "[WAEX:AntiEdit]"
        private const val PREF_KEY = "anti_edit_messages"
        private const val FIELD_EDIT_MSG_ID = "waex_bound_edit_msg_id"
        private const val FIELD_EDIT_MSG_TEXT = "waex_bound_edit_msg_text"
        private val messageTextCache = ConcurrentHashMap<String, String>()
        private val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)
    }

    private val editStore: EditMessageStore by lazy { EditMessageStore.getInstance(context) }
    private var fMessageClass: Class<*>? = null

    override fun hook() {
        initFMessageClass()
        hookBytecodeMessageEdit()
        hookDatabaseMessageEdit()
        hookListViewAdapter()
        hookViewAttachmentFallback()
        XposedBridge.log("$TAG All AntiEdit hooks initialized.")
    }

    private fun initFMessageClass() {
        try {
            fMessageClass = DexSearchEngine.getInstance().findClassWithCache(
                context, classLoader, "wpp_fmessage_class_edit",
                { bridge, loader ->
                    val data = bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString("FMessageUtil/isEdited", StringMatchType.Contains)
                        )
                    ).firstOrNull()
                    data?.getMethodInstance(loader)?.parameterTypes?.getOrNull(0)
                }
            )
        } catch (ignored: Throwable) {}
    }

    // ─── 1. Bytecode Edit Interception ──────────────────────────────────────────

    private fun hookBytecodeMessageEdit() {
        try {
            val editMethod = resolveMessageEditMethod()
            if (editMethod != null) {
                XposedBridge.hookMethod(
                    editMethod,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isEnabled(PREF_KEY, false)) return

                            try {
                                val messageObj = param.args.getOrNull(0) ?: return
                                val msgId = extractMessageId(messageObj) ?: return
                                val jid = extractMessageJid(messageObj)
                                val currentText = extractMessageText(messageObj)

                                val previousText = messageTextCache[msgId]
                                if (!previousText.isNullOrEmpty() && !currentText.isNullOrEmpty() && previousText != currentText) {
                                    editStore.recordEdit(msgId, jid, previousText, currentText, System.currentTimeMillis())
                                    XposedBridge.log("$TAG Recorded bytecode edit for msgId: $msgId")
                                }

                                if (!currentText.isNullOrEmpty()) {
                                    messageTextCache[msgId] = currentText
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG Error in bytecode edit hook: ${t.message}")
                            }
                        }
                    }
                )
                XposedBridge.log("$TAG Hooked bytecode edit method: ${editMethod.name}")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook bytecode edit: ${t.message}")
        }
    }

    private fun resolveMessageEditMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_message_edit_insert_v3",
                { bridge, loader ->
                    val data = bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "MessageEditInfoStore/insertEditInfo/missing",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull() ?: bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "MessageEditInfoStore/insertEditInfo",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()
                    data?.getMethodInstance(loader)
                }
            )
        } catch (t: Throwable) {
            null
        }
    }

    // ─── 2. SQLite Database Edit Interception ───────────────────────────────────

    private fun hookDatabaseMessageEdit() {
        try {
            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                ContentValues::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled(PREF_KEY, false)) return
                        val table = param.args.getOrNull(0) as? String ?: return
                        if (table == "message_edit_info" || table == "message_add_on") {
                            val values = param.args.getOrNull(2) as? ContentValues ?: return
                            handleDbEditInsert(values)
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "updateWithOnConflict",
                String::class.java,
                ContentValues::class.java,
                String::class.java,
                Array<String>::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled(PREF_KEY, false)) return
                        val table = param.args.getOrNull(0) as? String ?: return
                        if (table == "message") {
                            val values = param.args.getOrNull(1) as? ContentValues ?: return
                            val textData = values.getAsString("text_data")
                            val keyId = values.getAsString("key_id")
                            if (!keyId.isNullOrEmpty() && !textData.isNullOrEmpty()) {
                                val prev = messageTextCache[keyId]
                                if (!prev.isNullOrEmpty() && prev != textData) {
                                    editStore.recordEdit(keyId, "", prev, textData, System.currentTimeMillis())
                                    XposedBridge.log("$TAG Recorded DB update edit for keyId: $keyId")
                                }
                                messageTextCache[keyId] = textData
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Database edit hook error: ${t.message}")
        }
    }

    private fun handleDbEditInsert(values: ContentValues) {
        try {
            val keyId = values.getAsString("original_key_id")
                ?: values.getAsString("key_id")
                ?: values.getAsString("message_id")
            val originalText = values.getAsString("original_text")
                ?: values.getAsString("old_text")
            val newText = values.getAsString("edited_text")
                ?: values.getAsString("new_text")
            val timestamp = values.getAsLong("edit_timestamp") ?: System.currentTimeMillis()

            if (!keyId.isNullOrEmpty() && (!originalText.isNullOrEmpty() || !newText.isNullOrEmpty())) {
                val orig = originalText ?: messageTextCache[keyId] ?: ""
                editStore.recordEdit(keyId, "", orig, newText ?: "", timestamp)
                XposedBridge.log("$TAG Recorded DB insert edit for key: $keyId")
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG DB edit insert parsing error: ${t.message}")
        }
    }

    // ─── 3. ListView.setAdapter → getView Hook (Conversation Row Binding) ───────

    private fun hookListViewAdapter() {
        try {
            XposedHelpers.findAndHookMethod(
                ListView::class.java,
                "setAdapter",
                ListAdapter::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val currentAct = ActivityTracker.getCurrentActivity() ?: return
                        val actName = currentAct.javaClass.simpleName
                        if (!actName.contains("Conversation")) return

                        val listView = param.thisObject as? ListView ?: return
                        if (listView.id != android.R.id.list) return

                        var adapter = param.args.getOrNull(0) as? ListAdapter ?: return
                        if (adapter is HeaderViewListAdapter) {
                            adapter = adapter.wrappedAdapter
                        }
                        if (adapter == null) return

                        try {
                            val getViewMethod = adapter.javaClass.getDeclaredMethod(
                                "getView",
                                Int::class.javaPrimitiveType,
                                View::class.java,
                                ViewGroup::class.java
                            )
                            XposedBridge.hookMethod(
                                getViewMethod,
                                object : XC_MethodHook() {
                                    override fun afterHookedMethod(p: MethodHookParam) {
                                        if (!isEnabled(PREF_KEY, false)) return
                                        val pos = p.args.getOrNull(0) as? Int ?: return
                                        val row = p.result as? ViewGroup ?: return

                                        val item = try { adapter.getItem(pos) } catch (t: Throwable) { null } ?: return
                                        val msgId = extractMessageId(item)
                                        val msgText = extractMessageText(item)

                                        if (!msgId.isNullOrEmpty()) {
                                            XposedHelpers.setAdditionalInstanceField(row, FIELD_EDIT_MSG_ID, msgId)
                                            if (!msgText.isNullOrEmpty()) {
                                                messageTextCache[msgId] = msgText
                                                XposedHelpers.setAdditionalInstanceField(row, FIELD_EDIT_MSG_TEXT, msgText)
                                            }

                                            val editLabel = findChildByResourceName(row, "edit_label")
                                                ?: findChildByResourceName(row, "edited_label")
                                            if (editLabel != null) {
                                                bindEditLabel(editLabel, row, msgId, msgText)
                                            }
                                        }
                                    }
                                }
                            )
                            XposedBridge.log("$TAG getView adapter hook installed for AntiEdit.")
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Error hooking getView for AntiEdit: ${t.message}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG ListView setAdapter hook error: ${t.message}")
        }
    }

    // ─── 4. View.onAttachedToWindow Fallback ────────────────────────────────────

    private fun hookViewAttachmentFallback() {
        try {
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "onAttachedToWindow",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!isEnabled(PREF_KEY, false)) return

                        val view = param.thisObject as? View ?: return
                        val resName = getResourceEntryName(view)

                        if (resName == "edit_label" || resName == "edited_label") {
                            bindEditLabel(view, null, null, null)
                        } else if (view is ViewGroup) {
                            val editLabel = findChildByResourceName(view, "edit_label")
                                ?: findChildByResourceName(view, "edited_label")
                            if (editLabel != null) {
                                bindEditLabel(editLabel, view, null, null)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG View attachment hook error: ${t.message}")
        }
    }

    private fun bindEditLabel(labelView: View, parentRow: View?, boundMsgId: String?, boundText: String?) {
        if (labelView is TextView) {
            val originalText = labelView.text.toString()
            if (!originalText.contains("📝")) {
                val spannable = SpannableString("$originalText 📝")
                spannable.setSpan(UnderlineSpan(), 0, spannable.length, 0)
                labelView.text = spannable
            }
        }

        if (boundMsgId != null) {
            XposedHelpers.setAdditionalInstanceField(labelView, FIELD_EDIT_MSG_ID, boundMsgId)
        }
        if (boundText != null) {
            XposedHelpers.setAdditionalInstanceField(labelView, FIELD_EDIT_MSG_TEXT, boundText)
        }

        labelView.isClickable = true
        labelView.setOnClickListener { clickedView ->
            val row = parentRow ?: findParentMessageRow(clickedView)
            val msgId = (XposedHelpers.getAdditionalInstanceField(clickedView, FIELD_EDIT_MSG_ID) as? String)
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, FIELD_EDIT_MSG_ID) as? String })
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, "waex_key_id") as? String })

            val visibleBubbleText = (XposedHelpers.getAdditionalInstanceField(clickedView, FIELD_EDIT_MSG_TEXT) as? String)
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, FIELD_EDIT_MSG_TEXT) as? String })
                ?: extractTextFromMessageBubble(row)

            showEditHistoryBottomSheet(clickedView.context, msgId, visibleBubbleText)
        }
    }

    private fun showEditHistoryBottomSheet(context: Context, messageId: String?, currentText: String?) {
        val currentActivity = ActivityTracker.getCurrentActivity() ?: context
        val history = if (!messageId.isNullOrEmpty()) editStore.getHistoryByMessageId(messageId) else emptyList()

        val bottomSheet = WaexBottomSheet(currentActivity).asBottomSheet()
        bottomSheet.setTitle("Edited Message History")

        val density = currentActivity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val contentLayout = LinearLayout(currentActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }

        if (history.isNotEmpty()) {
            for ((idx, item) in history.withIndex()) {
                val formattedTime = if (item.timestamp > 0) timeFormatter.format(Date(item.timestamp)) else "Original"
                val versionTitle = if (idx == 0) "Original (Pre-Edit) • $formattedTime" else "Revision $idx • $formattedTime"
                val displayText = if (item.originalText.isNotEmpty()) item.originalText else item.editedText

                val itemBox = createHistoryCard(currentActivity, versionTitle, displayText, 0xFF00A884.toInt(), dp)
                contentLayout.addView(itemBox)
            }

            // Also show the latest edited text if available
            val latestItem = history.last()
            if (latestItem.editedText.isNotEmpty()) {
                val latestTitle = "Current Edited Message • ${timeFormatter.format(Date(latestItem.timestamp))}"
                val currentBox = createHistoryCard(currentActivity, latestTitle, latestItem.editedText, 0xFF21C063.toInt(), dp)
                contentLayout.addView(currentBox)
            }
        } else if (!currentText.isNullOrEmpty()) {
            // Display captured current text with copy button
            val currentBox = createHistoryCard(currentActivity, "Current Edited Message", currentText, 0xFF00A884.toInt(), dp)
            contentLayout.addView(currentBox)

            val notice = TextView(currentActivity).apply {
                text = "Note: This message was edited by sender. Future edits occurring in this chat will record all previous revisions here."
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(8))
                setTextColor(0xFF8696A0.toInt())
            }
            contentLayout.addView(notice)
        } else {
            val emptyNotice = TextView(currentActivity).apply {
                text = "Message was edited by sender."
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            }
            contentLayout.addView(emptyNotice)
        }

        bottomSheet.setView(contentLayout)
        bottomSheet.setPositiveButton("Close", null)
        bottomSheet.show()
    }

    private fun createHistoryCard(
        activity: Context,
        header: String,
        body: String,
        headerColor: Int,
        dp: (Int) -> Int
    ): View {
        val itemBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x18888888)
                cornerRadius = dp(12).toFloat()
            }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(10)
            }
            layoutParams = lp
        }

        val headerView = TextView(activity).apply {
            text = header
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(headerColor)
        }
        itemBox.addView(headerView)

        val bodyView = TextView(activity).apply {
            text = body
            textSize = 15f
            setPadding(0, dp(6), 0, dp(8))
        }
        itemBox.addView(bodyView)

        val copyHint = TextView(activity).apply {
            text = "Tap card to copy"
            textSize = 11.5f
            setTextColor(0xFF8696A0.toInt())
        }
        itemBox.addView(copyHint)

        itemBox.setOnClickListener {
            val clip = ClipData.newPlainText("Edited Message", body)
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(clip)
            Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        return itemBox
    }

    // ─── Extraction Utilities ──────────────────────────────────────────────────

    private fun extractMessageId(messageObj: Any): String? {
        return try {
            val keyField = messageObj.javaClass.fields.firstOrNull { it.name == "A1J" || it.name == "key" }
                ?: messageObj.javaClass.declaredFields.firstOrNull { it.type.name.contains("Key") }
            keyField?.isAccessible = true
            val keyObj = keyField?.get(messageObj) ?: return null
            val idField = keyObj.javaClass.fields.firstOrNull { it.name == "A01" || it.name == "id" }
                ?: keyObj.javaClass.declaredFields.firstOrNull { it.type == String::class.java }
            idField?.isAccessible = true
            idField?.get(keyObj) as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun extractMessageJid(messageObj: Any): String? {
        return try {
            val keyField = messageObj.javaClass.fields.firstOrNull { it.name == "A1J" || it.name == "key" }
                ?: messageObj.javaClass.declaredFields.firstOrNull { it.type.name.contains("Key") }
            keyField?.isAccessible = true
            val keyObj = keyField?.get(messageObj) ?: return null
            val jidField = keyObj.javaClass.fields.firstOrNull { it.name == "A00" || it.name == "jid" }
                ?: keyObj.javaClass.declaredFields.firstOrNull { it.name.contains("jid", ignoreCase = true) }
            jidField?.isAccessible = true
            jidField?.get(keyObj)?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    private fun extractMessageText(messageObj: Any): String? {
        return try {
            for (m in messageObj.javaClass.methods) {
                if (m.returnType == String::class.java && m.parameterTypes.isEmpty() && m.name != "toString" && m.name != "hashCode") {
                    val str = m.invoke(messageObj) as? String
                    if (!str.isNullOrEmpty() && !str.contains("@") && str.length < 5000) return str
                }
            }
            val textDataField = messageObj.javaClass.fields.firstOrNull { it.name == "textData" || it.name == "A02" }
            textDataField?.isAccessible = true
            textDataField?.get(messageObj) as? String
        } catch (t: Throwable) {
            null
        }
    }

    private fun extractTextFromMessageBubble(row: View?): String? {
        if (row !is ViewGroup) return null
        val messageTextView = findChildByResourceName(row, "message_text")
            ?: findChildByResourceName(row, "conversation_text")
            ?: findChildByResourceName(row, "caption")
        return (messageTextView as? TextView)?.text?.toString()
    }

    private fun findChildByResourceName(viewGroup: ViewGroup, targetResName: String): View? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (getResourceEntryName(child) == targetResName) return child
            if (child is ViewGroup) {
                val found = findChildByResourceName(child, targetResName)
                if (found != null) return found
            }
        }
        return null
    }

    private fun findParentMessageRow(view: View): View? {
        var current: View? = view
        while (current != null) {
            val resName = getResourceEntryName(current)
            if (resName == "conversation_row" || resName == "message_row" || resName == "row_message" || current.parent is ListView) {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun getResourceEntryName(view: View): String? {
        val id = view.id
        if (id == View.NO_ID) return null
        return try {
            view.resources.getResourceEntryName(id)
        } catch (t: Throwable) {
            null
        }
    }
}
