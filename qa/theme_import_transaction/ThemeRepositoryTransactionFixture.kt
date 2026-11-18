package com.waenhancer.theme

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.waenhancer.core.preferences.WaexPreferenceManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Runs the production ThemePackage + ThemeRepository code with real files and strict Android stubs. */
object ThemeRepositoryTransactionFixture {
    private const val OLD_CSS = "/* name = Before */\nchat { background-color: #111111; }"
    private const val NEW_CSS = "/* name = After */\nchat { background-color: #222222; }"
    private const val OTHER_CSS = "/* name = Other */\nchat { background-color: #333333; }"

    private class F : AutoCloseable {
        val storage = Files.createTempDirectory("waex-theme-transaction-").toFile()
        val resolver = ContentResolver()
        val context = Context(storage, resolver)
        val prefs = WaexPreferenceManager()
        val repo = ThemeRepository(context, prefs)
        val uri = Uri("content://fixtures/sample.zip")
        val dest: File get() = File(ThemeRepository.root(context), "sample")

        fun input(css: String, uri: Uri = this.uri, asset: String = "asset") {
            resolver.files[uri.value] = zip(css, asset)
        }

        fun first() {
            input(OLD_CSS, asset = "old-asset")
            check(repo.import(uri).isSuccess) { "preflight import failed" }
        }

        fun active() {
            first()
            check(repo.activate("sample"))
            check(repo.activeId() == "sample")
        }

        fun css(): String = File(dest, "style.css").readText()
        fun asset(): String = File(dest, "wall.png").readText()
        fun backupDirs(): List<File> = ThemeRepository.root(context).listFiles()
            .orEmpty().filter { it.name.startsWith(".backup-") }
        fun rejectedDirs(): List<File> = ThemeRepository.root(context).listFiles()
            .orEmpty().filter { it.name.startsWith(".rejected-") }

        override fun close() { storage.deleteRecursively() }
    }

    private class FaultMoves(private val failures: Set<Int> = emptySet(),
                             private val refuseBackupCleanup: Boolean = false) : ThemeInstallFiles {
        var moveCount = 0
        override fun move(source: File, destination: File): Boolean {
            moveCount++
            return if (moveCount in failures) false else source.renameTo(destination)
        }
        override fun remove(directory: File): Boolean =
            if (refuseBackupCleanup && directory.name.startsWith(".backup-")) false
            else directory.deleteRecursively()
    }

