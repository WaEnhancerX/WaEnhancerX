package com.waenhancer.core.preferences

import android.content.TestContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Independent keys and wire tags: an omitted schema entry must fail this suite. */
private data class Expected(val store: String, val key: String, val value: Any)
private const val PUBLIC = "waex_prefs"
private const val PRIVATE = "private_config"
private const val PERSONAL = "com.whatsapp:"
private const val BUSINESS = "com.whatsapp.w4b:"
private var passed = 0
private val failed = mutableListOf<String>()

private fun test(name: String, block: () -> Unit) {
    try {
        block()
        passed++
        println("PASS: $name")
    } catch (error: Throwable) {
        failed.add(name)
        println("FAIL: $name (${error.javaClass.simpleName}: ${error.message})")
    }
}

private fun settings() = listOf(
    Expected(PUBLIC, "module_config_mode", "Per Target App"),
    Expected(PUBLIC, "imagequality", false),
    Expected(PUBLIC, "status_style", "universal"),
    Expected(PUBLIC, "hidetabs", setOf("300")),
    Expected(PUBLIC, "deleted_message_color", 0xff112233.toInt()),
    Expected(PUBLIC, "video_limit_size", 20.5f),
    Expected(PRIVATE, "custom_transcription_model", "universal-model"),
    Expected(PUBLIC, PERSONAL + "imagequality", true),
    Expected(PUBLIC, PERSONAL + "status_style", "personal"),
    Expected(PUBLIC, PERSONAL + "hidetabs", setOf("400", "600")),
    Expected(PUBLIC, PERSONAL + "deleted_message_color", 0x80112233.toInt()),
    Expected(PUBLIC, PERSONAL + "video_limit_size", 33.5f),
    Expected(PRIVATE, PERSONAL + "custom_transcription_model", "personal-model"),
    Expected(PUBLIC, BUSINESS + "imagequality", false),
    Expected(PUBLIC, BUSINESS + "status_style", "business"),
    Expected(PUBLIC, BUSINESS + "hidetabs", emptySet<String>()),
    Expected(PRIVATE, BUSINESS + "custom_transcription_model", "business-model"),
    // Import does not require that the package is installed on this device.
    Expected(PUBLIC, "com.example.clone_2:imagequality", true),
    // Sensitivity belongs to the setting, not incidental words in a package ID.
    Expected(PUBLIC, "com.token.example:imagequality", true),
)

private fun seed(context: TestContext, entries: List<Expected>) {
    entries.forEach { context.seed(it.store, it.key, it.value) }
}

private fun assertSettings(context: TestContext, entries: List<Expected>) {
    check(context.count() == entries.size) { "Expected ${entries.size} exact keys, got ${context.count()}" }
    entries.forEach { expected ->
        check(context.get(expected.store, expected.key) == expected.value) {
            "Missing, changed or misrouted key: ${expected.key}"
        }
        val other = if (expected.store == PUBLIC) PRIVATE else PUBLIC
        check(context.get(other, expected.key) == null) { "Duplicated into wrong store: ${expected.key}" }
    }
}

