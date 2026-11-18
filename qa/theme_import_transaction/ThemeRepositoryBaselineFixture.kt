package com.waenhancer.theme

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.waenhancer.core.preferences.WaexPreferenceManager
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A standalone control compiled against pristine nightly (no candidate-only APIs). */
object ThemeRepositoryBaselineFixture {
    private fun zip(css: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("style.css"))
            zip.write(css.toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @JvmStatic fun main(args: Array<String>) {
        val root = Files.createTempDirectory("waex-theme-baseline-").toFile()
        try {
            val uri = Uri("content://fixture/sample.zip")
            val resolver = ContentResolver()
            val context = Context(root, resolver)
            val prefs = WaexPreferenceManager()
            val repository = ThemeRepository(context, prefs)
            val css1 = "/* name = old */\nchat { background-color: #111111; }"
            val css2 = "/* name = new */\nchat { background-color: #222222; }"
            resolver.files[uri.value] = zip(css1)
            check(repository.import(uri).isSuccess)
            check(repository.activate("sample"))
            check(repository.activeId() == "sample")
            resolver.files[uri.value] = zip(css2)
            check(repository.import(uri).isSuccess)
            check(repository.activeId() == "sample") { "ACTIVE-ID LOST after importing a replacement CSS" }
            check(prefs.store[ThemeRepository.KEY_CSS] == css2) { "CSS setting not updated after replacement" }
            println("PASS existing active theme stays active after content change")
        } finally {
            root.deleteRecursively()
        }
    }
}
