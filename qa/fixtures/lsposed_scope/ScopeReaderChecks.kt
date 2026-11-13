package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabase.ScopeRow
import android.os.Process
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
                expect(emptySet())
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
                require(snap.error?.contains("Could not parse LSPosed scope") == true)
            }
        }
        checkCase("no historical shared-preference grant is trusted on process start") {
            fixture(1_010_191) {
                require(LsposedScopeReader.cached(this) == null)
            }
        }
        checkCase("in-process cache matches latest successful read") {
            fixture(1_010_191, ScopeRow(4, MODULE, true, "com.whatsapp.w4b", 10)) {
                val live = LsposedScopeReader.read(this)
                require(live === LsposedScopeReader.cached(this))
                require(live.packages == setOf("com.whatsapp.w4b"))
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
            AppRestartHelper.databaseAvailable = true
            Context(cache).run()
        } finally {
            cache.deleteRecursively()
        }
    }

    private fun Context.expect(expected: Set<String>) {
        val snapshot = LsposedScopeReader.read(this)
        require(snapshot.frameworkReachable) { "Expected readable LSPosed DB: ${snapshot.error}" }
        require(snapshot.packages == expected) {
            "Expected scope $expected, observed ${snapshot.packages}"
        }
        require(SQLiteDatabase.queries == 1) { "Unexpected query/fallback count: ${SQLiteDatabase.queries}" }
    }
}
