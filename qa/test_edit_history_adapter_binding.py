#!/usr/bin/env python3
"""Compile production getView and click callbacks with explicit Kotlin view doubles.

Checks adapter identity, recycled-listener invalidation and zero canonical DB
lookups in binding. This is not a rendered Android/host performance measurement.
"""
from pathlib import Path
import argparse
import subprocess
import tempfile
import shutil

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=root / (
    'app/src/main/java/com/waenhancer/xposed/features/conversation/AntiEditMessagesHook.kt'))
parser.add_argument('--scenario', choices=['all', 'queries', 'recycle'], default='all')
args = parser.parse_args()
source = args.source.read_text()

def body_after(signature, source=source):
    assert source.count(signature) == 1, 'callback extraction must be unambiguous'
    start = source.index('{', source.index(signature))
    depth, quoted, escaped = 0, False, False
    for index in range(start, len(source)):
        char = source[index]
        if quoted:
            if escaped: escaped = False
            elif char == '\\': escaped = True
            elif char == '"': quoted = False
        elif char == '"': quoted = True
        elif char == '{': depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0: return source[start:index + 1]
    raise AssertionError('unterminated method')

fixture = r'''
class Context { val resources = Resources(); val packageName = "com.whatsapp" }
class Resources {
    fun getIdentifier(name: String, type: String, pkg: String) =
        if (name == "edit_label") 31 else if (name == "edited_label") 32 else 0
}
open class View {
    val context = Context()
    var resourceName: String? = null
    var parent: View? = null
    val fields = mutableMapOf<String, Any>()
    val tags = mutableMapOf<Int, Any>()
    fun setTag(key: Int, value: Any?) { if (value == null) tags.remove(key) else tags[key] = value }
    fun getTag(key: Int): Any? = tags[key]
    var listener: ((View) -> Unit)? = null
    var isClickable = false
    fun setOnClickListener(block: ((View) -> Unit)?) { listener = block }
    fun click() { if (isClickable) listener?.invoke(this) }
}
class TextView : View() { var text: CharSequence = "Edited" }
class ViewGroup : View() {
    val label = TextView().apply { resourceName = "edit_label"; parent = this@ViewGroup }
    @Suppress("UNCHECKED_CAST")
    fun <T : View> findViewById(id: Int): T? = (if (id == 31) label else null) as T?
}
class UnderlineSpan
class SpannableString(val value: String) : CharSequence by value {
    fun setSpan(span: Any, start: Int, end: Int, flags: Int) {}
    override fun toString() = value
}
data class Message(val id: Long, val key: String?, val text: String?)
interface ListAdapter { fun getItem(position: Int): Any? }
class Adapter(var message: Message?, var fail: Boolean = false) : ListAdapter {
    var reads = 0
    override fun getItem(position: Int): Any? {
        reads++
        if (fail) throw IllegalStateException("unsupported item")
        return if (position == 0) message else null
    }
}
class MethodHookParam(val thisObject: Any?, val args: Array<Any?>, val result: Any?)
object XposedHelpers {
    fun removeAdditionalInstanceField(row: View, key: String) { row.fields.remove(key) }
    fun setAdditionalInstanceField(row: View, key: String, value: Any) { row.fields[key] = value }
    fun getAdditionalInstanceField(row: View, key: String): Any? = row.fields[key]
}
class Callback(private val adapter: ListAdapter) {
    private val TAG_EDIT_DATA = 0x57414544
    private val TAG_EDIT_ORIGINAL_TEXT = 0x57414545
    private var sEditLabelResId = 0
    private var sEditedLabelResId = 0
    private data class EditData(val rowId: Long, val keyId: String?, val msgText: String?)
    private val FIELD_ROW_ID = "waex_row_id"
    private val FIELD_MSG_ID = "waex_msg_id"
    private val FIELD_MSG_TEXT = "waex_msg_text"
    private val FIELD_BINDING_TOKEN = "waex_edit_binding_token"
    var enabled = true
    var dbQueries = 0
    var treeSearches = 0
    val opened = mutableListOf<Message>()
    private fun isFeatureEnabled() = enabled
    private fun extractStableKeyId(item: Any) = (item as Message).key
    private fun extractRowId(item: Any) = (item as Message).id
    private fun extractMessageText(item: Any) = (item as Message).text
    private fun resolveCanonicalRowId(row: Long, key: String?): Long { dbQueries++; return row }
    private fun findChildByResourceName(row: ViewGroup, name: String): View? { treeSearches++; return row.label }
    private fun findParentMessageRow(row: View): View? = row.parent
    private fun getResourceEntryName(view: View): String? = view.resourceName
    private fun extractTextFromMessageBubble(row: View?): String? = null
    private fun showEditHistoryBottomSheet(context: Any, rowId: Long, keyId: String?, text: String?) {
        opened.add(Message(rowId, keyId, text))
    }
    private fun bindEditLabel(labelView: View, parentRow: View?, boundRowId: Long, boundKeyId: String?, boundText: String?) LABEL_BODY
    fun afterHookedMethod(p: MethodHookParam) CALLBACK_BODY
    private fun invalidateEditLabelBinding(labelView: View) INVALIDATE_BODY
}
private var checks = 0
private fun checkCase(value: Boolean, message: String) { checks++; if (!value) throw AssertionError(message) }
fun main(args: Array<String>) {
    val first = Adapter(Message(1, "first-chat", "first text"))
    val second = Adapter(Message(2, "second-chat", "second text"))
    val callback = Callback(first)
    val row = ViewGroup()
    if (args.single() == "queries" || args.single() == "all") {
        repeat(10_000) { callback.afterHookedMethod(MethodHookParam(first, arrayOf(0), row)) }
        checkCase(callback.dbQueries == 0, "scroll binding performed ${callback.dbQueries} canonical DB queries")
        row.label.click()
        checkCase(callback.opened.single().key == "first-chat", "keyed history remains accessible without row lookup")
        println("PASS: $checks callback query checks; 10,000 getView calls, ${callback.dbQueries} DB queries")
        if (args.single() == "queries") return
        callback.opened.clear()
        first.reads = 0
    }
    if (args.single() == "recycle") {
        callback.afterHookedMethod(MethodHookParam(first, arrayOf(0), row))
        row.label.click()
        val before = callback.opened.size
        callback.afterHookedMethod(MethodHookParam(Any(), arrayOf(0), row))
        row.label.click()
        checkCase(callback.opened.size == before, "unsupported recycled row opened its predecessor's history")
        checkCase("waex_msg_id" !in row.label.fields && "waex_key_id" !in row.label.fields, "unsupported bind retains identity")
        println("PASS: $checks unsupported recycling checks")
        return
    }
    checkCase(first.javaClass == second.javaClass, "fixture needs distinct instances of the same hooked class")
    callback.afterHookedMethod(MethodHookParam(first, arrayOf(0), row)); row.label.click()
    checkCase(callback.opened.last() == Message(0, "first-chat", "first text"), "first keyed adapter binding")
    callback.afterHookedMethod(MethodHookParam(second, arrayOf(0), row)); row.label.click()
    checkCase(callback.opened.last() == Message(0, "second-chat", "second text"), "current adapter supplies key and text")
    checkCase(row.label.fields["waex_msg_id"] == "second-chat", "row key follows conversation")
    checkCase("waex_row_id" !in row.label.fields, "keyed binding does not guess a numeric database ID")
    checkCase(first.reads == 1 && second.reads == 1, "class hook reads current receiver")
    checkCase(callback.dbQueries == 0, "getView must not query SQLite")
    val noKey = Adapter(Message(4, null, "fourth text"))
    callback.afterHookedMethod(MethodHookParam(noKey, arrayOf(0), row)); row.label.click()
    checkCase(callback.opened.last().id == 4L && callback.opened.last().key == null, "row-only compatibility")
    checkCase("waex_key_id" !in row.label.fields && "waex_msg_id" !in row.label.fields, "keyed-to-row-only recycle clears key")
    val third = Adapter(Message(3, "third-chat", null))
    callback.afterHookedMethod(MethodHookParam(third, arrayOf(0), row)); row.label.click()
    checkCase(callback.opened.last() == Message(0, "third-chat", null), "third adapter clears old text")
    checkCase("waex_msg_text" !in row.label.fields, "old bubble text removed")
    val before = callback.opened.size
    callback.afterHookedMethod(MethodHookParam(Any(), arrayOf(0), row))
    row.fields["waex_msg_id"] = "foreign-message"
    row.fields["waex_msg_text"] = "foreign text"
    row.label.click()
    checkCase(callback.opened.size == before, "unsupported receiver invalidates prior listener")
    row.fields.clear()
    checkCase(row.label.getTag(0x57414544) == null, "unsupported receiver retained edit-data tag")
    checkCase(row.label.text.toString() == "Edited", "stale WAEX decoration retained")
    checkCase("waex_msg_id" !in row.label.fields && "waex_key_id" !in row.label.fields, "unsupported receiver clears fields")
    for (bad in listOf(Adapter(null), Adapter(null, true), Adapter(Message(0, null, "unknown")))) {
        callback.afterHookedMethod(MethodHookParam(first, arrayOf(0), row))
        callback.afterHookedMethod(MethodHookParam(bad, arrayOf(0), row)); row.label.click()
        checkCase(callback.opened.size == before, "null/failed/unidentified row invalidates listener")
    }
    callback.afterHookedMethod(MethodHookParam(first, arrayOf(0), row))
    callback.enabled = false; row.label.click()
    checkCase(callback.opened.size == before, "disabled feature does not open a prior listener")
    callback.enabled = true; row.label.click()
    checkCase(callback.opened.last().key == "first-chat", "reenabled valid binding remains usable")
    val beforeDisabledRecycle = callback.opened.size
    callback.enabled = false
    callback.afterHookedMethod(MethodHookParam(second, arrayOf(0), row))
    callback.enabled = true; row.label.click()
    checkCase(callback.opened.size == beforeDisabledRecycle, "disabled-time recycle must invalidate prior listener before reenabling")
    callback.afterHookedMethod(MethodHookParam(second, arrayOf(0), row)); row.label.click()
    checkCase(callback.opened.last().key == "second-chat", "fresh bind after disabled recycle remains usable")
    // Removed attachment hook is not recreated in the fixture. Verify the
    // current getView invalidation also preserves host-updated label text.
    callback.afterHookedMethod(MethodHookParam(first, arrayOf(0), row))
    row.label.text = "Host updated"
    callback.afterHookedMethod(MethodHookParam(Any(), arrayOf(0), row))
    checkCase(row.label.text.toString() == "Host updated",
              "invalidation clobbered a new host-supplied label")
    checkCase(row.label.getTag(0x57414544) == null, "unsupported row retained tagged edit data")
    callback.afterHookedMethod(MethodHookParam(second, arrayOf(0), row))
    val nativeClicks = intArrayOf(0)
    row.label.setOnClickListener { nativeClicks[0]++ }
    callback.afterHookedMethod(MethodHookParam(Any(), arrayOf(0), row))
    row.label.click()
    checkCase(nativeClicks[0] == 1, "invalidation erased WhatsApp's new click listener")
    println("PASS: $checks extracted production adapter/click checks; 10,000 source getView calls")

}
'''.replace('CALLBACK_BODY', body_after('override fun afterHookedMethod(p: MethodHookParam)')).replace(
    'LABEL_BODY', body_after('private fun bindEditLabel(')).replace(
    'INVALIDATE_BODY', body_after('private fun invalidateEditLabelBinding(')
    if 'private fun invalidateEditLabelBinding(' in source
    else '{ throw AssertionError("unexpected invalidation callback") }')
with tempfile.TemporaryDirectory() as folder:
    temp = Path(folder)
    path = temp / 'AdapterBindingChecks.kt'
    path.write_text(fixture)
    subprocess.run([shutil.which('kotlinc.bat') or shutil.which('kotlinc') or 'kotlinc', str(path), '-jvm-target', '17', '-include-runtime', '-d', str(temp / 'checks.jar')], check=True)
    subprocess.run(['java', '-jar', str(temp / 'checks.jar'), args.scenario], check=True)
