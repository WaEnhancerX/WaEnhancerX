package com.waenhancer.utils

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import com.waenhancer.config.PreferenceStores
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
    /** DATABASE = live framework read, CACHE = last known persisted scope, UNAVAILABLE = no knowledge. */
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
    const val STAGED_OK_MARKER = "WAEX_LSPOSED_DB_OK"
    private const val PREF_KEY_CACHED_SCOPE = "cached_lsposed_scope"
    private const val PREF_KEY_CACHED_SCOPE_TIME = "cached_lsposed_scope_time"

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
                    return LsposedScopeSnapshot(
                        MODULE,
                        prefs.getStringSet(PREF_KEY_CACHED_SCOPE, emptySet()).orEmpty().toSet(),
                        frameworkReachable = false,
                        source = LsposedScopeSnapshot.Source.CACHE
                    ).also { lastSnapshot = it }
                }
            }
        }
        return lastSnapshot
    }

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
            if (!staged || !copy.exists()) return failed(context, "LSPosed scope database unavailable (root probe failed)")
            val packages = LinkedHashSet<String>()
            var moduleEnabled: Boolean? = null
            val userId = Process.myUid() / 100000
            SQLiteDatabase.openDatabase(copy.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT enabled FROM modules WHERE module_pkg_name = ?", arrayOf(MODULE)).use { rows ->
                    if (rows.moveToFirst()) moduleEnabled = rows.getInt(0) == 1
                }
                db.rawQuery(
                    "SELECT s.app_pkg_name FROM scope s JOIN modules m ON m.mid = s.mid " +
                        "WHERE m.module_pkg_name = ? AND m.enabled = 1 AND s.user_id = ?",
                    arrayOf(MODULE, userId.toString())
                ).use { rows ->
                    while (rows.moveToNext()) {
                        val pkg = rows.getString(0) ?: continue
                        if (DeclaredWhatsAppTargets.contains(pkg)) packages.add(pkg)
                    }
                }
            }
            runCatching {
                PreferenceStores.publicStore(context).edit()
                    .putStringSet(PREF_KEY_CACHED_SCOPE, packages)
                    .putLong(PREF_KEY_CACHED_SCOPE_TIME, System.currentTimeMillis())
                    .apply()
            }
            return LsposedScopeSnapshot(
                MODULE, packages, true, LsposedScopeSnapshot.Source.DATABASE, moduleEnabled = moduleEnabled
            ).also { lastSnapshot = it }
        } catch (e: Throwable) {
            return failed(context, "Could not read LSPosed scope: ${e.javaClass.simpleName}")
        } finally {
            listOf(copy, wal, shm).forEach { runCatching { it.delete() } }
        }
    }

    private fun failed(context: Context, error: String): LsposedScopeSnapshot {
        val known = cached(context)
        // Never re-label an old live read as live: the framework could not be read just now.
        return LsposedScopeSnapshot(
            MODULE,
            known?.packages.orEmpty(),
            false,
            if (known != null) LsposedScopeSnapshot.Source.CACHE else LsposedScopeSnapshot.Source.UNAVAILABLE,
            error,
            known?.moduleEnabled
        ).also { lastSnapshot = it }
    }
}
