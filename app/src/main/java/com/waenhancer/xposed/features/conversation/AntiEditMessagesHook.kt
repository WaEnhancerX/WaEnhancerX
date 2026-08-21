package com.waenhancer.xposed.features.conversation

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
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

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
        private const val TAG = "[WAEX:AntiEdit]"
        private const val PREF_KEY = "anti_edit_messages"
        private const val FIELD_ROW_ID = "waex_row_id"
        private const val FIELD_MSG_ID = "waex_msg_id"
        private const val FIELD_MSG_TEXT = "waex_msg_text"
        private val messageTextCache = ConcurrentHashMap<String, String>()
        private val timeFormatter = DateFormat.getTimeInstance(DateFormat.SHORT)
        private val KEY_ID_REGEX = Pattern.compile("id=([A-Za-z0-9]+)")
    }

    private val editStore: EditMessageStore by lazy { EditMessageStore.getInstance(context) }

    override fun hook() {
        hookMessageEditMethod()
        hookDatabaseEditInsert()
        hookListViewAdapter()
        hookViewAttachmentFallback()
        XposedBridge.log("$TAG All AntiEdit hooks initialized.")
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
                            if (!isEnabled(PREF_KEY, false)) return

                            try {
                                val fMessage = param.args.getOrNull(0) ?: return
                                val keyId = extractKeyId(fMessage)
                                val rowId = extractRowId(fMessage)
                                val newMessage = extractMessageText(fMessage)

                                if (!newMessage.isNullOrBlank() && (!keyId.isNullOrEmpty() || rowId > 0)) {
                                    val ts = System.currentTimeMillis()
                                    editStore.recordEdit(rowId, keyId, newMessage, ts)
                                    if (!keyId.isNullOrEmpty()) {
                                        messageTextCache[keyId] = newMessage
                                    }
                                }
                            } catch (t: Throwable) {
                                XposedBridge.log("$TAG Error in onMessageEdit: ${t.message}")
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Failed to hook onMessageEdit: ${t.message}")
        }
    }

    private fun resolveMessageEditMethod(): Method? {
        return try {
            DexSearchEngine.getInstance().findMethodWithCache(
                context,
                classLoader,
                "wpp_anti_edit_method_v14",
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

    // ─── 2. SQLite Database Interception Fallback ──────────────────────────────

    private fun hookDatabaseEditInsert() {
        try {
            XposedHelpers.findAndHookMethod(
                SQLiteDatabase::class.java,
                "insertWithOnConflict",
                String::class.java,
                String::class.java,
                android.content.ContentValues::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!isEnabled(PREF_KEY, false)) return
                        val table = param.args.getOrNull(0) as? String ?: return
                        if (table == "message_edit_info" || table == "message_add_on" || table == "message_add_on_edit_info") {
                            val values = param.args.getOrNull(2) as? android.content.ContentValues ?: return
                            val keyId = values.getAsString("original_key_id") ?: values.getAsString("key_id")
                            val rowId = values.getAsLong("message_row_id") ?: 0L
                            val editedText = values.getAsString("edited_text")
                                ?: values.getAsString("new_text")
                                ?: values.getAsString("text_data")
                            val ts = values.getAsLong("edit_timestamp")
                                ?: values.getAsLong("sender_timestamp")
                                ?: System.currentTimeMillis()

                            if (!editedText.isNullOrBlank() && (!keyId.isNullOrEmpty() || rowId > 0)) {
                                editStore.recordEdit(rowId, keyId, editedText, ts)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG Database insert hook error: ${t.message}")
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
                                        val keyId = extractKeyId(item)
                                        val rowId = extractRowId(item)
                                        val msgText = extractMessageText(item)

                                        if (rowId > 0) {
                                            XposedHelpers.setAdditionalInstanceField(row, FIELD_ROW_ID, rowId)
                                        }
                                        if (!keyId.isNullOrEmpty()) {
                                            XposedHelpers.setAdditionalInstanceField(row, FIELD_MSG_ID, keyId)
                                            XposedHelpers.setAdditionalInstanceField(row, "waex_key_id", keyId)
                                            if (!msgText.isNullOrEmpty()) {
                                                messageTextCache[keyId] = msgText
                                                XposedHelpers.setAdditionalInstanceField(row, FIELD_MSG_TEXT, msgText)
                                            }
                                        }

                                        val editLabel = findChildByResourceName(row, "edit_label")
                                            ?: findChildByResourceName(row, "edited_label")
                                        if (editLabel != null) {
                                            bindEditLabel(editLabel, row, rowId, keyId, msgText)
                                        }
                                    }
                                }
                            )
                        } catch (t: Throwable) {
                            XposedBridge.log("$TAG Error hooking getView: ${t.message}")
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
                            bindEditLabel(view, null, 0L, null, null)
                        } else if (view is ViewGroup) {
                            val editLabel = findChildByResourceName(view, "edit_label")
                                ?: findChildByResourceName(view, "edited_label")
                            if (editLabel != null) {
                                bindEditLabel(editLabel, view, 0L, null, null)
                            }
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("$TAG View attachment hook error: ${t.message}")
        }
    }

    private fun bindEditLabel(labelView: View, parentRow: View?, boundRowId: Long, boundKeyId: String?, boundText: String?) {
        if (labelView is TextView) {
            val originalText = labelView.text.toString()
            if (!originalText.contains("📝")) {
                val spannable = SpannableString("$originalText 📝")
                spannable.setSpan(UnderlineSpan(), 0, spannable.length, 0)
                labelView.text = spannable
            }
        }

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
            val row = parentRow ?: findParentMessageRow(clickedView)
            val rowId = (XposedHelpers.getAdditionalInstanceField(clickedView, FIELD_ROW_ID) as? Long)
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, FIELD_ROW_ID) as? Long })
                ?: 0L

            val keyId = (XposedHelpers.getAdditionalInstanceField(clickedView, FIELD_MSG_ID) as? String)
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, FIELD_MSG_ID) as? String })
                ?: (row?.let { XposedHelpers.getAdditionalInstanceField(it, "waex_key_id") as? String })

            val visibleBubbleText = (XposedHelpers.getAdditionalInstanceField(clickedView, FIELD_MSG_TEXT) as? String)
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
        bottomSheet.setTitle("Edited Message History")

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
            text = "Show changes"
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
                val timeStr = if (item.timestamp > 0) timeFormatter.format(Date(item.timestamp)) else ""

                val title = when {
                    isLatest -> "Version ${item.versionNumber} (Latest) • $timeStr"
                    isFirst -> "Version 1 (Original) • $timeStr"
                    else -> "Version ${item.versionNumber} • $timeStr"
                }
                val color = if (isLatest) 0xFF21C063.toInt() else 0xFF00A884.toInt()

                val cardView = createHistoryCard(currentActivity, title, item.textData, color, dp)
                val bodyTextView = cardView.findViewById<TextView>(android.R.id.text1)
                if (bodyTextView != null) {
                    cardTextViews.add(bodyTextView to item)
                }
                contentLayout.addView(cardView)
            }
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
            id = android.R.id.text1
            text = body
            textSize = 15f
            setTextIsSelectable(true)
            setPadding(0, dp(6), 0, dp(2))
        }
        itemBox.addView(bodyView)

        return itemBox
    }

    // ─── Text Diff Highlighting Logic ──────────────────────────────────────────

    private enum class DiffType { UNCHANGED, ADDED, REMOVED }
    private data class DiffChunk(val type: DiffType, val text: String)

    private fun buildDiffSpannable(oldStr: String, newStr: String): CharSequence {
        val chunks = computeDiff(oldStr, newStr)
        val ssb = SpannableStringBuilder()
        for (c in chunks) {
            val start = ssb.length
            ssb.append(c.text)
            val end = ssb.length
            when (c.type) {
                DiffType.UNCHANGED -> {}
                DiffType.ADDED -> {
                    ssb.setSpan(ForegroundColorSpan(0xFF21C063.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(0x3321C063.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                DiffType.REMOVED -> {
                    ssb.setSpan(ForegroundColorSpan(0xFFEF4444.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(BackgroundColorSpan(0x33EF4444.toInt()), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    ssb.setSpan(StrikethroughSpan(), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return ssb
    }

    private fun computeDiff(oldStr: String, newStr: String): List<DiffChunk> {
        val oldChars = oldStr.toCharArray()
        val newChars = newStr.toCharArray()
        val n = oldChars.size
        val m = newChars.size

        if (n > 300 || m > 300) {
            return computeWordDiff(oldStr, newStr)
        }

        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0 until n) {
            for (j in 0 until m) {
                if (oldChars[i] == newChars[j]) {
                    dp[i + 1][j + 1] = dp[i][j] + 1
                } else {
                    dp[i + 1][j + 1] = maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        var i = n
        var j = m
        val resultReversed = mutableListOf<DiffChunk>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldChars[i - 1] == newChars[j - 1]) {
                resultReversed.add(DiffChunk(DiffType.UNCHANGED, oldChars[i - 1].toString()))
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                resultReversed.add(DiffChunk(DiffType.ADDED, newChars[j - 1].toString()))
                j--
            } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                resultReversed.add(DiffChunk(DiffType.REMOVED, oldChars[i - 1].toString()))
                i--
            }
        }
        val reversed = resultReversed.reversed()
        val merged = mutableListOf<DiffChunk>()
        for (chunk in reversed) {
            if (merged.isNotEmpty() && merged.last().type == chunk.type) {
                val last = merged.removeAt(merged.size - 1)
                merged.add(DiffChunk(last.type, last.text + chunk.text))
            } else {
                merged.add(chunk)
            }
        }
        return merged
    }

    private fun computeWordDiff(oldStr: String, newStr: String): List<DiffChunk> {
        val oldWords = oldStr.split(Regex("(?<=\\s)|(?=\\s)"))
        val newWords = newStr.split(Regex("(?<=\\s)|(?=\\s)"))
        val n = oldWords.size
        val m = newWords.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in 0 until n) {
            for (j in 0 until m) {
                if (oldWords[i] == newWords[j]) {
                    dp[i + 1][j + 1] = dp[i][j] + 1
                } else {
                    dp[i + 1][j + 1] = maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        var i = n
        var j = m
        val resultReversed = mutableListOf<DiffChunk>()
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && oldWords[i - 1] == newWords[j - 1]) {
                resultReversed.add(DiffChunk(DiffType.UNCHANGED, oldWords[i - 1]))
                i--
                j--
            } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
                resultReversed.add(DiffChunk(DiffType.ADDED, newWords[j - 1]))
                j--
            } else if (i > 0 && (j == 0 || dp[i][j - 1] < dp[i - 1][j])) {
                resultReversed.add(DiffChunk(DiffType.REMOVED, oldWords[i - 1]))
                i--
            }
        }
        val reversed = resultReversed.reversed()
        val merged = mutableListOf<DiffChunk>()
        for (chunk in reversed) {
            if (merged.isNotEmpty() && merged.last().type == chunk.type) {
                val last = merged.removeAt(merged.size - 1)
                merged.add(DiffChunk(last.type, last.text + chunk.text))
            } else {
                merged.add(chunk)
            }
        }
        return merged
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

    private fun extractKeyId(messageObj: Any): String? {
        try {
            var curr: Class<*>? = messageObj.javaClass
            while (curr != null && curr != Any::class.java) {
                for (field in curr.declaredFields) {
                    field.isAccessible = true
                    val valObj = field.get(messageObj) ?: continue
                    val valClass = valObj.javaClass

                    val str = valObj.toString()
                    if (str.startsWith("Key(") || str.contains("id=") || valClass.name.contains("Key") || valClass.simpleName.contains("Key")) {
                        val matcher = KEY_ID_REGEX.matcher(str)
                        if (matcher.find()) {
                            val id = matcher.group(1)
                            if (!id.isNullOrEmpty()) return id
                        }
                        val id = extractMessageIdFromKey(valObj)
                        if (id != null) return id
                    }
                }
                curr = curr.superclass
            }

            val objStr = messageObj.toString()
            val matcher = KEY_ID_REGEX.matcher(objStr)
            if (matcher.find()) {
                val id = matcher.group(1)
                if (!id.isNullOrEmpty()) return id
            }
        } catch (ignored: Throwable) {}
        return null
    }

    private fun extractMessageIdFromKey(keyObj: Any): String? {
        try {
            var bestCandidate: String? = null
            var curr: Class<*>? = keyObj.javaClass
            while (curr != null && curr != Any::class.java) {
                for (field in curr.declaredFields) {
                    if (field.type != String::class.java) continue
                    field.isAccessible = true
                    val str = field.get(keyObj) as? String ?: continue
                    if (str.isEmpty() || str.contains("@")) continue
                    if (str.length >= 12) return str
                    if (str.length >= 6 && bestCandidate == null) bestCandidate = str
                }
                curr = curr.superclass
            }
            return bestCandidate
        } catch (ignored: Throwable) {}
        return null
    }

    private fun extractRowId(messageObj: Any): Long {
        try {
            var curr: Class<*>? = messageObj.javaClass
            while (curr != null && curr != Any::class.java) {
                for (field in curr.declaredFields) {
                    field.isAccessible = true
                    if (field.name == "A0j" || field.name == "A0k" || field.name == "rowId" || field.name == "A00" || field.name == "A01" || field.name == "_id") {
                        val v = field.get(messageObj)
                        if (v is Long && v > 0) return v
                        if (v is Int && v > 0) return v.toLong()
                    }
                }
                curr = curr.superclass
            }
        } catch (ignored: Throwable) {}
        return 0L
    }

    private fun extractMessageText(messageObj: Any): String? {
        try {
            var curr: Class<*>? = messageObj.javaClass
            while (curr != null && curr != Any::class.java) {
                for (field in curr.declaredFields) {
                    if (field.name == "A0Q" || field.name == "textData" || field.name == "A02" || field.name == "message") {
                        field.isAccessible = true
                        val str = field.get(messageObj) as? String
                        if (!str.isNullOrEmpty()) return str
                    }
                }
                curr = curr.superclass
            }

            for (m in messageObj.javaClass.methods) {
                if (m.returnType == String::class.java && m.parameterCount == 0 &&
                    m.name != "toString" && m.name != "hashCode" && m.name != "name"
                ) {
                    val str = m.invoke(messageObj) as? String
                    if (!str.isNullOrEmpty() && !str.contains("@") && str.length < 5000) return str
                }
            }
        } catch (ignored: Throwable) {}
        return null
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
