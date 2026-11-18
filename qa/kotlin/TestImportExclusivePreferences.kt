package com.waenhancer.core.preferences

import android.content.TestContext
import android.net.Uri
import com.waenhancer.utils.DiagnosticsRecorder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.json.JSONObject

private const val STORE = "waex_prefs"
private const val MODULE = "floating_bottom_bar"
private const val NATIVE = "native_floating_navigation"
private const val PERSONAL = "com.whatsapp:"
private const val BUSINESS = "com.whatsapp.w4b:"
private var passed = 0
private val failed = mutableListOf<String>()

private fun test(name: String, block: () -> Unit) {
    try { block(); passed++; println("PASS: $name") }
    catch (failure: Throwable) {
        failed.add(name)
        println("FAIL: $name (${failure.javaClass.simpleName}: ${failure.message})")
    }
}

/** Independently authored wire tags/keys, not enumerated from the production schema. */
private fun binary(entries: List<Pair<String, Any>>): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        fun text(value: String) { val raw = value.toByteArray(Charsets.UTF_8); out.writeInt(raw.size); out.write(raw) }
        out.write(byteArrayOf(87, 65, 69, 88, 67, 70, 71, 0))
        out.writeInt(1); out.writeLong(0); out.writeInt(entries.size)
        entries.forEach { (key, value) ->
            text(key)
            when (value) {
                is Boolean -> { out.writeByte(0); out.writeBoolean(value) }
                is String -> { out.writeByte(1); text(value) }
                else -> error("Unsupported fixture value")
            }
        }
    }
    return bytes.toByteArray()
}

private fun payload(entries: List<Pair<String, Any>>, format: String): ByteArray {
    if (format == "binary") return binary(entries)
    val body = JSONObject()
    entries.forEach { (key, value) -> body.put(key, value) }
    val root = if (format == "versioned") JSONObject().put("schemaVersion", 1).put("settings", body) else body
    return root.toString().toByteArray(Charsets.UTF_8)
}

private fun seed(context: TestContext, vararg entries: Pair<String, Any>) {
    entries.forEach { (key, value) -> context.seed(STORE, key, value) }
}

private fun expect(context: TestContext, key: String, value: Any?) {
    check(context.get(STORE, key) == value) { "$key expected=$value actual=${context.get(STORE, key)}" }
}

private fun import(context: TestContext, entries: List<Pair<String, Any>>, target: String? = null, format: String = "binary"): Int {
    // Select a UI target without invoking the unrelated one-time target initializer.
    if (target != null) context.seed(STORE, "$target:target_seeded", true)
    val manager = WaexPreferenceManager(context)
    manager.setTargetPackage(target)
    context.resolver.input = payload(entries, format)
    DiagnosticsRecorder.reset()
    return manager.importSettings(Uri.parse("content://fixture/settings"))
}

