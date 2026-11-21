#!/usr/bin/env python3
"""Compile the complete Tasker hook and execute its registered callbacks.

Uses the production TaskerIntegrationHook, BaseFeature and TaskerMessagePolicy.
Android, Xposed, DexKit, preferences, ActivityTracker and the main looper are
explicit in-process doubles. No broadcast is sent to Android or another app;
this does not validate native injection, provider refresh latency or Tasker UI.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
HOOK = Path("app/src/main/java/com/waenhancer/xposed/features/automation/TaskerIntegrationHook.kt")
BASE = Path("app/src/main/java/com/waenhancer/xposed/core/BaseFeature.kt")
POLICY = Path("app/src/main/java/com/waenhancer/xposed/features/automation/TaskerMessagePolicy.java")

STUBS = {
    "Content.kt": r'''package android.content
import android.net.Uri
interface SharedPreferences { fun getBoolean(key: String, fallback: Boolean): Boolean }
open class Context(val packageName: String = "com.whatsapp") {
    val receivers = mutableListOf<Triple<BroadcastReceiver, IntentFilter, Int?>>()
    val broadcasts = mutableListOf<Intent>()
    fun registerReceiver(r: BroadcastReceiver, f: IntentFilter) { receivers.add(Triple(r,f,null)) }
    fun registerReceiver(r: BroadcastReceiver, f: IntentFilter, flags: Int) { receivers.add(Triple(r,f,flags)) }
    fun sendBroadcast(intent: Intent) { broadcasts.add(intent) }
    companion object { const val RECEIVER_EXPORTED = 2 }
}
abstract class BroadcastReceiver { abstract fun onReceive(ctx: Context, intent: Intent) }
class IntentFilter(val action: String)
class ActivityNotFoundException : RuntimeException()
class Intent(val action: String? = null, val data: Uri? = null) {
    val extras = mutableMapOf<String, Any?>()
    var targetPackage: String? = null
    fun getStringExtra(key: String): String? = extras[key] as String?
    fun putExtra(key: String, value: String): Intent { extras[key] = value; return this }
    fun setPackage(value: String): Intent { targetPackage = value; return this }
    companion object { const val ACTION_VIEW = "android.intent.action.VIEW" }
}
''',
    "Uri.kt": r'''package android.net
class Uri private constructor(val value: String) {
    companion object {
        fun parse(value: String) = Uri(value)
        fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
''',
    "Os.kt": r'''package android.os
class Looper { companion object { fun getMainLooper() = Looper() } }
class Handler(looper: Looper) {
    fun post(task: Runnable): Boolean { pending.add(task); return true }
    companion object {
        val pending = java.util.ArrayDeque<Runnable>()
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }
}
object Build {
    object VERSION { var SDK_INT = 35 }
    object VERSION_CODES { const val TIRAMISU = 33 }
}
object SystemClock { var now = 10_000L; fun elapsedRealtime() = now }
''',
    "Activity.kt": r'''package android.app
import android.content.Context
import android.content.Intent
class DecorView { var focused = true; fun hasWindowFocus() = focused }
class Window { val decorView = DecorView() }
class Activity(packageName: String = "com.whatsapp") : Context(packageName) {
    var isFinishing = false
    var isDestroyed = false
    var window: Window? = Window()
    val launches = mutableListOf<Intent>()
    fun startActivity(intent: Intent) { launches.add(intent) }
}
''',
    "ActivityTracker.kt": r'''package com.waenhancer.xposed.utils
import android.app.Activity
object ActivityTracker { var current: Activity? = null; fun getCurrentActivity() = current }
''',
    "Xposed.kt": r'''package de.robv.android.xposed
import java.lang.reflect.Method
open class XC_MethodHook {
    open fun beforeHookedMethod(param: MethodHookParam) {}
    class MethodHookParam(val args: Array<Any?>) { var result: Any? = null }
}
object XposedBridge {
    val hooks = mutableListOf<Pair<Method, XC_MethodHook>>()
    val logs = mutableListOf<String>()
    fun hookMethod(method: Method, callback: XC_MethodHook) { hooks.add(method to callback) }
    fun log(message: String) { logs.add(message) }
}
object XposedHelpers {
    var reads = 0
    var onRead: ((Any, String) -> Unit)? = null
    fun getObjectField(owner: Any, field: String): Any? {
        reads++
        onRead?.invoke(owner, field)
        return owner.javaClass.getField(field).get(owner)
    }
}
''',
    "Dex.kt": r'''package com.waenhancer.xposed.core.devkit
import android.content.Context
import org.luckypray.dexkit.query.FindMethod
import java.lang.reflect.Method
class Host { fun receipt(a: Any?, b: Any?, c: Any?, key: Any?) {} }
class MethodResult {
    fun getMethodInstance(loader: ClassLoader): Method = Host::class.java.getDeclaredMethod(
        "receipt", Any::class.java, Any::class.java, Any::class.java, Any::class.java)
}
class Bridge { fun findMethod(query: FindMethod): List<MethodResult> = listOf(MethodResult()) }
class DexSearchEngine {
    fun findMethodWithCache(context: Context, loader: ClassLoader, key: String,
                            finder: (Bridge, ClassLoader) -> Method?): Method? = finder(Bridge(), loader)
    companion object { fun getInstance() = DexSearchEngine() }
}
''',
    "Query.kt": r'''package org.luckypray.dexkit.query
import org.luckypray.dexkit.query.enums.StringMatchType
class MethodMatcher { fun addUsingString(value: String, match: StringMatchType) {} }
class FindMethod {
    fun matcher(block: MethodMatcher.() -> Unit): FindMethod { MethodMatcher().block(); return this }
    companion object { fun create() = FindMethod() }
}
''',
    "StringMatchType.kt": r'''package org.luckypray.dexkit.query.enums
enum class StringMatchType { Contains }
''',
}

TEST = r'''package fixture
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.waenhancer.xposed.features.automation.TaskerIntegrationHook
import com.waenhancer.xposed.utils.ActivityTracker
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

class Prefs : SharedPreferences {
    var value: Any? = true
    var failure: RuntimeException? = null
    override fun getBoolean(key: String, fallback: Boolean): Boolean {
        check(key == "tasker_integration")
        failure?.let { throw it }
        return value?.let { it as Boolean } ?: fallback
    }
}
class Key(@JvmField val A00: Any?, @JvmField val A01: Any?)
class Environment(enabled: Boolean = true, sdk: Int = 35) {
    val prefs = Prefs().apply { value = enabled }
    val context = Context()
    val activity = Activity()
    init {
        XposedBridge.hooks.clear(); XposedBridge.logs.clear()
        XposedHelpers.reads = 0; XposedHelpers.onRead = null
        Handler.pending.clear(); ActivityTracker.current = activity
        Build.VERSION.SDK_INT = sdk; SystemClock.now = 10_000L
        TaskerIntegrationHook(context, javaClass.classLoader, prefs).hook()
    }
    fun incoming(key: Any? = Key("15551234567@s.whatsapp.net", "synthetic-id"),
                 args: Array<Any?> = arrayOf(null,null,null,key)) {
        val callback = XposedBridge.hooks.single().second
        val original = Any()
        val param = XC_MethodHook.MethodHookParam(args).apply { result = original }
        callback.beforeHookedMethod(param)
        check(param.result === original && param.args === args) { "Host result or arguments changed" }
    }
    fun request(action: String = TaskerIntegrationHook.ACTION_SEND_MESSAGE) {
        val intent = Intent(action).putExtra("number", "+1 555 123 4567").putExtra("message", "Draft for review")
        context.receivers.single().first.onReceive(context, intent)
    }
}
private var total = 0
private var failures = 0
private fun test(name: String, block: () -> Unit) {
    total++
    try { block(); println("PASS: $name") }
    catch (error: Throwable) {
        failures++
        println("FAIL: " + name + ": " + error.javaClass.simpleName + ": " + error.message)
    }
}
fun main() {
    test("disabled startup installs no receiver or incoming callback") {
        val env = Environment(false)
        check(env.context.receivers.isEmpty() && XposedBridge.hooks.isEmpty())
    }
    test("enabled startup retains Android 13 exported automation receiver") {
        val env = Environment()
        check(XposedBridge.hooks.size == 1)
        val receiver = env.context.receivers.single()
        check(receiver.second.action == TaskerIntegrationHook.ACTION_SEND_MESSAGE)
        check(receiver.third == Context.RECEIVER_EXPORTED)
    }
    test("legacy registration retains existing receiver overload") {
        val env = Environment(sdk=32)
        check(env.context.receivers.single().third == null)
    }
    test("enabled incoming callback preserves action and exact extras") {
        val env = Environment(); env.incoming()
        val event = env.context.broadcasts.single()
        check(event.action == TaskerIntegrationHook.ACTION_MESSAGE_RECEIVED)
        check(event.extras == mapOf("jid" to "15551234567@s.whatsapp.net", "message_id" to "synthetic-id"))
        check(env.activity.launches.isEmpty())
    }
    test("live disable prevents broadcast and identifier extraction") {
        val env = Environment(); env.prefs.value = false; env.incoming()
        check(env.context.broadcasts.isEmpty() && XposedHelpers.reads == 0)
    }
    test("missing live preference uses disabled default") {
        val env = Environment(); env.prefs.value = null; env.incoming()
        check(env.context.broadcasts.isEmpty() && XposedHelpers.reads == 0)
    }
    test("malformed live preference fails closed") {
        val env = Environment(); env.prefs.value = "invalid"; env.incoming()
        check(env.context.broadcasts.isEmpty() && XposedHelpers.reads == 0)
    }
    test("preference read denied fails closed") {
        val env = Environment(); env.prefs.failure = SecurityException("synthetic"); env.incoming()
        check(env.context.broadcasts.isEmpty() && XposedHelpers.reads == 0)
    }
    test("disable during host jid extraction suppresses event") {
        val env = Environment()
        XposedHelpers.onRead = { _, field -> if (field == "A00") env.prefs.value = false }
        env.incoming(); check(env.context.broadcasts.isEmpty())
    }
    test("invalid preference during message id extraction suppresses event") {
        val env = Environment()
        XposedHelpers.onRead = { _, field -> if (field == "A01") env.prefs.failure = ClassCastException("synthetic") }
        env.incoming(); check(env.context.broadcasts.isEmpty())
    }
    test("already installed callback resumes when setting is reenabled") {
        val env = Environment(); env.prefs.value = false; env.incoming()
        env.prefs.value = true; env.incoming()
        check(env.context.broadcasts.size == 1)
    }
    test("status receipt remains excluded") {
        val env = Environment(); env.incoming(Key("status@broadcast", "synthetic-status"))
        check(env.context.broadcasts.isEmpty())
    }
    test("short callback and absent key remain harmless") {
        val env = Environment(); env.incoming(args=arrayOf(null)); env.incoming(null)
        check(env.context.broadcasts.isEmpty())
    }
    test("missing host fields preserve host result without broadcast") {
        val env = Environment(); env.incoming(Any())
        check(env.context.broadcasts.isEmpty())
    }
    test("queued explicit command respects disable before UI launch") {
        val env = Environment(); env.request(); env.prefs.value = false; Handler.drain()
        check(env.activity.launches.isEmpty() && env.context.broadcasts.isEmpty())
    }
    test("enabled explicit command only opens a prepared same-package chat") {
        val env = Environment(); env.request(); Handler.drain()
        val launch = env.activity.launches.single()
        check(launch.action == Intent.ACTION_VIEW && launch.targetPackage == env.context.packageName)
        check(launch.data!!.value.startsWith("https://api.whatsapp.com/send?phone=15551234567&text="))
        check(env.context.broadcasts.isEmpty())
    }
    test("unrelated broadcast action cannot launch a chat") {
        val env = Environment(); env.request("synthetic.unrelated.ACTION"); Handler.drain()
        check(env.activity.launches.isEmpty())
    }
    test("background command cannot launch an activity") {
        val env = Environment(); env.activity.window!!.decorView.focused = false
        env.request(); Handler.drain(); check(env.activity.launches.isEmpty())
    }
    println("RESULT total=$total failed=$failures")
    check(total == 18) { "Expected 18 complete callback scenarios" }
    check(failures == 0) { "$failures scenario(s) failed" }
}
'''


def execute(source_root, output, allow_failure):
    output.mkdir(parents=True, exist_ok=True)
    classes = output / "classes"
    classes.mkdir()
    fixtures = output / "fixtures"
    fixtures.mkdir()
    for name, contents in {**STUBS, "Checks.kt": TEST}.items():
        (fixtures / name).write_text(contents, encoding="utf-8")
    commands = [
        ["javac", "--release", "17", "-d", str(classes), str(source_root / POLICY)],
        ["kotlinc", "-jvm-target", "17", "-classpath", str(classes), "-include-runtime",
         "-d", str(output / "tasker.jar"), str(source_root / BASE), str(source_root / HOOK),
         *map(str, sorted(fixtures.glob("*.kt")))],
    ]
    compile_logs = []
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True, timeout=90)
        compile_logs.append(result.stdout + result.stderr)
        (output / "compile.log").write_text("\n".join(compile_logs), encoding="utf-8")
        if result.returncode:
            raise AssertionError("Production compilation failed; see " + str(output / "compile.log"))
    result = subprocess.run(["java", "-cp", str(output / "tasker.jar") + ":" + str(classes),
                             "fixture.ChecksKt"], capture_output=True, text=True, timeout=20)
    log = result.stdout + result.stderr
    (output / "tests.log").write_text(log, encoding="utf-8")
    summary = re.search(r"RESULT total=(\d+) failed=(\d+)", result.stdout)
    if summary is None or int(summary[1]) != 18:
        raise AssertionError("Fixture did not finish all 18 scenarios: " + log)
    record = {
        "source_root": str(source_root),
        "sources": {str(path): hashlib.sha256((source_root / path).read_bytes()).hexdigest()
                    for path in (HOOK, BASE, POLICY)},
        "fixture_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "compile_exit_codes": [0, 0], "test_exit_code": result.returncode,
        "total": int(summary[1]), "failed": int(summary[2]),
        "boundaries": "Android/Xposed/DexKit/preferences/main-looper doubles; no Android or external broadcast",
    }
    (output / "result.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    print(log, end="")
    print("Evidence:", output / "result.json")
    if result.returncode and not allow_failure:
        raise AssertionError("Tasker runtime callback regression")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--allow-test-failure", action="store_true", help="Negative control only; compilation must pass")
    args = parser.parse_args()
    if args.out:
        execute(args.source_root.resolve(), args.out.resolve(), args.allow_test_failure)
    else:
        with tempfile.TemporaryDirectory(prefix="waex_tasker_runtime_") as temp:
            execute(args.source_root.resolve(), Path(temp), args.allow_test_failure)
