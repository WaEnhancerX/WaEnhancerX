package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.waenhancer.config.PreferenceStores
import com.waenhancer.xposed.utils.AppRestartHelper
import java.io.File

data class LsposedScopeSnapshot(
    val modulePackage: String,
    val packages: Set<String>,
    /** True only when the framework database was read live in this process lifetime. */
    val frameworkReachable: Boolean,
    val source: Source,
    val error: String? = null,
    /** Framework-reported enabled flag for the module; null when unknown. */
    val moduleEnabled: Boolean? = null
) {
    /** DATABASE = live framework read, CACHE = last known persisted scope, UNAVAILABLE = no knowledge. */
    enum class Source { DATABASE, CACHE, UNAVAILABLE }
    fun contains(packageName: String): Boolean = packages.contains(packageName)

    /** A live snapshot is authoritative: absence from it really means "not in scope". */
    val isLive: Boolean get() = frameworkReachable && source == Source.DATABASE
}

/** Reads LSPosed's framework-owned scope database without treating unknown as in-scope. */
object LsposedScopeReader {
    private const val DB = "/data/adb/lspd/config/modules_config.db"
    private const val CACHE = "waex_lsposed_scope.db"
    private const val MODULE = "com.waenhancer"
    private const val PREF_KEY_CACHED_SCOPE = "cached_lsposed_scope"
    private const val PREF_KEY_CACHED_SCOPE_TIME = "cached_lsposed_scope_time"
    private val packageToken = Regex("\\b[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+\\b")

    @Volatile
    private var lastSnapshot: LsposedScopeSnapshot? = null

    /** Last known scope: the in-memory snapshot, else the persisted one (never treated as live). */
    fun cached(context: Context? = null): LsposedScopeSnapshot? {
        lastSnapshot?.let { return it }
        if (context != null) {
            runCatching {
                val prefs = PreferenceStores.publicStore(context)
                // An empty set is a valid cached answer, so presence is tracked by the timestamp.
                if (prefs.getLong(PREF_KEY_CACHED_SCOPE_TIME, 0L) > 0L) {
                    val snapshot = LsposedScopeSnapshot(
                        modulePackage = MODULE,
                        packages = prefs.getStringSet(PREF_KEY_CACHED_SCOPE, emptySet()).orEmpty().toSet(),
                        frameworkReachable = false,
                        source = LsposedScopeSnapshot.Source.CACHE
                    )
                    lastSnapshot = snapshot
                    return snapshot
                }
            }
        }
        return lastSnapshot
    }

    private fun staleFallback(context: Context, error: String): LsposedScopeSnapshot {
        val known = cached(context)
        // Never re-label an old live read as live: the framework could not be reached just now.
        return LsposedScopeSnapshot(
            MODULE,
            known?.packages.orEmpty(),
            false,
            if (known != null) LsposedScopeSnapshot.Source.CACHE else LsposedScopeSnapshot.Source.UNAVAILABLE,
            error,
            known?.moduleEnabled
        ).also { lastSnapshot = it }
    }

    @Synchronized
    fun read(context: Context): LsposedScopeSnapshot {
        val declared = DeclaredWhatsAppTargets.packages()
        val copy = File(context.cacheDir, CACHE)
        val wal = File(context.cacheDir, "$CACHE-wal")
        val shm = File(context.cacheDir, "$CACHE-shm")
        listOf(copy, wal, shm).forEach { runCatching { it.delete() } }
        val command = "if [ -f $DB ]; then cp -f $DB '${copy.absolutePath}' 2>/dev/null; " +
            "cp -f $DB-wal '${wal.absolutePath}' 2>/dev/null; cp -f $DB-shm '${shm.absolutePath}' 2>/dev/null; " +
            "chmod 644 '${copy.absolutePath}' '${wal.absolutePath}' '${shm.absolutePath}' 2>/dev/null; " +
            "echo WAEX_LSPOSED_DB_OK; else echo WAEX_LSPOSED_DB_MISSING; fi"
        val rootResult = runCatching { AppRestartHelper.runRootCommand(command).orEmpty() }.getOrDefault("")
        if (!rootResult.contains("WAEX_LSPOSED_DB_OK") || !copy.exists()) {
            return staleFallback(context, "LSPosed scope database unavailable")
        }
        return try {
            val packages = LinkedHashSet<String>()
            var moduleEnabled: Boolean? = null
            SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // Current LSPosed schema: module identity and scope are normalized into
                // separate tables and joined by modules.mid.
                val normalizedSchema = runCatching {
                    db.rawQuery("SELECT enabled FROM modules WHERE module_pkg_name = ?", arrayOf(MODULE)).use { rows ->
                        if (rows.moveToFirst()) moduleEnabled = rows.getInt(0) == 1
                    }
                    db.rawQuery(
                        "SELECT s.app_pkg_name FROM scope s JOIN modules m ON m.mid = s.mid " +
                            "WHERE m.module_pkg_name = ? AND m.enabled = 1",
                        arrayOf(MODULE)
                    ).use { rows ->
                        while (rows.moveToNext()) {
                            val pkg = rows.getString(0)
                            if (pkg in declared) packages.add(pkg)
                        }
                    }
                }.isSuccess
                // Vendor forks that denormalize module and scope: only used when the standard
                // schema is absent, so unrelated rows never widen a valid answer.
                if (!normalizedSchema) readDenormalizedScope(db, declared, packages)
            }
            runCatching {
                PreferenceStores.publicStore(context).edit()
                    .putStringSet(PREF_KEY_CACHED_SCOPE, packages)
                    .putLong(PREF_KEY_CACHED_SCOPE_TIME, System.currentTimeMillis())
                    .apply()
            }
            LsposedScopeSnapshot(
                MODULE, packages, true, LsposedScopeSnapshot.Source.DATABASE,
                moduleEnabled = moduleEnabled
            ).also { lastSnapshot = it }
        } catch (e: Throwable) {
            staleFallback(context, "Could not parse LSPosed scope: ${e.javaClass.simpleName}")
        } finally {
            listOf(copy, wal, shm).forEach { runCatching { it.delete() } }
        }
    }

    private fun readDenormalizedScope(db: SQLiteDatabase, declared: Set<String>, out: MutableSet<String>) {
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { tables ->
            while (tables.moveToNext()) {
                val table = tables.getString(0)
                if (!table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue
                val columns = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(1)) }
                }
                val scopeColumns = columns.filter {
                    it.contains("scope", true) || it.contains("app_pkg", true) ||
                        it.contains("target", true) || it.contains("package", true)
                }
                if (scopeColumns.isEmpty()) continue
                val projection = scopeColumns.joinToString(",") { "\"$it\"" }
                db.rawQuery("SELECT $projection FROM \"$table\"", null).use { rows ->
                    while (rows.moveToNext()) {
                        val values = scopeColumns.indices.map { rows.getString(it) ?: "" }
                        if (values.none { it.contains(MODULE) }) continue
                        values.forEach { v ->
                            packageToken.findAll(v).map { it.value }.filter { it in declared }.forEach(out::add)
                        }
                    }
                }
            }
        }
    }
}
