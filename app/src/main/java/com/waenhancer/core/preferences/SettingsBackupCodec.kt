package com.waenhancer.core.preferences

import android.content.Context
import com.waenhancer.config.PreferenceSchema
import com.waenhancer.config.PreferenceStores
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale
import android.content.SharedPreferences
import java.io.ByteArrayInputStream
import java.nio.CharBuffer

/**
 * Versioned WAEX settings container with support for both binary (.waex) and legacy JSON backups.
 */
object SettingsBackupCodec {
    const val EXTENSION = "waex"
    const val MIME_TYPE = "application/vnd.waenhancer.settings"

    private val magic = byteArrayOf(0x57, 0x41, 0x45, 0x58, 0x43, 0x46, 0x47, 0x00) // WAEXCFG\0
    private const val VERSION = 1
    private const val MAX_ENTRIES = 10_000
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_JSON_DEPTH = 64

    private val BLOCKED_KEYS = Collections.unmodifiableSet(
        hashSetOf(
            "keybox", "keybox_xml", "private_key", "certificate", "certificates",
            "license_key", "license_token", "tg_username", "gh_username", "encrypted_config",
            "is_pro_verified", "expires_at", "pro_plugin_path", "pro_plugin_lib_path",
            "github_token", "gh_public_token", "tasker_secret", "installation_id",
            "module_heartbeat", "last_crash", "crash_stacktrace"
        )
    )

    private val LEGACY_ALIASES: Map<String, String> = mapOf(
        // General & Theme aliases
        "colors_customization" to "changecolor",
        "custom_primary_color" to "primary_color",
        "showname" to "shownamehome",
        "showbio" to "showbiohome",
        "show_name_home" to "shownamehome",
        "show_bio_home" to "showbiohome",
        "separate_groups" to "separategroups",
        "separate_groups_counter" to "separategroups_counter_type",
        "separate_groups_tabs" to "separategroups",
        "statusdowload" to "downloadstatus",
        "download_status" to "downloadstatus",
        "status_downloader" to "downloadstatus",
        "locked_chats_enhancer" to "lockedchats_enhancer",
        "show_message_device_source" to "message_device_source",
        "toast_viewer" to "toast_viewed_message",
        "audio_transcript" to "audio_transcription",
        "voice_transcription" to "audio_transcription",
        "voice_status_enhancement" to "send_audio_as_voice_status",
        "freeze_last_seen" to "freezelastseen",
        "ghostmode_enabled" to "ghostmode",
        "bubble_colors" to "bubble_color",
        "custom_theme" to "custom_css",
        "custom_time_format" to "segundos",
        "custom_toolbar" to "novaconfig",
        "custom_view_dpi" to "change_dpi",
        "seen_tick_customization" to "seentick",
        "channels_enhancements" to "removechannel_rec",
        "chat_filters" to "chatfilter",
        "hide_ui_tabs" to "hidetabs",
        "instagram_status_layout" to "igstatus",
        "quick_home_menu" to "show_home_menu",
        "contact_blocked_verify" to "verify_blocked_contact",
        "miscellaneous_enhancements" to "disable_sensor_proximity",
        "high_res_media" to "imagequality",
        "direct_chat_button" to "newchat",
        "status_caption_copy" to "copystatus",
        "copy_status_text" to "copystatus",
        "jump_to_first_message" to "go_to_first_message",
        "dnd_mode" to "show_dndmode",
        "stealth_view_status" to "hidestatusview",
        "stealth_status_view" to "hidestatusview",
        "anti_view_once" to "downloadviewonce",
        "anti_view_once_media" to "downloadviewonce",
        "anti_edit_messages" to "antieditmessages",
        "anti_revoke" to "antirevoke",
        "online_status_indicator" to "dotonline",
        "unlimited_pinned_chats" to "pinnedlimit",
        "chat_limits_bypass" to "removeforwardlimit",
        "unlimited_video_status" to "status_video_splitter",
        "status_text_composer" to "status_style",
        "voice_note_speed_modifier" to "voicenote_speed",
        "group_admin_tools" to "admin_grp",
        "hide_chats" to "lockedchats_enhancer",
        "hide_read_receipts" to "hideread",
        "hide_delivery_receipts" to "hidereceipt_ios",
        "hide_forwarded_tag" to "hidetag",
        "tasker_integration" to "tasker",
        "sticker_confirm_alert" to "alertsticker",
        "floating_nav_bar" to "floating_bottom_bar",
        "floating_bottom_bar_scroll_hide" to "floating_bottom_bar_scroll_hide_mode",
        "call_recording_enable" to "call_recording_enabled"
    )

