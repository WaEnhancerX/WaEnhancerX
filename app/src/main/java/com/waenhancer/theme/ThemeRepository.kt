package com.waenhancer.theme

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.waenhancer.core.preferences.WaexPreferenceManager
import java.io.File
import java.io.IOException

/** File moves are injectable in the isolated contract tests to exercise I/O failure paths. */
internal interface ThemeInstallFiles {
    fun move(source: File, destination: File): Boolean
    fun remove(directory: File): Boolean
}

private object RealThemeInstallFiles : ThemeInstallFiles {
    override fun move(source: File, destination: File): Boolean = source.renameTo(destination)
    override fun remove(directory: File): Boolean = directory.deleteRecursively()
}

/** One installed WAEX theme package. */
data class InstalledTheme(val id: String, val header: ThemeHeader, val assetCount: Int) {
    val displayName: String get() = header.name ?: id
}

/**
 * Theme library kept in app-private storage (`files/waex_themes/<id>/`). WhatsApp never reads
 * these folders directly; assets are served through the WAEX hook provider.
 */
class ThemeRepository(
    private val context: Context,
    /** Scoped to the target app being edited, so a theme chosen for WA Business never reaches WhatsApp. */
    private val preferences: WaexPreferenceManager
) {

    /** Only the test harness substitutes these operations; production always uses File.renameTo. */
    internal var installFiles: ThemeInstallFiles = RealThemeInstallFiles

    fun list(): List<InstalledTheme> =
        root(context).listFiles(File::isDirectory).orEmpty()
            .filter { ThemeIds.isValidId(it.name) }
            .mapNotNull { dir ->
                val css = File(dir, ThemeArchive.STYLE_FILE).takeIf(File::isFile)?.readText() ?: return@mapNotNull null
                InstalledTheme(dir.name, ThemeHeader.parse(css), dir.listFiles().orEmpty().size - 1)
            }
            .sortedBy { it.displayName.lowercase() }

    /** The active theme id, or null when none is set or its folder is gone (e.g. after restoring a backup). */
    fun activeId(): String? = preferences.getString(KEY_ACTIVE, null)?.takeIf { id ->
        if (!ThemeIds.isValidId(id)) return@takeIf false
        val style = File(File(root(context), id), ThemeArchive.STYLE_FILE).takeIf(File::isFile) ?: return@takeIf false
        // Hand-edited or cleared CSS detaches the selection, so a stale id never labels other CSS.
        preferences.getString(KEY_CSS, "").orEmpty().trim() == style.readText().trim()
    }

    /** Reads only the active theme's folder, so it is cheap enough to call from UI summaries. */
    fun activeTheme(): InstalledTheme? {
        val id = activeId() ?: return null
        val dir = File(root(context), id)
        val css = File(dir, ThemeArchive.STYLE_FILE).takeIf(File::isFile)?.readText() ?: return null
        return InstalledTheme(id, ThemeHeader.parse(css), dir.listFiles().orEmpty().size - 1)
    }

    /** Imports [uri] as a new theme (replacing a same-named one) and returns it. */
    @Synchronized
    fun import(uri: Uri): Result<InstalledTheme> = runCatching {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: uri.lastPathSegment.orEmpty()
        val id = ThemeIds.fromDisplayName(displayName)
        val library = root(context)
        val staging = File(library, ".import-${System.nanoTime()}")
        try {
            val css = context.contentResolver.openInputStream(uri)?.use { ThemeArchive.extract(it, staging) }
                ?: throw ThemeArchive.InvalidThemeException("Unable to read the selected file.")
            val destination = File(library, id)

            // Read the selection BEFORE replacing style.css. After replacement activeId()
            // no longer matches the old CSS and silently drops the selected theme.
            val wasActive = activeId() == id
            val oldCss = if (wasActive) preferences.getString(KEY_CSS, "").orEmpty() else null

            // Never delete the previously installed theme before the candidate is ready.
            // Keep a hidden, recoverable directory for failures between the two moves.
            val backup = File(library, ".backup-$id-${System.nanoTime()}")
            var hasBackup = false
            var promoted = false
            var activePreferenceWriteAttempted = false
            try {
                if (destination.exists()) {
                    if (backup.exists() || !installFiles.move(destination, backup)) {
                        throw IOException("Unable to preserve the previous theme.")
                    }
                    hasBackup = true
                }
                if (!installFiles.move(staging, destination)) {
                    throw IOException("Unable to install theme; previous version will be restored.")
                }
                promoted = true
                val installed = InstalledTheme(
                    id, ThemeHeader.parse(css), destination.listFiles().orEmpty().size - 1
                )

                // The active id already equals id. Changing only its CSS preserves the
                // package-scoped selection without an unnecessary second preferences write.
                if (wasActive) {
                    activePreferenceWriteAttempted = true
                    if (!preferences.putPublicString(KEY_CSS, css)) {
                        throw IOException("Unable to update the active theme preferences.")
                    }
                }

                // A failed cleanup must retain the old files, not turn a valid install
                // into an ambiguous error or delete a backup prematurely.
                if (hasBackup) runCatching { installFiles.remove(backup) }
                installed
            } catch (failure: Throwable) {
                var destinationVacant = !promoted
                var rejectedCandidate: File? = null
                if (promoted) {
                    val rejected = File(library, ".rejected-$id-${System.nanoTime()}")
                    if (!rejected.exists() && installFiles.move(destination, rejected)) {
                        destinationVacant = true
                        rejectedCandidate = rejected
                    } else {
                        failure.addSuppressed(IOException("Failed theme candidate retained; original backup preserved."))
                    }
                }
                var recovered = !hasBackup
                if (hasBackup && destinationVacant) {
                    recovered = installFiles.move(backup, destination)
                    if (!recovered) failure.addSuppressed(IOException("Original theme retained in backup; restore required."))
                }
                val safeToRemove = rejectedCandidate
                if (recovered && safeToRemove != null) {
                    // This is the candidate extracted from the input, NEVER the old theme.
                    runCatching { installFiles.remove(safeToRemove) }
                }
                if (activePreferenceWriteAttempted && oldCss != null &&
                    !runCatching { preferences.putPublicString(KEY_CSS, oldCss) }.getOrDefault(false)) {
                    // A failed commit can still change the SharedPreferences memory cache.
                    // The original files have been restored or kept in backup for recovery.
                    failure.addSuppressed(IOException("Active theme preferences could not be restored."))
                }
                throw failure
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Makes [id] the active theme, or clears the theme when [id] is null. */
    fun activate(id: String?): Boolean {
        if (id == null) {
            // Explicit empty values, never removals: a missing key falls back to the universal value.
            return preferences.putPublicString(KEY_CSS, "") &&
                preferences.putPublicString(KEY_ACTIVE, "") &&
                preferences.putPublicString("custom_css", "") &&
                preferences.putPublicString("custom_css_theme", "")
        }
        if (!ThemeIds.isValidId(id)) return false
        val css = File(File(root(context), id), ThemeArchive.STYLE_FILE).takeIf(File::isFile)?.readText() ?: return false
        if (!ThemeCssValidator.validate(css).valid) return false
        // CSS first: if the second write fails the theme still renders, just without a selected name.
        return preferences.putPublicString(KEY_CSS, css) && preferences.putPublicString(KEY_ACTIVE, id)
    }

    fun delete(id: String): Boolean {
        if (!ThemeIds.isValidId(id)) return false
        if (activeId() == id) activate(null)
        return File(root(context), id).deleteRecursively()
    }

    companion object {
        const val KEY_ACTIVE = "active_theme"
        const val KEY_CSS = "css_theme"

        fun root(context: Context): File = File(context.filesDir, "waex_themes").apply { mkdirs() }

        /** Resolves a bundled asset, or null when the id/name is unsafe or the file does not exist. */
        fun assetFile(context: Context, themeId: String?, name: String?): File? {
            if (!ThemeIds.isValidId(themeId) || !ThemeIds.isValidAssetName(name)) return null
            val base = File(root(context), themeId!!).canonicalFile
            val file = File(base, name!!).canonicalFile
            return file.takeIf { it.parentFile == base && it.isFile }
        }
    }
}