fun main() {
    for (target in listOf(null, "com.whatsapp", "com.whatsapp.w4b")) {
        for (format in listOf("binary", "legacy", "versioned")) {
            test("all scopes normalize independently of selected target=$target format=$format") {
                val context = TestContext()
                seed(context, "module_config_mode" to "Per Target App")
                val incoming = listOf(MODULE to true, NATIVE to true, PERSONAL + MODULE to true,
                    PERSONAL + NATIVE to true, BUSINESS + MODULE to true, BUSINESS + NATIVE to true)
                check(import(context, incoming, target, format) == incoming.size)
                for (scope in listOf("", PERSONAL, BUSINESS)) {
                    expect(context, scope + MODULE, true); expect(context, scope + NATIVE, false)
                }
                check(context.commits(STORE) == 1) { "Restore must normalize in its public commit" }
                check(context.resolver.notifications == 1)
                check(DiagnosticsRecorder.imports == 1 && DiagnosticsRecorder.lastCount == incoming.size)
            }
        }
    }
    test("record order does not change the declared restore priority") {
        val incoming = listOf(PERSONAL + MODULE to true, PERSONAL + NATIVE to true)
        for (ordered in listOf(incoming, incoming.reversed())) {
            val context = TestContext()
            check(import(context, ordered) == 2)
            expect(context, PERSONAL + MODULE, true); expect(context, PERSONAL + NATIVE, false)
        }
    }
    test("partial merge retains module priority against an existing opposite value") {
        for (incomingKey in listOf(MODULE, NATIVE)) {
            val context = TestContext()
            val existing = if (incomingKey == MODULE) NATIVE else MODULE
            seed(context, PERSONAL + existing to true)
            check(import(context, listOf(PERSONAL + incomingKey to true)) == 1)
            expect(context, PERSONAL + MODULE, true); expect(context, PERSONAL + NATIVE, false)
            check(context.commits(STORE) == 1)
        }
    }
    test("scoped import handles inherited native value without changing universal intent") {
        val context = TestContext()
        seed(context, NATIVE to true, BUSINESS + MODULE to false)
        check(import(context, listOf(PERSONAL + MODULE to true)) == 1)
        expect(context, PERSONAL + MODULE, true); expect(context, PERSONAL + NATIVE, false)
        expect(context, NATIVE, true); expect(context, MODULE, null)
        expect(context, BUSINESS + MODULE, false); expect(context, BUSINESS + NATIVE, null)
    }
    test("universal import considers inherited targets and preserves explicit false overrides") {
        val context = TestContext()
        seed(context, PERSONAL + NATIVE to true, BUSINESS + MODULE to false, BUSINESS + NATIVE to true)
        check(import(context, listOf(MODULE to true)) == 1)
        expect(context, MODULE, true); expect(context, NATIVE, null)
        expect(context, PERSONAL + MODULE, null); expect(context, PERSONAL + NATIVE, false)
        expect(context, BUSINESS + MODULE, false); expect(context, BUSINESS + NATIVE, true)
    }
    test("universal import does not repair fully overridden unrelated target conflicts") {
        val context = TestContext()
        seed(context, BUSINESS + MODULE to true, BUSINESS + NATIVE to true)
        check(import(context, listOf(MODULE to true)) == 1)
        expect(context, MODULE, true); expect(context, NATIVE, null)
        expect(context, BUSINESS + MODULE, true); expect(context, BUSINESS + NATIVE, true)
    }
    test("universal repair precedes inherited target evaluation") {
        val context = TestContext()
        seed(context, NATIVE to true, PERSONAL + MODULE to true, BUSINESS + MODULE to false)
        check(import(context, listOf(MODULE to true)) == 1)
        expect(context, MODULE, true); expect(context, NATIVE, false)
        // The corrected fallback is sufficient; do not materialize unnecessary overrides.
        expect(context, PERSONAL + MODULE, true); expect(context, PERSONAL + NATIVE, null)
        expect(context, BUSINESS + MODULE, false); expect(context, BUSINESS + NATIVE, null)
    }
    test("rollback preserves an existing wrongly typed partner exactly") {
        val context = TestContext()
        seed(context, PERSONAL + NATIVE to "true")
        context.failCommits(STORE, 1, true)
        val failure = runCatching { import(context, listOf(PERSONAL + MODULE to true)) }.exceptionOrNull()
        check(failure is IllegalStateException)
        expect(context, PERSONAL + NATIVE, "true"); expect(context, PERSONAL + MODULE, null)
        check(context.resolver.notifications == 0 && DiagnosticsRecorder.imports == 0)
    }
    test("unrelated import leaves preexisting conflicts unchanged in every scope") {
        val context = TestContext()
        seed(context, MODULE to true, NATIVE to true, PERSONAL + MODULE to true, PERSONAL + NATIVE to true)
        check(import(context, listOf("imagequality" to true)) == 1)
        for (scope in listOf("", PERSONAL)) {
            expect(context, scope + MODULE, true); expect(context, scope + NATIVE, true)
        }
        check(context.commits(STORE) == 1)
    }
    test("scoped import leaves an unrelated target conflict unchanged") {
        val context = TestContext()
        seed(context, BUSINESS + MODULE to true, BUSINESS + NATIVE to true)
        check(import(context, listOf(PERSONAL + MODULE to true, PERSONAL + NATIVE to true)) == 2)
        expect(context, PERSONAL + NATIVE, false)
        expect(context, BUSINESS + MODULE, true); expect(context, BUSINESS + NATIVE, true)
    }
    test("disabled and unset pairs do not synthesize opposite settings") {
        val context = TestContext()
        check(import(context, listOf(MODULE to false, PERSONAL + NATIVE to true, BUSINESS + MODULE to false)) == 3)
        expect(context, NATIVE, null); expect(context, PERSONAL + MODULE, null); expect(context, BUSINESS + NATIVE, null)
        expect(context, PERSONAL + NATIVE, true)
        check(context.count() == 3)
    }
    test("normalization does not need a second potentially failed commit") {
        val context = TestContext()
        context.failAtCommit(STORE, 2, false)
        check(import(context, listOf(MODULE to true, NATIVE to true)) == 2)
        expect(context, MODULE, true); expect(context, NATIVE, false)
        check(context.commits(STORE) == 1)
    }
    for (writeBeforeFailure in listOf(false, true)) {
        test("failure restores synthesized partner absence memoryWrite=$writeBeforeFailure") {
            val context = TestContext()
            seed(context, NATIVE to true, "imagequality" to false)
            context.failCommits(STORE, 1, writeBeforeFailure)
            val failure = runCatching { import(context, listOf(PERSONAL + MODULE to true)) }.exceptionOrNull()
            check(failure is IllegalStateException)
            check(failure.message == "Backup restore failed; previous preferences restored")
            expect(context, NATIVE, true); expect(context, PERSONAL + MODULE, null); expect(context, PERSONAL + NATIVE, null)
            expect(context, "imagequality", false)
            check(context.count() == 2)
            check(context.resolver.notifications == 0 && DiagnosticsRecorder.imports == 0)
        }
    }
    test("failed compensation remains failure even when memory resembles rollback") {
        val context = TestContext()
        seed(context, NATIVE to true)
        context.failCommits(STORE, 2, true)
        val failure = runCatching { import(context, listOf(PERSONAL + MODULE to true)) }.exceptionOrNull()
        check(failure is IllegalStateException && failure.message == "Backup restore failed and rollback could not restore all preferences")
        check(context.resolver.notifications == 0 && DiagnosticsRecorder.imports == 0)
        expect(context, PERSONAL + MODULE, null); expect(context, PERSONAL + NATIVE, null)
    }
    test("public failure compensates both private import and exclusive normalization") {
        val context = TestContext()
        seed(context, NATIVE to true)
        context.seed("private_config", "custom_transcription_model", "original")
        context.failCommits(STORE, 1, true)
        val failure = runCatching {
            import(context, listOf(PERSONAL + MODULE to true, "custom_transcription_model" to "replacement"))
        }.exceptionOrNull()
        check(failure is IllegalStateException)
        expect(context, NATIVE, true); expect(context, PERSONAL + MODULE, null); expect(context, PERSONAL + NATIVE, null)
        check(context.get("private_config", "custom_transcription_model") == "original")
        check(context.resolver.notifications == 0 && DiagnosticsRecorder.imports == 0)
    }
    println("EXCLUSIVE_RESULT: $passed/${passed + failed.size} cases passed; ${failed.size} failed")
    check(failed.isEmpty()) { "Exclusive import failures: ${failed.joinToString()}" }
}
