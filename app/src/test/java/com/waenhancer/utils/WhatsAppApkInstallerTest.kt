package com.waenhancer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class WhatsAppApkInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = tmp.newFile(name)
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (entryName, bytes) ->
                out.putNextEntry(ZipEntry(entryName))
                out.write(bytes)
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `a plain apk is not treated as a bundle`() {
        val apk = zip("plain.pkg", mapOf("AndroidManifest.xml" to ByteArray(10), "classes.dex" to ByteArray(10)))
        val staged = WhatsAppApkInstaller.unpack(apk, tmp.newFolder("out1"))
        assertEquals(listOf(apk), staged.apks)
        assertEquals(apk, staged.base)
        assertFalse(staged.isBundle)
    }

    @Test
    fun `an apkm bundle extracts base and splits with the base first`() {
        val bundle = zip("b.pkg", mapOf(
            "info.json" to "{}".toByteArray(),
            "split_config.arm64_v8a.apk" to ByteArray(20),
            "base.apk" to ByteArray(500),
            "icon.png" to ByteArray(5)
        ))
        val staged = WhatsAppApkInstaller.unpack(bundle, tmp.newFolder("out2"))
        assertTrue(staged.isBundle)
        assertEquals("base.apk", staged.base.name)
        assertEquals("base.apk", staged.apks.first().name)
        assertEquals(setOf("base.apk", "split_config.arm64_v8a.apk"), staged.apks.map { it.name }.toSet())
        assertEquals(listOf("split_config.arm64_v8a.apk"), staged.splits.map { it.name })
    }

    @Test
    fun `an xapk uses the manifest to find the base`() {
        val manifest = """{"split_apks":[{"file":"com.whatsapp.apk","id":"base"},{"file":"config.arm64_v8a.apk","id":"config.arm64_v8a"}]}"""
        val bundle = zip("x.pkg", mapOf(
            "manifest.json" to manifest.toByteArray(),
            "config.arm64_v8a.apk" to ByteArray(900),
            "com.whatsapp.apk" to ByteArray(100)
        ))
        val staged = WhatsAppApkInstaller.unpack(bundle, tmp.newFolder("out3"))
        assertEquals("com.whatsapp.apk", staged.base.name)
    }

    @Test
    fun `unsafe entry names are never extracted`() {
        val bundle = zip("z.pkg", mapOf(
            "../evil.apk" to ByteArray(10),
            "dir/inner.apk" to ByteArray(10),
            "base.apk" to ByteArray(10)
        ))
        val dest = tmp.newFolder("out4")
        val extracted = WhatsAppApkInstaller.extractRootApks(bundle, dest)
        assertEquals(listOf("base.apk"), extracted.map { it.name })
        assertFalse(File(dest.parentFile, "evil.apk").exists())
    }

    @Test
    fun `base selection falls back to the largest apk`() {
        val small = tmp.newFile("a.apk").apply { writeBytes(ByteArray(5)) }
        val large = tmp.newFile("b.apk").apply { writeBytes(ByteArray(50)) }
        assertEquals(large, WhatsAppApkInstaller.selectBase(listOf(small, large), null))
        assertEquals(small, WhatsAppApkInstaller.selectBase(listOf(small, large), "a.apk"))
    }

    @Test
    fun `manifest base parsing tolerates bad input`() {
        assertNull(WhatsAppApkInstaller.parseManifestBase("not json"))
        assertNull(WhatsAppApkInstaller.parseManifestBase("""{"split_apks":"x"}"""))
        assertNull(WhatsAppApkInstaller.parseManifestBase("""{"split_apks":[{"file":"a.apk","id":"config"}]}"""))
    }
}
