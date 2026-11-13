package com.waenhancer.theme

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.waenhancer.core.preferences.WaexPreferenceManager
import java.io.File

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

    fun list(): List<InstalledTheme> =
        root(context).listFiles(File::isDirectory).orEmpty()
            .filter { ThemeIds.isValidId(it.name) }
            .mapNotNull { dir ->
                val css = File(dir, ThemeArchive.STYLE_FILE).takeIf(File::isFile)?.readText() ?: return@mapNotNull null
                InstalledTheme(dir.name, ThemeHeader.parse(css), dir.listFiles().orEmpty().size - 1)
            }
            .sortedBy { it.displayName.lowercase() }

    /** The active theme id, or null when none is set or its folder is gone (e.g. after restoring a backup). */
    fun activeId(): String? = preferences.getString(KEY_ACTIVE, null)
        ?.takeIf { ThemeIds.isValidId(it) && File(File(root(context), it), ThemeArchive.STYLE_FILE).isFile }

    /** Reads only the active theme's folder, so it is cheap enough to call from UI summaries. */
    fun activeTheme(): InstalledTheme? {
        val id = activeId() ?: return null
        val dir = File(root(context), id)
        val css = File(dir, ThemeArchive.STYLE_FILE).takeIf(File::isFile)?.readText() ?: return null
        return InstalledTheme(id, ThemeHeader.parse(css), dir.listFiles().orEmpty().size - 1)
    }

    /** Imports [uri] as a new theme (replacing a same-named one) and returns it. */
    fun import(uri: Uri): Result<InstalledTheme> = runCatching {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: uri.lastPathSegment.orEmpty()
        val id = ThemeIds.fromDisplayName(displayName)
        val staging = File(root(context), ".import-${System.nanoTime()}")
        try {
            val css = context.contentResolver.openInputStream(uri)?.use { ThemeArchive.extract(it, staging) }
                ?: throw ThemeArchive.InvalidThemeException("Unable to read the selected file.")
            val destination = File(root(context), id)
            destination.deleteRecursively()
            if (!staging.renameTo(destination)) throw java.io.IOException("Unable to install theme.")
            if (activeId() == id) activate(id)
            InstalledTheme(id, ThemeHeader.parse(css), destination.listFiles().orEmpty().size - 1)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Makes [id] the active theme, or clears the theme when [id] is null. */
    fun activate(id: String?): Boolean {
        if (id == null) {
            preferences.remove(KEY_ACTIVE)
            return preferences.putPublicString(KEY_CSS, "")
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