    fun encode(context: Context, output: OutputStream): Int =
        DataOutputStream(BufferedOutputStream(output)).use { out ->
            // Enumerate persisted keys, not only the unqualified schema catalogue:
            // target overrides are stored as package:key. Keep each complete key
            // and use its canonical definition only for type/store/sensitivity.
            val publicValues = PreferenceStores.publicStore(context).all
            val privateValues = PreferenceStores.privateStore(context).all
            val records = (publicValues.keys + privateValues.keys).sorted().mapNotNull { key ->
                val entry = backupEntry(key) ?: return@mapNotNull null
                val value = if (entry.store == PreferenceSchema.Store.PRIVATE) {
                    privateValues[key] ?: publicValues[key]
                } else {
                    publicValues[key] ?: privateValues[key]
                } ?: return@mapNotNull null
                normalizeForKey(entry.key, entry.type, value)?.let { Record(key, entry.type, it) }
            }
            // Validate the complete container before emitting a header. Otherwise
            // encode() could succeed with a file decode() necessarily rejects.
            validateEncodedSize(records)
            out.write(magic)
            out.writeInt(VERSION)
            out.writeLong(System.currentTimeMillis())
            out.writeInt(records.size)
            records.forEach { record ->
                writeText(out, record.key)
                out.writeByte(record.type.ordinal)
                when (record.type) {
                    PreferenceSchema.Type.BOOLEAN -> out.writeBoolean(record.value as Boolean)
                    PreferenceSchema.Type.STRING -> writeText(out, record.value as String)
                    PreferenceSchema.Type.STRING_SET -> {
                        val values = (record.value as Set<*>).filterIsInstance<String>().sorted()
                        out.writeInt(values.size)
                        values.forEach { writeText(out, it) }
                    }
                    PreferenceSchema.Type.INT -> out.writeInt((record.value as Number).toInt())
                    PreferenceSchema.Type.LONG -> out.writeLong((record.value as Number).toLong())
                    PreferenceSchema.Type.FLOAT -> out.writeFloat((record.value as Number).toFloat())
                }
            }
            records.size
        }

