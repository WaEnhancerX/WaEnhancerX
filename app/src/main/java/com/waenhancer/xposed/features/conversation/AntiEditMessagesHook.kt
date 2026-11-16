package com.waenhancer.xposed.features.conversation


import com.waenhancer.app.R
import com.waenhancer.utils.UiText
import android.R as AndroidR

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.UnderlineSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.HeaderViewListAdapter
import android.widget.ListAdapter
import android.widget.ListView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.waenhancer.xposed.core.BaseFeature
import com.waenhancer.xposed.utils.HostResourceIds
import com.waenhancer.xposed.core.components.WaexBottomSheet
import com.waenhancer.xposed.core.db.EditMessageStore
import com.waenhancer.xposed.core.db.MessageStore
import com.waenhancer.xposed.core.devkit.DexSearchEngine
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.enums.StringMatchType
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import android.content.ContentValues
import android.graphics.drawable.GradientDrawable

/**
 * Anti-Edit Messages Feature for WhatsApp.
 *
 * - Renders all distinct versions in descending chronological order (Latest at top).
 * - Selectable text on all cards.
 * - Top-Right WDSSwitch "Show changes" to dynamically highlight text diffs (Green for added, Red for removed).
 */
class AntiEditMessagesHook(
    context: Context,
    classLoader: ClassLoader,
    prefs: SharedPreferences
) : BaseFeature(context, classLoader, prefs) {

    override val name: String
        get() = "AntiEditMessages"

    companion object {
        private val sSnapshotted = ConcurrentHashMap<String, String>()

        // Per-class reflection caches. These run for every row bind while scrolling;
        // getDeclaredFields() allocates a Field per member each call.
        private val sKeyFieldCache = ConcurrentHashMap<Class<*>, Field>()
        private val sKeylessMisses = ConcurrentHashMap<Class<*>, Int>()
        private val sLooksLikeKey = ConcurrentHashMap<Class<*>, Boolean>()
        private val sKeyStringFields = ConcurrentHashMap<Class<*>, List<Field>>()
        private val sRowIdFields = ConcurrentHashMap<Class<*>, List<Field>>()
        private val sTextFields = ConcurrentHashMap<Class<*>, List<Field>>()
        private val sTextGetters = ConcurrentHashMap<Class<*>, List<Method>>()
        private const val KEYLESS_MISS_LIMIT = 3
        private val MESSAGE_ID_REGEX: Pattern = Pattern.compile("(?:messageId|messageID|keyId)=([A-Za-z0-9_-]+)")
        private const val TAG = "[WAEX:AntiEdit]"
        private const val PREF_KEY = "anti_edit_messages"
        private const val FIELD_ROW_ID = "waex_row_id"
        private const val FIELD_MSG_ID = "waex_msg_id"
        private const val FIELD_MSG_TEXT = "waex_msg_text"
        private const val FIELD_BINDING_TOKEN = "waex_edit_binding_token"
        private const val TAG_EDIT_DATA = 0x57414544 // 'WAED'
        private const val TAG_EDIT_ORIGINAL_TEXT = 0x57414545 // 'WAEE'
        private val pendingEditText = ThreadLocal<String?>()
        private var sEditLabelResId = 0
        private var sEditedLabelResId = 0
        private val hookedAdapterClasses = ConcurrentHashMap.newKeySet<Class<*>>()
        private val timeFormatter = object : ThreadLocal<DateFormat>() {
            override fun initialValue(): DateFormat = DateFormat.getTimeInstance(DateFormat.SHORT)
        }
        private val KEY_ID_REGEX = Pattern.compile("id=([A-Za-z0-9]+)")
    }

    private val editStore: EditMessageStore by lazy { EditMessageStore.getInstance(context) }
    private var originalKeyMethod: Method? = null

    private fun isFeatureEnabled(): Boolean {
        return isEnabled(PREF_KEY, false) || isEnabled("antieditmessages", false) || isEnabled("anti_edit", false)
    }

    override fun hook() {
        originalKeyMethod = resolveOriginalKeyMethod()
        hookMessageEditMethod()
        hookDatabaseEditInsert()
        hookIncomingMessageSnapshot()
        hookListViewAdapter()
    }

    // ─── 0. Baseline Snapshot On Receipt (UI independent) ───────────────────────

    private var fMessageClass: Class<*>? = null

    /**
     * Captures each incoming message's original text as it is processed, the same point at
     * which the device source is recorded. Relying on the conversation list binding only
     * saw messages that were on screen, so an edit to anything else lost its original.
     */
    private fun hookIncomingMessageSnapshot() {
        try {
            fMessageClass = DexSearchEngine.getInstance().findClassWithCache(
                context,
                classLoader,
                "waex_fmessage_class",
                { bridge, loader ->
                    bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "FMessage/getSenderUserJid/key.id",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()?.getMethodInstance(loader)?.declaringClass
                }
            )
            val target = DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "waex_shared_msg_proc_plaintext",
                { bridge, loader ->
                    bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "SharedMessageProcessor/handlePlaintext",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()?.getMethodInstance(loader)
                }
            ) ?: return
            XposedBridge.hookMethod(target, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!isFeatureEnabled()) return
                    try {
                        val message = findFMessage(param.args) ?: return
                        val text = extractMessageText(message)
                        if (text.isNullOrBlank() || text.startsWith("/")) return
                        val keyId = extractStableKeyId(message)
                        if (keyId.isNullOrEmpty()) return
                        if (sSnapshotted.size > 4000) sSnapshotted.clear()
                        if (sSnapshotted.put(keyId, text) != text) {
                            editStore.recordTransitionAsync(0L, keyId, null, text, System.currentTimeMillis())
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG Snapshot on receipt error: ${t.javaClass.simpleName}")
                    }
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook receipt snapshot: ${t.javaClass.simpleName}")
        }
    }

    private fun findFMessage(candidates: Array<Any?>): Any? {
        val type = fMessageClass ?: return null
        for (candidate in candidates) {
            if (candidate == null) continue
            if (type.isInstance(candidate)) return candidate
            var curr: Class<*>? = candidate.javaClass
            while (curr != null && curr != Any::class.java) {
                for (field in curr.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || !type.isAssignableFrom(field.type)) continue
                    try {
                        field.isAccessible = true
                        field.get(candidate)?.let { return it }
                    } catch (_: Throwable) {
                    }
                }
                curr = curr.superclass
            }
        }
        return null
    }

    // ─── 1. Bytecode Edit Interception ──────────────────────────────────────────

    private fun hookMessageEditMethod() {
        try {
            val onMessageEdit = resolveMessageEditMethod()
            if (onMessageEdit != null) {
                XposedBridge.hookMethod(
                    onMessageEdit,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            if (!isFeatureEnabled()) return

                            try {
                                val fMessage = param.args.getOrNull(0) ?: return
                                val keyId = extractStableKeyId(fMessage)
                                val newMessage = extractMessageText(fMessage)

                                // The argument is the NEW edit message, so its key/row id are the
                                // edit's own, not the original's. The message_edit_info insert that
                                // follows on this thread carries the original key; record it there.
                                pendingEditText.set(newMessage?.takeIf { it.isNotBlank() })
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG Error in onMessageEdit: ${t.javaClass.simpleName}")
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook onMessageEdit: ${t.javaClass.simpleName}")
        }
    }

    private fun resolveMessageEditMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "waex_anti_edit_method_v14",
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

    private fun resolveOriginalKeyMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "waex_original_key_for_edited_message_v1",
                { bridge, loader ->
                    bridge.findMethod(
                        FindMethod.create().matcher(
                            MethodMatcher.create().addUsingString(
                                "FMessageUtil/getOriginalMessageKeyIfEdited",
                                StringMatchType.Contains
                            )
                        )
                    ).firstOrNull()?.getMethodInstance(loader)
                }
            )
        } catch (_: Throwable) {
            null
        }
    }

    // ─── 2. SQLite Database Interception Fallback ──────────────────────────────

    private fun hookDatabaseEditInsert() {
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
                        if (!isFeatureEnabled()) return
                        val table = param.args.getOrNull(0) as? String ?: return
                        if (table == "message_edit_info" || table == "message_add_on" || table == "message_add_on_edit_info") {
                            val values = param.args.getOrNull(2) as? ContentValues ?: return
                            val keyId = values.getAsString("original_key_id") ?: values.getAsString("key_id")
                            var rowId = values.getAsLong("message_row_id") ?: 0L
                            val isEditInfo = table == "message_edit_info" || table == "message_add_on_edit_info"
                            val editedText = values.getAsString("edited_text")
                                ?: values.getAsString("new_text")
                                ?: values.getAsString("text_data")
                                ?: if (isEditInfo) pendingEditText.get() else null
                            if (isEditInfo) pendingEditText.remove()
                            val ts = values.getAsLong("edit_timestamp")
                                ?: values.getAsLong("sender_timestamp")
                                ?: System.currentTimeMillis()
                            rowId = resolveCanonicalRowId(rowId, keyId)

                            if (!editedText.isNullOrBlank() && (!keyId.isNullOrEmpty() || rowId > 0)) {
                                editStore.recordTransitionAsync(rowId, keyId, null, editedText, ts)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Database insert hook error: ${t.javaClass.simpleName}")
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

                        param.thisObject as? ListView ?: return

                        var adapter = param.args.getOrNull(0) as? ListAdapter ?: return
                        if (adapter is HeaderViewListAdapter) {
                            adapter = adapter.wrappedAdapter
                        }

                        try {
                            if (!hookedAdapterClasses.add(adapter.javaClass)) return
                            // getView() satisfies android.widget.Adapter and cannot be renamed by
                            // obfuscation; a direct JVM lookup resolves it even when Xposed's fuzzy
                            // findMethodBestMatch fails to disambiguate bridge/synthetic overloads.
                            val getViewMethod = try {
                                adapter.javaClass.getMethod(
                                    "getView",
                                    Int::class.javaPrimitiveType,
                                    View::class.java,
                                    ViewGroup::class.java
                                )
                            } catch (t: Throwable) {
                                XposedHelpers.findMethodBestMatch(
                                    adapter.javaClass,
                                    "getView",
                                    Int::class.javaPrimitiveType,
                                    View::class.java,
                                    ViewGroup::class.java
                                )
                            } ?: return
                            XposedBridge.hookMethod(
                                getViewMethod,
                                object : XC_MethodHook() {
                                    override fun afterHookedMethod(p: MethodHookParam) {
                                        val row = p.result as? ViewGroup ?: return

                                        // Cached (including "not in this build" = 0) by HostResourceIds.
                                        sEditLabelResId = HostResourceIds.id(row.context, "edit_label")
                                        sEditedLabelResId = HostResourceIds.id(row.context, "edited_label")

                                        val editLabel = (if (sEditLabelResId != 0) row.findViewById<View>(sEditLabelResId) else null)
                                            ?: (if (sEditedLabelResId != 0) row.findViewById<View>(sEditedLabelResId) else null)

                                        if (editLabel != null) {
                                            // The framework may reuse the same view for a different
                                            // chat or an unsupported item. Drop our previous identity
                                            // even when the feature has since been disabled.
                                            invalidateEditLabelBinding(editLabel)
                                        }
                                        if (!isFeatureEnabled()) return

                                        val pos = p.args.getOrNull(0) as? Int ?: return
                                        val boundAdapter = p.thisObject as? ListAdapter ?: return
                                        val item = try { boundAdapter.getItem(pos) } catch (t: Throwable) { null } ?: return
                                        var keyId = extractStableKeyId(item)
                                        // Edited rows expose the edit's own key; history lives under the original's.
                                        if (editLabel != null && !keyId.isNullOrEmpty()) {
                                            keyId = editStore.resolveOriginalKey(keyId)
                                        }
                                        val rowId = if (keyId.isNullOrEmpty()) extractRowId(item) else 0L
                                        val msgText = extractMessageText(item)

                                        // Snapshot every bound message's current text, edited or not.
                                        // By the time any edit-bookkeeping hook fires, WhatsApp has
                                        // already overwritten its own row with the new text, so that
                                        // is too late to recover the original from. Capturing it here,
                                        // while the message is still unedited on screen, is what lets
                                        // the later edit show up as version 2 instead of replacing the
                                        // only version on record.
                                        if (!msgText.isNullOrBlank() && !msgText.startsWith("/") && !extractTextFromMessageBubble(row).isNullOrBlank()
                                            && (rowId > 0 || !keyId.isNullOrEmpty())) {
                                            // Rows rebind on every scroll; only queue a DB transaction
                                            // when this message's text has not been snapshotted yet.
                                            val snapshotKey = if (!keyId.isNullOrEmpty()) keyId else "row:$rowId"
                                            if (sSnapshotted.size > 4000) sSnapshotted.clear()
                                            if (sSnapshotted.put(snapshotKey, msgText) != msgText) {
                                                editStore.recordTransitionAsync(rowId, keyId, null, msgText, System.currentTimeMillis())
                                            }
                                        }

                                        if (editLabel != null) {
                                            bindEditLabel(editLabel, row, rowId, keyId, msgText)
                                        }
                                    }
                                }
                            )
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Error hooking getView: ${t.javaClass.simpleName}")
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG ListView setAdapter hook error: ${t.javaClass.simpleName}")
        }
    }

    private data class EditData(val rowId: Long, val keyId: String?, val msgText: String?)

    private fun invalidateEditLabelBinding(labelView: View) {
        if (labelView.getTag(TAG_EDIT_DATA) !is EditData) return

        // WhatsApp may already have replaced this text while recycling the row:
        // only undo WAEX's marker when the displayed text still matches ours.
        val originalText = labelView.getTag(TAG_EDIT_ORIGINAL_TEXT) as? CharSequence
        if (originalText != null && labelView is TextView &&
            labelView.text.toString() == "$originalText 📝") {
            labelView.text = originalText
        }
        labelView.setTag(TAG_EDIT_ORIGINAL_TEXT, null)
        labelView.setTag(TAG_EDIT_DATA, null)
        XposedHelpers.removeAdditionalInstanceField(labelView, FIELD_ROW_ID)
        XposedHelpers.removeAdditionalInstanceField(labelView, FIELD_MSG_ID)
        XposedHelpers.removeAdditionalInstanceField(labelView, FIELD_MSG_TEXT)
        // Leave host-supplied click listeners untouched. Any stale WAEX listener
        // now observes a missing tag and must not open a different history.
    }

    private fun bindEditLabel(labelView: View, parentRow: View?, boundRowId: Long, boundKeyId: String?, boundText: String?) {
        if (boundRowId <= 0 && boundKeyId.isNullOrEmpty()) return
        if (labelView is TextView) {
            val originalText = labelView.text.toString()
            if (!originalText.contains("📝")) {
                labelView.setTag(TAG_EDIT_ORIGINAL_TEXT, labelView.text)
                val spannable = SpannableString("$originalText 📝")
                spannable.setSpan(UnderlineSpan(), 0, spannable.length, 0)
                labelView.text = spannable
            }
        }

        val editData = EditData(boundRowId, boundKeyId, boundText)
        labelView.setTag(TAG_EDIT_DATA, editData)
        if (boundRowId > 0) {
            XposedHelpers.setAdditionalInstanceField(labelView, FIELD_ROW_ID, boundRowId)
        }
        if (boundKeyId != null) {
            XposedHelpers.setAdditionalInstanceField(labelView, FIELD_MSG_ID, boundKeyId)
        }
        if (boundText != null) {
            XposedHelpers.setAdditionalInstanceField(labelView, FIELD_MSG_TEXT, boundText)
        }

        labelView.isClickable = true
        labelView.setOnClickListener { clickedView ->
            if (!isFeatureEnabled()) return@setOnClickListener
            val data = clickedView.getTag(TAG_EDIT_DATA) as? EditData
                ?: return@setOnClickListener
            val row = parentRow ?: findParentMessageRow(clickedView)
            val rowId = data?.rowId
                ?: (XposedHelpers.getAdditionalInstanceField(clickedView, FIELD_ROW_ID) as? Long)
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, FIELD_ROW_ID) as? Long })
                ?: 0L

            val keyId = data?.keyId
                ?: (XposedHelpers.getAdditionalInstanceField(clickedView, FIELD_MSG_ID) as? String)
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, FIELD_MSG_ID) as? String })
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, "waex_key_id") as? String })

            val visibleBubbleText = data?.msgText
                ?: (XposedHelpers.getAdditionalInstanceField(clickedView, FIELD_MSG_TEXT) as? String)
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, FIELD_MSG_TEXT) as? String })
                ?: extractTextFromMessageBubble(row)

            showEditHistoryBottomSheet(clickedView.context, rowId, keyId, visibleBubbleText)
        }
    }

    // ─── 5. WDS Bottom Sheet Display (Descending Order with Diff Highlighting) ──

    private fun showEditHistoryBottomSheet(context: Context, rowId: Long, keyId: String?, currentText: String?) {
        val currentActivity = ActivityTracker.getCurrentActivity() ?: context
        val allVersions = loadAllChronologicalVersions(context, rowId, keyId, currentText)

        val bottomSheet = WaexBottomSheet(currentActivity).asBottomSheet()
        bottomSheet.setTitle(UiText.get(R.string.waex_ui_edited_message_history, "Edited Message History"))

        val density = currentActivity.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // Map each version number to its exact text for preceding diff lookups
        val versionTextMap = mutableMapOf<Int, String>()
        for (item in allVersions) {
            versionTextMap[item.versionNumber] = item.textData
        }

        val cardTextViews = mutableListOf<Pair<TextView, EditMessageStore.MessageItem>>()

        // Top-Right "Show changes" Switch
        val switchRow = LinearLayout(currentActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val switchLabel = TextView(currentActivity).apply {
            text = UiText.get(R.string.waex_ui_show_changes, "Show changes")
            textSize = 13f
            setTextColor(0xFF8696A0.toInt())
            setPadding(0, 0, dp(6), 0)
        }
        switchRow.addView(switchLabel)

        val wdsSwitch = createWdsSwitch(currentActivity).apply {
            isChecked = false
            setOnCheckedChangeListener { _, isChecked ->
                for ((textView, item) in cardTextViews) {
                    if (isChecked) {
                        val prevText = versionTextMap[item.versionNumber - 1]
                        if (!prevText.isNullOrEmpty() && item.versionNumber > 1) {
                            textView.text = buildDiffSpannable(prevText, item.textData)
                        } else {
                            textView.text = item.textData
                        }
                    } else {
                        textView.text = item.textData
                    }
                }
            }
        }
        switchRow.addView(wdsSwitch)
        bottomSheet.setTopRightView(switchRow)

        val contentLayout = LinearLayout(currentActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }

        if (allVersions.isNotEmpty()) {
            // Render in descending order: latest version on top
            val descendingList = allVersions.reversed()
            val totalVersions = allVersions.size

            for (item in descendingList) {
                val isFirst = (item.versionNumber == 1)
                val isLatest = (item.versionNumber == totalVersions && totalVersions > 1)
                val timeStr = if (item.timestamp > 0) timeFormatter.get().format(Date(item.timestamp)) else ""

                val title = when {
                    isLatest -> UiText.get(R.string.waex_ui_version_1_s_latest_2_s, "Version %1\$s (Latest) • %2\$s", item.versionNumber, timeStr)
                    isFirst && totalVersions > 1 -> UiText.get(R.string.waex_ui_version_1_original_1_s, "Version 1 (Original) • %1\$s", timeStr)
                    totalVersions == 1 -> UiText.get(R.string.waex_ui_version_1_latest_1_s, "Version 1 (Latest) • %1\$s", timeStr)
                    else -> UiText.get(R.string.waex_ui_version_with_metadata, "Version %1\$s • %2\$s", item.versionNumber, timeStr)
                }
                val color = if (isLatest) 0xFF21C063.toInt() else 0xFF00A884.toInt()

                val cardView = createHistoryCard(currentActivity, title, item.textData, color, dp)
                val bodyTextView = cardView.findViewById<TextView>(AndroidR.id.text1)
                if (bodyTextView != null) {
                    cardTextViews.add(bodyTextView to item)
                }
                contentLayout.addView(cardView)
            }
        } else {
            val emptyNotice = TextView(currentActivity).apply {
                text = UiText.get(R.string.waex_ui_message_was_edited_by_sender, "Message was edited by sender.")
                textSize = 14f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, dp(16))
            }
            contentLayout.addView(emptyNotice)
        }

        bottomSheet.setView(contentLayout)
        bottomSheet.setPositiveButton(UiText.get(R.string.waex_ui_close, "Close"), null)
        bottomSheet.show()
    }

    private fun loadAllChronologicalVersions(
        ctx: Context,
        rowId: Long,
        keyId: String?,
        currentText: String?
    ): List<EditMessageStore.MessageItem> {
        val result = mutableListOf<EditMessageStore.MessageItem>()
        val seenTexts = mutableSetOf<String>()

        // 1. Pull from EditMessageStore
        val stored = editStore.getMessages(rowId, keyId)
        for (item in stored) {
            if (item.textData.isNotBlank() && seenTexts.add(item.textData.trim())) {
                result.add(item)
            }
        }

        // 2. If current visible bubble text is not in the list, append it
        if (!currentText.isNullOrBlank() && seenTexts.add(currentText.trim())) {
            result.add(
                EditMessageStore.MessageItem(
                    rowId = rowId,
                    keyId = keyId ?: "",
                    textData = currentText.trim(),
                    timestamp = System.currentTimeMillis(),
                    versionNumber = result.size + 1
                )
            )
        }

        return result.mapIndexed { index, item ->
            item.copy(versionNumber = index + 1)
        }
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
            background = GradientDrawable().apply {
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
            id = AndroidR.id.text1
            text = body
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, dp(2))
        }
        itemBox.addView(bodyView)

        return itemBox
    }

    // ─── Text Diff Highlighting Logic ──────────────────────────────────────────

    private fun buildDiffSpannable(oldStr: String, newStr: String): CharSequence {
        val chunks = EditHistoryDiff.compute(oldStr, newStr)
        val ssb = SpannableStringBuilder()
        for (c in chunks) {
            val start = ssb.length
            ssb.append(c.text)
            val end = ssb.length
            when (c.type) {
                EditHistoryDiff.Type.UNCHANGED -> {}
                EditHistoryDiff.Type.ADDED -> {
                    ssb.setSpan(ForegroundColorSpan(0xFF21C063.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(0x3321C063.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                EditHistoryDiff.Type.REMOVED -> {
                    ssb.setSpan(ForegroundColorSpan(0xFFEF4444.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(0x33EF4444.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return ssb
    }

    private fun createWdsSwitch(ctx: Context): CompoundButton {
        return try {
            val clazz = ctx.classLoader.loadClass("com.whatsapp.ui.wds.components.toggle.WDSSwitch")
            clazz.getConstructor(Context::class.java, AttributeSet::class.java).newInstance(ctx, null) as CompoundButton
        } catch (t: Throwable) {
            try {
                val materialSwitchClass = ctx.classLoader.loadClass("com.google.android.material.materialswitch.MaterialSwitch")
                materialSwitchClass.getConstructor(Context::class.java).newInstance(ctx) as CompoundButton
            } catch (t2: Throwable) {
                Switch(ctx)
            }
        }
    }

    // ─── Multi-Level Key & Row ID Extraction ───────────────────────────────────

    private fun extractStableKeyId(messageObj: Any): String? {
        val resolver = originalKeyMethod
        if (resolver != null) {
            try {
                resolver.isAccessible = true
                val resolverParameters = resolver.parameterTypes
                val originalKey = when {
                    Modifier.isStatic(resolver.modifiers) && resolverParameters.size == 1 ->
                        resolver.invoke(null, messageObj)
                    !Modifier.isStatic(resolver.modifiers) && resolverParameters.size == 0 &&
                        resolver.declaringClass.isInstance(messageObj) -> resolver.invoke(messageObj)
                    else -> null
                }
                if (originalKey != null) {
                    extractMessageIdFromKey(originalKey)?.let { return it }
                }
            } catch (_: Throwable) {
            }
        }
        return extractKeyId(messageObj)
    }

    private fun extractKeyId(messageObj: Any): String? {
        val type = messageObj.javaClass
        try {
            // Fast path: the key field of this message class was found earlier.
            sKeyFieldCache[type]?.let { cached ->
                try {
                    val keyObj = cached.get(messageObj)
                    if (keyObj != null) {
                        val id = extractMessageIdFromKey(keyObj)
                        if (!id.isNullOrEmpty()) return id
                    }
                } catch (_: Throwable) {
                }
            }
            // Classes that repeatedly yield no key (separators, system rows) skip discovery.
            if ((sKeylessMisses[type] ?: 0) >= KEYLESS_MISS_LIMIT) return null

            var curr: Class<*>? = type
            while (curr != null && curr != Any::class.java) {
                for (field in curr.declaredFields) {
                    if (Modifier.isStatic(field.modifiers) || field.type.isPrimitive) continue
                    field.isAccessible = true
                    val valObj = field.get(messageObj) ?: continue
                    val valClass = valObj.javaClass
                    if (valClass == String::class.java) continue

                    // JID/contact objects also contain an "id" in toString(). Only inspect
                    // objects whose type represents a message key, otherwise every message in
                    // one chat can accidentally receive the same contact ID.
                    val typeLooksLikeKey = valClass.name.contains("Key", ignoreCase = true)
                        || valClass.simpleName.contains("Key", ignoreCase = true)
                        || looksLikeMessageKey(valClass)
                    val representation = if (typeLooksLikeKey) null else valObj.toString()
                    if (typeLooksLikeKey || representation!!.startsWith("Key(")) {
                        val directId = extractMessageIdFromKey(valObj)
                        if (!directId.isNullOrEmpty()) {
                            sKeyFieldCache[type] = field
                            return directId
                        }
                        val matcher = KEY_ID_REGEX.matcher(representation ?: valObj.toString())
                        if (matcher.find()) {
                            val id = matcher.group(1)
                            if (!id.isNullOrEmpty()) return id
                        }
                    }
                }
                curr = curr.superclass
            }

            if (looksLikeMessageKey(type)) {
                extractMessageIdFromKey(messageObj)?.let { return it }
            }

            val matcher = MESSAGE_ID_REGEX.matcher(messageObj.toString())
            if (matcher.find()) {
                val id = matcher.group(1)
                if (!id.isNullOrEmpty()) return id
            }
            sKeylessMisses.merge(type, 1, Int::plus)
        } catch (ignored: Throwable) {}
        return null
    }

    private fun looksLikeMessageKey(type: Class<*>): Boolean {
        return sLooksLikeKey.getOrPut(type) {
            var hasString = false
            var hasBoolean = false
            var current: Class<*>? = type
            while (current != null && current != Any::class.java) {
                for (field in current.declaredFields) {
                    if (Modifier.isStatic(field.modifiers)) continue
                    if (field.type == String::class.java) hasString = true
                    if (field.type == Boolean::class.javaPrimitiveType || field.type == Boolean::class.java) {
                        hasBoolean = true
                    }
                }
                current = current.superclass
            }
            hasString && hasBoolean
        }
    }

    private fun extractMessageIdFromKey(keyObj: Any): String? {
        try {
            val fields = sKeyStringFields.getOrPut(keyObj.javaClass) {
                val found = ArrayList<Field>()
                var curr: Class<*>? = keyObj.javaClass
                while (curr != null && curr != Any::class.java) {
                    for (field in curr.declaredFields) {
                        if (field.type != String::class.java) continue
                        field.isAccessible = true
                        found.add(field)
                    }
                    curr = curr.superclass
                }
                found
            }
            var bestCandidate: String? = null
            for (field in fields) {
                val str = field.get(keyObj) as? String ?: continue
                if (str.isEmpty() || str.contains("@")) continue
                if (str.length >= 12) return str
                if (str.length >= 6 && bestCandidate == null) bestCandidate = str
            }
            return bestCandidate
        } catch (ignored: Throwable) {}
        return null
    }

    private fun extractRowId(messageObj: Any): Long {
        try {
            val fields = sRowIdFields.getOrPut(messageObj.javaClass) {
                val found = ArrayList<Field>()
                var curr: Class<*>? = messageObj.javaClass
                while (curr != null && curr != Any::class.java) {
                    for (field in curr.declaredFields) {
                        if (field.name == "A0j" || field.name == "A0k" || field.name == "rowId" || field.name == "A00" || field.name == "A01" || field.name == "_id") {
                            field.isAccessible = true
                            found.add(field)
                        }
                    }
                    curr = curr.superclass
                }
                found
            }
            for (field in fields) {
                val v = field.get(messageObj)
                if (v is Long && v > 0) return v
                if (v is Int && v > 0) return v.toLong()
            }
        } catch (ignored: Throwable) {}
        return 0L
    }

    private fun resolveCanonicalRowId(reflectedRowId: Long, keyId: String?): Long {
        if (!keyId.isNullOrBlank()) {
            val databaseRowId = MessageStore.getInstance(context).getIdFromKey(keyId)
            if (databaseRowId > 0) return databaseRowId
        }
        return reflectedRowId
    }

    private fun extractMessageText(messageObj: Any): String? {
        try {
            val type = messageObj.javaClass
            val fields = sTextFields.getOrPut(type) {
                val found = ArrayList<Field>()
                var curr: Class<*>? = type
                while (curr != null && curr != Any::class.java) {
                    for (field in curr.declaredFields) {
                        if (field.name == "A0Q" || field.name == "textData" || field.name == "A02" || field.name == "message") {
                            field.isAccessible = true
                            found.add(field)
                        }
                    }
                    curr = curr.superclass
                }
                found
            }
            for (field in fields) {
                val str = field.get(messageObj) as? String
                if (!str.isNullOrEmpty()) return str
            }

            val getters = sTextGetters.getOrPut(type) {
                type.methods.filter { m ->
                    m.returnType == String::class.java && m.parameterTypes.isEmpty() &&
                        m.name != "toString" && m.name != "hashCode" && m.name != "name"
                }
            }
            for (m in getters) {
                val str = m.invoke(messageObj) as? String
                if (!str.isNullOrEmpty() && !str.contains("@") && str.length < 5000) return str
            }
        } catch (ignored: Throwable) {}
        return null
    }

    private fun extractTextFromMessageBubble(row: View?): String? {
        if (row !is ViewGroup) return null
        // Id lookups are cached; the previous recursive resource-name walk resolved a name
        // string for every view in the row on every bind.
        val ctx = row.context
        val messageTextView = sequenceOf("message_text", "conversation_text", "caption")
            .map { HostResourceIds.id(ctx, it) }
            .filter { it != 0 }
            .mapNotNull { row.findViewById<View>(it) }
            .firstOrNull()
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
