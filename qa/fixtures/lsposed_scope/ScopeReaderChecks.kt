package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.ScopeRow
import android.os.Process
import com.waenhancer.config.PreferenceStores
import com.waenhancer.xposed.utils.AppRestartHelper
import java.nio.file.Files

/** Exercised against the actual production LsposedScopeReader.kt with strict Android doubles. */
object ScopeReaderChecks {
    private const val MODULE = "com.waenhancer"
    private var checks = 0

    @JvmStatic
    fun main(args: Array<String>) {
        checkCase("personal-only scope is not visible to work profile") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp", 0)) {
                expect(emptySet())
            }
        }
        checkCase("work-only scope is not visible to personal profile") {
            fixture(10_191, ScopeRow(4, MODULE, true, "com.whatsapp", 10)) {
                expect(emptySet())
            }
        }
        checkCase("personal scope is visible to personal profile") {
            fixture(10_191, ScopeRow(4, MODULE, true, "com.whatsapp", 0)) {
                expect(setOf("com.whatsapp"))
            }
        }
        checkCase("work scope is visible to work profile") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp", 10)) {
                expect(setOf("com.whatsapp"))
            }
        }
        checkCase("two users can independently select the same package") {
            val both = arrayOf(
                ScopeRow(4, MODULE, true, "com.whatsapp", 0),
                ScopeRow(4, MODULE, true, "com.whatsapp", 10)
            )
            fixture(10_191, *both) { expect(setOf("com.whatsapp")) }
            fixture(1_010_191, *both) { expect(setOf("com.whatsapp")) }
        }
        checkCase("target from another Android user is not in scope") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp.w4b", 11)) {
                expect(emptySet())
            }
        }
        checkCase("disabled module has no active scope") {
            fixture(1_010_191, ScopeRow(4, MODULE, false, "com.whatsapp", 10)) {
                require(expect(emptySet()).moduleEnabled == false)
            }
        }
        checkCase("other modules cannot lend WAEX scope") {
            fixture(1_010_191, ScopeRow(9, "other.module", true, "com.whatsapp", 10)) {
                expect(emptySet())
            }
        }
        checkCase("undeclared host package cannot be inferred") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "not.whatsapp.host", 10)) {
                expect(emptySet())
            }
        }
        checkCase("a valid empty scope is distinct from a database error") {
            fixture(1_010_191) {
                val snap = LsposedScopeReader.read(this)
                require(snap.packages.isEmpty() && snap.frameworkReachable)
                require(snap.source == LsposedScopeSnapshot.Source.DATABASE)
            }
        }
        checkCase("missing DB invalidates a previously positive observation") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp", 10)) {
                expect(setOf("com.whatsapp"))
                AppRestartHelper.databaseAvailable = false
                val snap = LsposedScopeReader.read(this)
                require(!snap.frameworkReachable && snap.packages.isEmpty())
                require(!LsposedScopeReader.cached(this)!!.contains("com.whatsapp"))
            }
        }
        checkCase("unsupported DB schema never reuses a stale scope") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp", 10)) {
                expect(setOf("com.whatsapp"))
                SQLiteDatabase.unsupportedSchema = true
                val snap = LsposedScopeReader.read(this)
                require(!snap.frameworkReachable && snap.packages.isEmpty())
                require(snap.error == "Could not read LSPosed scope: IllegalStateException")
            }
        }
        checkCase("no historical shared-preference grant is trusted on process start") {
            fixture(1_010_191) {
                PreferenceStores.seedLegacyScope(setOf("com.whatsapp"))
                require(LsposedScopeReader.cached(this) == null)
                require(PreferenceStores.calls == 0)
            }
        }
        checkCase("in-process cache matches latest successful read") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp.w4b", 10)) {
                val live = LsposedScopeReader.read(this)
                require(live === LsposedScopeReader.cached(this))
                require(live.packages == setOf("com.whatsapp.w4b"))
            }
        }
        checkCase("shared staged parse preserves enabled state without repeating root work") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp.w4b", 10)) {
                val output = AppRestartHelper.runRootCommand(LsposedScopeReader.stageCommand(this))
                val snap = LsposedScopeReader.parseStaged(this, output.contains(LsposedScopeReader.STAGED_OK_MARKER))
                require(snap.packages == setOf("com.whatsapp.w4b") && snap.isLive)
                require(snap.moduleEnabled == true)
                require(AppRestartHelper.calls == 1 && SQLiteDatabase.queries == 2)
                require(cacheDir.listFiles()!!.isEmpty())
            }
        }
        checkCase("staged failure clears prior enabled and scope observations and cleans all copies") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp", 10)) {
                expect(setOf("com.whatsapp"))
                listOf("waex_lsposed_scope.db", "waex_lsposed_scope.db-wal", "waex_lsposed_scope.db-shm")
                    .forEach { java.io.File(cacheDir, it).writeText("synthetic") }
                val snap = LsposedScopeReader.parseStaged(this, false)
                require(!snap.isLive && snap.packages.isEmpty() && snap.moduleEnabled == null)
                require(snap.source == LsposedScopeSnapshot.Source.UNAVAILABLE)
                require(AppRestartHelper.calls == 1 && SQLiteDatabase.queries == 2)
                require(cacheDir.listFiles()!!.isEmpty())
            }
        }
        checkCase("missing module has unknown enabled state rather than false evidence") {
            fixture(1_010_191, ScopeRow(9, "other.module", true, "com.whatsapp", 10)) {
                require(expect(emptySet()).moduleEnabled == null)
            }
        }
        checkCase("successful read never persists a user-agnostic scope cache") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp", 10)) {
                expect(setOf("com.whatsapp"))
                require(PreferenceStores.calls == 0)
            }
        }
        println("PASS $checks strict production-reader cases")
    }

    private fun checkCase(label: String, run: () -> Unit) {
        try {
            run()
            checks++
            println("PASS: $label")
        } catch (e: Throwable) {
            System.err.println("FAIL: $label: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
    }

    private fun fixture(uid: Int, vararg rows: ScopeRow, run: Context.() -> Unit) {
        val cache = Files.createTempDirectory("waex-lsposed-scope-fixture-").toFile()
        try {
            LsposedScopeReader::class.java.getDeclaredField("lastSnapshot").apply {
                isAccessible = true
                set(null, null)
            }
            Process.setUidForTest(uid)
            SQLiteDatabase.rows.clear()
            SQLiteDatabase.rows.addAll(rows)
            SQLiteDatabase.unsupportedSchema = false
            SQLiteDatabase.queries = 0
            SQLiteDatabase.moduleQueries = 0
            SQLiteDatabase.scopeQueries = 0
            AppRestartHelper.databaseAvailable = true
            AppRestartHelper.calls = 0
            PreferenceStores.reset()
            Context(cache).run()
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun Context.expect(expected: Set<String>): LsposedScopeSnapshot {
        val snapshot = LsposedScopeReader.read(this)
        require(snapshot.frameworkReachable) { "Expected readable LSPosed DB: ${snapshot.error}" }
        require(snapshot.packages == expected) {
            "Expected scope $expected, observed ${snapshot.packages}"
        }
        require(SQLiteDatabase.queries == 2 && SQLiteDatabase.moduleQueries == 1 && SQLiteDatabase.scopeQueries == 1) {
            "Expected exactly one module query and one user-bound scope query: ${SQLiteDatabase.queries}"
        }
        return snapshot
    }
}
