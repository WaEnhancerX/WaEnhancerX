package com.waenhancer.core.preferences

import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.util.Random
import org.json.JSONArray

import android.content.TestContext
import com.waenhancer.config.PreferenceSchema
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.json.JSONObject

private var checks = 0
private val failures = mutableListOf<String>()
private fun test(name: String, action: () -> Unit) {
    checks++
    try { action(); println("PASS: $name") }
    catch (t: Throwable) { failures.add(name); println("FAIL: $name (${t.javaClass.simpleName})") }
}
private fun target() = TestContext().also {
    it.seed("waex_prefs", "always_online", true)
    it.seed("waex_prefs", "hide_read_receipts", true)
    it.seed("waex_prefs", "css_theme", "original")
    it.seed("waex_prefs", "anti_revoke_indicator", 7)
    it.seed("waex_prefs", "hidetabs", setOf("300"))
}
private fun decode(text: String, context: TestContext = target()): TestContext {
    SettingsBackupCodec.decode(context, text.byteInputStream())
    return context
}
private fun value(key: String, jsonValue: String, expected: Any) {
    val context = decode("{\"$key\":$jsonValue}")
    check(context.get("waex_prefs", key) == expected)
}
private fun rejects(bytes: ByteArray) {
    val context = target()
    val error = runCatching { SettingsBackupCodec.decode(context, ByteArrayInputStream(bytes)) }.exceptionOrNull()
    check(error is IllegalArgumentException || error is IllegalStateException || error is IOException) { "Must fail with a controlled validation error" }
    check(context.get("waex_prefs", "hide_read_receipts") == true)
    check(context.get("waex_prefs", "always_online") == true)
    check(context.get("waex_prefs", "css_theme") == "original")
    check(context.get("waex_prefs", "anti_revoke_indicator") == 7)
    check(context.get("waex_prefs", "hidetabs") == setOf("300"))
    check(context.count() == 5)
}
private fun rejects(text: String) = rejects(text.toByteArray())
private fun binary(key: String, ordinal: Int, write: DataOutputStream.() -> Unit): ByteArray {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use {
        it.write(byteArrayOf(87,65,69,88,67,70,71,0)); it.writeInt(1); it.writeLong(0); it.writeInt(1)
        val encoded = key.toByteArray(); it.writeInt(encoded.size); it.write(encoded); it.writeByte(ordinal); it.write()
    }
    return bytes.toByteArray()
}
fun main() {
    test("actual AOSP parser handles escaped Unicode and arrays") {
        val parsed=JSONObject("{\"a\":[\"x\",\"\\u00e9\"],\"n\":null}")
        check(parsed.getJSONArray("a").getString(1)=="é"); check(parsed.isNull("n"))
    }
    for ((raw, expected) in listOf("true" to true,"false" to false,"\"TRUE\"" to true,"\"false\"" to false,"1" to true,"0" to false)) {
        test("legacy boolean $raw") { value("hide_read_receipts",raw,expected) }
        test("wrapped boolean $raw") { value("hide_read_receipts","{\"type\":\"boolean\",\"value\":$raw}",expected) }
    }
    for (raw in listOf("\"not-a-boolean\"","\"\"","2","-1","0.5","null","[]","{}")) {
        test("reject invalid direct Boolean $raw") { rejects("{\"hide_read_receipts\":$raw}") }
        test("reject invalid wrapped Boolean $raw") { rejects("{\"hide_read_receipts\":{\"type\":\"boolean\",\"value\":$raw}}") }
    }
    test("null is not a literal string") { rejects("{\"css_theme\":null}") }
    test("wrapped null is not a literal string") { rejects("{\"css_theme\":{\"type\":\"string\",\"value\":null}}") }
    test("object is not CSS text") { rejects("{\"css_theme\":{\"type\":\"string\",\"value\":{\"x\":1}}}") }
    test("unknown wrapper type rejected") { rejects("{\"css_theme\":{\"type\":\"unknown\",\"value\":\"bad\"}}") }
    test("versioned string set accepts actual array") { check(decode("{\"schemaVersion\":1,\"settings\":{\"hidetabs\":[\"300\",\"600\"]}}").get("waex_prefs","hidetabs")==setOf("300","600")) }
    test("legacy string set accepts actual array") { value("hidetabs","[\"300\",\"600\"]",setOf("300","600")) }
    test("legacy wrapped set") { value("hidetabs","{\"type\":\"hashset\",\"value\":[\"300\",\"600\"]}",setOf("300","600")) }
    test("legacy CSV set") { value("hidetabs","\"300,600\"",setOf("300","600")) }
    for (bad in listOf("null", "7", "true", "{}", "[]")) {
        test("invalid set element $bad") { rejects("{\"hidetabs\":[\"300\",$bad]}") }
    }
    for (raw in listOf("2147483648","-2147483649","1.5","\"NaN\"","\"Infinity\"","\"1e100\"")) {
        test("invalid integer $raw") { rejects("{\"anti_revoke_indicator\":$raw}") }
        test("invalid wrapped integer $raw") { rejects("{\"anti_revoke_indicator\":{\"type\":\"int\",\"value\":$raw}}") }
    }
    test("integer exact maximum") { value("anti_revoke_indicator","2147483647",Int.MAX_VALUE) }
    test("integer exact minimum") { value("anti_revoke_indicator","-2147483648",Int.MIN_VALUE) }
    test("legacy integral decimal string") { value("anti_revoke_indicator","\"12.0\"",12) }
    test("legacy ARGB color") { value("deleted_message_color","\"#80112233\"",0x80112233.toInt()) }
    val normalize = SettingsBackupCodec::class.java.declaredMethods.single { it.name=="normalize" }
    normalize.isAccessible=true
    test("wrapped LONG retains exact large string without Double conversion") {
        val method=SettingsBackupCodec::class.java.declaredMethods.single { it.name=="decodeJsonValue" }
        method.isAccessible=true
        check(method.invoke(SettingsBackupCodec,JSONObject("{\"type\":\"long\",\"value\":\"9007199254740993\"}"),true)==9007199254740993L)
    }
    test("LONG overflow rejected by normalization") { check(normalize.invoke(SettingsBackupCodec,PreferenceSchema.Type.LONG,"9223372036854775808")==null) }
    for(raw in listOf("\"NaN\"","\"Infinity\"","\"-Infinity\"","1e100")) {
        test("nonfinite Float $raw") { rejects("{\"floating_bottom_bar_glass_opacity\":$raw}") }
    }
    for (version in listOf("2","-1","null","true","\"1\"","1.5")) {
        test("unsupported/malformed schemaVersion $version") { rejects("{\"schemaVersion\":$version,\"settings\":{\"hide_read_receipts\":false}}") }
    }
    test("known schemaVersion accepted") { check(decode("{\"schemaVersion\":1,\"settings\":{\"hide_read_receipts\":false}}").get("waex_prefs","hide_read_receipts")==false) }
    test("trailing JSON object rejected before writes") { rejects("{\"hide_read_receipts\":false}{}") }
    test("trailing JSON text rejected before writes") { rejects("{\"hide_read_receipts\":false}junk") }
    test("trailing JSON comment retained") { check(decode("{\"hide_read_receipts\":false} /* old comment */").get("waex_prefs","hide_read_receipts")==false) }
    test("UTF8 BOM retained") { check(decode("\uFEFF{\"hide_read_receipts\":false}").get("waex_prefs","hide_read_receipts")==false) }
    test("invalid UTF8 rejected") { rejects("{\"css_theme\":\"".toByteArray()+byteArrayOf(0xc3.toByte(),0x28)+"\"}".toByteArray()) }
    test("excessive nesting rejected with validation exception, not StackOverflowError") { rejects("{\"unknown\":"+"[".repeat(4096)+"0"+"]".repeat(4096)+",\"hide_read_receipts\":false}") }
    test("brackets and quotes inside strings do not count toward nesting") { value("css_theme",JSONObject.quote("[{}]".repeat(256)+"\"quoted\""),"[{}]".repeat(256)+"\"quoted\"") }
    test("brackets in comments do not count toward nesting") { check(decode("{/*"+"[".repeat(256)+"*/\"hide_read_receipts\":false}").get("waex_prefs","hide_read_receipts")==false) }
    test("excessive JSON entries rejected") { rejects((0..10000).joinToString(prefix="{",postfix=",\"hide_read_receipts\":false}") { "\"unknown$it\":0" }) }
    test("excessive JSON set rejected") { rejects("{\"hidetabs\":"+(0..10000).joinToString(prefix="[",postfix="]}") { "\"$it\"" }) }
    test("canonical legacy key wins in both orders") {
        for (text in listOf("{\"custom_time_format\":true,\"segundos\":false}","{\"segundos\":false,\"custom_time_format\":true}")) {
            check(decode(text).get("waex_prefs","segundos")==false)
        }
    }
    test("typing and audio privacy keys retain independent meanings") {
        val c=decode("{\"typing_online_toasts\":true,\"typing_privacy\":false,\"hide_seen_receipts\":true,\"hidereceipt\":false}")
        check(c.get("waex_prefs","typing_online_toasts")==true && c.get("waex_prefs","typing_privacy")==false)
        check(c.get("waex_prefs","hide_seen_receipts")==true && c.get("waex_prefs","hidereceipt")==false)
    }
    test("invalid value cannot overwrite valid setting during mixed restore") {
        val c=decode("{\"always_online\":false,\"hide_read_receipts\":\"broken\"}")
        check(c.get("waex_prefs","always_online")==false && c.get("waex_prefs","hide_read_receipts")==true)
    }
    test("valid binary round trip still works") {
        val source=target(); val bytes=ByteArrayOutputStream(); val count=SettingsBackupCodec.encode(source,bytes)
        val dest=TestContext();check(SettingsBackupCodec.decode(dest,ByteArrayInputStream(bytes.toByteArray()))==count)
        check(dest.get("waex_prefs","hidetabs")==setOf("300"))
    }
    test("binary trailing byte rejected") { rejects(binary("hide_read_receipts",PreferenceSchema.Type.BOOLEAN.ordinal){writeBoolean(false)}+byteArrayOf(0)) }
    test("binary noncanonical Boolean rejected") { rejects(binary("hide_read_receipts",PreferenceSchema.Type.BOOLEAN.ordinal){writeByte(2)}) }
    test("binary invalid UTF8 rejected") { rejects(binary("css_theme",PreferenceSchema.Type.STRING.ordinal){writeInt(2);write(byteArrayOf(0xc3.toByte(),0x28))}) }
    test("binary truncated input never commits") {
        val b=binary("hide_read_receipts",PreferenceSchema.Type.BOOLEAN.ordinal){writeBoolean(false)}
        for(i in b.indices) rejects(b.copyOf(i))
    }
    test("export does not create an over-limit unreadable backup") {
        val c=TestContext(); c.seed("waex_prefs","css_theme","x".repeat(4_300_000));c.seed("waex_prefs","custom_css","y".repeat(4_300_000))
        val bytes=ByteArrayOutputStream();check(runCatching{SettingsBackupCodec.encode(c,bytes)}.exceptionOrNull() is IllegalArgumentException); check(bytes.size()==0)
    }
    test("export does not create unreadable oversized string set") {
        val c=TestContext();c.seed("waex_prefs","hidetabs",(0..10000).map{it.toString()}.toSet())
        val bytes=ByteArrayOutputStream();check(runCatching{SettingsBackupCodec.encode(c,bytes)}.exceptionOrNull() is IllegalArgumentException);check(bytes.size()==0)
    }
    test("binary duplicate preference rejected before mutation") {
        val one=binary("hide_read_receipts",PreferenceSchema.Type.BOOLEAN.ordinal){writeBoolean(false)}
        val duplicate=one.copyOf().also { it[23]=2 } + one.copyOfRange(24,one.size)
        rejects(duplicate)
    }
    test("binary invalid ordinal rejected") { rejects(binary("hide_read_receipts",255){writeBoolean(false)}) }
    test("binary negative string length rejected") { rejects(binary("css_theme",PreferenceSchema.Type.STRING.ordinal){writeInt(-1)}) }
    test("binary excessive string length rejected") { rejects(binary("css_theme",PreferenceSchema.Type.STRING.ordinal){writeInt(Int.MAX_VALUE)}) }
    test("binary excessive set count rejected") { rejects(binary("hidetabs",PreferenceSchema.Type.STRING_SET.ordinal){writeInt(10001)}) }
    test("binary nonfinite Float does not overwrite settings") { rejects(binary("floating_bottom_bar_glass_opacity",PreferenceSchema.Type.FLOAT.ordinal){writeFloat(Float.NaN)}) }
    test("raw NUL cannot hide trailing JSON data") { rejects("{\"hide_read_receipts\":false}\u0000junk") }
    test("export closes stream on validation failure") {
        val c=TestContext();c.seed("waex_prefs","hidetabs",(0..10000).map{it.toString()}.toSet())
        var closed=false
        val stream=object:ByteArrayOutputStream(){override fun close(){closed=true;super.close()}}
        check(runCatching{SettingsBackupCodec.encode(c,stream)}.isFailure);check(closed);check(stream.size()==0)
    }
    test("export rejects malformed UTF16 instead of replacing it") {
        val c=TestContext();c.seed("waex_prefs","css_theme",String(charArrayOf(0xd800.toChar())))
        val bytes=ByteArrayOutputStream();check(runCatching{SettingsBackupCodec.encode(c,bytes)}.exceptionOrNull() is CharacterCodingException);check(bytes.size()==0)
    }
    test("export-import exact 8 MiB boundary") {
        val text="x".repeat(8*1024*1024-42)
        val c=TestContext();c.seed("waex_prefs","css_theme",text)
        val out=ByteArrayOutputStream();check(SettingsBackupCodec.encode(c,out)==1);check(out.size()==8*1024*1024)
        val dest=TestContext();check(SettingsBackupCodec.decode(dest,ByteArrayInputStream(out.toByteArray()))==1);check(dest.get("waex_prefs","css_theme")==text)
    }
    test("export rejects one byte beyond 8 MiB") {
        val c=TestContext();c.seed("waex_prefs","css_theme","x".repeat(8*1024*1024-41))
        val out=ByteArrayOutputStream();check(runCatching{SettingsBackupCodec.encode(c,out)}.exceptionOrNull() is IllegalArgumentException);check(out.size()==0)
    }
    test("export-import exactly 10000 set entries") {
        val value=(0 until 10000).map{it.toString()}.toSet();val c=TestContext();c.seed("waex_prefs","hidetabs",value)
        val out=ByteArrayOutputStream();check(SettingsBackupCodec.encode(c,out)==1)
        val dest=TestContext();check(SettingsBackupCodec.decode(dest,ByteArrayInputStream(out.toByteArray()))==1);check(dest.get("waex_prefs","hidetabs")==value)
    }
    test("seeded exact integer normalization boundaries: 2000 cases") {
        val random=Random(0x14baL)
        repeat(1000) {
            val number=random.nextInt()
            check(normalize.invoke(SettingsBackupCodec,PreferenceSchema.Type.INT,number.toString())==number)
            check(normalize.invoke(SettingsBackupCodec,PreferenceSchema.Type.INT,"${number}.5")==null)
        }
    }
    test("all declared settings preserve names, types and values through real versioned JSON") {
        val settings=JSONObject();val expected=linkedMapOf<String,Any>()
        for((i,key) in PreferenceSchema.exportableKeys().sorted().withIndex()) {
            val entry=PreferenceSchema.entry(key)!!
            val v:Any=when(entry.type) {
                PreferenceSchema.Type.BOOLEAN -> i%2==0
                PreferenceSchema.Type.STRING -> "Value é $i\n\t"
                PreferenceSchema.Type.STRING_SET -> setOf("a,$i","b\\$i","")
                PreferenceSchema.Type.INT -> Int.MIN_VALUE+i
                PreferenceSchema.Type.LONG -> Long.MAX_VALUE-i
                PreferenceSchema.Type.FLOAT -> i+0.25f
            }
            expected[key]=v
            settings.put(key,if(v is Set<*>) JSONArray(v) else v)
        }
        val root=JSONObject().put("schemaVersion",1).put("settings",settings)
        val c=TestContext();check(SettingsBackupCodec.decode(c,root.toString().byteInputStream())==expected.size)
        for((key,v) in expected) {
            val store=if(PreferenceSchema.entry(key)!!.store==PreferenceSchema.Store.PRIVATE) "private_config" else "waex_prefs"
            check(c.get(store,key)==v){"Round trip mismatch $key"}
        }
        println("DETAIL: ${expected.size} preferences round-tripped through Android JSON")
    }
    println("RESULT: ${checks-failures.size}/$checks tests passed; ${failures.size} failed")
    check(failures.isEmpty()) { "Validation failures: ${failures.joinToString()}" }
}
