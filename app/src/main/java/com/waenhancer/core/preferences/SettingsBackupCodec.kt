package com.waenhancer.core.preferences

import android.content.Context
import com.waenhancer.config.PreferenceSchema
import com.waenhancer.config.PreferenceStores
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Versioned WAEX settings container. This is deliberately neither JSON nor XML. */
object SettingsBackupCodec {
    const val EXTENSION = "waex"
    const val MIME_TYPE = "application/vnd.waenhancer.settings"

    private val magic = byteArrayOf(0x57, 0x41, 0x45, 0x58, 0x43, 0x46, 0x47, 0x00) // WAEXCFG\0
    private const val VERSION = 1
    private const val MAX_ENTRIES = 10_000
    private const val MAX_BYTES = 8 * 1024 * 1024

    fun encode(context: Context, output: OutputStream): Int {
        val records = PreferenceSchema.exportableKeys().sorted().mapNotNull { key ->
            val value = PreferenceStores.read(context, key) ?: return@mapNotNull null
            val entry = PreferenceSchema.entry(key) ?: return@mapNotNull null
            normalize(entry.type, value)?.let { Record(key, entry.type, it) }
        }

        DataOutputStream(BufferedOutputStream(output)).use { out ->
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
        }
        return records.size
    }

    fun decode(context: Context, input: InputStream): Int {
        val records = DataInputStream(BufferedInputStream(input)).use { source ->
            val actualMagic = ByteArray(magic.size)
            source.readFully(actualMagic)
            require(actualMagic.contentEquals(magic)) { "Not a WAEX settings file" }
            require(source.readInt() == VERSION) { "Unsupported WAEX settings version" }
            source.readLong() // creation time, reserved for diagnostics
            val count = source.readInt()
            require(count in 0..MAX_ENTRIES) { "Invalid settings record count" }
            buildList(count) {
                repeat(count) {
                    val key = readText(source)
                    val typeOrdinal = source.readUnsignedByte()
                    val type = PreferenceSchema.Type.entries.getOrNull(typeOrdinal)
                        ?: throw IllegalArgumentException("Unknown settings value type")
                    val schema = PreferenceSchema.entry(key)
                    require(schema != null && schema.isExportable() && schema.type == type) {
                        "Invalid or non-exportable setting: $key"
                    }
                    val value: Any = when (type) {
                        PreferenceSchema.Type.BOOLEAN -> source.readBoolean()
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
                    add(Record(key, type, value))
                }
            }
        }

        // Parsing and validation finish before any preference is changed.
        val publicEditor = PreferenceStores.publicStore(context).edit()
        val privateEditor = PreferenceStores.privateStore(context).edit()
        records.forEach { record ->
            val schema = PreferenceSchema.entry(record.key)!!
            val editor = if (schema.store == PreferenceSchema.Store.PRIVATE) privateEditor else publicEditor
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
        check(privateEditor.commit() && publicEditor.commit()) { "Could not save imported settings" }
        return records.size
    }

    private fun writeText(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Settings value is too large" }
        out.writeInt(bytes.size)
        out.write(bytes)
    }

    private fun readText(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_BYTES) { "Invalid settings value length" }
        return ByteArray(size).also(input::readFully).toString(Charsets.UTF_8)
    }

    private fun normalize(type: PreferenceSchema.Type, value: Any): Any? = when (type) {
        PreferenceSchema.Type.BOOLEAN -> when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> null
        }
        PreferenceSchema.Type.STRING -> value.toString()
        PreferenceSchema.Type.STRING_SET -> (value as? Set<*>)?.filterIsInstance<String>()?.toSet()
        PreferenceSchema.Type.INT -> when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
        PreferenceSchema.Type.LONG -> when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        PreferenceSchema.Type.FLOAT -> when (value) {
            is Number -> value.toFloat()
            is String -> value.toFloatOrNull()
            else -> null
        }
    }

    private data class Record(val key: String, val type: PreferenceSchema.Type, val value: Any)
}