private fun DataOutputStream.text(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

/** Builds version-1 bytes directly; does not call the exporter or schema. */
private fun binary(entries: List<Expected>): ByteArray {
    val output = ByteArrayOutputStream()
    DataOutputStream(output).use { writer ->
        writer.write(byteArrayOf(87, 65, 69, 88, 67, 70, 71, 0))
        writer.writeInt(1)
        writer.writeLong(0L)
        writer.writeInt(entries.size)
        entries.forEach { entry ->
            writer.text(entry.key)
            when (val value = entry.value) {
                is Boolean -> { writer.writeByte(0); writer.writeBoolean(value) }
                is String -> { writer.writeByte(1); writer.text(value) }
                is Set<*> -> {
                    writer.writeByte(2); writer.writeInt(value.size)
                    value.forEach { writer.text(it as String) }
                }
                is Int -> { writer.writeByte(3); writer.writeInt(value) }
                is Long -> { writer.writeByte(4); writer.writeLong(value) }
                is Float -> { writer.writeByte(5); writer.writeFloat(value) }
                else -> error("Unsupported test input")
            }
        }
    }
    return output.toByteArray()
}

private fun json(entries: List<Expected>, versioned: Boolean): ByteArray {
    val body = JSONObject()
    entries.forEach { entry ->
        body.put(entry.key, if (entry.value is Set<*>) JSONArray(entry.value) else entry.value)
    }
    val root = if (versioned) JSONObject().put("schemaVersion", 1).put("settings", body) else body
    return root.toString().toByteArray(Charsets.UTF_8)
}

private val rejectedKeys = listOf(
    "com.whatsapp:assemblyai_key", "com.whatsapp:custom_transcription_key",
    "com.whatsapp:tasker_secret", "com.whatsapp:github_token",
    "com.whatsapp:need_restart", "com.whatsapp:module_heartbeat",
    "com.whatsapp:download_profile_saved_identities", "com.whatsapp:waex_pro_active",
    "com.whatsapp:unknown_setting", "com.whatsapp:module_config_mode",
    ":imagequality", "com.whatsapp:", "single:imagequality",
    "com..whatsapp:imagequality", "1com.whatsapp:imagequality",
    "com.1whatsapp:imagequality", "com.whats-app:imagequality",
    "com.whatsapp:com.whatsapp.w4b:imagequality", " com.whatsapp:imagequality",
    "com.whatsapp :imagequality", "com.whatsapp:imagequality ",
)

fun main() {
    test("binary export/import preserves 19 independently listed settings and all scopes") {
        val entries = settings()
        val source = TestContext().also { seed(it, entries) }
        val output = ByteArrayOutputStream()
        check(SettingsBackupCodec.encode(source, output) == entries.size) { "Exporter omitted scoped settings or mode" }
        val destination = TestContext()
        check(SettingsBackupCodec.decode(destination, output.toByteArray().inputStream()) == entries.size)
        assertSettings(destination, entries)
    }
    test("hand-authored binary imports exact keys independently of exporter") {
        val entries = settings()
        val destination = TestContext()
        check(SettingsBackupCodec.decode(destination, binary(entries).inputStream()) == entries.size)
        assertSettings(destination, entries)
    }
    for (versioned in listOf(false, true)) {
        test("${if (versioned) "versioned" else "legacy"} JSON preserves 19 exact settings") {
            val entries = settings()
            val destination = TestContext()
            check(SettingsBackupCodec.decode(destination, json(entries, versioned).inputStream()) == entries.size)
            assertSettings(destination, entries)
        }
    }
    test("all rejected keys stay out of exports, including scoped secrets and runtime data") {
        val source = TestContext()
        source.seed(PUBLIC, "imagequality", true)
        rejectedKeys.forEach { source.seed(PUBLIC, it, true); source.seed(PRIVATE, it, "do-not-export") }
        val output = ByteArrayOutputStream()
        check(SettingsBackupCodec.encode(source, output) == 1)
        val text = output.toByteArray().toString(Charsets.ISO_8859_1)
        rejectedKeys.forEach { check(!text.contains(it)) { "Rejected key exported: $it" } }
    }
    for (format in listOf("binary", "legacy", "versioned")) {
        test("$format import does not admit scoped secrets, unknown keys or malformed targets") {
            val entries = listOf(Expected(PUBLIC, "imagequality", true)) +
                rejectedKeys.map { Expected(PUBLIC, it, "do-not-import") }
            val bytes = if (format == "binary") binary(entries) else json(entries, format == "versioned")
            val destination = TestContext()
            check(SettingsBackupCodec.decode(destination, bytes.inputStream()) == 1)
            assertSettings(destination, listOf(Expected(PUBLIC, "imagequality", true)))
        }
    }
    test("legacy aliases migrate only unscoped keys and never reinterpret a target suffix") {
        val entries = listOf(
            Expected(PUBLIC, "custom_time_format", true),
            Expected(PUBLIC, "com.whatsapp:custom_time_format", false),
        )
        val destination = TestContext()
        check(SettingsBackupCodec.decode(destination, json(entries, false).inputStream()) == 1)
        assertSettings(destination, listOf(Expected(PUBLIC, "segundos", true)))
    }
    test("scoped RGB/ARGB legacy values use the canonical color normalization") {
        val raw = listOf(Expected(PUBLIC, PERSONAL + "deleted_message_color", "#80112233"),
            Expected(PUBLIC, BUSINESS + "deleted_message_color", "#112233"))
        val expected = listOf(Expected(PUBLIC, PERSONAL + "deleted_message_color", 0x80112233.toInt()),
            Expected(PUBLIC, BUSINESS + "deleted_message_color", 0xff112233.toInt()))
        for (format in listOf("binary", "legacy", "versioned", "export")) {
            val bytes = when (format) {
                "binary" -> binary(raw)
                "export" -> ByteArrayOutputStream().also { output ->
                    val source = TestContext().also { seed(it, raw) }
                    check(SettingsBackupCodec.encode(source, output) == 2)
                }.toByteArray()
                else -> json(raw, format == "versioned")
            }
            val destination = TestContext()
            check(SettingsBackupCodec.decode(destination, bytes.inputStream()) == 2)
            assertSettings(destination, expected)
        }
    }
    test("schema store wins, historical other-store fallback survives, and import routes correctly") {
        val source = TestContext()
        source.seed(PUBLIC, PERSONAL + "imagequality", true)
        source.seed(PRIVATE, PERSONAL + "imagequality", false)
        source.seed(PRIVATE, PERSONAL + "custom_transcription_model", "private-wins")
        source.seed(PUBLIC, PERSONAL + "custom_transcription_model", "stale-public")
        source.seed(PUBLIC, BUSINESS + "custom_transcription_model", "legacy-location")
        val output = ByteArrayOutputStream()
        check(SettingsBackupCodec.encode(source, output) == 3)
        val destination = TestContext()
        check(SettingsBackupCodec.decode(destination, output.toByteArray().inputStream()) == 3)
        assertSettings(destination, listOf(
            Expected(PUBLIC, PERSONAL + "imagequality", true),
            Expected(PRIVATE, PERSONAL + "custom_transcription_model", "private-wins"),
            Expected(PRIVATE, BUSINESS + "custom_transcription_model", "legacy-location"),
        ))
    }
    test("restore is a merge and never synthesizes, flattens or deletes other target values") {
        val destination = TestContext()
        destination.seed(PUBLIC, "imagequality", false)
        destination.seed(PUBLIC, BUSINESS + "imagequality", false)
        destination.seed(PUBLIC, BUSINESS + "status_style", "unchanged")
        val entry = Expected(PUBLIC, PERSONAL + "imagequality", true)
        check(SettingsBackupCodec.decode(destination, binary(listOf(entry)).inputStream()) == 1)
        assertSettings(destination, listOf(entry, Expected(PUBLIC, "imagequality", false),
            Expected(PUBLIC, BUSINESS + "imagequality", false),
            Expected(PUBLIC, BUSINESS + "status_style", "unchanged")))
    }
    for (failedStore in listOf(PUBLIC, PRIVATE)) {
        for (writeBeforeFailure in listOf(false, true)) {
            test("rollback keeps scoped original types after $failedStore failure, memoryWrite=$writeBeforeFailure") {
                val destination = TestContext()
                val original = listOf(
                    Expected(PUBLIC, PERSONAL + "imagequality", "legacy-wrong-type"),
                    Expected(PRIVATE, PERSONAL + "custom_transcription_model", "original-model"),
                    Expected(PUBLIC, BUSINESS + "imagequality", false),
                )
                seed(destination, original)
                destination.failCommits(failedStore, 1, writeBeforeFailure)
                val incoming = listOf(Expected(PUBLIC, PERSONAL + "imagequality", true),
                    Expected(PRIVATE, PERSONAL + "custom_transcription_model", "replacement"),
                    Expected(PUBLIC, PERSONAL + "status_style", "new-key"))
                val error = runCatching { SettingsBackupCodec.decode(destination, binary(incoming).inputStream()) }.exceptionOrNull()
                check(error is IllegalStateException) { "Expected persistence failure, not skipped records" }
                check(error.message == "Backup restore failed; previous preferences restored")
                assertSettings(destination, original)
            }
        }
    }
    for (failedStore in listOf(PUBLIC, PRIVATE)) {
        test("failed compensating commit is reported for $failedStore, even if memory looks restored") {
            val destination = TestContext()
            val original = listOf(
                Expected(PUBLIC, PERSONAL + "imagequality", false),
                Expected(PRIVATE, PERSONAL + "custom_transcription_model", "original-model"),
                Expected(PUBLIC, BUSINESS + "imagequality", false),
            )
            seed(destination, original)
            destination.failCommits(failedStore, 2, true)
            val incoming = listOf(Expected(PUBLIC, PERSONAL + "imagequality", true),
                Expected(PRIVATE, PERSONAL + "custom_transcription_model", "replacement"))
            val error = runCatching { SettingsBackupCodec.decode(destination, binary(incoming).inputStream()) }.exceptionOrNull()
            check(error is IllegalStateException)
            check(error.message == "Backup restore failed and rollback could not restore all preferences")
            // The double models memory changing before a failed disk commit.
            // Observing the old in-memory values is not proof of durable recovery.
            assertSettings(destination, original)
        }
    }
    test("same canonical suffix in two packages is not a duplicate") {
        val entries = listOf(Expected(PUBLIC, PERSONAL + "imagequality", true),
            Expected(PUBLIC, BUSINESS + "imagequality", false))
        val destination = TestContext()
        check(SettingsBackupCodec.decode(destination, binary(entries).inputStream()) == 2)
        assertSettings(destination, entries)
    }
    test("an exact duplicate scoped binary key rejects the complete import before writes") {
        val destination = TestContext()
        val original = Expected(PUBLIC, PERSONAL + "imagequality", false)
        seed(destination, listOf(original))
        val repeated = Expected(PUBLIC, PERSONAL + "imagequality", true)
        val error = runCatching { SettingsBackupCodec.decode(destination, binary(listOf(repeated, repeated)).inputStream()) }.exceptionOrNull()
        check(error is IllegalArgumentException)
        assertSettings(destination, listOf(original))
    }
    test("10000 scoped records round-trip; 10001 fail before the export header") {
        val source = TestContext()
        val entries = (0 until 10000).map { Expected(PUBLIC, "com.fixture.p$it:imagequality", it % 2 == 0) }
        seed(source, entries)
        val output = ByteArrayOutputStream()
        check(SettingsBackupCodec.encode(source, output) == 10000)
        val destination = TestContext()
        check(SettingsBackupCodec.decode(destination, ByteArrayInputStream(output.toByteArray())) == 10000)
        assertSettings(destination, entries)
        source.seed(PUBLIC, "com.fixture.overflow:imagequality", true)
        val overflow = ByteArrayOutputStream()
        check(runCatching { SettingsBackupCodec.encode(source, overflow) }.exceptionOrNull() is IllegalArgumentException)
        check(overflow.size() == 0)
    }
    test("long target syntax is processed iteratively within the existing backup bound") {
        val entry = Expected(PUBLIC, "a.".repeat(20000) + "b:imagequality", true)
        val destination = TestContext()
        check(SettingsBackupCodec.decode(destination, binary(listOf(entry)).inputStream()) == 1)
        assertSettings(destination, listOf(entry))
    }
    println("SCOPED_RESULT: $passed/${passed + failed.size} cases passed; ${failed.size} failed")
    check(failed.isEmpty()) { "Scoped backup failures: ${failed.joinToString()}" }
}