    /**
     * Avoid InputStream.readBytes(): an oversized/malicious backup could exhaust
     * process memory *before* the post-read MAX_BYTES check. Read at most one
     * byte beyond the declared limit to distinguish exact-size EOF from overflow.
     */
    private fun readBoundedBackup(input: InputStream): ByteArray = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var consumed = 0
        while (true) {
            val allowed = minOf(buffer.size, MAX_BYTES + 1 - consumed)
            val count = stream.read(buffer, 0, allowed)
            if (count < 0) break
            if (count == 0) {
                // An unusual InputStream may make no progress on bulk reads.
                val single = stream.read()
                if (single < 0) break
                output.write(single)
                consumed++
            } else {
                output.write(buffer, 0, count)
                consumed += count
            }
            require(consumed <= MAX_BYTES) { "Backup exceeds the allowed size limit" }
        }
        output.toByteArray()
    }

    fun decode(context: Context, input: InputStream): Int {
        val bytes = readBoundedBackup(input)
        require(bytes.isNotEmpty()) { "Backup is empty" }

        val records = if (bytes.size >= magic.size && bytes.take(magic.size).toByteArray().contentEquals(magic)) {
            decodeBinary(bytes)
        } else {
            decodeJson(bytes)
        }

        require(records.isNotEmpty()) { "No valid settings found in backup" }

        val publicStore = PreferenceStores.publicStore(context)
        val privateStore = PreferenceStores.privateStore(context)
        // Preserve original *runtime* types, including legacy wrong-typed
        // entries. Capture only touched keys and clone string sets so changes
        // during the import cannot silently mutate our rollback snapshots.
        val publicBefore = snapshotForRollback(publicStore.all)
        val privateBefore = snapshotForRollback(privateStore.all)
        val publicKeys = LinkedHashSet<String>()
        val privateKeys = LinkedHashSet<String>()
        val publicEditor = publicStore.edit()
        val privateEditor = privateStore.edit()
        records.forEach { record ->
            val schema = backupEntry(record.key)!!
            val isPrivate = schema.store == PreferenceSchema.Store.PRIVATE
            val editor = if (isPrivate) privateEditor else publicEditor
            if (isPrivate) privateKeys.add(record.key) else publicKeys.add(record.key)
            when (record.type) {
                PreferenceSchema.Type.BOOLEAN -> editor.putBoolean(record.key, record.value as Boolean)
                PreferenceSchema.Type.STRING -> editor.putString(record.key, record.value as String)
                PreferenceSchema.Type.STRING_SET -> @Suppress("UNCHECKED_CAST")
                editor.putStringSet(record.key, record.value as Set<String>)
                PreferenceSchema.Type.INT -> editor.putInt(record.key, record.value as Int)
                PreferenceSchema.Type.LONG -> editor.putLong(record.key, record.value as Long)
                PreferenceSchema.Type.FLOAT -> editor.putFloat(record.key, record.value as Float)
            }
        }

        // SharedPreferences has no atomic multi-file commit. When either
        // commit fails, attempt to restore the keys written to BOTH stores.
        // Never report a successful import after a partially committed backup.
        var privateAttempted = false
        var publicAttempted = false
        try {
            if (privateKeys.isNotEmpty()) {
                privateAttempted = true
                check(privateEditor.commit()) { "Could not save imported private settings" }
            }
            if (publicKeys.isNotEmpty()) {
                publicAttempted = true
                check(publicEditor.commit()) { "Could not save imported public settings" }
            }
        } catch (failure: Exception) {
            val publicRecovered = !publicAttempted ||
                    restoreTouchedKeys(publicStore, publicBefore, publicKeys)
            val privateRecovered = !privateAttempted ||
                    restoreTouchedKeys(privateStore, privateBefore, privateKeys)
            check(publicRecovered && privateRecovered) {
                "Backup restore failed and rollback could not restore all preferences"
            }
            throw IllegalStateException("Backup restore failed; previous preferences restored", failure)
        }
        return records.size
    }

    private fun snapshotForRollback(values: Map<String, *>): Map<String, Any?> =
        values.mapValues { (_, value) ->
            if (value is Set<*>) value.filterIsInstance<String>().toSet() else value
        }

    /** Best-effort compensation for a failed multi-store commit, limited to touched keys. */
    private fun restoreTouchedKeys(
        store: SharedPreferences,
        original: Map<String, Any?>,
        touched: Set<String>
    ): Boolean {
        if (touched.isEmpty()) return true
        return try {
            val editor = store.edit()
            for (key in touched) {
                if (!original.containsKey(key)) {
                    editor.remove(key)
                    continue
                }
                when (val value = original[key]) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    else -> return false
                }
            }
            editor.commit()
        } catch (ignored: Exception) {
            false
        }
    }

    private fun decodeBinary(bytes: ByteArrayInputStream.() -> Unit = {}): List<Record> {
        throw UnsupportedOperationException()
    }

    private fun decodeBinary(bytes: ByteArray): List<Record> {
        return DataInputStream(BufferedInputStream(bytes.inputStream())).use { source ->
            val actualMagic = ByteArray(magic.size)
            source.readFully(actualMagic)
            require(actualMagic.contentEquals(magic)) { "Not a WAEX settings file" }
            require(source.readInt() == VERSION) { "Unsupported WAEX settings version" }
            source.readLong() // creation time
            val count = source.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid settings record count" }
            val seenKeys = HashSet<String>()
            val records = buildList(count) {
                repeat(count) {
                    val rawKey = readText(source)
                    require(seenKeys.add(rawKey)) { "Duplicate settings record key" }
                    // Binary WAEX backups already contain canonical schema keys.
                    // Never reinterpret a valid key through a legacy JSON alias.
                    val key = resolveKey(rawKey, legacyJson = false)
                    val typeOrdinal = source.readUnsignedByte()
                    val type = PreferenceSchema.Type.entries.getOrNull(typeOrdinal)
                        ?: throw IllegalArgumentException("Unknown settings value type")

                    val value: Any = when (type) {
                        PreferenceSchema.Type.BOOLEAN -> {
                            val value = source.readUnsignedByte()
                            require(value in 0..1) { "Invalid Boolean settings value" }
                            value == 1
                        }
                        PreferenceSchema.Type.STRING -> readText(source)
                        PreferenceSchema.Type.STRING_SET -> {
                            val size = source.readInt()
                            require(size in 0..MAX_ENTRIES) { "Invalid string-set size" }
                            buildSet(size) { repeat(size) { add(readText(source)) } }
                        }
                        PreferenceSchema.Type.INT -> source.readInt()
                        PreferenceSchema.Type.LONG -> source.readLong()
                        PreferenceSchema.Type.FLOAT -> source.readFloat()
                    }

                    if (key != null) {
                        val schema = backupEntry(key)
                        if (schema != null) {
                            val normalized = normalizeForKey(schema.key, schema.type, value)
                            if (normalized != null) {
                                add(Record(key, schema.type, normalized))
                            }
                        }
                    }
                }
            }
            require(source.read() == -1) { "Unexpected data after settings records" }
            records
        }
    }

    private fun decodeJson(bytes: ByteArray): List<Record> {
        val text = decodeUtf8(bytes)
        checkJsonNesting(text)
        val root = try {
            val parser = JSONTokener(text)
            val value = parser.nextValue()
            require(value is JSONObject) { "Backup JSON root must be an object" }
            require(parser.nextClean() == '\u0000') { "Unexpected data after backup JSON" }
            value
        } catch (e: JSONException) {
            throw IllegalArgumentException("Backup is neither a valid WAEX binary nor a valid JSON backup file", e)
        }

        val legacy = !root.has("schemaVersion")
        val source: JSONObject = if (legacy) {
            root
        } else {
            val version = root.opt("schemaVersion")
            require((version is Int || version is Long) && (version as Number).toLong() == VERSION.toLong()) {
                "Unsupported backup JSON schema version"
            }
            root.optJSONObject("settings") ?: throw IllegalArgumentException("Backup JSON does not contain settings object")
        }
        require(source.length() <= MAX_ENTRIES) { "Invalid settings record count" }

        val records = mutableListOf<Record>()
        val keys = source.keys()
        while (keys.hasNext()) {
            val rawKey = keys.next()
            // Only unversioned v1 JSON files use the old alias migration rules.
            val key = resolveKey(rawKey, legacyJson = legacy) ?: continue
            // A legacy alias must never overwrite a modern canonical setting
            // when both are present in one unversioned JSON backup, regardless
            // of the JSONObject key iteration order.
            if (legacy && rawKey != key && source.has(key)) continue
            val schema = backupEntry(key) ?: continue
            if (isSensitive(schema.key)) continue

            val rawVal = source.opt(rawKey) ?: continue
            val decodedValue = decodeJsonValue(rawVal, legacy) ?: continue
            val normalized = normalizeForKey(schema.key, schema.type, decodedValue) ?: continue
            records.add(Record(key, schema.type, normalized))
        }
        return records
    }

    private fun decodeJsonValue(raw: Any, legacy: Boolean): Any? {
        if (raw === JSONObject.NULL) return null
        if (raw is JSONObject) {
            val type = raw.optString("type", "").lowercase(Locale.ROOT)
            val value = raw.opt("value") ?: return null
            if (value === JSONObject.NULL) return null
            return when (type) {
                "boolean" -> normalize(PreferenceSchema.Type.BOOLEAN, value)
                "integer", "int" -> normalize(PreferenceSchema.Type.INT, value)
                "long" -> normalize(PreferenceSchema.Type.LONG, value)
                "double", "float" -> normalize(PreferenceSchema.Type.FLOAT, value)
                "hashset", "string_set", "jsonarray" -> toStringSet(value)
                "string" -> value as? String
                else -> null
            }
        }
        // Android's NULL sentinel and containers must not become literal strings
        // or false Booleans. Arrays are valid sets in both JSON formats.
        return when (raw) {
            is JSONArray -> toStringSet(raw)
            is Boolean, is Number, is String -> raw
            else -> null
        }
    }

    private fun toStringSet(value: Any): Set<String>? {
        val items: List<*> = when (value) {
            is JSONArray -> {
                require(value.length() <= MAX_ENTRIES) { "Invalid string-set size" }
                List(value.length()) { value.opt(it) }
            }
            is Set<*> -> value.toList()
            is List<*> -> value
            is String -> if (value.isEmpty()) emptyList() else value.split(",").map { it.trim() }
            else -> return null
        }
        require(items.size <= MAX_ENTRIES) { "Invalid string-set size" }
        if (items.any { it !is String }) return null
        return items.filterIsInstance<String>().toCollection(LinkedHashSet())
    }

    /** A bounded lexical preflight, not a replacement JSON parser. */
    private fun checkJsonNesting(text: String) {
        require('\u0000' !in text) { "Unexpected NUL in backup JSON" }
        var depth = 0
        var quote = '\u0000'
        var lineComment = false
        var blockComment = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val next = text.getOrNull(i + 1)
            if (lineComment) {
                if (c == '\n' || c == '\r') lineComment = false
            } else if (blockComment) {
                if (c == '*' && next == '/') { blockComment = false; i++ }
            } else if (quote != '\u0000') {
                if (c == '\\') i++ else if (c == quote) quote = '\u0000'
            } else {
                when {
                    c == '"' || c == '\'' -> quote = c
                    c == '#' -> lineComment = true
                    c == '/' && next == '/' -> { lineComment = true; i++ }
                    c == '/' && next == '*' -> { blockComment = true; i++ }
                    c == '{' || c == '[' -> {
                        depth++
                        require(depth <= MAX_JSON_DEPTH) { "Backup JSON nesting is too deep" }
                    }
                    c == '}' || c == ']' -> depth--
                }
            }
            i++
        }
    }

    /**
     * Target qualification never grants exportability to an unknown/secret key.
     * Accept Android application IDs without requiring installation, so backups
     * remain portable. A package scope is not an Android user/profile identity.
     * Only the unqualified configuration-mode key has meaning in production.
     */
    private fun backupEntry(key: String): PreferenceSchema.Entry? {
        PreferenceSchema.entry(key)?.let { return it.takeIf { entry -> entry.isExportable() } }
        val separator = key.indexOf(':')
        if (separator <= 0 || separator == key.lastIndex || key.indexOf(':', separator + 1) >= 0) return null
        val target = key.substring(0, separator)
        if (!isTargetPackage(target)) return null
        val canonical = key.substring(separator + 1)
        if (canonical == "module_config_mode") return null
        val entry = PreferenceSchema.entry(canonical)
        return if (entry != null && entry.isExportable()) entry else null
    }

    // Iterative validation avoids regex recursion on an untrusted, long key.
    private fun isTargetPackage(value: String): Boolean {
        var segmentStart = true
        var hasSeparator = false
        for (char in value) {
            if (char == '.') {
                if (segmentStart) return false
                hasSeparator = true
                segmentStart = true
            } else {
                val letter = char in 'a'..'z' || char in 'A'..'Z'
                if (segmentStart && !letter) return false
                if (!letter && char !in '0'..'9' && char != '_') return false
                segmentStart = false
            }
        }
        return hasSeparator && !segmentStart
    }

    private fun resolveKey(originalKey: String, legacyJson: Boolean): String? {
        // Every key already present in the schema retains its OWN meaning, even
        // in old unversioned JSON backups. Same-type legacy aliases can denote
        // unrelated features (e.g. typing_online_toasts vs typing_privacy, or
        // hide_seen_receipts vs hidereceipt). Rewriting them changes privacy
        // settings without the user's intention.
        if (PreferenceSchema.entry(originalKey) != null) return originalKey
        if (backupEntry(originalKey) != null) return originalKey

        // Only truly unknown old keys may migrate, and only from legacy JSON.
        // New binary/JSON backup formats must never guess missing key names.
        if (!legacyJson) return null
        val mappedKey = LEGACY_ALIASES[originalKey] ?: return null
        return mappedKey.takeIf { PreferenceSchema.entry(it) != null }
    }

    private fun isSensitive(key: String?): Boolean {
        if (key == null) return true
        if (PreferenceSchema.isSecret(key)) return true
        val normalized = key.lowercase(Locale.ROOT)
        if (BLOCKED_KEYS.contains(normalized)) return true
        return normalized.contains("private_key") ||
                normalized.contains("secret") ||
                normalized.contains("token") ||
                normalized.contains("certificate") ||
                normalized.contains("keybox") ||
                normalized.contains("license") ||
                normalized.contains("plugin_path") ||
                normalized.contains("classloader") ||
                normalized.contains("heartbeat") ||
                normalized.contains("crash_stack")
    }

    private fun writeText(out: DataOutputStream, value: String) {
        val bytes = encodeUtf8(value)
        require(bytes.size <= MAX_BYTES) { "Settings value is too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_BYTES) { "Invalid settings value length" }
        return decodeUtf8(ByteArray(size).also(input::readFully))
    }

    /** Restore hex RGB/ARGB strings written by earlier UI versions for this INT key. */
    private fun normalizeForKey(key: String, type: PreferenceSchema.Type, value: Any): Any? {
        if (key == "deleted_message_color" && type == PreferenceSchema.Type.INT && value is String) {
            val hex = value.trim()
            if (hex.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) {
                val parsed = hex.substring(1).toLong(16)
                return (if (hex.length == 7) parsed or 0xFF000000L else parsed).toInt()
            }
        }
        return normalize(type, value)
    }

    private fun normalize(type: PreferenceSchema.Type, value: Any): Any? = when (type) {
        PreferenceSchema.Type.BOOLEAN -> when (value) {
            is Boolean -> value
            is Number -> when (exactLong(value)) { 0L -> false; 1L -> true; else -> null }
            is String -> when (value.trim().lowercase(Locale.ROOT)) {
                "true", "1" -> true
                "false", "0" -> false
                else -> null
            }
            else -> null
        }
        PreferenceSchema.Type.STRING -> value as? String
        PreferenceSchema.Type.STRING_SET -> toStringSet(value)
        PreferenceSchema.Type.INT -> exactLong(value)?.takeIf {
            it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
        }?.toInt()
        PreferenceSchema.Type.LONG -> exactLong(value)
        PreferenceSchema.Type.FLOAT -> when (value) {
            is Number -> value.toFloat().takeIf { it.isFinite() }
            is String -> value.toFloatOrNull()?.takeIf { it.isFinite() }
            else -> null
        }
    }

    /** Never truncate fractional integers, wrap overflow, or round Long via Double. */
    private fun exactLong(value: Any): Long? {
        if (value !is Number && value !is String) return null
        val text = value.toString().trim()
        if (text.length > 128) return null
        return try {
            BigDecimal(text).longValueExact()
        } catch (invalid: NumberFormatException) {
            null
        } catch (outOfRange: ArithmeticException) {
            null
        }
    }

    private fun encodeUtf8(value: String): ByteArray {
        val bytes = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(value))
        return ByteArray(bytes.remaining()).also { bytes.get(it) }
    }

    private fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun validateEncodedSize(records: List<Record>) {
        require(records.size <= MAX_ENTRIES) { "Invalid settings record count" }
        var total = magic.size.toLong() + 4 + 8 + 4
        fun textSize(value: String): Long = 4L + encodeUtf8(value).size
        for (record in records) {
            total += textSize(record.key) + 1
            total += when (record.type) {
                PreferenceSchema.Type.BOOLEAN -> 1L
                PreferenceSchema.Type.STRING -> textSize(record.value as String)
                PreferenceSchema.Type.STRING_SET -> {
                    val values = record.value as Set<*>
                    require(values.size <= MAX_ENTRIES) { "Invalid string-set size" }
                    4L + values.sumOf { textSize(it as String) }
                }
                PreferenceSchema.Type.INT, PreferenceSchema.Type.FLOAT -> 4L
                PreferenceSchema.Type.LONG -> 8L
            }
            require(total <= MAX_BYTES) { "Backup exceeds the allowed size limit" }
        }
    }

    private data class Record(val key: String, val type: PreferenceSchema.Type, val value: Any)
}