    private fun zip(css: String, asset: String): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("style.css"))
            zip.write(css.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("wall.png"))
            zip.write(asset.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }

    private fun checkOld(f: F) {
        check(f.css() == OLD_CSS) { "original CSS lost" }
        check(f.asset() == "old-asset") { "original asset lost" }
        check(f.prefs.store[ThemeRepository.KEY_CSS] == OLD_CSS) { "original active CSS setting lost" }
        check(f.repo.activeId() == "sample") { "original active id lost" }
    }

    private fun runCase(name: String, body: () -> Unit) {
        body()
        println("PASS $name")
    }

    @JvmStatic fun main(args: Array<String>) {
        runCase("new-import-does-not-create-unrequested-selection") {
            F().use { f ->
                f.first()
                check(f.repo.activeId() == null)
                check(f.prefs.writes.isEmpty())
                check(f.css() == OLD_CSS)
                check(f.repo.list().size == 1)
            }
        }
        runCase("active-replace-updates-css-and-retains-id") {
            F().use { f ->
                f.active()
                f.input(NEW_CSS, asset = "new-asset")
                check(f.repo.import(f.uri).isSuccess)
                check(f.repo.activeId() == "sample") { "replacing active theme detached its id" }
                check(f.prefs.store[ThemeRepository.KEY_CSS] == NEW_CSS)
                check(f.css() == NEW_CSS && f.asset() == "new-asset")
                check(f.backupDirs().isEmpty())
            }
        }
        runCase("rename-stage-failure-restores-original-without-preference-write") {
            F().use { f ->
                f.active()
                f.input(NEW_CSS, asset = "new-asset")
                val beforeWrites = f.prefs.writes.size
                val fault = FaultMoves(setOf(2))
                f.repo.installFiles = fault
                check(f.repo.import(f.uri).isFailure)
                check(fault.moveCount == 3)
                checkOld(f)
                check(f.prefs.writes.size == beforeWrites) { "preferences were touched before file promotion" }
                check(f.backupDirs().isEmpty())
            }
        }
        runCase("rename-old-to-backup-failure-leaves-original") {
            F().use { f ->
                f.active()
                f.input(NEW_CSS)
                val beforeWrites = f.prefs.writes.size
                f.repo.installFiles = FaultMoves(setOf(1))
                check(f.repo.import(f.uri).isFailure)
                checkOld(f)
                check(f.prefs.writes.size == beforeWrites)
            }
        }
        runCase("failed-backup-restoration-retains-recoverable-old-bytes") {
            F().use { f ->
                f.active()
                f.input(NEW_CSS)
                val beforeWrites = f.prefs.writes.size
                f.repo.installFiles = FaultMoves(setOf(2, 3))
                check(f.repo.import(f.uri).isFailure)
                val saved = f.backupDirs()
                check(saved.size == 1 && File(saved.single(), "style.css").readText() == OLD_CSS)
                check(File(saved.single(), "wall.png").readText() == "old-asset")
                check(f.prefs.writes.size == beforeWrites)
            }
        }
        runCase("failed-active-css-commit-after-mutating-memory-rolls-back") {
            F().use { f ->
                f.active()
                f.input(NEW_CSS, asset = "new-asset")
                f.prefs.cssFault = WaexPreferenceManager.WriteFault.FAIL_ONCE_AFTER
                check(f.repo.import(f.uri).isFailure)
                checkOld(f)
                check(f.backupDirs().isEmpty())
                check(f.rejectedDirs().isEmpty())
            }
        }
        runCase("thrown-active-css-commit-after-mutating-memory-rolls-back") {
            F().use { f ->
                f.active()
                f.input(NEW_CSS, asset = "new-asset")
                f.prefs.cssFault = WaexPreferenceManager.WriteFault.THROW_ONCE_AFTER
                check(f.repo.import(f.uri).isFailure)
                checkOld(f)
            }
        }
        runCase("persistent-css-store-failure-returns-failure-keeps-original-files") {
            F().use { f ->
                f.active()
                f.input(NEW_CSS, asset = "new-asset")
                f.prefs.cssFault = WaexPreferenceManager.WriteFault.FAIL_ALWAYS_AFTER
                val result = f.repo.import(f.uri)
                check(result.isFailure)
                check(f.css() == OLD_CSS && f.asset() == "old-asset")
                check(result.exceptionOrNull()?.suppressed?.any { it.message?.contains("could not be restored") == true } == true)
                // The store itself is broken and can retain an inconsistent in-memory CSS value.
                // The library must never report a successful installation in this state.
            }
        }
        runCase("replace-inactive-theme-does-not-change-selected-app-scope") {
            F().use { f ->
                f.first()
                val other = Uri("content://fixtures/other.zip")
                f.input(OTHER_CSS, other, "other-asset")
                check(f.repo.import(other).isSuccess)
                check(f.repo.activate("other"))
                val before = f.prefs.writes.size
                f.input(NEW_CSS, asset = "new-asset")
                check(f.repo.import(f.uri).isSuccess)
                check(f.prefs.writes.size == before)
                check(f.repo.activeId() == "other")
                check(f.prefs.store[ThemeRepository.KEY_CSS] == OTHER_CSS)
                check(f.css() == NEW_CSS)
            }
        }
        runCase("backup-cleanup-failure-retains-recovery-copy") {
            F().use { f ->
                f.active()
                f.input(NEW_CSS, asset = "new-asset")
                f.repo.installFiles = FaultMoves(refuseBackupCleanup = true)
                check(f.repo.import(f.uri).isSuccess)
                check(f.repo.activeId() == "sample")
                check(f.backupDirs().size == 1)
                check(File(f.backupDirs().single(), "style.css").readText() == OLD_CSS)
                check(f.repo.list().size == 1) { "hidden recovery copy appeared as an installed theme" }
            }
        }
        runCase("invalid-zip-leaves-selected-theme-intact") {
            F().use { f ->
                f.active()
                f.resolver.files[f.uri.value] = "not a ZIP".toByteArray()
                check(f.repo.import(f.uri).isFailure)
                checkOld(f)
            }
        }
    }
}
