package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.waenhancer.config.PreferenceStores
import com.waenhancer.xposed.utils.AppRestartHelper
import java.io.File

data class LsposedScopeSnapshot(
    val modulePackage: String,
    val packages: Set<String>,
    val frameworkReachable: Boolean,
    val source: Source,
    val error: String? = null
) {
    enum class Source { DATABASE, UNAVAILABLE }
    fun contains(packageName: String): Boolean = packages.contains(packageName)
}

/** Reads LSPosed's framework-owned scope database without treating unknown as in-scope. */
object LsposedScopeReader {
    private const val DB = "/data/adb/lspd/config/modules_config.db"
    private const val CACHE = "waex_lsposed_scope.db"
    private const val MODULE = "com.waenhancer"
    private const val PREF_KEY_CACHED_SCOPE = "cached_lsposed_scope"
    private val packageToken = Regex("\\b[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+\\b")

    @Volatile
    private var lastSnapshot: LsposedScopeSnapshot? = null

    fun cached(context: Context? = null): LsposedScopeSnapshot? {
        if (lastSnapshot != null) return lastSnapshot
        if (context != null) {
            runCatching {
                val prefs = PreferenceStores.publicStore(context)
                val saved = prefs.getStringSet(PREF_KEY_CACHED_SCOPE, null)
                if (!saved.isNullOrEmpty()) {
                    val snapshot = LsposedScopeSnapshot(
                        modulePackage = MODULE,
                        packages = saved,
                        frameworkReachable = true,
                        source = LsposedScopeSnapshot.Source.DATABASE
                    )
                    lastSnapshot = snapshot
                    return snapshot
                }
            }
        }
        return lastSnapshot
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
            val fallback = cached(context) ?: LsposedScopeSnapshot(
                MODULE,
                emptySet(),
                false,
                LsposedScopeSnapshot.Source.UNAVAILABLE,
                "LSPosed scope database unavailable"
            )
            return fallback.also { if (lastSnapshot == null) lastSnapshot = it }
        }
        return try {
            val packages = LinkedHashSet<String>()
            SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                // Current LSPosed schema: module identity and scope are normalized into
                // separate tables and joined by modules.mid.
                runCatching {
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
                }
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { tables ->
                    while (tables.moveToNext()) {
                        val table = tables.getString(0)
                        if (!table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) continue
                        val columns = db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                            buildList { while (c.moveToNext()) add(c.getString(1)) }
                        }
                        val scopeColumns = columns.filter { it.contains("scope", true) ||
                            it.contains("app_pkg", true) || it.contains("target", true) || it.contains("package", true) }
                        if (scopeColumns.isEmpty()) continue
                        val projection = scopeColumns.joinToString(",") { "\"$it\"" }
                        db.rawQuery("SELECT $projection FROM \"$table\"", null).use { rows ->
                            while (rows.moveToNext()) for (i in scopeColumns.indices) {
                                val values = scopeColumns.indices.map { rows.getString(it) ?: "" }
                                // Fallback for vendor forks that denormalize module and scope.
                                if (values.none { it == MODULE || it.contains(MODULE) }) continue
                                packageToken.findAll(values[i]).map { it.value }
                                    .filter { it in declared }.forEach { packages.add(it) }
                            }
                        }
                    }
                }
            }
            if (packages.isNotEmpty()) {
                runCatching {
                    PreferenceStores.publicStore(context).edit()
                        .putStringSet(PREF_KEY_CACHED_SCOPE, packages)
                        .apply()
                }
            }
            LsposedScopeSnapshot(MODULE, packages, true, LsposedScopeSnapshot.Source.DATABASE).also {
                lastSnapshot = it
            }
        } catch (e: Throwable) {
            val fallback = cached(context) ?: LsposedScopeSnapshot(
                MODULE,
                emptySet(),
                true,
                LsposedScopeSnapshot.Source.UNAVAILABLE,
                "Could not parse LSPosed scope: ${e.javaClass.simpleName}"
            )
            fallback.also {
                if (lastSnapshot == null) lastSnapshot = it
            }
        } finally {
            listOf(copy, wal, shm).forEach { runCatching { it.delete() } }
        }
    }
}
