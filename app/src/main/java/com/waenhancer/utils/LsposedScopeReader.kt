package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import com.waenhancer.xposed.utils.AppRestartHelper
import java.io.File

data class LsposedScopeSnapshot(
    val modulePackage: String,
    /** Fixed supported targets that are in the module's LSPosed scope for the current user. */
    val packages: Set<String>,
    /** True only when the framework database was read live. */
    val frameworkReachable: Boolean,
    val source: Source,
    val error: String? = null,
    /** Framework-reported enabled flag for the module; null when unknown. */
    val moduleEnabled: Boolean? = null
) {
    /** CACHE is retained for compatibility; this reader publishes only live or unavailable evidence. */
    enum class Source { DATABASE, CACHE, UNAVAILABLE }
    fun contains(packageName: String): Boolean = packages.contains(packageName)

    /** A live snapshot is authoritative: absence from it really means "not in scope". */
    val isLive: Boolean get() = frameworkReachable && source == Source.DATABASE
}

/**
 * Reads the module's scope from LSPosed's own database (root only) and keeps only the fixed list of
 * supported targets ([DeclaredWhatsAppTargets]). Other scoped apps are never treated as targets.
 */
object LsposedScopeReader {
    private const val DB = "/data/adb/lspd/config/modules_config.db"
    private const val CACHE = "waex_lsposed_scope.db"
    private const val MODULE = "com.waenhancer"
    // Android assigns each user a separate block of 100,000 app UIDs. The
    // LSPosed scope table uses the Android user ID, not the package UID.
    private const val PER_USER_RANGE = 100_000
    private const val SCOPE_QUERY = """
        SELECT s.app_pkg_name FROM scope s
        JOIN modules m ON m.mid = s.mid
        WHERE m.module_pkg_name = ? AND m.enabled = 1 AND s.user_id = ?
    """
    const val STAGED_OK_MARKER = "WAEX_LSPOSED_DB_OK"

    @Volatile
    private var lastSnapshot: LsposedScopeSnapshot? = null

    /** Only this process's latest observation; legacy persisted values have no verified user identity. */
    @Suppress("UNUSED_PARAMETER")
    fun cached(context: Context? = null): LsposedScopeSnapshot? = lastSnapshot

    /** Shell fragment that stages a private copy of the scope DB and prints [STAGED_OK_MARKER]. */
    fun stageCommand(context: Context): String {
        val copy = File(context.cacheDir, CACHE)
        val wal = File(context.cacheDir, "$CACHE-wal")
        val shm = File(context.cacheDir, "$CACHE-shm")
        listOf(copy, wal, shm).forEach { runCatching { it.delete() } }
        return "if [ -f $DB ]; then cp -f $DB '${copy.absolutePath}' 2>/dev/null; " +
            "cp -f $DB-wal '${wal.absolutePath}' 2>/dev/null; cp -f $DB-shm '${shm.absolutePath}' 2>/dev/null; " +
            "chmod 644 '${copy.absolutePath}' '${wal.absolutePath}' '${shm.absolutePath}' 2>/dev/null; " +
            "echo $STAGED_OK_MARKER; else echo WAEX_LSPOSED_DB_MISSING; fi"
    }

    /** Standalone read for callers that only need scope. Prefer the shared refresh in TargetAppManager. */
    @Synchronized
    fun read(context: Context): LsposedScopeSnapshot {
        val out = runCatching { AppRestartHelper.runRootCommand(stageCommand(context)).orEmpty() }.getOrDefault("")
        return parseStaged(context, out.contains(STAGED_OK_MARKER))
    }

    /** Turns the staged copy (if [staged]) into a snapshot; always removes the temporary files. */
    @Synchronized
    fun parseStaged(context: Context, staged: Boolean): LsposedScopeSnapshot {
        val copy = File(context.cacheDir, CACHE)
        val wal = File(context.cacheDir, "$CACHE-wal")
        val shm = File(context.cacheDir, "$CACHE-shm")
        try {
            if (!staged || !copy.exists()) return failed("LSPosed scope database unavailable (root probe failed)")
            val packages = LinkedHashSet<String>()
            var moduleEnabled: Boolean? = null
            val userId = Process.myUid() / PER_USER_RANGE
            SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT enabled FROM modules WHERE module_pkg_name = ?", arrayOf(MODULE)).use { rows ->
                    if (rows.moveToFirst()) moduleEnabled = rows.getInt(0) == 1
                }
                // Never infer another Android user's scope from a package-only row.
                // Unknown vendor schemas must be reported as unavailable, not promoted
                // into a positive scope result by scanning arbitrary table strings.
                db.rawQuery(SCOPE_QUERY, arrayOf(MODULE, userId.toString())).use { rows ->
                    while (rows.moveToNext()) {
                        val pkg = rows.getString(0) ?: continue
                        if (DeclaredWhatsAppTargets.contains(pkg)) packages.add(pkg)
                    }
                }
            }
            return LsposedScopeSnapshot(
                MODULE, packages, true, LsposedScopeSnapshot.Source.DATABASE, moduleEnabled = moduleEnabled
            ).also { lastSnapshot = it }
        } catch (e: Throwable) {
            return failed("Could not read LSPosed scope: ${e.javaClass.simpleName}")
        } finally {
            listOf(copy, wal, shm).forEach { runCatching { it.delete() } }
        }
    }

    private fun failed(error: String): LsposedScopeSnapshot {
        // A failed refresh invalidates even a prior in-process positive observation.
        // Persisted package-only scope does not identify an Android user and is not evidence.
        return LsposedScopeSnapshot(
            MODULE,
            emptySet(),
            false,
            LsposedScopeSnapshot.Source.UNAVAILABLE,
            error
        ).also { lastSnapshot = it }
    }
}
