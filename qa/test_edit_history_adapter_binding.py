#!/usr/bin/env python3
"""Compile production getView and click callbacks with explicit Kotlin view doubles.

Checks adapter identity, recycled-listener invalidation and zero canonical DB
lookups in binding. This is not a rendered Android/host performance measurement.
"""
from pathlib import Path
import argparse
import os
import subprocess
import tempfile
import shutil

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--source', type=Path, default=root / (
    'app/src/main/java/com/waenhancer/xposed/features/conversation/AntiEditMessagesHook.kt'))
parser.add_argument('--scenario', choices=['all', 'queries', 'recycle'], default='all')
parser.add_argument('--store-source', type=Path, default=root / (
    'app/src/main/java/com/waenhancer/xposed/core/db/EditMessageStore.kt'))
args = parser.parse_args()
store = args.store_source.read_text()
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
import java.util.concurrent.ConcurrentHashMap

object HostResourceIds {
    fun id(context: Context, name: String): Int = context.resources.getIdentifier(name, "id", context.packageName)
}
object MessageStore {
    fun findMsgstoreDbFile(context: Context) = java.io.File("synthetic.db")
}
class Cursor(private val value: String?) : AutoCloseable {
    fun moveToFirst() = value != null
    fun getString(index: Int): String? { check(index == 0); return value }
    override fun close() {}
}
class SQLiteDatabase : AutoCloseable {
    companion object {
        const val OPEN_READONLY = 1
        var opens = 0
        var queries = 0
        var failRead = false
        val aliases = mutableMapOf<String, String>()
        fun openDatabase(path: String, factory: Any?, flags: Int): SQLiteDatabase {
            check(flags == OPEN_READONLY); opens++; return SQLiteDatabase()
        }
    }
    fun rawQuery(sql: String, args: Array<String>): Cursor {
        check(sql.contains("message_edit_info") && args.size == 1); queries++
        if (failRead) throw IllegalStateException("fixture read failed")
        return Cursor(aliases[args.single()])
    }
    override fun close() {}
}
object XposedBridge { fun log(line: String) {} }
class EditMessageStore(private val context: Context) {
    private val TAG = "fixture"
    private val originalKeyCache = ConcurrentHashMap<String, String>()
    var queued = 0
    fun cachedOriginalKey(keyId: String): String? CACHE_BODY
    fun resolveOriginalKey(keyId: String): String RESOLVE_BODY
    fun recordTransitionAsync(rowId: Long, keyId: String?, before: String?, text: String, time: Long) { queued++ }
}
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
    var visibleText: String? = null
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
    private data class EditData(val rowId: Long, val keyId: String?, val msgText: String?,
                                val resolveOnClick: Boolean = false, val binding: Any = Any())
    private val FIELD_ROW_ID = "waex_row_id"
    private val FIELD_MSG_ID = "waex_msg_id"
    private val FIELD_MSG_TEXT = "waex_msg_text"
    private val FIELD_BINDING_TOKEN = "waex_edit_binding_token"
    var enabled = true
    var dbQueries = 0
    var bindingOpens = 0
    val editStore = EditMessageStore(Context())
    private val sSnapshotted = ConcurrentHashMap<String, String>()
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
    private fun extractTextFromMessageBubble(row: View?): String? = (row as? ViewGroup)?.visibleText
    private fun showEditHistoryBottomSheet(context: Any, rowId: Long, keyId: String?, text: String?) {
        opened.add(Message(rowId, keyId, text))
    }
    private fun bindEditLabel(labelView: View, parentRow: View?, boundRowId: Long, boundKeyId: String?, boundText: String?, resolveOnClick: Boolean = false) LABEL_BODY
    fun afterHookedMethod(p: MethodHookParam) {
        val before = SQLiteDatabase.queries
        val opened = SQLiteDatabase.opens
        try { bind(p) } finally {
            dbQueries += SQLiteDatabase.queries - before
            bindingOpens += SQLiteDatabase.opens - opened
        }
    }
    private fun bind(p: MethodHookParam) CALLBACK_BODY
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
        row.visibleText = "visible"
        repeat(10_000) { callback.afterHookedMethod(MethodHookParam(first, arrayOf(0), row)) }
        checkCase(callback.dbQueries == 0, "scroll binding performed ${callback.dbQueries} canonical DB queries")
        checkCase(callback.bindingOpens == 0, "a cold binding opened SQLite")
        checkCase(callback.editStore.queued == 0, "a cold edited binding queued an optional snapshot")
        row.label.click()
        checkCase(SQLiteDatabase.queries == 1 && callback.editStore.queued == 0, "explicit click must perform a single read only")
        checkCase(callback.opened.single().key == "first-chat", "keyed history remains accessible without row lookup")
        row.visibleText = null
        callback.afterHookedMethod(MethodHookParam(first, arrayOf(0), row)); row.label.click()
        checkCase(SQLiteDatabase.queries == 1 && callback.opened.last().key == "first-chat",
                  "a definitive miss must reuse the input key without repeating the lookup")
        println("PASS: $checks callback query checks; 10,000 cold getView calls, ${callback.dbQueries} DB queries")
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
    val typed = Callback(first)
    val edited = Adapter(Message(39, "edit-a", "shown"))
    val aliasedRow = ViewGroup()
    SQLiteDatabase.aliases["edit-a"] = "original-a"
    val start = SQLiteDatabase.queries
    typed.afterHookedMethod(MethodHookParam(edited, arrayOf(0), aliasedRow))
    val stale = requireNotNull(aliasedRow.label.listener)
    typed.afterHookedMethod(MethodHookParam(second, arrayOf(0), aliasedRow))
    stale(aliasedRow.label)
    checkCase(typed.opened.isEmpty() && SQLiteDatabase.queries == start, "recycled listener read or opened another row")
    typed.afterHookedMethod(MethodHookParam(edited, arrayOf(0), aliasedRow))
    typed.enabled = false; aliasedRow.label.click()
    checkCase(typed.opened.isEmpty() && SQLiteDatabase.queries == start, "disabled click resolved the alias")
    typed.enabled = true; aliasedRow.label.click()
    checkCase(typed.opened.single() == Message(0, "original-a", "shown"), "explicit click did not resolve the original key")
    checkCase(SQLiteDatabase.queries == start + 1 && typed.editStore.queued == 0, "explicit alias read performed an extra operation")
    typed.afterHookedMethod(MethodHookParam(edited, arrayOf(0), aliasedRow)); aliasedRow.label.click()
    checkCase(SQLiteDatabase.queries == start + 1 && typed.opened.last().key == "original-a", "warm binding or click repeated alias SQL")
    val failed = Adapter(Message(88, "unknown-alias", "body"))
    aliasedRow.visibleText = "shown"
    typed.afterHookedMethod(MethodHookParam(failed, arrayOf(0), aliasedRow))
    SQLiteDatabase.failRead = true; aliasedRow.label.click(); SQLiteDatabase.failRead = false
    val afterFailure = SQLiteDatabase.queries
    repeat(5) { typed.afterHookedMethod(MethodHookParam(failed, arrayOf(0), aliasedRow)) }
    checkCase(typed.opened.last().key == "unknown-alias", "explicit failed lookup changed the existing fallback")
    checkCase(SQLiteDatabase.queries == afterFailure && typed.editStore.queued == 0, "failed read led to automatic retry or persistence")
    checkCase(typed.dbQueries == 0 && typed.bindingOpens == 0, "row binding performed storage IO")
    println("PASS: $checks extracted production adapter/click checks; 10,000 source getView calls")

}
'''.replace('CACHE_BODY', body_after('fun cachedOriginalKey(', store)).replace(
    'RESOLVE_BODY', body_after('fun resolveOriginalKey(', store)).replace('CALLBACK_BODY', body_after('override fun afterHookedMethod(p: MethodHookParam)')).replace(
    'LABEL_BODY', body_after('private fun bindEditLabel(')).replace(
    'INVALIDATE_BODY', body_after('private fun invalidateEditLabelBinding(')
    if 'private fun invalidateEditLabelBinding(' in source
    else '{ throw AssertionError("unexpected invalidation callback") }')
with tempfile.TemporaryDirectory() as folder:
    temp = Path(folder)
    path = temp / 'AdapterBindingChecks.kt'
    path.write_text(fixture)
    subprocess.run([shutil.which('kotlinc.bat' if os.name == 'nt' else 'kotlinc') or 'kotlinc', str(path), '-jvm-target', '17', '-include-runtime', '-d', str(temp / 'checks.jar')], check=True)
    subprocess.run(['java', '-jar', str(temp / 'checks.jar'), args.scenario], check=True)
