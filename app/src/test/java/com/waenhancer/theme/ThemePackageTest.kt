package com.waenhancer.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThemePackageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            entries.forEach { (name, body) ->
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private val css = "/* name = \"Ocean\"\nauthor = \"Ada\" */\n#fab { background: #00AAFF; }"

    @Test
    fun `header exposes name and author`() {
        val header = ThemeHeader.parse(css)
        assertEquals("Ocean", header.name)
        assertEquals("Ada", header.author)
        assertNull(header.version)
    }

    @Test
    fun `extracts style and assets, tolerating a wrapper folder`() {
        val target = tmp.newFolder("t")
        ArchiveFixture.extract(zip("Ocean/style.css" to css, "Ocean/bg.png" to "png"), target)
        assertTrue(target.resolve("style.css").isFile)
        assertTrue(target.resolve("bg.png").isFile)
    }

    @Test
    fun `rejects path traversal`() {
        try {
            ArchiveFixture.extract(zip("../evil.css" to "x", "style.css" to css), tmp.newFolder("t"))
            fail("expected rejection")
        } catch (expected: ThemeArchive.InvalidThemeException) {
            assertTrue(expected.message!!.contains("Unsafe"))
        }
    }

    @Test
    fun `ignores non-asset files and requires style css`() {
        val target = tmp.newFolder("t")
        try {
            ArchiveFixture.extract(zip("run.sh" to "rm -rf /"), target)
            fail("expected rejection")
        } catch (expected: ThemeArchive.InvalidThemeException) {
            assertFalse(target.resolve("run.sh").exists())
        }
    }

    @Test
    fun `validator blocks remote imports and unbalanced braces`() {
        assertFalse(ThemeCssValidator.validate("@import url(https://x.test/a.css); a { }").valid)
        assertFalse(ThemeCssValidator.validate("#a { background: url(//x.test/a.png); }").valid)
        assertFalse(ThemeCssValidator.validate("#a { color: red;").valid)
        assertTrue(ThemeCssValidator.validate("#a { background: url('bg.png'); }").valid)
    }

    @Test
    fun `ids and asset names reject traversal`() {
        assertTrue(ThemeIds.isValidId("ocean-2"))
        assertFalse(ThemeIds.isValidId("../x"))
        assertFalse(ThemeIds.isValidId("a..b"))
        assertFalse(ThemeIds.isValidAssetName("../a.png"))
        assertFalse(ThemeIds.isValidAssetName("sub/a.png"))
        assertEquals("my-theme", ThemeIds.fromDisplayName("My Theme.zip"))
    }

    private object ArchiveFixture {
        fun extract(input: ByteArrayInputStream, target: java.io.File) = ThemeArchive.extract(input, target)
    }
}
